package com.hanchen.veritycustomapi.client;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 语音按键归属自检：原版 push-to-talk 与扩展语音键不能撞在一起。
 *
 * <p>为什么这条值得测：这个键已经改坏过两次，每次都是「看起来修了、实际更坏」：
 * <ol>
 *   <li>默认统一改成 B —— 桌面端不撞了，但手机端（原版链路被跳过）语音直接没了；</li>
 *   <li>而且 B 本身又被快捷指令面板占用，等于把红字挪到了另一行；</li>
 *   <li>迁移用的标记文件沿用旧名，被上一版的 bug 写死过一次，导致新版永远不迁移。</li>
 * </ol>
 * 所以这里同时钉住「默认值按平台」「目标键必须空闲」「标记文件必须换名」三件事。
 */
public final class SpeechKeyConflictTest {
    private SpeechKeyConflictTest() {
    }

    public static void main(String[] args) throws Exception {
        extensionKeyIsPlatformAware();
        vanillaChainTakesPriority();
        mobileNeverLosesItsOnlyEntry();
        migrationCannotBeBlockedForever();
        migrationMustNotBurnFlagTooEarly();
        targetKeyMustBeFree();
        labelsTellTheTruth();
        everyKeyHasTranslation();
        System.out.println("语音按键归属检查通过：桌面端不撞键，手机端保留唯一入口");
    }

    /**
     * 迁移只允许在「真正做出决定」之后置位 {@code legacySpeechKeyChecked}。
     *
     * <p>这里踩过一次：第一行就置位，然后才判 options/手机端/按键值——
     * 首帧 options 还没就绪时走 return，标记已经消耗掉，之后每 tick 直接返回，
     * 迁移永远不会再跑，玩家看到的就是「装了新版但按键还是 V」。
     */
    private static void migrationMustNotBurnFlagTooEarly() throws Exception {
        String source = read("src/main/java/com/hanchen/veritycustomapi/client/ClientEventHandler.java");
        int migrate = source.indexOf("private static void restoreLegacySpeechKey");
        assertTrue(migrate >= 0, "找不到语音键迁移方法");
        String body = source.substring(migrate, Math.min(source.length(), migrate + 2600));
        // 真正的「改键」语句（不是某个提前分支里的置位）之前的全部逻辑
        int setKey = body.indexOf("SPEECH_KEY.setKey");
        assertTrue(setKey > 0, "找不到改键语句");
        String beforeSet = body.substring(0, setKey);
        // 首帧 options 可能未就绪；此时 return 不能消耗标记
        assertTrue(beforeSet.contains("mc.options == null"),
                "必须先确认 options 就绪，再考虑置位，否则首帧会被消耗掉");
        // 注入在进世界时才做，主菜单阶段 isActive() 必然 false；
        // 必须等 player 出现后再据此下定论，否则桌面端会被永久判成「原版不可用」。
        // 只看代码行，不看注释（注释里也会出现方法名）。
        int playerGuard = -1;
        int activeGuard = -1;
        int cursor = 0;
        for (String raw : beforeSet.split("\n", -1)) {
            String line = raw.trim();
            boolean code = !line.startsWith("//") && !line.startsWith("*") && !line.startsWith("/*");
            if (code && playerGuard < 0 && line.contains("mc.player == null")) playerGuard = cursor;
            if (code && activeGuard < 0 && line.contains("isActive()")) activeGuard = cursor;
            cursor += raw.length() + 1;
        }
        assertTrue(playerGuard >= 0, "必须先等玩家进世界，再判断原版链路是否可用");
        assertTrue(activeGuard < 0 || playerGuard < activeGuard,
                "isActive() 的判断必须在 player 就绪之后，否则主菜单阶段就会误判并永久放弃迁移");
    }

    /**
     * 扩展语音键的默认值必须按平台区分。
     *
     * <p>桌面端不能是 V（原版 push-to-talk 占着），也不能是 B（快捷指令面板占着）；
     * 手机端必须是 V（原版链路在手机上被跳过，这个键是唯一入口）。
     */
    private static void extensionKeyIsPlatformAware() throws Exception {
        String source = read("src/main/java/com/hanchen/veritycustomapi/client/KeyBindings.java");
        int speech = source.indexOf("SPEECH_KEY = new KeyMapping");
        assertTrue(speech >= 0, "找不到 SPEECH_KEY 定义");
        String block = source.substring(speech, Math.min(source.length(), speech + 900));
        assertTrue(block.contains("isAndroidRuntime()"), "语音键默认值必须按平台区分");
        assertTrue(block.contains("GLFW_KEY_V"), "手机端默认键应为 V（唯一入口）");
        assertTrue(block.contains("GLFW_KEY_J"), "桌面端默认键应为 J（V 与 B 都已被占用）");
    }

    /** 原版链路可用时必须直接 return，不允许扩展再单独录音。 */
    private static void vanillaChainTakesPriority() throws Exception {
        String source = read("src/main/java/com/hanchen/veritycustomapi/client/ClientEventHandler.java");
        int active = source.indexOf("if (OriginalVeritySttInjector.isActive())");
        assertTrue(active >= 0, "找不到原版链路判定");
        String tail = source.substring(active);
        int returnPos = tail.indexOf("return;");
        int speechCall = tail.indexOf("SpeechRecognitionManager.INSTANCE.onKeyPressed()");
        assertTrue(returnPos >= 0, "原版链路可用时必须提前返回");
        assertTrue(speechCall < 0 || returnPos < speechCall,
                "原版链路可用时不能再去调扩展录音，否则两条录音链会抢麦克风");
    }

    /** 手机端绝不能把 V 迁走，否则玩家再也没法发语音。 */
    private static void mobileNeverLosesItsOnlyEntry() throws Exception {
        String source = read("src/main/java/com/hanchen/veritycustomapi/client/ClientEventHandler.java");
        int migrate = source.indexOf("private static void restoreLegacySpeechKey");
        assertTrue(migrate >= 0, "找不到语音键迁移方法");
        String body = source.substring(migrate, Math.min(source.length(), migrate + 2200));
        int androidGuard = body.indexOf("RuntimePlatform.isAndroidRuntime()");
        int setKey = body.indexOf("SPEECH_KEY.setKey");
        assertTrue(androidGuard >= 0, "迁移逻辑必须显式判断手机端");
        assertTrue(setKey < 0 || androidGuard < setKey,
                "手机端必须在改键之前就返回，否则 V 会被迁走、语音功能消失");
        assertTrue(body.contains("OriginalVeritySttInjector.isActive()"),
                "桌面端也要确认原版链路可用后才迁移");
    }

    /**
     * 迁移标记必须换过名字。
     *
     * <p>沿用旧名会让「上一版 bug 版写过标记」的存档永远跳过迁移——
     * 玩家装了新版却看到 V 还在冲突，这就是本次实际踩到的坑。
     */
    private static void migrationCannotBeBlockedForever() throws Exception {
        String source = read("src/main/java/com/hanchen/veritycustomapi/client/ClientEventHandler.java");
        assertTrue(source.contains("speech-key-conflict-v2.flag"),
                "迁移标记必须换新名字，否则被旧版标记永久拦住");
        assertTrue(!source.contains("\"speech-key-conflict.flag\""),
                "不应再引用旧标记名 speech-key-conflict.flag");
    }

    /**
     * 目标键必须真的空闲，不能写死一个可能被占用的键。
     *
     * <p>写死过的 V（原版占用）和 B（快捷指令占用）都撞了，所以必须走运行时探测。
     */
    private static void targetKeyMustBeFree() throws Exception {
        String source = read("src/main/java/com/hanchen/veritycustomapi/client/ClientEventHandler.java");
        assertTrue(source.contains("findFreeSpeechKey()"), "迁移必须探测空闲键，不能写死目标键");
        int finder = source.indexOf("private static int findFreeSpeechKey()");
        assertTrue(finder >= 0, "找不到空闲键探测方法");
        String body = source.substring(finder, Math.min(source.length(), finder + 1600));
        assertTrue(body.contains("keyMappings"), "探测必须遍历现有按键表");
        assertTrue(body.contains("SPEECH_KEY"), "探测时必须跳过语音键自身，否则永远判定自己占用了候选键");
    }

    /** 按键名称必须说明手机端是主入口，桌面端原版可用时不生效。 */
    private static void labelsTellTheTruth() throws Exception {
        String zh = read("src/main/resources/assets/verity_extended/lang/zh_cn.json");
        assertTrue(zh.contains("手机端为唯一入口"), "中文按键名应说明手机端是唯一入口");
        String en = read("src/main/resources/assets/verity_extended/lang/en_us.json");
        assertTrue(en.toLowerCase(java.util.Locale.ROOT).contains("primary on mobile"),
                "英文按键名应说明手机端是主入口");
    }

    /**
     * 注册的每个按键都必须有中英文翻译。
     *
     * <p>漏一个，按键设置里就会直接显示原始 id（例如 {@code key.veritycustomapi.ride}），
     * 玩家看不懂这是干什么的。这一条是真的漏过：ride 键一直没写翻译。
     */
    private static void everyKeyHasTranslation() throws Exception {
        String keys = read("src/main/java/com/hanchen/veritycustomapi/client/KeyBindings.java");
        java.util.List<String> ids = new java.util.ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"(key\\.veritycustomapi\\.[a-z_]+)\"").matcher(keys);
        while (m.find()) {
            if (!ids.contains(m.group(1))) ids.add(m.group(1));
        }
        assertTrue(!ids.isEmpty(), "没有从 KeyBindings 里解析出任何按键 id");
        for (String lang : new String[]{"zh_cn", "en_us"}) {
            String json = read("src/main/resources/assets/verity_extended/lang/" + lang + ".json");
            for (String id : ids) {
                assertTrue(json.contains("\"" + id + "\""),
                        lang + " 缺少按键翻译：" + id + "（会直接显示原始 id）");
            }
        }
    }

    private static String read(String rel) throws Exception {
        return Files.readString(Path.of(rel));
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
