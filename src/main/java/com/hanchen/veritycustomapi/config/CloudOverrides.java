package com.hanchen.veritycustomapi.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 云端配置的通用下发通道。
 *
 * 原来的云端字段是「一个配置项一个字段」，每加一个能远程配置的功能，云端和 Mod 都得同时发一版。
 * 这里换成一个 {@code overrides} 映射（键 = TOML 点分路径），170 项配置都能直接下发，
 * 以后新增配置项也不用再改云端协议。
 *
 * 值是直接写进 ForgeConfigSpec 的，不是套一层 getter。项目里有 500 多处直接读
 * {@code ModConfig.XXX.get()}（配置界面 276 处、恐怖事件、AI 建造、管理员指令……），
 * 远比走 {@code ConfigManager} 的地方多；只在 getter 上做覆盖的话，那些直接读的地方会静默拿到本地值，
 * 覆盖看起来生效了、实际一半功能没变。写进 spec 则无论谁读、怎么读，读到的都是覆盖后的值。
 *
 * 写穿也有代价。Forge 的 {@code ConfigValue.set()} 改的是内存里那棵树，随后任何一次
 * {@code COMMON_SPEC.save()}（配置界面点保存、管理员指令改配置……）都会把它写进玩家的 TOML 文件，
 * 云端下发值污染本地配置后，云端撤掉这一项玩家也回不去了。所以所有保存都必须走
 * {@link #saveCommon()} / {@link #saveClient()}：保存前把被覆盖的键临时写回本地值，保存后再写回覆盖值。
 * 保存时如果发现某个键的值已经不是我们写进去的那个，说明玩家或管理员自己改过，那就以玩家的改动为准，
 * 采纳为新本地值并放弃这一项的覆盖。
 *
 * 唯一没有写穿的是 {@code cloud.*}：让云端能改自己的地址就等于把引导过程交出去
 * （之后所有请求连同密钥都会发往任意端点），所以硬禁止。
 */
public final class CloudOverrides {

    /** 一次下发最多接受多少个键，防止畸形负载把内存撑爆。 */
    private static final int MAX_KEYS = 512;

    /** 硬禁止下发的配置段。 */
    private static final String FORBIDDEN_ROOT = "cloud";

    /**
     * 联网搜索整组（开关 / 服务商 / 密钥 / 自定义接口）由本机管理，**无条件**不受云端覆盖。
     *
     * <p>服务商与密钥只存在本地，云端下发一个不匹配的服务商（本地 DeepSeek 密钥 +
     * 云端把 provider 改成 TAVILY）会让搜索直接失效；云端下发 false 还会让开关看起来开着、
     * 运行时却是关的。因此这组键与 {@code cloud.*} 一样，不参与云端下发。
     */
    private static final String WEB_SEARCH_SECTION = "ai_extensions";
    private static final String WEB_SEARCH_PREFIX = "webSearch";

    private CloudOverrides() {
    }

    /** 一个可覆盖的配置项。localValue / cloudValue 只在它被覆盖期间有效。 */
    private static final class Slot {
        final ForgeConfigSpec spec;
        final ForgeConfigSpec.ConfigValue<?> value;
        final List<String> path;
        Object localValue;
        Object cloudValue;

        Slot(ForgeConfigSpec spec, ForgeConfigSpec.ConfigValue<?> value, List<String> path) {
            this.spec = spec;
            this.value = value;
            this.path = path;
        }
    }

    private static volatile Map<List<String>, Slot> index;

    /** 当前生效的覆盖，key 是路径。用 LinkedHashMap 保证日志顺序稳定。 */
    private static final Map<List<String>, Slot> active = new LinkedHashMap<>();

    /**
     * 玩家自己改过、因而放弃云端覆盖的键 -> 放弃时云端给的值。
     *
     * 不记下来的话，下一次拉取会把云端值再写一遍，玩家刚改好的设置几秒后就自己变回去。
     * 云端把这个键的值改掉时（值不再相等）会重新接管，等于「云端改主意了就再管起来」。
     */
    private static final Map<List<String>, Object> adopted = new LinkedHashMap<>();

    /** 上一次下发的摘要，用来「只在变化时打日志」，否则每次轮询都会刷一遍。 */
    private static String lastSummary = "";

    /** 上一次被本地保留的联网搜索键集合，避免每次拉取配置都打一遍同一条日志。 */
    private static String lastLocalPinned = "";

    // ==================== 键索引 ====================

    /**
     * 枚举全部配置项。Forge 的 {@code getValues()} 返回的树里，叶子就是 {@code ConfigValue} 对象本身
     * （{@code Builder.build()} 会把每个 ConfigValue 按路径塞进一棵新配置树），
     * 所以直接递归就能拿全，不需要反射读 ModConfig 的字段。
     */
    private static Map<List<String>, Slot> index() {
        Map<List<String>, Slot> cached = index;
        if (cached != null) return cached;
        synchronized (CloudOverrides.class) {
            if (index == null) {
                Map<List<String>, Slot> built = new LinkedHashMap<>();
                collect(ModConfig.COMMON_SPEC, built);
                collect(ModConfig.CLIENT_SPEC, built);
                index = built;
            }
            return index;
        }
    }

    private static void collect(ForgeConfigSpec spec, Map<List<String>, Slot> out) {
        if (spec == null) return;
        walk(spec, spec.getValues(), new ArrayList<>(), out);
    }

    private static void walk(ForgeConfigSpec spec, Object node, List<String> prefix, Map<List<String>, Slot> out) {
        if (!(node instanceof com.electronwill.nightconfig.core.UnmodifiableConfig config)) return;
        for (com.electronwill.nightconfig.core.UnmodifiableConfig.Entry entry : config.entrySet()) {
            String key = entry.getKey();
            // _comment_ / _translation_key_ 之类的元数据不是配置项
            if (key == null || key.isEmpty() || key.charAt(0) == '_') continue;
            List<String> path = new ArrayList<>(prefix);
            path.add(key);
            Object raw = entry.getRawValue();
            if (raw instanceof ForgeConfigSpec.ConfigValue<?> value) {
                List<String> immutable = List.copyOf(path);
                out.put(immutable, new Slot(spec, value, immutable));
            } else {
                walk(spec, raw, path, out);
            }
        }
    }

    /** 全部可覆盖的键（点分路径）。自检用。 */
    public static List<String> allOverridableKeys() {
        List<String> keys = new ArrayList<>(index().size());
        for (List<String> path : index().keySet()) keys.add(dotted(path));
        return Collections.unmodifiableList(keys);
    }

    /** 硬禁止下发的段名。自检用。 */
    public static String forbiddenRoot() {
        return FORBIDDEN_ROOT;
    }

    // ==================== 下发 ====================

    /**
     * 应用一次云端下发。
     *
     * @param raw {@code data.overrides}；为 null 或空对象表示「本次没有覆盖」，会清空现有覆盖
     * @return 实际生效的键数
     */
    public static synchronized int apply(JsonObject raw) {
        if (raw == null || raw.size() == 0) {
            clearLocked();
            return 0;
        }

        Map<List<String>, JsonElement> flat = flatten(raw);
        if (flat.size() > MAX_KEYS) {
            List<List<String>> kept = new ArrayList<>(flat.keySet()).subList(0, MAX_KEYS);
            Map<List<String>, JsonElement> trimmed = new LinkedHashMap<>();
            for (List<String> path : kept) trimmed.put(path, flat.get(path));
            OllamaProxyMod.LOGGER.warn("云端覆盖超过 {} 项，只接受前 {} 项（下发 {} 项）",
                    MAX_KEYS, MAX_KEYS, flat.size());
            flat = trimmed;
        }

        List<String> forbidden = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        List<String> unloaded = new ArrayList<>();
        List<String> badType = new ArrayList<>();
        List<String> localPinned = new ArrayList<>();
        Map<Slot, Object> coerced = new LinkedHashMap<>();

        for (Map.Entry<List<String>, JsonElement> entry : flat.entrySet()) {
            List<String> path = entry.getKey();
            if (FORBIDDEN_ROOT.equals(path.get(0))) {
                forbidden.add(dotted(path));
                continue;
            }
            // 联网搜索整组由本机管理：服务商、密钥、开关都在本地，云端一律不参与。
            if (isWebSearchPath(path)) {
                localPinned.add(dotted(path));
                continue;
            }
            Slot slot = index().get(path);
            if (slot == null) {
                unknown.add(dotted(path));
                continue;
            }
            // 规格还没加载时 set() 会抛、get() 会返回默认值，两者都不能用。
            // 服务端上 CLIENT_SPEC 永远不加载，客户端专属的键会被跳过——这是对的，
            // 那些设置本来就该由客户端自己那次拉取来生效。
            if (!slot.spec.isLoaded()) {
                unloaded.add(dotted(path));
                continue;
            }
            Object value;
            try {
                value = coerce(entry.getValue(), slot.value.getDefault());
            } catch (RuntimeException error) {
                value = null;
            }
            if (value == null) {
                badType.add(dotted(path));
                continue;
            }
            coerced.put(slot, value);
        }

        Set<Slot> rejected = validate(coerced);

        Set<Slot> accepted = new LinkedHashSet<>();
        List<String> outOfRange = new ArrayList<>();
        List<String> keptLocal = new ArrayList<>();
        for (Map.Entry<Slot, Object> entry : coerced.entrySet()) {
            Slot slot = entry.getKey();
            if (rejected.contains(slot)) {
                outOfRange.add(dotted(slot.path));
                continue;
            }
            if (!active.containsKey(slot.path)) {
                // 只有第一次覆盖时才记本地值：之后再记拿到的就是上一次的覆盖值了
                slot.localValue = slot.value.get();
            }
            if (adopted.containsKey(slot.path) && Objects.equals(adopted.get(slot.path), entry.getValue())) {
                // 玩家改过这个键，而云端给的还是同一个值：以玩家的为准，别再改回去
                keptLocal.add(dotted(slot.path));
                continue;
            }
            adopted.remove(slot.path);
            slot.cloudValue = entry.getValue();
            try {
                write(slot.value, entry.getValue());
            } catch (RuntimeException error) {
                OllamaProxyMod.LOGGER.warn("云端覆盖 {} 写入失败: {}", dotted(slot.path), error.getMessage());
                continue;
            }
            active.put(slot.path, slot);
            accepted.add(slot);
        }

        restoreMissing(accepted);

        String pinnedSummary = localPinned.toString();
        if (localPinned.isEmpty()) {
            lastLocalPinned = "";
        } else if (!pinnedSummary.equals(lastLocalPinned)) {
            lastLocalPinned = pinnedSummary;
            OllamaProxyMod.LOGGER.info("联网搜索设置由本机管理，忽略云端覆盖: {}", localPinned);
        }
        report(flat.size(), accepted.size(), forbidden, unknown, unloaded, badType, outOfRange, keptLocal);
        return accepted.size();
    }

    /** 是否是联网搜索相关键（{@code ai_extensions.webSearch*}）。 */
    private static boolean isWebSearchPath(List<String> path) {
        return path.size() >= 2 && WEB_SEARCH_SECTION.equals(path.get(0))
                && path.get(1).startsWith(WEB_SEARCH_PREFIX);
    }

    /** 清空覆盖并把所有键还原成本地值。云端关闭 / 授权失效 / 下发里不再包含某项时都会走到这里。 */
    public static synchronized void clear() {
        clearLocked();
    }

    private static void clearLocked() {
        for (Slot slot : active.values()) {
            if (!slot.spec.isLoaded()) continue;
            try {
                write(slot.value, slot.localValue);
            } catch (RuntimeException error) {
                OllamaProxyMod.LOGGER.warn("云端覆盖 {} 还原失败: {}", dotted(slot.path), error.getMessage());
            }
        }
        active.clear();
        adopted.clear();
        lastSummary = "";
    }

    public static synchronized boolean isActive() {
        return !active.isEmpty();
    }

    public static synchronized int activeCount() {
        return active.size();
    }

    public static synchronized Set<String> activeKeys() {
        Set<String> keys = new TreeSet<>();
        for (List<String> path : active.keySet()) keys.add(dotted(path));
        return Collections.unmodifiableSet(keys);
    }

    private static void restoreMissing(Set<Slot> accepted) {
        List<Slot> dropped = new ArrayList<>();
        for (Slot slot : active.values()) {
            if (!accepted.contains(slot)) dropped.add(slot);
        }
        for (Slot slot : dropped) {
            if (slot.spec.isLoaded()) {
                try {
                    write(slot.value, slot.localValue);
                } catch (RuntimeException error) {
                    OllamaProxyMod.LOGGER.warn("云端覆盖 {} 还原失败: {}", dotted(slot.path), error.getMessage());
                }
            }
            active.remove(slot.path);
            // 云端不再管这个键了，玩家的「已采纳」记录也随之作废；
            // 云端下次重新下发时算一次新的接管
            adopted.remove(slot.path);
        }
    }

    // ==================== 保存守卫 ====================

    public static void saveCommon() {
        saveSpec(ModConfig.COMMON_SPEC);
    }

    public static void saveClient() {
        saveSpec(ModConfig.CLIENT_SPEC);
    }

    /**
     * 保存 spec，但保证云端下发值不会落进玩家的 TOML 文件。
     *
     * 保存前把被覆盖的键临时写回本地值；保存后立刻写回覆盖值，运行期行为不变。
     * 若某个键当前的值与云端写进去的不一致，说明玩家（配置界面 / 管理员指令 / 其它模块）
     * 自己改过——此时以玩家的改动为准：采纳为新的本地值，并放弃这一项的覆盖。
     */
    public static synchronized void saveSpec(ForgeConfigSpec spec) {
        if (spec == null) return;
        if (active.isEmpty() || !spec.isLoaded()) {
            // 没覆盖就保持原样；没加载时也照旧让它抛，别把真实错误吞掉
            spec.save();
            return;
        }

        List<Slot> reapply = new ArrayList<>();
        List<Slot> adopt = new ArrayList<>();
        for (Slot slot : active.values()) {
            if (slot.spec != spec) continue;
            Object current;
            try {
                current = slot.value.get();
            } catch (RuntimeException error) {
                continue;
            }
            if (!Objects.equals(current, slot.cloudValue)) {
                adopt.add(slot);
                continue;
            }
            try {
                write(slot.value, slot.localValue);
                reapply.add(slot);
            } catch (RuntimeException error) {
                OllamaProxyMod.LOGGER.warn("云端覆盖 {} 保存前还原失败: {}", dotted(slot.path), error.getMessage());
            }
        }

        // 采纳只改内存里的账，不动 spec，所以可以放在保存之前，保存抛异常也不会漏
        for (Slot slot : adopt) {
            Object adoptedValue = slot.value.get();
            slot.localValue = adoptedValue;
            active.remove(slot.path);
            // 记下放弃时云端给的值：下次拉取如果还是这个值就不再抢回去
            adopted.put(slot.path, slot.cloudValue);
            OllamaProxyMod.LOGGER.info("配置项 {} 已被本地改动覆盖（{}），放弃该项的云端覆盖",
                    dotted(slot.path), adoptedValue);
        }

        try {
            spec.save();
        } finally {
            for (Slot slot : reapply) {
                try {
                    write(slot.value, slot.cloudValue);
                } catch (RuntimeException error) {
                    OllamaProxyMod.LOGGER.warn("云端覆盖 {} 保存后恢复失败: {}", dotted(slot.path), error.getMessage());
                }
            }
        }
    }

    /**
     * 往配置项写值。{@code ConfigValue<?>} 的通配符捕获让 set() 收不下 Object，
     * 而我们的值本来就来自 JSON、类型已经按 getDefault() 校正过，所以这里显式擦除一次。
     */
    @SuppressWarnings("unchecked")
    private static void write(ForgeConfigSpec.ConfigValue<?> value, Object raw) {
        ((ForgeConfigSpec.ConfigValue<Object>) value).set(raw);
    }

    // ==================== 类型转换与校验 ====================

    /**
     * 按配置项的声明类型转换 JSON 值。返回 null 表示「这个值不能用」。
     * 目前配置项只有 Boolean / Integer / Double / String 四种类型。
     */
    private static Object coerce(JsonElement element, Object defaultValue) {
        if (element == null || element.isJsonNull()) return null;
        if (defaultValue instanceof Boolean) return asBoolean(element);
        if (defaultValue instanceof Integer) return asInteger(element);
        if (defaultValue instanceof Double) return asDouble(element);
        if (defaultValue instanceof String) return asString(element);
        return null;
    }

    private static Boolean asBoolean(JsonElement element) {
        if (!element.isJsonPrimitive()) return null;
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isBoolean()) return primitive.getAsBoolean();
        if (primitive.isNumber()) return primitive.getAsInt() != 0;
        if (primitive.isString()) {
            String text = primitive.getAsString().trim().toLowerCase(Locale.ROOT);
            if (text.equals("true") || text.equals("on") || text.equals("yes") || text.equals("1")) {
                return Boolean.TRUE;
            }
            if (text.equals("false") || text.equals("off") || text.equals("no") || text.equals("0")) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    private static Integer asInteger(JsonElement element) {
        Double value = asDouble(element);
        if (value == null || !Double.isFinite(value)) return null;
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) return null;
        // 配置项里 3.0 和 3 是一回事，四舍五入比截断更符合直觉
        return (int) Math.round(value);
    }

    private static Double asDouble(JsonElement element) {
        if (!element.isJsonPrimitive()) return null;
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isNumber()) return primitive.getAsDouble();
        if (primitive.isBoolean()) return primitive.getAsBoolean() ? 1.0D : 0.0D;
        if (primitive.isString()) {
            try {
                return Double.parseDouble(primitive.getAsString().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String asString(JsonElement element) {
        if (!element.isJsonPrimitive()) return null;
        return element.getAsJsonPrimitive().getAsString();
    }

    /**
     * 借 Forge 自己的校验：把候选值塞进一份临时配置，跑一遍 {@code correct()}，
     * 被它判为不合法的键就丢掉。
     *
     * 这样范围 / 取值约束永远和 {@link ModConfig} 里 {@code defineInRange} 的声明一致，
     * 不需要在这里再抄一份区间表——那种副本迟早会和声明走偏。
     * 注意这里是「丢弃」而不是「改成默认值」：静默换成默认值会让云端以为下发成功了。
     */
    private static Set<Slot> validate(Map<Slot, Object> coerced) {
        Set<Slot> rejected = new LinkedHashSet<>();
        if (coerced.isEmpty()) return rejected;

        Map<ForgeConfigSpec, List<Map.Entry<Slot, Object>>> groups = new LinkedHashMap<>();
        for (Map.Entry<Slot, Object> entry : coerced.entrySet()) {
            groups.computeIfAbsent(entry.getKey().spec, key -> new ArrayList<>()).add(entry);
        }

        for (Map.Entry<ForgeConfigSpec, List<Map.Entry<Slot, Object>>> group : groups.entrySet()) {
            CommentedConfig scratch = CommentedConfig.inMemory();
            for (Map.Entry<Slot, Object> entry : group.getValue()) {
                try {
                    scratch.set(entry.getKey().path, entry.getValue());
                } catch (RuntimeException error) {
                    rejected.add(entry.getKey());
                }
            }
            Set<List<String>> flagged = new LinkedHashSet<>();
            try {
                group.getKey().correct(scratch, (action, path, incorrect, corrected) ->
                        flagged.add(List.copyOf(path)));
            } catch (RuntimeException error) {
                OllamaProxyMod.LOGGER.warn("云端覆盖校验失败，本批按原值生效: {}", error.getMessage());
                continue;
            }
            for (Map.Entry<Slot, Object> entry : group.getValue()) {
                if (flagged.contains(entry.getKey().path)) rejected.add(entry.getKey());
            }
        }
        return rejected;
    }

    // ==================== 展平 ====================

    /**
     * 把下发内容展平成「路径 -> 值」。
     * 支持两种写法，且点分写法优先：
     *
     * { "hud.dialogueHud": false }        // 扁平
     * { "hud": { "dialogueHud": false } } // 嵌套
     *
     */
    private static Map<List<String>, JsonElement> flatten(JsonObject raw) {
        Map<List<String>, JsonElement> nested = new LinkedHashMap<>();
        Map<List<String>, JsonElement> flat = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : raw.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) continue;
            JsonElement value = entry.getValue();
            List<String> prefix = split(key);
            if (prefix.isEmpty()) continue;
            if (value != null && value.isJsonObject()) {
                collectNested(prefix, value.getAsJsonObject(), nested);
            } else {
                flat.put(prefix, value);
            }
        }
        Map<List<String>, JsonElement> merged = new LinkedHashMap<>(nested);
        merged.putAll(flat);
        return merged;
    }

    private static void collectNested(List<String> prefix, JsonObject node, Map<List<String>, JsonElement> out) {
        for (Map.Entry<String, JsonElement> entry : node.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) continue;
            List<String> path = new ArrayList<>(prefix);
            path.addAll(split(key));
            if (path.isEmpty()) continue;
            JsonElement value = entry.getValue();
            if (value != null && value.isJsonObject()) {
                collectNested(path, value.getAsJsonObject(), out);
            } else {
                out.put(path, value);
            }
        }
    }

    private static List<String> split(String key) {
        List<String> parts = new ArrayList<>(2);
        for (String part : key.split("\\.")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) parts.add(trimmed);
        }
        return parts;
    }

    private static String dotted(List<String> path) {
        return String.join(".", path);
    }

    // ==================== 日志 ====================

    /** 摘要只在变化时打，否则每次轮询都会刷一遍同样的行。 */
    private static void report(int offered, int applied, List<String> forbidden, List<String> unknown,
                               List<String> unloaded, List<String> badType, List<String> outOfRange,
                               List<String> keptLocal) {
        String summary = applied + "|" + forbidden + "|" + unknown + "|" + unloaded + "|" + badType + "|"
                + outOfRange + "|" + keptLocal;
        if (summary.equals(lastSummary)) return;
        lastSummary = summary;

        OllamaProxyMod.LOGGER.info("云端配置覆盖: 下发 {} 项，生效 {} 项", offered, applied);
        if (!forbidden.isEmpty()) {
            OllamaProxyMod.LOGGER.warn("云端覆盖包含禁止下发的 {} 段，已忽略: {}", FORBIDDEN_ROOT, forbidden);
        }
        if (!unknown.isEmpty()) {
            OllamaProxyMod.LOGGER.warn("云端覆盖包含未知配置项，已忽略: {}", unknown);
        }
        if (!outOfRange.isEmpty()) {
            OllamaProxyMod.LOGGER.warn("云端覆盖的值超出声明范围，已忽略: {}", outOfRange);
        }
        if (!badType.isEmpty()) {
            OllamaProxyMod.LOGGER.warn("云端覆盖的值类型与配置项不符，已忽略: {}", badType);
        }
        if (!keptLocal.isEmpty()) {
            OllamaProxyMod.LOGGER.info("这些配置项玩家自己改过，保留玩家的值: {}", keptLocal);
        }
        if (!unloaded.isEmpty()) {
            OllamaProxyMod.LOGGER.debug("云端覆盖的配置段在当前环境未加载，已跳过: {}", unloaded);
        }
    }

    /** 单测用：清掉「上次摘要」缓存，让下一次 report 一定打日志。 */
    static synchronized void resetReportCacheForTesting() {
        lastSummary = "";
    }
}
