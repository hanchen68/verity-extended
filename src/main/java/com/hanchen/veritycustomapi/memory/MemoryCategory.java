package com.hanchen.veritycustomapi.memory;

import java.util.Locale;

/**
 * 记忆分类。分类只存在扩展层的旁路元数据里，不动原版 verity_memory.json 的存储格式，
 * 原版记忆工具照常能用。每个分类同时是一个 key 前缀，按 <分类>_<名称> 命名就会自动归类。
 */
public enum MemoryCategory {
    /** 玩家偏好：喜欢什么、不喜欢什么、习惯怎么玩。 */
    PREFERENCE("pref", "偏好"),
    /** 客观事实：玩家告诉过 Verity 的稳定信息。 */
    FACT("fact", "事实"),
    /** 地点与坐标：基地、矿洞、重要建筑的位置。 */
    PLACE("place", "地点"),
    /** 约定与计划：双方说好要做的事。 */
    PLAN("plan", "约定"),
    /** 临时信息：一次性、会过期的事，衰减最快。 */
    TEMPORARY("temp", "临时");

    private final String id;
    private final String label;

    MemoryCategory(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    /** 解析模型返回的分类，认不出来就回退到「事实」。 */
    public static MemoryCategory fromId(String raw) {
        if (raw == null || raw.isBlank()) {
            return FACT;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (MemoryCategory category : values()) {
            if (category.id.equals(normalized)
                    || category.name().toLowerCase(Locale.ROOT).equals(normalized)
                    || category.label.equals(raw.trim())) {
                return category;
            }
        }
        return FACT;
    }

    /** 没有元数据时，按 key 前缀推分类。 */
    public static MemoryCategory inferFromKey(String key) {
        if (key == null || key.isBlank()) {
            return FACT;
        }
        String lower = key.toLowerCase(Locale.ROOT);
        // 先剥掉玩家隔离前缀（p<8位十六进制>_），后面那个才是真正的分类前缀
        if (lower.length() > 10 && lower.charAt(0) == 'p' && lower.charAt(9) == '_'
                && isHexToken(lower.substring(1, 9))) {
            return inferFromKey(lower.substring(10));
        }
        // 6.2 起原版把记忆分类写进了主提示词：like_ / dislike_ / player_traits / note_
        if (lower.startsWith("like_") || lower.startsWith("dislike_")) {
            return PREFERENCE;
        }
        if (lower.startsWith("player_traits") || lower.startsWith("traits_")) {
            return FACT;
        }
        if (lower.startsWith("note_")) {
            return PLAN;
        }
        for (MemoryCategory category : values()) {
            if (lower.startsWith(category.id + "_") || lower.startsWith(category.id + ":")) {
                return category;
            }
        }
        return FACT;
    }

    /** 是不是全是小写十六进制字符，用来认玩家隔离前缀里的短 UUID 段。 */
    private static boolean isHexToken(String token) {
        if (token.isEmpty()) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
