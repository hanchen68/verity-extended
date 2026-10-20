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
        require(overridable.size() == 181, "配置项总数变了（期望 181，实际 " + overridable.size() + "）");

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

    private static void coercionFollowsDeclaredType() {
        // 布尔：字符串和数字写法都认
        CloudOverrides.clear();
        apply("{\"hud.dialogueHud\":\"off\"}");
        require(!ModConfig.HUD_ENABLED.get(), "字符串 off 没被转成 false");
        require(CloudOverrides.activeKeys().contains("hud.dialogueHud"), "布尔项没有被覆盖");

        CloudOverrides.clear();
        apply("{\"hud.dialogueHud\":1}");
        require(ModConfig.HUD_ENABLED.get(), "数字 1 没被转成 true");

        CloudOverrides.clear();
        apply("{\"hud.dialogueHud\":\"maybe\"}");
        require(ModConfig.HUD_ENABLED.get(), "非法的布尔写法被写进去了");
        require(CloudOverrides.activeCount() == 0, "非法布尔写法不应产生覆盖");

        // 整数：字符串可解析、小数四舍五入
        CloudOverrides.clear();
        apply("{\"hud.dialogueSeconds\":\"20\"}");
        require(ModConfig.HUD_DIALOGUE_SECONDS.get() == 20, "字符串数字没被转成整数");

        CloudOverrides.clear();
        apply("{\"hud.dialogueSeconds\":21.6}");
        require(ModConfig.HUD_DIALOGUE_SECONDS.get() == 22, "小数没有四舍五入到整数");

        // 小数
        CloudOverrides.clear();
        apply("{\"text_to_speech.speed\":2.5}");
        require(Math.abs(ModConfig.TTS_SPEED.get() - 2.5D) < 1e-6D, "小数项没有被写入");

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
        apply("{\"hud\":{\"dialogueHud\":false}}");
        require(!ModConfig.HUD_ENABLED.get(), "嵌套写法没有生效");

        // 扁平写法
        CloudOverrides.clear();
        apply("{\"hud.dialogueHud\":true}");
        require(ModConfig.HUD_ENABLED.get(), "点分写法没有生效");

        // 两种写法同时出现：以点分写法为准
        CloudOverrides.clear();
        apply("{\"hud\":{\"dialogueHud\":true},\"hud.dialogueHud\":false}");
        require(!ModConfig.HUD_ENABLED.get(), "同键同时给出两种写法时没有以点分写法为准");

        CloudOverrides.clear();
    }

    private static void outOfRangeValuesAreRejected() {
        // hud.dialogueSeconds 声明范围 3..60
        CloudOverrides.clear();
        apply("{\"hud.dialogueSeconds\":1}");
        require(ModConfig.HUD_DIALOGUE_SECONDS.get() == 12, "超范围的值被写进去了");
        require(CloudOverrides.activeCount() == 0, "超范围的值不应产生覆盖");

        CloudOverrides.clear();
        apply("{\"hud.dialogueSeconds\":60}");
        require(ModConfig.HUD_DIALOGUE_SECONDS.get() == 60, "边界值应当被接受");

        // 小数范围 0.25..4.0
        CloudOverrides.clear();
        apply("{\"text_to_speech.speed\":9.0}");
        require(Math.abs(ModConfig.TTS_SPEED.get() - 1.0D) < 1e-6D, "超范围的小数被写进去了");

        CloudOverrides.clear();
    }

    private static void forbiddenSectionIsDropped() {
        CloudOverrides.clear();
        apply("{\"cloud.enable\":true,\"cloud.timeout\":5,\"cloud.apiKey\":\"hijack\",\"hud.dialogueHud\":false}");

        require(!ModConfig.CLOUD_ENABLE.get(), "cloud.enable 被云端改掉了——这等于把引导过程交出去");
        require(ModConfig.CLOUD_TIMEOUT.get() == 10, "cloud.timeout 被云端改掉了");
        require(ModConfig.CLOUD_API_KEY.get().isEmpty(), "cloud.apiKey 被云端改掉了");
        // 同一批里合法的键照常生效，一个非法键不该拖累整批
        require(!ModConfig.HUD_ENABLED.get(), "合法键被同一批里的非法键拖累了");
        require(CloudOverrides.activeKeys().equals(Set.of("hud.dialogueHud")),
                "cloud.* 不应进入生效集合，实际 " + CloudOverrides.activeKeys());

        CloudOverrides.clear();
    }

    private static void unknownAndEmptyPayloadsAreTolerated() {
        CloudOverrides.clear();
        apply("{\"nope.nothing\":1,\"hud.nope\":2,\"hud\":{\"alsoNope\":3}}");
        require(CloudOverrides.activeCount() == 0, "未知配置项不应生效");

        // 下发里没有 overrides（null）或空对象：视为「本次没有覆盖」，应当清空
        apply("{\"hud.dialogueHud\":false}");
        require(!ModConfig.HUD_ENABLED.get(), "前置覆盖没有生效");
        CloudOverrides.apply(null);
        require(CloudOverrides.activeCount() == 0, "null 下发没有清空覆盖");
        require(ModConfig.HUD_ENABLED.get(), "null 下发没有把值还原成本地值");

        apply("{\"hud.dialogueHud\":false}");
        CloudOverrides.apply(new JsonObject());
        require(CloudOverrides.activeCount() == 0, "空对象下发没有清空覆盖");
        require(ModConfig.HUD_ENABLED.get(), "空对象下发没有把值还原成本地值");

        CloudOverrides.clear();
    }

    private static void overridesApplyAndClearRestoresLocal() {
        // 本地值先落盘，作为「玩家的配置」
        ModConfig.HUD_ENABLED.set(true);
        ModConfig.HUD_DIALOGUE_SECONDS.set(12);
        CloudOverrides.saveClient();

        int applied = apply("{\"hud.dialogueHud\":false,\"hud.dialogueSeconds\":30}");
        require(applied == 2, "生效项数不对，实际 " + applied);
        require(!ModConfig.HUD_ENABLED.get(), "布尔项覆盖没生效");
        require(ModConfig.HUD_DIALOGUE_SECONDS.get() == 30, "整数项覆盖没生效");
        require(CloudOverrides.activeKeys().containsAll(Set.of("hud.dialogueHud", "hud.dialogueSeconds")),
                "生效集合不完整：" + CloudOverrides.activeKeys());

        // 重复下发同一份：本地值不能被当成「上一次的覆盖值」记下来
        apply("{\"hud.dialogueHud\":false,\"hud.dialogueSeconds\":30}");
        CloudOverrides.clear();
        require(ModConfig.HUD_ENABLED.get(), "clear 之后没有还原成本地值");
        require(ModConfig.HUD_DIALOGUE_SECONDS.get() == 12, "clear 之后整数项没有还原成本地值");

        // 新一份下发里不再包含某项 -> 该项还原
        apply("{\"hud.dialogueHud\":false,\"hud.dialogueSeconds\":30}");
        apply("{\"hud.dialogueHud\":false}");
        require(!ModConfig.HUD_ENABLED.get(), "仍在下发的项不应被还原");
        require(ModConfig.HUD_DIALOGUE_SECONDS.get() == 12, "不再下发的项应当还原成本地值");
        require(!CloudOverrides.activeKeys().contains("hud.dialogueSeconds"), "不再下发的项仍留在生效集合里");

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

        // 本地：dialogueHud=true，先正常落盘
        ModConfig.HUD_ENABLED.set(true);
        CloudOverrides.saveClient();
        require(Boolean.TRUE.equals(readToml(clientFile, "hud", "dialogueHud")), "本地值没写进文件");

        // 云端覆盖成 false
        apply("{\"hud.dialogueHud\":false}");
        require(!ModConfig.HUD_ENABLED.get(), "覆盖没有生效");

        // 保存（配置界面点保存 / 恢复默认值都会走到这里）
        CloudOverrides.saveClient();

        Object persisted = readToml(clientFile, "hud", "dialogueHud");
        require(Boolean.TRUE.equals(persisted), "云端下发值被写进了玩家的 TOML：" + persisted);
        require(!ModConfig.HUD_ENABLED.get(), "保存之后运行期不再是覆盖值");
        require(CloudOverrides.activeKeys().contains("hud.dialogueHud"), "保存之后覆盖被丢掉了");

        CloudOverrides.clear();
        require(ModConfig.HUD_ENABLED.get(), "clear 之后没有还原成本地值");
    }

    private static void localEditBeatsCloudOverride() throws Exception {
        CloudOverrides.clear();

        ModConfig.HUD_DIALOGUE_SECONDS.set(12);
        CloudOverrides.saveClient();

        apply("{\"hud.dialogueSeconds\":30}");
        require(ModConfig.HUD_DIALOGUE_SECONDS.get() == 30, "覆盖没有生效");

        // 玩家在配置界面把它改成 45：这与云端写进去的 30 不一致，说明是人改的
        ModConfig.HUD_DIALOGUE_SECONDS.set(45);
        CloudOverrides.saveClient();

        require(ModConfig.HUD_DIALOGUE_SECONDS.get() == 45, "本地改动被云端覆盖盖掉了");
        require(!CloudOverrides.activeKeys().contains("hud.dialogueSeconds"), "本地改动后仍保留着云端覆盖");
        Object persisted = readToml(clientFile, "hud", "dialogueSeconds");
        require(persisted instanceof Number number && number.intValue() == 45,
                "玩家的改动没有落盘：" + persisted);

        // 下一次拉取下发的还是同一个值：不能把玩家刚改好的设置抢回去
        apply("{\"hud.dialogueSeconds\":30}");
        require(ModConfig.HUD_DIALOGUE_SECONDS.get() == 45, "下一次拉取把玩家的改动抢回去了");
        require(!CloudOverrides.activeKeys().contains("hud.dialogueSeconds"), "已采纳的键又被云端接管了");

        // 云端把这一项改成别的值 = 云端改主意了，重新接管
        apply("{\"hud.dialogueSeconds\":50}");
        require(ModConfig.HUD_DIALOGUE_SECONDS.get() == 50, "云端改值之后应当重新接管");
        require(CloudOverrides.activeKeys().contains("hud.dialogueSeconds"), "云端改值之后应当重新接管");

        CloudOverrides.clear();
        require(ModConfig.HUD_DIALOGUE_SECONDS.get() == 45, "采纳后应当以玩家的值作为新的本地值");
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
        apply("{\"hud.dialogueHud\":false,\"ai_extensions.memoryEnabled\":false}");
        require(ModConfig.CLOUD_ENABLE.get(), "云端覆盖不应改动 cloud.enable");
        CloudOverrides.clear();
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
        require(guard.contains("spec.save();"), "保存守卫本身没有真的执行保存");

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
