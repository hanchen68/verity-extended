package com.hanchen.veritycustomapi.compat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class BilingualSpeechTextTest {
    private BilingualSpeechTextTest() {
    }

    public static void main(String[] args) throws Exception {
        String encoded = BilingualSpeechText.encode("Hello, Verity.", "你好，Verity。 ");
        BilingualSpeechText.Parts bilingual = BilingualSpeechText.decode(encoded);
        assertEquals("Hello, Verity.", bilingual.english());
        assertEquals("你好，Verity。", bilingual.chinese());
        assertTrue(bilingual.bilingual());

        BilingualSpeechText.Parts prefixed = BilingualSpeechText.decode(
                "<Verity> Hello.<|verity_zh|>你好。"
        );
        assertEquals("<Verity> Hello.", prefixed.english());
        assertEquals("你好。", prefixed.chinese());
        assertTrue(prefixed.bilingual());

        assertEquals("English only", BilingualSpeechText.encode(" English only ", ""));
        BilingualSpeechText.Parts plain = BilingualSpeechText.decode(" Plain text ");
        assertEquals("Plain text", plain.english());
        assertEquals("", plain.chinese());
        assertFalse(plain.bilingual());

        BilingualSpeechText.Parts incomplete = BilingualSpeechText.decode("Hello<|verity_zh|>");
        assertEquals("Hello", incomplete.english());
        assertEquals("", incomplete.chinese());
        assertFalse(incomplete.bilingual());

        BilingualSpeechText.Parts rawJson = BilingualSpeechText.decode(
                "{\"response\":\"欢迎回来，我一直在这里等着你呢。\","
                        + "\"translation\":\"Welcome back, I have been here waiting for you.\","
                        + "\"action\":[],\"emotion\":\"happy_talking\"}"
        );
        assertEquals("Welcome back, I have been here waiting for you.", rawJson.english());
        assertEquals("欢迎回来，我一直在这里等着你呢。", rawJson.chinese());
        assertTrue(rawJson.bilingual());

        BilingualSpeechText.Parts fenced = BilingualSpeechText.decode(
                "```json{\"response\":\"Hello\",\"translation\":\"你好\"}```"
        );
        assertEquals("Hello", fenced.english());
        assertEquals("你好", fenced.chinese());
        assertTrue(fenced.bilingual());

        // 模型无视 JSON 约束、直接输出「原文/翻译」标签（用户截图里的那种情况）
        BilingualSpeechText.Parts labeled = BilingualSpeechText.decode(
                "【原文】Hello there, welcome back.\n【翻译】你好呀，欢迎回来。"
        );
        assertEquals("Hello there, welcome back.", labeled.english());
        assertEquals("你好呀，欢迎回来。", labeled.chinese());
        assertTrue(labeled.bilingual());

        // 英文标签形式（行首才认）
        BilingualSpeechText.Parts englishLabels = BilingualSpeechText.decode(
                "Original: Good morning.\nTranslation: 早上好。"
        );
        assertEquals("Good morning.", englishLabels.english());
        assertEquals("早上好。", englishLabels.chinese());
        assertTrue(englishLabels.bilingual());

        // 中文台词里出现「翻译：」不算双语（没有原文可朗读）
        BilingualSpeechText.Parts chineseOnly = BilingualSpeechText.decode("我来翻译：这句话。");
        assertEquals("我来翻译：这句话。", chineseOnly.english());
        assertEquals("", chineseOnly.chinese());
        assertFalse(chineseOnly.bilingual());

        // 只有译文标签、没有原文 -> 保持原样
        BilingualSpeechText.Parts translationOnly = BilingualSpeechText.decode("【翻译】你好。");
        assertEquals("【翻译】你好。", translationOnly.english());
        assertFalse(translationOnly.bilingual());

        // 普通台词不受影响
        assertFalse(BilingualSpeechText.isMostlyChinese("Hello there."));
        assertTrue(BilingualSpeechText.isMostlyChinese("你好呀，欢迎回来。"));

        // 聊天栏显示：中文（带 [译] 标记）+ 原前缀
        assertEquals("<Verity> [译] 你好。",
                BilingualSpeechText.toChineseDisplay("<Verity> Hello.<|verity_zh|>你好。"));
        // 多人 @玩家名 标记要原样保留
        assertEquals("<Verity> @Steve: [译] 你好。",
                BilingualSpeechText.toChineseDisplay("<Verity> @Steve: Hello.<|verity_zh|>你好。"));
        // 模型自行输出的标签格式也走同一条显示逻辑
        assertEquals("<Verity> [译] 你好。",
                BilingualSpeechText.toChineseDisplay("<Verity> 【原文】Hello.\n【翻译】你好。"));
        // 非双语消息原样返回（不能把普通台词改掉）
        assertEquals("Plain text", BilingualSpeechText.toChineseDisplay("Plain text"));
        assertEquals("<Verity> Just English.",
                BilingualSpeechText.toChineseDisplay("<Verity> Just English."));

        // HUD 气泡用纯译文（不带前缀、不带标记）
        assertEquals("你好。", BilingualSpeechText.translatedContent("<Verity> Hello.<|verity_zh|>你好。"));
        assertNull(BilingualSpeechText.translatedContent("<Verity> Just English."));

        // 模型自己加的标签必须剥掉，否则会变成 [译] [翻译] 你好。
        assertEquals("你好。", BilingualSpeechText.cleanTranslation("[翻译] 你好。"));
        assertEquals("你好。", BilingualSpeechText.cleanTranslation("【翻译】你好。"));
        assertEquals("你好。", BilingualSpeechText.cleanTranslation("译文：你好。"));
        assertEquals("你好。", BilingualSpeechText.cleanTranslation("中文: 你好。"));
        assertEquals("你好。", BilingualSpeechText.cleanTranslation("[译] 你好。"));
        assertEquals("你好。", BilingualSpeechText.cleanTranslation("你好。[翻译]"));
        assertEquals("你好。", BilingualSpeechText.cleanTranslation("[翻译] 你好。 [翻译]"));
        assertEquals("你好。", BilingualSpeechText.cleanTranslation("你好。"));
        // 正文里正当的方括号不能被误删
        assertEquals("【系统】你好。", BilingualSpeechText.cleanTranslation("【系统】你好。"));

        // 译文尾巴上挂着 [翻译] 时，聊天栏输出也不能带出来
        assertEquals("<Verity> [译] 你好。",
                BilingualSpeechText.toChineseDisplay("<Verity> Hello.<|verity_zh|>你好。[翻译]"));
        // 中文标签形式也要能识别
        assertEquals("你好。", BilingualSpeechText.translatedContent("<Verity> Hello.\n中文：你好。"));

        // 分隔符绝不能泄漏到聊天栏：只有一侧有内容时要退回那一侧
        BilingualSpeechText.Parts delimiterLeftover = BilingualSpeechText.decode("<|verity_zh|>你好。");
        assertEquals("你好。", delimiterLeftover.english());
        assertFalse(delimiterLeftover.bilingual());
        assertEquals("<Verity> Hello.", BilingualSpeechText.toChineseDisplay("<Verity> Hello.<|verity_zh|>"));
        // 译文被清洗成空（模型只回了标签）-> 退回英文，而不是返回带分隔符的原文
        assertEquals("<Verity> Hello.", BilingualSpeechText.toChineseDisplay("<Verity> Hello.<|verity_zh|>[翻译]"));
        assertNull(BilingualSpeechText.translatedContent("<Verity> Hello.<|verity_zh|>[翻译]"));

        // 朗读兜底：半截双语消息不能把分隔符念出来
        assertEquals("Hello.", BilingualSpeechText.stripDelimiter("Hello.<|verity_zh|>你好。"));
        assertEquals("Hello.", BilingualSpeechText.stripDelimiter("Hello."));
        assertEquals("", BilingualSpeechText.stripDelimiter("<|verity_zh|>你好。"));
        assertTrue(BilingualSpeechText.containsDelimiter("Hello.<|verity_zh|>"));
        assertFalse(BilingualSpeechText.containsDelimiter("Hello."));

        overheadSubtitleStaysBehindOverheadOnlyMode();
        System.out.println("Bilingual speech text checks passed");
    }

    /**
     * 双语模式的普通台词聊天栏已经显示中文译文，头顶不再重复显示；
     * 头顶字幕只保留给「仅头顶」模式——那句话在聊天栏里被撤掉了，头顶是唯一的可视化。
     *
     * 这个行为长在客户端 Mixin 里，纯 JVM 跑不起来，所以从源码层面守住：
     * 头顶显示的调用必须包在 {@code display.overheadOnly()} 的判断里。
     */
    private static void overheadSubtitleStaysBehindOverheadOnlyMode() throws Exception {
        Path root = Path.of("src", "main", "java");
        String mixin = Files.readString(root.resolve(
                "com/hanchen/veritycustomapi/mixin/PlayTtsClientHandlerMixin.java"),
                StandardCharsets.UTF_8);

        int occurrences = mixin.split("VeritySubtitleManager.show", -1).length - 1;
        require(occurrences == 1,
                "头顶字幕的显示入口应当只有一处，实际 " + occurrences + " 处");

        int show = mixin.indexOf("VeritySubtitleManager.show");
        int guard = mixin.lastIndexOf("if (", show);
        require(guard >= 0, "找不到头顶字幕显示调用的守卫");
        String condition = mixin.substring(guard, show);
        require(condition.contains("display.overheadOnly()"),
                "双语模式的普通台词不应该再往头顶挂字幕——显示调用必须包在 display.overheadOnly() 里");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertTrue(boolean value) {
        if (!value) {
            throw new AssertionError("expected=true, actual=false");
        }
    }

    private static void assertFalse(boolean value) {
        if (value) {
            throw new AssertionError("expected=false, actual=true");
        }
    }

    private static void assertNull(Object value) {
        if (value != null) {
            throw new AssertionError("expected=null, actual=" + value);
        }
    }
}
