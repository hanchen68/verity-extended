package com.hanchen.veritycustomapi.voice;

import java.util.Locale;

/**
 * 朗读用得到的语言。
 *
 * 两处用到：一是「显示语言」——台词本身该用哪种语言写；二是「朗读语言」——
 * 合成器该用哪种语言念。两者不同时，朗读前需要先翻译。
 */
public enum VoiceLanguage {
    /** 跟随：显示语言跟游戏语言走；朗读语言表示「就用台词原本的语言，不翻译」。 */
    FOLLOW("跟随", ""),
    ZH("简体中文", "Simplified Chinese"),
    EN("English", "English"),
    JA("日本語", "Japanese"),
    KO("한국어", "Korean");

    private final String label;
    private final String promptName;

    VoiceLanguage(String label, String promptName) {
        this.label = label;
        this.promptName = promptName;
    }

    /** 给玩家看的名字。 */
    public String label() {
        return label;
    }

    /** 写进提示词的名字；{@link #FOLLOW} 为空，表示不指定。 */
    public String promptName() {
        return promptName;
    }

    public String configValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    @Override
    public String toString() {
        return label;
    }

    /** 宽松解析，认不出来返回 {@code null}。 */
    public static VoiceLanguage parse(String raw) {
        if (raw == null) return null;
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "follow":
            case "auto":
            case "跟随":
                return FOLLOW;
            case "zh":
            case "zh_cn":
            case "cn":
            case "中文":
                return ZH;
            case "en":
            case "en_us":
            case "english":
            case "英文":
                return EN;
            case "ja":
            case "jp":
            case "ja_jp":
            case "日语":
                return JA;
            case "ko":
            case "ko_kr":
            case "韩语":
                return KO;
            default:
                return null;
        }
    }

    /**
     * 把 Minecraft 的语言代码映射过来（如 {@code zh_cn}）。
     * 认不出来时返回 {@code fallback}。
     */
    public static VoiceLanguage fromLanguageCode(String code, VoiceLanguage fallback) {
        if (code == null || code.isBlank()) return fallback;
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("zh")) return ZH;
        if (normalized.startsWith("en")) return EN;
        if (normalized.startsWith("ja")) return JA;
        if (normalized.startsWith("ko")) return KO;
        return fallback;
    }
}
