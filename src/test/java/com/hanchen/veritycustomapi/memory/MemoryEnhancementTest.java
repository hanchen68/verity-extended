package com.hanchen.veritycustomapi.memory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 记忆增强层的纯逻辑回归：分词、相关性排序、key 规则、模型输出容错解析。
 * 不依赖 Minecraft 运行时，可直接用 JavaExec 跑。
 */
public final class MemoryEnhancementTest {
    private MemoryEnhancementTest() {
    }

    public static void main(String[] args) {
        tokenizeFindsChineseAndEnglish();
        rankPrefersRelevantMemory();
        rankSkipsUnrelatedMemories();
        rankFallsBackToImportanceWhenQueryIsEmpty();
        buildKeyAppliesCategoryAndPlayerPrefix();
        parseEntriesToleratesCodeFenceAndNoise();
        parseEntriesSkipsMalformedItems();
        inferCategoryFromKeyPrefix();
        rollingSummarySafeWithoutData();
        sanitizeOutputCleansText();
        System.out.println("Memory enhancement checks passed");
    }

    private static void tokenizeFindsChineseAndEnglish() {
        Set<String> tokens = MemoryRanker.tokenize("我喜欢 redstone 装置");
        assertTrue(tokens.contains("redstone"), "英文单词应被识别");
        assertTrue(tokens.contains("喜欢"), "中文二元组应被识别");
    }

    private static void rankPrefersRelevantMemory() {
        long now = System.currentTimeMillis();
        Map<String, String> memories = new LinkedHashMap<>();
        memories.put("pref_redstone", "玩家喜欢用红石做自动化");
        memories.put("place_base", "基地在 100,64,200");
        Map<String, MemoryMeta> metas = new LinkedHashMap<>();
        metas.put("pref_redstone", new MemoryMeta(MemoryCategory.PREFERENCE, 3, now, null));
        metas.put("place_base", new MemoryMeta(MemoryCategory.PLACE, 3, now, null));

        List<MemoryRanker.Scored> ranked = MemoryRanker.rank(memories, metas::get,
                MemoryRanker.tokenize("帮我做个红石自动门"), now, 30, 6);

        assertEquals(1, ranked.size(), "只有红石那条应该命中");
        assertEquals("pref_redstone", ranked.get(0).key(), "命中项应是红石偏好");
    }

    private static void rankSkipsUnrelatedMemories() {
        long now = System.currentTimeMillis();
        Map<String, String> memories = new LinkedHashMap<>();
        memories.put("place_base", "基地在 100,64,200");
        List<MemoryRanker.Scored> ranked = MemoryRanker.rank(memories, key -> null,
                MemoryRanker.tokenize("今天心情不错"), now, 30, 6);
        assertEquals(0, ranked.size(), "完全不相关时不应注入记忆");
    }

    private static void rankFallsBackToImportanceWhenQueryIsEmpty() {
        long now = System.currentTimeMillis();
        Map<String, String> memories = new LinkedHashMap<>();
        memories.put("fact_low", "不重要");
        memories.put("fact_high", "很重要");
        Map<String, MemoryMeta> metas = new LinkedHashMap<>();
        metas.put("fact_low", new MemoryMeta(MemoryCategory.FACT, 1, now, null));
        metas.put("fact_high", new MemoryMeta(MemoryCategory.FACT, 5, now, null));

        List<MemoryRanker.Scored> ranked = MemoryRanker.rank(memories, metas::get,
                Set.of(), now, 30, 6);

        assertEquals(2, ranked.size(), "空查询时按重要度排序而不是清空");
        assertEquals("fact_high", ranked.get(0).key(), "重要度高的排前面");
    }

    private static void buildKeyAppliesCategoryAndPlayerPrefix() {
        String key = MemoryAutoWriter.buildKey("Likes Redstone!", MemoryCategory.PREFERENCE,
                "3f2a1b4c-0000-0000-0000-000000000000");
        assertEquals("p3f2a1b4c_pref_likes_redstone", key, "key 应带玩家与分类前缀");

        String global = MemoryAutoWriter.buildKey("pref_likes_tea", MemoryCategory.PREFERENCE, null);
        assertEquals("pref_likes_tea", global, "已有分类前缀时不应重复添加");
    }

    private static void parseEntriesToleratesCodeFenceAndNoise() {
        String text = "好的，我记下了：\n```json\n[{\"key\":\"likes_tea\",\"value\":\"玩家喜欢喝奶茶\","
                + "\"category\":\"pref\",\"importance\":4}]\n```";
        List<MemoryAutoWriter.Entry> entries = MemoryAutoWriter.parseEntries(text);
        assertEquals(1, entries.size(), "应解析出 1 条记忆");
        assertEquals("likes_tea", entries.get(0).key(), "key 解析正确");
        assertEquals(4, entries.get(0).importance(), "重要度解析正确");
    }

    private static void parseEntriesSkipsMalformedItems() {
        String text = "[{\"key\":\"\",\"value\":\"缺 key\"},{\"key\":\"ok\",\"value\":\"正常\"},"
                + "{\"key\":\"no_value\",\"value\":\"\"}]";
        List<MemoryAutoWriter.Entry> entries = MemoryAutoWriter.parseEntries(text);
        assertEquals(1, entries.size(), "缺字段的条目应被跳过");
        assertEquals("ok", entries.get(0).key(), "保留完整条目");
    }

    private static void inferCategoryFromKeyPrefix() {
        assertEquals(MemoryCategory.PREFERENCE, MemoryCategory.inferFromKey("pref_likes_tea"),
                "pref_ 前缀应识别为偏好");
        assertEquals(MemoryCategory.PLACE, MemoryCategory.inferFromKey("place_base"),
                "place_ 前缀应识别为地点");
        assertEquals(MemoryCategory.FACT, MemoryCategory.inferFromKey("随便起的名字"),
                "无前缀时回退到事实");

        // 6.2 原版写进主提示词的分类前缀（like_ / dislike_ / player_traits / note_）
        assertEquals(MemoryCategory.PREFERENCE, MemoryCategory.inferFromKey("like_carrot"),
                "like_ 前缀应识别为偏好");
        assertEquals(MemoryCategory.PREFERENCE, MemoryCategory.inferFromKey("dislike_sudden_teleport"),
                "dislike_ 前缀应识别为偏好");
        assertEquals(MemoryCategory.PLACE, MemoryCategory.inferFromKey("place_home"),
                "place_ 前缀（新版）应识别为地点");
        assertEquals(MemoryCategory.FACT, MemoryCategory.inferFromKey("player_traits"),
                "player_traits 应识别为事实");
        assertEquals(MemoryCategory.PLAN, MemoryCategory.inferFromKey("note_promise_lanterns"),
                "note_ 前缀应识别为约定");

        // 玩家隔离前缀（p<8位十六进制>_）之后仍能识别分类
        assertEquals(MemoryCategory.PREFERENCE, MemoryCategory.inferFromKey("p3f2a1b4c_pref_likes_tea"),
                "隔离前缀后的 pref_ 应识别为偏好");
        assertEquals(MemoryCategory.PREFERENCE, MemoryCategory.inferFromKey("p3f2a1b4c_like_tea"),
                "隔离前缀后的 like_ 应识别为偏好");
    }

    private static void rollingSummarySafeWithoutData() {
        // 无摘要数据时应返回空串；不依赖世界与配置，不能抛异常
        String summary = com.hanchen.veritycustomapi.memory.ConversationContext.rollingSummary();
        assertTrue(summary.isEmpty(), "无摘要数据时应返回空串");
    }

    private static void sanitizeOutputCleansText() {
        assertEquals("clean", com.hanchen.veritycustomapi.memory.ChatCompletionClient
                .sanitizeOutput("§cclean§r", 100), "格式码应被剥离");
        String longText = "あ".repeat(50);
        assertTrue(com.hanchen.veritycustomapi.memory.ChatCompletionClient
                .sanitizeOutput(longText, 10).endsWith("…"), "超长应截断并加省略号");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertEquals(int expected, int actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + " expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertEquals(String expected, String actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + " expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertEquals(MemoryCategory expected, MemoryCategory actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + " expected=" + expected + ", actual=" + actual);
        }
    }
}
