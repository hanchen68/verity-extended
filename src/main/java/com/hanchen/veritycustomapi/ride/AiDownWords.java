package com.hanchen.veritycustomapi.ride;

/** 站头/骑乘场景的中文词表：AI 明确拒绝词与玩家"下来"请求词。 */
public final class AiDownWords {
    /** AI 明确拒绝（只认与骑乘/站头强相关的表达，避免普通对话里的"不行"被误判成拒绝）。 */
    private static final String[] REFUSALS = {
            "不能站", "别站", "不要站", "别蹲", "快下来",
            "别骑", "不要骑", "不能骑", "不可以骑",
            "放开我", "让我下来", "放我下来"
    };

    /** 明确请求用语，命中即视为"让我下来"。 */
    private static final String[] DOWN_REQUESTS = {
            "抱我下来", "放我下来", "别站头", "别站头上", "别蹲", "别骑",
            "不要骑", "别骑我", "跟我下来", "脱离"
    };

    /** 单独出现的"下来/下去"算请求，但先排除这些常见复合词，避免"接下来/坐下来"误触发。 */
    private static final String[] BARE_WORDS = {"下来", "下去"};

    private static final String[] COMPOUND_FALSE_POSITIVES = {
            "接下来", "坐下来", "留下来", "停下来", "记下来", "写下来", "拍下来",
            "掉下来", "摔下来", "静下来", "说下去", "坚持下去", "活下去",
            "吃下去", "喝下去", "看下去", "想下去"
    };

    private AiDownWords() {
    }

    public static boolean isRefusal(String answer) {
        return containsAny(answer, REFUSALS);
    }

    public static boolean isDownRequest(String message) {
        if (containsAny(message, DOWN_REQUESTS)) return true;
        return containsAny(message, BARE_WORDS) && !containsAny(message, COMPOUND_FALSE_POSITIVES);
    }

    private static boolean containsAny(String text, String[] words) {
        if (text == null || text.isBlank()) return false;
        String value = text.replace(" ", "").replace("\u3000", "");
        for (String word : words) {
            if (value.contains(word)) return true;
        }
        return false;
    }
}
