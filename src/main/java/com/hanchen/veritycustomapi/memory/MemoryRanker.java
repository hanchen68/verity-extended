package com.hanchen.veritycustomapi.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 记忆相关性排序：关键词命中 × 重要度 × 时间衰减。不用向量模型，只做确定性的字符/词命中统计，
 * 省得引入额外依赖和网络请求。目标是挑出最可能用得上的几条，不追求精确的语义匹配。
 */
public final class MemoryRanker {
    private static final long MILLIS_PER_DAY = 86_400_000L;
    /** 时间衰减的下限系数，免得旧记忆被彻底排除。 */
    private static final double DECAY_FLOOR = 0.35;

    private MemoryRanker() {
    }

    /** 一条参与排序的记忆。 */
    public record Scored(String key, String value, double score, int hits) {
    }

    /** 把用户这句话切成检索用的 token：ASCII 词加中文二元组，不引分词库也能有能用的召回。 */
    public static Set<String> tokenize(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder word = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (c < 128 && (Character.isLetterOrDigit(c))) {
                flushCjk(cjk, tokens);
                word.append(c);
            } else if (Character.isIdeographic(c)) {
                flushWord(word, tokens);
                cjk.append(c);
            } else {
                flushWord(word, tokens);
                flushCjk(cjk, tokens);
            }
        }
        flushWord(word, tokens);
        flushCjk(cjk, tokens);
        return tokens;
    }

    private static void flushWord(StringBuilder buffer, Set<String> tokens) {
        if (buffer.length() >= 2) {
            tokens.add(buffer.toString());
        }
        buffer.setLength(0);
    }

    private static void flushCjk(StringBuilder buffer, Set<String> tokens) {
        if (buffer.length() == 1) {
            tokens.add(buffer.toString());
        } else {
            for (int i = 0; i + 1 < buffer.length(); i++) {
                tokens.add(buffer.substring(i, i + 2));
            }
        }
        buffer.setLength(0);
    }

    /** 命中次数：命中的 token 数，key 命中额外加权。 */
    public static int hits(String key, String value, Set<String> queryTokens) {
        if (queryTokens.isEmpty()) {
            return 0;
        }
        String keyText = key == null ? "" : key.toLowerCase(Locale.ROOT);
        String valueText = value == null ? "" : value.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String token : queryTokens) {
            if (valueText.contains(token)) {
                score += 2;
            }
            if (keyText.contains(token)) {
                score += 1;
            }
        }
        return score;
    }

    /** 单条记忆的权重：重要度乘时间衰减。 */
    public static double weight(MemoryMeta meta, long now, int decayDays) {
        int importance = meta == null ? 3 : meta.importance();
        double importanceFactor = 0.55 + importance * 0.15;
        long reference = meta == null ? 0L : meta.referenceAt();
        double decay = 1.0;
        if (reference > 0L) {
            double days = Math.max(0.0, (now - reference) / (double) MILLIS_PER_DAY);
            double halfLife = Math.max(1, decayDays);
            double raw = Math.exp(-days / halfLife);
            decay = DECAY_FLOOR + (1.0 - DECAY_FLOOR) * raw;
        }
        if (meta != null && meta.category() == MemoryCategory.TEMPORARY) {
            decay *= 0.6;
        }
        return importanceFactor * decay;
    }

    /** 排序取前 N 条：有 token 就只留命中的，没 token（比如极短的招呼语）就按重要度和新鲜度排。 */
    public static List<Scored> rank(Map<String, String> memories,
                                    Function<String, MemoryMeta> metaLookup,
                                    Set<String> queryTokens,
                                    long now,
                                    int decayDays,
                                    int topN) {
        List<Scored> scored = new ArrayList<>();
        if (memories == null || memories.isEmpty() || topN <= 0) {
            return scored;
        }
        boolean requireHit = !queryTokens.isEmpty();
        for (Map.Entry<String, String> entry : memories.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue() == null ? "" : entry.getValue();
            int hits = hits(key, value, queryTokens);
            if (requireHit && hits <= 0) {
                continue;
            }
            // 命中权重做归一，免得长文本光靠字数多就排名虚高
            double relevance = hits / (double) Math.max(1, queryTokens.size() * 3);
            relevance = Math.min(1.0, relevance);
            MemoryMeta meta = metaLookup == null ? null : metaLookup.apply(key);
            double score = weight(meta, now, decayDays) * (0.35 + 0.65 * relevance);
            scored.add(new Scored(key, value, score, hits));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparing(Scored::key));
        return scored.size() > topN ? new ArrayList<>(scored.subList(0, topN)) : scored;
    }
}
