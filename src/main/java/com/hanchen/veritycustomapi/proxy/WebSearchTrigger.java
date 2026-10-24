package com.hanchen.veritycustomapi.proxy;

import com.hanchen.veritycustomapi.ConfigManager;

/**
 * 「这句话该不该联网搜」的判定。
 *
 * <p>抽自 {@code OllamaProxyServer.ChatProxyHandler.shouldSearch}。
 * 抽出的理由不是行数，而是**判据和词表必须放在一起**：
 * 三段词表（显式要求 / 疑问句式 / 时效信号）和判定逻辑分开放时，
 * 改词表的人看不到判定顺序，很容易以为「加了词就一定命中」，
 * 实际上「疑问 + 时效」必须同时成立才算。
 *
 * <p>注意：这条判定只服务于「关键词预判」这条旧路径。主路径是把
 * {@code web_search} 作为工具交给模型自己决定——模型知道自己要答什么，
 * 比用关键词猜「现在几点了」准得多。这里保留是因为「每轮强制搜索」和
 * 旧配置仍在使用它。
 */
public final class WebSearchTrigger {

    private WebSearchTrigger() {
    }

    /** 话里直接要求联网的词。 */
    private static final String[] EXPLICIT_SEARCH_HINTS = {
            "搜索", "搜一下", "搜下", "联网", "上网查", "网上查", "查一下", "帮我查", "查查",
            "最新消息", "实时", "search the web", "search online", "look up online", "google"
    };

    /** 疑问句式信号。 */
    private static final String[] QUESTION_HINTS = {
            "?", "？", "吗", "呢", "什么", "多少", "怎么", "如何", "为什么", "哪", "谁",
            "when", "what", "how", "why", "which", "who"
    };

    /** 时效性信号：这些词一出现，答案就依赖「现在」。 */
    private static final String[] RECENCY_HINTS = {
            "最新", "今天", "今日", "现在", "目前", "最近", "近期", "今年", "昨天", "明天",
            "新闻", "发布", "上线", "版本", "价格", "多少钱", "股价", "汇率", "天气",
            "比分", "赛程", "发售", "更新日志",
            "latest", "today", "now", "current", "recent", "news", "release", "price", "weather"
    };

    /**
     * 这句话要不要搜。
     *
     * <p>判据刻意保守：**宁可漏搜**（模型自己会说不知道），也不要每句都搜——
     * 每次搜索都是一次真实请求，有延迟也有费用。
     *
     * <p>顺序：
     * <ol>
     *   <li>开关关着 / 空串 → 不搜；</li>
     *   <li>「每轮强制搜索」开着 → 一定搜（关键词判据再怎么写都会漏，
     *       「现在几点了」「这游戏最新版多少」都可能不含预设词）；</li>
     *   <li>命中显式要求词 → 搜；</li>
     *   <li>疑问信号与时效信号**同时**成立 → 搜，只有其一不搜。</li>
     * </ol>
     */
    public static boolean shouldSearch(String query) {
        if (query == null || query.isBlank()) return false;
        if (!ConfigManager.isWebSearchEnabled()) return false;
        if (ConfigManager.isWebSearchAlways()) return true;

        String q = query.toLowerCase();
        for (String word : EXPLICIT_SEARCH_HINTS) {
            if (q.contains(word)) return true;
        }

        boolean asking = false;
        for (String word : QUESTION_HINTS) {
            if (q.contains(word)) {
                asking = true;
                break;
            }
        }
        if (!asking) return false;
        for (String word : RECENCY_HINTS) {
            if (q.contains(word)) return true;
        }
        return false;
    }
}
