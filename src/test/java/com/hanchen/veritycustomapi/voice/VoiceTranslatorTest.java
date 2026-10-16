package com.hanchen.veritycustomapi.voice;

/**
 * 台词翻译与语言映射的自检。
 *
 * 这条路有两个不能错的地方。一是「该不该翻」判错，中文台词会被翻成英文念出来；
 * 二是「翻失败怎么办」处理错，整句话直接没声音。所以判断矩阵和失败兜底都钉在这，
 * 真正联网的部分不测（测不了，也不该测）。
 */
public final class VoiceTranslatorTest {

    private VoiceTranslatorTest() {
    }

    public static void main(String[] args) {
        needsTranslationMatrix();
        forSpeechShortCircuits();
        forSpeechNeverBreaksReadAloud();
        polishStripsModelDecoration();
        languageParsingIsLenient();
        languageCodeMapping();

        System.out.println("Voice translator checks passed");
    }

    // 该不该翻

    private static void needsTranslationMatrix() {
        // 朗读语言是「跟随」= 就用台词原本的语言念，绝不翻译
        require(!VoiceTranslator.needsTranslation(VoiceLanguage.FOLLOW, VoiceLanguage.FOLLOW),
                "两边都是跟随时不翻译");
        require(!VoiceTranslator.needsTranslation(VoiceLanguage.ZH, VoiceLanguage.FOLLOW),
                "朗读语言是跟随时不翻译");
        require(!VoiceTranslator.needsTranslation(VoiceLanguage.EN, VoiceLanguage.FOLLOW),
                "朗读语言是跟随时不翻译（哪怕显示语言是英文）");

        // 两种语言一致 = 没有可翻的
        require(!VoiceTranslator.needsTranslation(VoiceLanguage.ZH, VoiceLanguage.ZH),
                "显示与朗读语言一致时不翻译");
        require(!VoiceTranslator.needsTranslation(VoiceLanguage.JA, VoiceLanguage.JA),
                "显示与朗读语言一致时不翻译");

        // 显示语言是跟随、朗读语言指定了 = 要翻（台词可能是任何语言）
        require(VoiceTranslator.needsTranslation(VoiceLanguage.FOLLOW, VoiceLanguage.ZH),
                "朗读语言指定中文时应翻译");
        require(VoiceTranslator.needsTranslation(VoiceLanguage.FOLLOW, VoiceLanguage.EN),
                "朗读语言指定英文时应翻译");

        // 两边都指定但不同 = 要翻
        require(VoiceTranslator.needsTranslation(VoiceLanguage.ZH, VoiceLanguage.EN),
                "显示中文、朗读英文时应翻译");
        require(VoiceTranslator.needsTranslation(VoiceLanguage.EN, VoiceLanguage.JA),
                "显示英文、朗读日文时应翻译");

        require(!VoiceTranslator.needsTranslation(VoiceLanguage.ZH, null),
                "朗读语言为 null 时按不翻译处理");
    }

    private static void forSpeechShortCircuits() {
        require("你好".equals(VoiceTranslator.forSpeech("你好", VoiceLanguage.ZH, VoiceLanguage.ZH)),
                "不需要翻译时必须原样返回");
        require("你好".equals(VoiceTranslator.forSpeech("你好", VoiceLanguage.ZH, VoiceLanguage.FOLLOW)),
                "朗读语言跟随时必须原样返回");
        require(VoiceTranslator.forSpeech(null, VoiceLanguage.ZH, VoiceLanguage.EN) == null,
                "null 输入应原样返回 null，不能抛异常");
        require("   ".equals(VoiceTranslator.forSpeech("   ", VoiceLanguage.ZH, VoiceLanguage.EN)),
                "空白输入应原样返回，不该为它发起一次翻译请求");
        require("".equals(VoiceTranslator.forSpeech("", VoiceLanguage.ZH, VoiceLanguage.EN)),
                "空串应原样返回");
    }

    /**
     * 没有可用线路 / 请求失败时，{@link VoiceTranslator#forSpeech} 必须退回原文。
     *
     * 自检环境里没有 Forge 配置也没有网络，正好是最坏的输入条件——
     * 这条用例保证「翻译坏掉」永远不会升级成「朗读坏掉」。
     */
    private static void forSpeechNeverBreaksReadAloud() {
        String spoken = VoiceTranslator.forSpeech("你好，今天过得怎么样？",
                VoiceLanguage.ZH, VoiceLanguage.EN);
        require(spoken != null, "翻译失败时也必须返回可朗读的文本，不能是 null");
        require(!spoken.isBlank(), "翻译失败时也必须返回可朗读的文本，不能是空串");
        require("你好，今天过得怎么样？".equals(spoken),
                "翻译失败时必须原样退回原文，实际: " + spoken);
    }

    // 输出清洗

    private static void polishStripsModelDecoration() {
        require("你好".equals(VoiceTranslator.polish("\"你好\"")), "应去掉成对的双引号");
        require("你好".equals(VoiceTranslator.polish("“你好”")), "应去掉成对的中文引号");
        require("你好".equals(VoiceTranslator.polish("「你好」")), "应去掉成对的直角引号");
        require("你好".equals(VoiceTranslator.polish("『你好』")), "应去掉成对的双直角引号");
        require("你好".equals(VoiceTranslator.polish("'你好'")), "应去掉成对的单引号");
        require("你好".equals(VoiceTranslator.polish("译文：你好")), "应去掉「译文：」前缀");
        require("你好".equals(VoiceTranslator.polish("翻译: 你好")), "应去掉「翻译:」前缀");
        require("hello".equals(VoiceTranslator.polish("Translation: hello")),
                "应去掉英文的 Translation 前缀");
        require("a b".equals(VoiceTranslator.polish("a\n\nb")), "换行应折叠成单个空格");
        require("a b".equals(VoiceTranslator.polish("a   b")), "连续空格应折叠成一个");
        require("你好".equals(VoiceTranslator.polish("  你好  ")), "首尾空白应去掉");
        require("".equals(VoiceTranslator.polish(null)), "null 应清洗成空串");
        require("".equals(VoiceTranslator.polish("   ")), "全空白应清洗成空串");
        // 只有半边引号不能乱删
        require("\"你好".equals(VoiceTranslator.polish("\"你好")), "不成对的引号不能删");
        // 「你好」这种只有一层包裹的情况不能把内容也吃掉
        require("你\"好".equals(VoiceTranslator.polish("你\"好")), "中间出现的引号不能删");
    }

    // 语言映射

    private static void languageParsingIsLenient() {
        require(VoiceLanguage.parse("follow") == VoiceLanguage.FOLLOW, "follow 应解析为跟随");
        require(VoiceLanguage.parse("跟随") == VoiceLanguage.FOLLOW, "「跟随」应解析为跟随");
        require(VoiceLanguage.parse("auto") == VoiceLanguage.FOLLOW, "auto 应等同于跟随");
        require(VoiceLanguage.parse("zh") == VoiceLanguage.ZH, "zh 应解析为简体中文");
        require(VoiceLanguage.parse("中文") == VoiceLanguage.ZH, "「中文」应解析为简体中文");
        require(VoiceLanguage.parse("EN") == VoiceLanguage.EN, "解析应大小写不敏感");
        require(VoiceLanguage.parse("english") == VoiceLanguage.EN, "english 应解析为英文");
        require(VoiceLanguage.parse("jp") == VoiceLanguage.JA, "jp 应解析为日文");
        require(VoiceLanguage.parse("韩语") == VoiceLanguage.KO, "「韩语」应解析为韩文");
        require(VoiceLanguage.parse("fr") == null, "认不出来必须返回 null，由调用方决定默认值");
        require(VoiceLanguage.parse(null) == null, "null 必须返回 null");

        for (VoiceLanguage language : VoiceLanguage.values()) {
            require(VoiceLanguage.parse(language.configValue()) == language,
                    "自己的配置值必须能被解析回来: " + language.configValue());
        }
        // 只有 FOLLOW 的提示词名是空的（空 = 不向模型指定语言）
        for (VoiceLanguage language : VoiceLanguage.values()) {
            if (language == VoiceLanguage.FOLLOW) {
                require(language.promptName().isEmpty(), "跟随不应该有提示词名");
            } else {
                require(!language.promptName().isBlank(), "指定语言必须有提示词名: " + language);
            }
        }
    }

    private static void languageCodeMapping() {
        require(VoiceLanguage.fromLanguageCode("zh_cn", VoiceLanguage.FOLLOW) == VoiceLanguage.ZH,
                "zh_cn 应映射为简体中文");
        require(VoiceLanguage.fromLanguageCode("zh_tw", VoiceLanguage.FOLLOW) == VoiceLanguage.ZH,
                "zh_tw 也应映射为中文（合成器不区分）");
        require(VoiceLanguage.fromLanguageCode("en_us", VoiceLanguage.FOLLOW) == VoiceLanguage.EN,
                "en_us 应映射为英文");
        require(VoiceLanguage.fromLanguageCode("ja_jp", VoiceLanguage.FOLLOW) == VoiceLanguage.JA,
                "ja_jp 应映射为日文");
        require(VoiceLanguage.fromLanguageCode("ko_kr", VoiceLanguage.FOLLOW) == VoiceLanguage.KO,
                "ko_kr 应映射为韩文");
        require(VoiceLanguage.fromLanguageCode("fr_fr", VoiceLanguage.EN) == VoiceLanguage.EN,
                "认不出来的语言代码应返回给定的兜底值");
        require(VoiceLanguage.fromLanguageCode(null, VoiceLanguage.ZH) == VoiceLanguage.ZH,
                "null 语言代码应返回给定的兜底值");
        require(VoiceLanguage.fromLanguageCode("  ", VoiceLanguage.ZH) == VoiceLanguage.ZH,
                "空白语言代码应返回给定的兜底值");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
