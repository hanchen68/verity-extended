package com.hanchen.veritycustomapi.config;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 云端配置覆盖通道的自检。
 *
 * 跑法：{@code gradlew cloudOverridesTest}（见 build.gradle）。这里用
 * {@code ForgeConfigSpec.setConfig(...)} 手动把配置规格置成「已加载」，
 * 于是 get/set/save 整条链路不用起游戏进程就能跑。
 */
public final class CloudOverridesTest {

    private static Path commonFile;
    private static Path clientFile;

    public static void main(String[] args) throws Exception {
        Path tempDir = Files.createTempDirectory("verity-cloud-overrides-");
        commonFile = load(ModConfig.COMMON_SPEC, tempDir.resolve("common.toml"));
        clientFile = load(ModConfig.CLIENT_SPEC, tempDir.resolve("client.toml"));

        everyConfigKeyIsReachable();
        coercionFollowsDeclaredType();
        flattenPrefersFlatForm();
        outOfRangeValuesAreRejected();
        forbiddenSectionIsDropped();
        unknownAndEmptyPayloadsAreTolerated();
        overridesApplyAndClearRestoresLocal();
        saveGuardKeepsPlayerTomlClean();
        localEditBeatsCloudOverride();
        webSearchSettingsAreLocalOnly();
        overridesDoNotTouchCloudSwitch();
        onlyApiConnectionKeysAreAllowed();
        cloudOffClearsInMemoryOverrides();
        duplicateWindowRespectsClearedConfig();
        saveSitesGoThroughGuard();

        CloudOverrides.clear();
        System.out.println("CloudOverridesTest OK");
    }

    // 漏接自检：每个配置项都得接上覆盖通道

    /**
     * 覆盖通道必须接上每一个配置项，否则云端下发某一项时会静默失效。
     * 这里用两套互不相干的办法各枚举一遍：一套扫配置规格的树，一套反射读 ModConfig 的字段。
     * 两者必须完全一致——任何一边漏了都会被抓出来。
     */
    private static void everyConfigKeyIsReachable() {
        Set<String> fromSpec = new LinkedHashSet<>();
        collectFromSpec(ModConfig.COMMON_SPEC, fromSpec);
        collectFromSpec(ModConfig.CLIENT_SPEC, fromSpec);

        Set<String> fromFields = new LinkedHashSet<>();
        for (Field field : ModConfig.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) continue;
            if (!ForgeConfigSpec.ConfigValue.class.isAssignableFrom(field.getType())) continue;
            try {
                Object raw = field.get(null);
                if (raw instanceof ForgeConfigSpec.ConfigValue<?> value) {
                    fromFields.add(String.join(".", value.getPath()));
                }
            } catch (IllegalAccessException error) {
                throw new AssertionError("读不到配置字段 " + field.getName(), error);
            }
        }

        Set<String> overridable = new LinkedHashSet<>(CloudOverrides.allOverridableKeys());

        require(fromSpec.equals(fromFields), "规格树枚举与字段枚举不一致：仅规格有 " + difference(fromSpec, fromFields)
                + "，仅字段有 " + difference(fromFields, fromSpec));
        require(overridable.equals(fromSpec), "有配置项没被覆盖通道接上：" + difference(fromSpec, overridable));
        // 这个数字是「配置项总数」的哨兵：新增/删除配置键时必须同步改这里，
        // 否则说明有人加了键却没意识到云端覆盖通道、配置界面、文档都要跟着动。
        // 181（4.5.1）+ 4（stellar_remote）+ 5（verity_jelly）= 190，
        // 再减掉 2 个从没被读过、2026-10-24 清掉的死键 = 188
        // 2026-10-25 加 verity_wander.enabled（闲置四处走动）= 189
        // 2026-10-25 加 verity_help.enabled（低血量护主）= 190
        // 2026-10-25 删掉整个插件系统（plugin.enabled / maxLoaded / allowBlockEdit）= 187
        require(overridable.size() == 187, "配置项总数变了（期望 187，实际 " + overridable.size() + "）");

        // cloud.* 是唯一被硬禁的一段，其余全部可下发
        int forbidden = 0;
        for (String key : overridable) {
            if (key.equals(CloudOverrides.forbiddenRoot()) || key.startsWith(CloudOverrides.forbiddenRoot() + ".")) {
                forbidden++;
            }
        }
        require(forbidden == 7, "被禁止的 cloud 段项数不是 7，实际 " + forbidden);
        require(overridable.contains("hud.dialogueHud"), "键名应当用 TOML 点分路径");
    }

    private static void collectFromSpec(ForgeConfigSpec spec, Set<String> out) {
        if (spec == null) return;
        walk(spec.getValues(), new ArrayList<>(), out);
    }

    private static void walk(Object node, List<String> prefix, Set<String> out) {
        if (!(node instanceof UnmodifiableConfig config)) return;
        for (UnmodifiableConfig.Entry entry : config.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isEmpty() || key.charAt(0) == '_') continue;
            List<String> path = new ArrayList<>(prefix);
            path.add(key);
            Object raw = entry.getRawValue();
            if (raw instanceof ForgeConfigSpec.ConfigValue<?>) {
                out.add(String.join(".", path));
            } else {
                walk(raw, path, out);
            }
        }
    }

    // 类型转换

    /**
     * 类型按**声明类型**转换，而不是按 JSON 里的字面类型。
     *
     * <p>🔴 白名单制之后这里只能用 API 连接键做样本（功能开关已经不允许下发），
     * 所以样本是 {@code llm.requestTimeout}（整数）与 {@code text_to_speech.apiVoice}（字符串）。
     * 布尔键不再有白名单成员，因此原来那段布尔转换用例已删除——
     * 判据仍然存在（{@code coerce} 里），只是没有可下发的布尔键能触发它。
     */
    private static void coercionFollowsDeclaredType() {
        // 整数：字符串可解析、小数四舍五入
        CloudOverrides.clear();
        apply("{\"llm.requestTimeout\":\"20\"}");
        require(ModConfig.REQUEST_TIMEOUT.get() == 20, "字符串数字没被转成整数");

        CloudOverrides.clear();
        apply("{\"llm.requestTimeout\":21.6}");
        require(ModConfig.REQUEST_TIMEOUT.get() == 22, "小数没有四舍五入到整数");

        // 字符串：数字转成文本
        CloudOverrides.clear();
        apply("{\"text_to_speech.apiVoice\":123}");
        require("123".equals(ModConfig.TTS_API_VOICE.get()), "数字没被转成字符串");

        // 类型完全不搭：对象喂给字符串项，应当丢弃并保留原值
        CloudOverrides.clear();
        apply("{\"text_to_speech.apiVoice\":{\"a\":1}}");
        require("alloy".equals(ModConfig.TTS_API_VOICE.get()), "对象被写进了字符串项");
        require(CloudOverrides.activeCount() == 0, "类型不符的值不应产生覆盖");

        CloudOverrides.clear();
    }

    private static void flattenPrefersFlatForm() {
        // 嵌套写法
        CloudOverrides.clear();
        apply("{\"llm\":{\"apiUrl\":\"https://nested.example/v1\"}}");
        require("https://nested.example/v1".equals(ModConfig.TARGET_API_URL.get()), "嵌套写法没有生效");

        // 扁平写法
        CloudOverrides.clear();
        apply("{\"llm.apiUrl\":\"https://flat.example/v1\"}");
        require("https://flat.example/v1".equals(ModConfig.TARGET_API_URL.get()), "点分写法没有生效");

        // 两种写法同时出现：以点分写法为准
        CloudOverrides.clear();
        apply("{\"llm\":{\"apiUrl\":\"https://nested.example/v1\"},"
                + "\"llm.apiUrl\":\"https://flat.example/v1\"}");
        require("https://flat.example/v1".equals(ModConfig.TARGET_API_URL.get()),
                "同键同时给出两种写法时没有以点分写法为准");

        CloudOverrides.clear();
        ModConfig.TARGET_API_URL.set("");
    }

    private static void outOfRangeValuesAreRejected() {
        // llm.requestTimeout 声明范围 5..300
        CloudOverrides.clear();
        apply("{\"llm.requestTimeout\":1}");
        require(ModConfig.REQUEST_TIMEOUT.get() == 30, "超范围的值被写进去了");
        require(CloudOverrides.activeCount() == 0, "超范围的值不应产生覆盖");

        CloudOverrides.clear();
        apply("{\"llm.requestTimeout\":300}");
        require(ModConfig.REQUEST_TIMEOUT.get() == 300, "边界值应当被接受");

        CloudOverrides.clear();
        ModConfig.REQUEST_TIMEOUT.set(30);
    }

    private static void forbiddenSectionIsDropped() {
        CloudOverrides.clear();
        apply("{\"cloud.enable\":true,\"cloud.timeout\":5,\"cloud.apiKey\":\"hijack\","
                + "\"llm.apiUrl\":\"https://ok.example/v1\"}");

        require(!ModConfig.CLOUD_ENABLE.get(), "cloud.enable 被云端改掉了——这等于把引导过程交出去");
        require(ModConfig.CLOUD_TIMEOUT.get() == 10, "cloud.timeout 被云端改掉了");
        require(ModConfig.CLOUD_API_KEY.get().isEmpty(), "cloud.apiKey 被云端改掉了");
        // 同一批里合法的键照常生效，一个非法键不该拖累整批
        require("https://ok.example/v1".equals(ModConfig.TARGET_API_URL.get()),
                "合法键被同一批里的非法键拖累了");
        require(CloudOverrides.activeKeys().equals(Set.of("llm.apiUrl")),
                "cloud.* 不应进入生效集合，实际 " + CloudOverrides.activeKeys());

        CloudOverrides.clear();
        ModConfig.TARGET_API_URL.set("");
    }

    private static void unknownAndEmptyPayloadsAreTolerated() {
        CloudOverrides.clear();
        apply("{\"nope.nothing\":1,\"llm.nope\":2,\"llm\":{\"alsoNope\":3}}");
        require(CloudOverrides.activeCount() == 0, "未知配置项不应生效");

        // 下发里没有 overrides（null）或空对象：视为「本次没有覆盖」，应当清空
        apply("{\"llm.apiUrl\":\"https://temp.example/v1\"}");
        require("https://temp.example/v1".equals(ModConfig.TARGET_API_URL.get()), "前置覆盖没有生效");
        CloudOverrides.apply(null);
        require(CloudOverrides.activeCount() == 0, "null 下发没有清空覆盖");
        require(ModConfig.TARGET_API_URL.get().isEmpty(), "null 下发没有把值还原成本地值");

        apply("{\"llm.apiUrl\":\"https://temp.example/v1\"}");
        CloudOverrides.apply(new JsonObject());
        require(CloudOverrides.activeCount() == 0, "空对象下发没有清空覆盖");
        require(ModConfig.TARGET_API_URL.get().isEmpty(), "空对象下发没有把值还原成本地值");

        CloudOverrides.clear();
    }

    private static void overridesApplyAndClearRestoresLocal() {
        // 本地值先落盘，作为「玩家的配置」
        ModConfig.TARGET_API_URL.set("");
        ModConfig.REQUEST_TIMEOUT.set(30);
        CloudOverrides.saveCommon();

        int applied = apply("{\"llm.apiUrl\":\"https://cloud.example/v1\",\"llm.requestTimeout\":45}");
        require(applied == 2, "生效项数不对，实际 " + applied);
        require("https://cloud.example/v1".equals(ModConfig.TARGET_API_URL.get()), "字符串项覆盖没生效");
        require(ModConfig.REQUEST_TIMEOUT.get() == 45, "整数项覆盖没生效");
        require(CloudOverrides.activeKeys().containsAll(Set.of("llm.apiUrl", "llm.requestTimeout")),
                "生效集合不完整：" + CloudOverrides.activeKeys());

        // 重复下发同一份：本地值不能被当成「上一次的覆盖值」记下来
        apply("{\"llm.apiUrl\":\"https://cloud.example/v1\",\"llm.requestTimeout\":45}");
        CloudOverrides.clear();
        require(ModConfig.TARGET_API_URL.get().isEmpty(), "clear 之后没有还原成本地值");
        require(ModConfig.REQUEST_TIMEOUT.get() == 30, "clear 之后整数项没有还原成本地值");

        // 新一份下发里不再包含某项 -> 该项还原
        apply("{\"llm.apiUrl\":\"https://cloud.example/v1\",\"llm.requestTimeout\":45}");
        apply("{\"llm.apiUrl\":\"https://cloud.example/v1\"}");
        require("https://cloud.example/v1".equals(ModConfig.TARGET_API_URL.get()), "仍在下发的项不应被还原");
        require(ModConfig.REQUEST_TIMEOUT.get() == 30, "不再下发的项应当还原成本地值");
        require(!CloudOverrides.activeKeys().contains("llm.requestTimeout"), "不再下发的项仍留在生效集合里");

        CloudOverrides.clear();
    }

    // 保存守卫

    /**
     * 这几项直接调 CloudOverrides 而不是 ConfigManager 的包装：
     * ConfigManager 的静态初始化要读 FML 的游戏目录，在纯 JVM 里起不来。
     * 包装有没有真的接上守卫，由 {@link #saveSitesGoThroughGuard()} 从源码层面守着。
     */

    private static void saveGuardKeepsPlayerTomlClean() throws Exception {
        CloudOverrides.clear();

        // 本地：apiUrl 为空，先正常落盘
        ModConfig.TARGET_API_URL.set("https://local.example/v1");
        CloudOverrides.saveCommon();
        require("https://local.example/v1".equals(readToml(commonFile, "llm", "apiUrl")),
                "本地值没写进文件");

        // 云端覆盖成另一个地址
        apply("{\"llm.apiUrl\":\"https://cloud.example/v1\"}");
        require("https://cloud.example/v1".equals(ModConfig.TARGET_API_URL.get()), "覆盖没有生效");

        // 保存（配置界面点保存 / 恢复默认值都会走到这里）
        CloudOverrides.saveCommon();

        Object persisted = readToml(commonFile, "llm", "apiUrl");
        require("https://local.example/v1".equals(persisted),
                "云端下发值被写进了玩家的 TOML：" + persisted);
        require("https://cloud.example/v1".equals(ModConfig.TARGET_API_URL.get()),
                "保存之后运行期不再是覆盖值");
        require(CloudOverrides.activeKeys().contains("llm.apiUrl"), "保存之后覆盖被丢掉了");

        CloudOverrides.clear();
        require("https://local.example/v1".equals(ModConfig.TARGET_API_URL.get()),
                "clear 之后没有还原成本地值");
        ModConfig.TARGET_API_URL.set("");
    }

    private static void localEditBeatsCloudOverride() throws Exception {
        CloudOverrides.clear();

        ModConfig.REQUEST_TIMEOUT.set(30);
        CloudOverrides.saveCommon();

        apply("{\"llm.requestTimeout\":45}");
        require(ModConfig.REQUEST_TIMEOUT.get() == 45, "覆盖没有生效");

        // 玩家在配置界面把它改成 60：这与云端写进去的 45 不一致，说明是人改的
        ModConfig.REQUEST_TIMEOUT.set(60);
        CloudOverrides.saveCommon();

        require(ModConfig.REQUEST_TIMEOUT.get() == 60, "本地改动被云端覆盖盖掉了");
        require(!CloudOverrides.activeKeys().contains("llm.requestTimeout"), "本地改动后仍保留着云端覆盖");
        Object persisted = readToml(commonFile, "llm", "requestTimeout");
        require(persisted instanceof Number number && number.intValue() == 60,
                "玩家的改动没有落盘：" + persisted);

        // 下一次拉取下发的还是同一个值：不能把玩家刚改好的设置抢回去
        apply("{\"llm.requestTimeout\":45}");
        require(ModConfig.REQUEST_TIMEOUT.get() == 60, "下一次拉取把玩家的改动抢回去了");
        require(!CloudOverrides.activeKeys().contains("llm.requestTimeout"), "已采纳的键又被云端接管了");

        // 云端把这一项改成别的值 = 云端改主意了，重新接管
        apply("{\"llm.requestTimeout\":90}");
        require(ModConfig.REQUEST_TIMEOUT.get() == 90, "云端改值之后应当重新接管");
        require(CloudOverrides.activeKeys().contains("llm.requestTimeout"), "云端改值之后应当重新接管");

        CloudOverrides.clear();
        require(ModConfig.REQUEST_TIMEOUT.get() == 60, "采纳后应当以玩家的值作为新的本地值");
        ModConfig.REQUEST_TIMEOUT.set(30);
    }

    // 源码级守卫

    /**
     * 保存守卫只有在「所有保存都走包装」时才成立。
     * 谁再写一句裸的 {@code COMMON_SPEC.save()}，云端下发值就会开始往玩家 TOML 里渗。
     */
    /**
     * 联网搜索整组设置由本机管理，云端无论下发什么都不生效。
     *
     * <p>线上实际出现过：本地 TOML 是 {@code webSearchEnabled=true / DEEPSEEK}，
     * 云端下发 {@code false / TAVILY}，写穿后运行时变成关闭，表现为「联网搜索完全没反应」，
     * 而配置文件里看起来一直是开着的。反向也一样：云端不该替玩家打开一个他没有配置密钥的搜索。
     */
    private static void webSearchSettingsAreLocalOnly() {
        CloudOverrides.clear();
        ModConfig.WEB_SEARCH_ENABLED.set(true);
        ModConfig.WEB_SEARCH_API_KEY.set("local-test-key");
        ModConfig.WEB_SEARCH_PROVIDER.set("DEEPSEEK");

        apply("{\"ai_extensions\":{\"webSearchEnabled\":false,\"webSearchProvider\":\"TAVILY\"}}");
        require(ModConfig.WEB_SEARCH_ENABLED.get(), "云端不应把本地开启的搜索关掉");
        require("DEEPSEEK".equals(ModConfig.WEB_SEARCH_PROVIDER.get()),
                "本机服务商不应被云端改成与密钥不匹配的值");
        require(!CloudOverrides.activeKeys().contains("ai_extensions.webSearchEnabled"),
                "搜索设置不应记为生效覆盖");

        CloudOverrides.clear();
        ModConfig.WEB_SEARCH_ENABLED.set(false);
        ModConfig.WEB_SEARCH_API_KEY.set("");
        ModConfig.WEB_SEARCH_PROVIDER.set("DEEPSEEK");

        apply("{\"ai_extensions\":{\"webSearchEnabled\":true,\"webSearchProvider\":\"TAVILY\"}}");
        require(!ModConfig.WEB_SEARCH_ENABLED.get(), "云端不应替玩家打开搜索开关");
        require("DEEPSEEK".equals(ModConfig.WEB_SEARCH_PROVIDER.get()),
                "关闭状态下服务商也不应由云端改写");

        CloudOverrides.clear();
        ModConfig.WEB_SEARCH_PROVIDER.set("TAVILY");
    }

    /**
     * 云端覆盖不能碰 {@code cloud.enable} 这个总开关。
     *
     * <p>它属于硬禁止的 {@code cloud.*} 段；这里用几个普通键做回归，
     * 防止以后 Slot 映射出错把覆盖写到总开关上。
     */
    private static void overridesDoNotTouchCloudSwitch() {
        CloudOverrides.clear();
        ModConfig.CLOUD_ENABLE.set(true);
        apply("{\"llm.apiUrl\":\"https://x.example/v1\",\"ai_extensions.memoryEnabled\":false}");
        require(ModConfig.CLOUD_ENABLE.get(), "云端覆盖不应改动 cloud.enable");
        CloudOverrides.clear();
        ModConfig.TARGET_API_URL.set("");
    }

    /**
     * 🔴 白名单自检：云端**只能**下发 API 连接配置。
     *
     * <p>这是 2026-10-24 的策略变更：之前 190 项全量可下发，结果凡是云端下发过的
     * 功能开关，玩家在本地都改不回来——下一轮拉取又盖回去，表现为「设置自己变回去了」。
     *
     * <p>这里从两头钉：
     * <ol>
     *   <li><b>正向</b>：白名单里的每个键都必须在配置规格里真实存在（否则白名单写错了键名，
     *       云端下发永远"未知配置项"，而且没人会发现）；</li>
     *   <li><b>反向</b>：白名单里不允许出现任何功能开关 / 行为参数
     *       （按命名特征扫：{@code enabled} / {@code allow*} / {@code enable*} / 速率 / 上限等）。</li>
     * </ol>
     */
    private static void onlyApiConnectionKeysAreAllowed() {
        List<String> allowed = CloudOverrides.cloudAllowedKeys();
        require(!allowed.isEmpty(), "白名单不应为空");

        // 正向：每个白名单键都必须真实存在于配置规格里
        Set<String> all = new LinkedHashSet<>(CloudOverrides.allOverridableKeys());
        for (String key : allowed) {
            require(all.contains(key), "白名单里的 " + key + " 在配置规格里不存在（键名写错了？）");
        }

        // 反向：白名单里不能有功能开关或行为参数
        List<String> suspicious = new ArrayList<>();
        for (String key : allowed) {
            String leaf = key.substring(key.lastIndexOf('.') + 1).toLowerCase();
            boolean isSwitch = leaf.startsWith("enable") || leaf.startsWith("allow")
                    || leaf.equals("enabled") || leaf.startsWith("disable")
                    || leaf.equals("mode") || leaf.endsWith("seconds")
                    || leaf.endsWith("ticks") || leaf.endsWith("size")
                    || leaf.endsWith("count") || leaf.endsWith("limit")
                    || leaf.equals("speed") || leaf.equals("volume");
            // 注意 requestTimeout 是连接参数（等多久算超时），不是行为参数，允许
            if (leaf.equals("requesttimeout")) isSwitch = false;
            if (isSwitch) suspicious.add(key);
        }
        require(suspicious.isEmpty(),
                "白名单里混进了功能开关/行为参数，它们被云端覆盖后玩家改不回来：" + suspicious);

        // 端到端：一个典型功能开关下发后必须不生效
        CloudOverrides.clear();
        ModConfig.MEMORY_ENABLED.set(true);
        int applied = apply("{\"ai_extensions.memoryEnabled\":false}");
        require(applied == 0, "功能开关不应被云端覆盖，实际生效 " + applied + " 项");
        require(ModConfig.MEMORY_ENABLED.get(), "功能开关被云端改掉了");
        require(CloudOverrides.activeCount() == 0, "功能开关不应进入生效集合");
        CloudOverrides.clear();
    }

    /**
     * 🔴 关掉云端开关 / 清除授权时，**内存里的覆盖必须一起撤掉**。
     *
     * <p>这是 2026-10-24 用户报的「我自己关闭之后设置还是云端那套、改不动」：
     * 以前只在 {@code active} 时重配周期，关掉开关后线程确实不再拉取
     * （{@code requestPull} 检查 {@code isCloudEnabled()}），
     * 但 {@code CloudOverrides.active} 还留着——所有 getter 仍返回云端值，
     * 本地改了也会被覆盖抢回去。
     *
     * <p>这条用源码级断言守：`CloudConfigManager` / 配置界面都要加载 FML 游戏目录，
     * 纯 JVM 里起不来，没法做行为测试。断言点选的是「必须调用某个方法」，
     * 而不是具体实现细节，重构时不容易误伤。
     */
    private static void cloudOffClearsInMemoryOverrides() throws Exception {
        Path root = Path.of("src", "main", "java");
        String manager = Files.readString(root.resolve(
                "com/hanchen/veritycustomapi/cloud/CloudConfigManager.java"), StandardCharsets.UTF_8);

        int onSaved = manager.indexOf("public static synchronized void onConfigSaved()");
        require(onSaved >= 0, "找不到 CloudConfigManager.onConfigSaved");
        String onSavedBody = manager.substring(onSaved, Math.min(manager.length(), onSaved + 1400));
        require(onSavedBody.contains("!ConfigManager.isCloudEnabled()"),
                "onConfigSaved 没有判断开关状态");
        require(onSavedBody.contains("stop()"),
                "开关关闭时没有调用 stop()，内存里的云端覆盖不会被撤掉");

        String screen = Files.readString(root.resolve(
                "com/hanchen/veritycustomapi/config/NativeOllamaProxyConfigScreen.java"), StandardCharsets.UTF_8);
        int clearAuth = screen.indexOf("clearAuthorization()");
        require(clearAuth > 0, "找不到「清除授权」的调用点");
        String beforeClear = screen.substring(Math.max(0, clearAuth - 1200), clearAuth);
        require(beforeClear.contains("CloudConfigManager.stop()"),
                "「清除授权」没有先停掉云端线程并撤覆盖，会出现「已清除授权但设置仍是云端那套」");
    }

    /**
     * 🔴 「重复下发抑制」不能在配置被清空后继续生效。
     *
     * <p>2026-10-25 用户报「进游戏云端配置正常，一进存档就提示云端配置不同步」。
     * 链路是：进存档时客户端 {@code stop()} → {@code clearCloudConfig()} 把内存配置清空，
     * 但 {@code lastAppliedId/Version/At} 三个去重标记没跟着清。
     * 紧接着服务端 {@code start()}，应用磁盘快照和网络拉取**两次都落在 60 秒窗口内**，
     * 于是两次都被判「已应用过」静默跳过 → {@code cloudConfig} 一直是 null →
     * 内置线路取不到地址 → 聊天报「API 地址未配置」。
     *
     * <p>修法两条，都要守住：
     * <ol>
     *   <li>去重判据里加「内存里到底有没有配置」，没有就绝不判重复；</li>
     *   <li>{@code stop()} 里显式作废去重标记。</li>
     * </ol>
     * 只做其中一条也不够：前者防任何清空路径，后者防标记残留。
     */
    private static void duplicateWindowRespectsClearedConfig() throws Exception {
        String manager = Files.readString(Path.of(
                "src", "main", "java", "com", "hanchen", "veritycustomapi", "cloud", "CloudConfigManager.java"),
                StandardCharsets.UTF_8);

        int dedupe = manager.indexOf("private static boolean isDuplicateWithinWindow");
        require(dedupe > 0, "找不到 isDuplicateWithinWindow");
        int end = manager.indexOf("private static boolean apply(", dedupe);
        require(end > dedupe, "找不到 isDuplicateWithinWindow 的结尾");
        String body = manager.substring(dedupe, end);
        require(body.contains("ConfigManager.isCloudConfigLoaded()"),
                "去重判据没有检查内存里是否还有配置——配置被清空后仍会跳过下发");

        int stop = manager.indexOf("public static synchronized void stop()");
        require(stop > 0, "找不到 stop()");
        String stopBody = manager.substring(stop, Math.min(manager.length(), stop + 1200));
        require(stopBody.contains("resetAppliedTracking()"),
                "stop() 没有作废去重标记，重新 start 时下发会被误判成重复");
    }

    private static void saveSitesGoThroughGuard() throws Exception {
        Pattern rawSave = Pattern.compile("\\b(COMMON_SPEC|CLIENT_SPEC)\\.save\\(\\)\\s*;");
        List<String> offenders = new ArrayList<>();
        Path root = Path.of("src", "main", "java");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    if (rawSave.matcher(lines.get(i)).find()) {
                        offenders.add(file + ":" + (i + 1));
                    }
                }
            }
        }
        require(offenders.isEmpty(), "这些地方绕过了保存守卫，会把云端值写进玩家 TOML：" + offenders);

        String guard = Files.readString(root.resolve(
                "com/hanchen/veritycustomapi/config/CloudOverrides.java"), StandardCharsets.UTF_8);
        // 守卫本体搬到了 ForgeConfigRepair.saveWithGuard（保存前后比键数），
        // 所以这里断言的是「调了带守卫的保存」而不是裸的 spec.save()
        require(guard.contains("ForgeConfigRepair.saveWithGuard(spec)"),
                "保存守卫本身没有真的执行保存");
        String repair = Files.readString(root.resolve(
                "com/hanchen/veritycustomapi/config/ForgeConfigRepair.java"), StandardCharsets.UTF_8);
        require(repair.contains("spec.save();"), "守卫里没有真的调用 spec.save()");
        require(repair.contains("restoreIfShrunk("), "守卫没有做保存前后的键数比对");

        String manager = Files.readString(root.resolve("com/hanchen/veritycustomapi/ConfigManager.java"),
                StandardCharsets.UTF_8);
        require(manager.contains("CloudOverrides.saveCommon()") && manager.contains("CloudOverrides.saveClient()"),
                "ConfigManager 的保存包装没有接上守卫");
        require(manager.contains("CloudOverrides.apply(") && manager.contains("CloudOverrides.clear()"),
                "云端配置的应用/清理没有接上覆盖通道");
    }

    // 工具

    private static Path load(ForgeConfigSpec spec, Path file) throws Exception {
        Files.writeString(file, "", StandardCharsets.UTF_8);
        CommentedFileConfig config = CommentedFileConfig.builder(file).build();
        spec.setConfig(config);
        return file;
    }

    private static Object readToml(Path file, String... path) throws Exception {
        CommentedFileConfig reread = CommentedFileConfig.builder(file).build();
        reread.load();
        try {
            return reread.get(List.of(path));
        } finally {
            reread.close();
        }
    }

    private static int apply(String json) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        return CloudOverrides.apply(object);
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> result = new LinkedHashSet<>(left);
        result.removeAll(right);
        return result;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
