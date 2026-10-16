package com.hanchen.veritycustomapi.client;

import com.hanchen.veritycustomapi.compat.VeritySpeaker;
import net.minecraft.client.Minecraft;
import varmite.verity.entity.verity.VerityEntity;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 客户端对话数据源：缓存最近一句 Verity 台词、当前表情和今日对话次数，给 HUD 用。
 * 台词从聊天栏来，表情读实体同步的 variant，前缀识别交给 {@link VeritySpeaker}。
 */
public final class VerityDialogueFeed {
    /** 原版实体使用的合法表情，取不到时回退 happy。 */
    private static final List<String> KNOWN_VARIANTS = List.of(
            "crazy_talking", "happy", "happy_sleep", "happy_talking", "hurt", "neutral", "noface",
            "serious_1", "serious_2", "serious_3", "serious_talking", "evil", "evil_talking",
            "smiling_evil", "crazy", "neutral_talking");
    private static final String FALLBACK_VARIANT = "happy";

    private static final Object LOCK = new Object();
    private static volatile String latestText = "";
    private static volatile String latestVariant = FALLBACK_VARIANT;
    private static volatile long latestAt;
    private static volatile int todayCount;
    private static volatile long countedDay = Long.MIN_VALUE;

    private VerityDialogueFeed() {
    }

    /**
     * 从聊天栏原文里识别 Verity 台词。
     *
     * @return 是否识别成功（不是 Verity 的发言时返回 false，不产生任何副作用）
     */
    public static boolean captureFromChat(String rawMessage) {
        String line = VeritySpeaker.stripPrefix(rawMessage);
        if (line == null) {
            return false;
        }
        // 双语消息：HUD 气泡显示中文译文，否则会把内部的 <|verity_zh|> 分隔标记画出来
        // 只接受最终完整台词；流式中间片段若还带协议标记，不计入 HUD 的今日次数。
        if (rawMessage != null && rawMessage.contains("<|verity_zh|>")
                && com.hanchen.veritycustomapi.compat.BilingualSpeechText.translatedContent(rawMessage) == null) {
            return false;
        }
        String translated = com.hanchen.veritycustomapi.compat.BilingualSpeechText.translatedContent(rawMessage);
        if (translated != null) {
            line = translated;
        } else {
            // 非双语（或译文被清洗成空）：至少把「[翻译] / 译文：」这类标签和
            // 半截双语分隔符去掉，免得被画进气泡里
            line = com.hanchen.veritycustomapi.compat.BilingualSpeechText.stripDelimiter(line);
            line = com.hanchen.veritycustomapi.compat.BilingualSpeechText.stripLabels(line);
        }
        synchronized (LOCK) {
            latestText = line;
            latestVariant = readNearestVariant();
            latestAt = System.currentTimeMillis();
            long today = LocalDate.now().toEpochDay();
            if (countedDay != today) {
                countedDay = today;
                todayCount = 0;
            }
            todayCount++;
        }
        return true;
    }

    /** 最近一句台词；没有则返回空串。 */
    public static String latestText() {
        return latestText;
    }

    /** 最近一次发言距离现在是否还在展示时间内。 */
    public static boolean isFresh(int seconds) {
        long at = latestAt;
        return at > 0L && System.currentTimeMillis() - at < Math.max(1, seconds) * 1000L;
    }

    /** 用于 HUD 头像的表情：优先读当前最近实体的真实 variant。 */
    public static String displayVariant() {
        String live = readNearestVariant();
        return live == null || live.isBlank() ? latestVariant : live;
    }

    public static int todayCount() {
        long today = LocalDate.now().toEpochDay();
        return countedDay == today ? todayCount : 0;
    }

    public static void clear() {
        synchronized (LOCK) {
            latestText = "";
            latestVariant = FALLBACK_VARIANT;
            latestAt = 0L;
        }
    }

    /** 取玩家附近最近的 Verity 实体，用于读表情。 */
    public static VerityEntity nearestVerity() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return null;
        }
        try {
            List<VerityEntity> candidates = minecraft.level.getEntitiesOfClass(
                    VerityEntity.class, minecraft.player.getBoundingBox().inflate(48.0D));
            return candidates.stream()
                    .min(Comparator.comparingDouble(entity -> entity.distanceToSqr(minecraft.player)))
                    .orElse(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * 反射读取表情名：原版字段名在不同版本可能变化，读不到就回退，
     * 避免为了一个 HUD 头像把编译期依赖绑死。
     */
    private static String readNearestVariant() {
        VerityEntity entity = nearestVerity();
        if (entity == null) {
            return FALLBACK_VARIANT;
        }
        try {
            Object value = entity.getClass().getMethod("getVariant").invoke(entity);
            return normalizeVariant(value == null ? null : value.toString());
        } catch (Throwable ignored) {
            return FALLBACK_VARIANT;
        }
    }

    private static String normalizeVariant(String raw) {
        if (raw == null || raw.isBlank()) {
            return FALLBACK_VARIANT;
        }
        String cleaned = raw.trim().toLowerCase(Locale.ROOT);
        int slash = cleaned.lastIndexOf('/');
        if (slash >= 0) {
            cleaned = cleaned.substring(slash + 1);
        }
        if (cleaned.endsWith(".png")) {
            cleaned = cleaned.substring(0, cleaned.length() - 4);
        }
        return KNOWN_VARIANTS.contains(cleaned) ? cleaned : FALLBACK_VARIANT;
    }
}
