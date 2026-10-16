package com.hanchen.veritycustomapi.client;

import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 定时提醒：聊天里「20 分钟后提醒我收菜」这种请求记成待办，到点让主动感知开口。
 * 用本地正则识别，不花 token，也不会因为模型漏输出就丢提醒。
 */
public final class ReminderManager {
    private static final long MIN_DELAY_MS = 5_000L;
    private static final long MAX_DELAY_MS = 24L * 60L * 60L * 1000L;
    /** 必须同时出现「时间量」与「提醒类动词」才认为是提醒请求，避免误判普通聊天。 */
    private static final Pattern DURATION = Pattern.compile(
            "(\\d+)\\s*(秒钟|秒|分钟|分|个小时|小时|时|seconds|second|sec|minutes|minute|min|hours|hour|hr)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TRIGGER = Pattern.compile(
            "提醒|叫我|记得|remind", Pattern.CASE_INSENSITIVE);
    /** 把小助手式的客套话从提醒正文里剥掉，只留下真正的待办内容。 */
    private static final Pattern FILLER = Pattern.compile(
            "(请|帮我)?\\s*(到时候|待会|一会|一会儿|之后|以后|到点)?\\s*(记得)?\\s*"
                    + "(提醒我一下|提醒我|提醒|叫我一下|叫我)",
            Pattern.CASE_INSENSITIVE);

    /** 一条待触发的提醒。 */
    public record Entry(long fireAt, String text) {
    }

    private static final List<Entry> ENTRIES = new CopyOnWriteArrayList<>();

    private ReminderManager() {
    }

    /** 解析结果：延迟毫秒数 + 提醒正文。 */
    record Parsed(long delayMillis, String content) {
    }

    /**
     * 纯解析：只做文本判断，不读配置、不接触游戏状态，便于回归测试。
     *
     * @return 解析成功返回结果，否则返回 null
     */
    static Parsed parse(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        if (!TRIGGER.matcher(message).find()) {
            return null;
        }
        Matcher matcher = DURATION.matcher(message);
        if (!matcher.find()) {
            return null;
        }
        long amount;
        try {
            amount = Long.parseLong(matcher.group(1));
        } catch (NumberFormatException error) {
            return null;
        }
        long delay = toMillis(amount, matcher.group(2));
        if (delay <= 0L) {
            return null;
        }
        return new Parsed(delay, extractContent(message, matcher.start(), matcher.end()));
    }

    /**
     * 尝试从玩家发言里解析出定时提醒。
     *
     * @return 是否成功登记
     */
    public static boolean tryScheduleFromMessage(String message) {
        if (!ConfigManager.isReminderEnabled()) {
            return false;
        }
        Parsed parsed = parse(message);
        if (parsed == null) {
            return false;
        }
        add(parsed.delayMillis(), parsed.content());
        return true;
    }

    /** 直接登记一条提醒（供命令使用）。 */
    public static void add(long delayMs, String text) {
        long clamped = Math.max(MIN_DELAY_MS, Math.min(MAX_DELAY_MS, delayMs));
        String content = text == null || text.isBlank() ? "该做的事情到点了" : text.trim();
        ENTRIES.add(new Entry(System.currentTimeMillis() + clamped, content));
        notifyClient("§b【提醒】好，" + formatDelay(clamped) + "后我会提醒你：" + content);
        OllamaProxyMod.LOGGER.info("定时提醒已登记: {}ms 后 -> {}", clamped, content);
    }

    /** 客户端每 tick 检查是否有到点的提醒。 */
    public static void onClientTick(Minecraft minecraft) {
        if (ENTRIES.isEmpty() || minecraft.player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        List<Entry> due = new ArrayList<>();
        for (Entry entry : ENTRIES) {
            if (entry.fireAt() <= now) {
                due.add(entry);
            }
        }
        for (Entry entry : due) {
            if (ENTRIES.remove(entry)) {
                VerityAwarenessManager.triggerReminder(entry.text());
            }
        }
    }

    public static List<Entry> pending() {
        return List.copyOf(ENTRIES);
    }

    public static void clear() {
        ENTRIES.clear();
    }

    public static String formatDelay(long millis) {
        if (millis >= 3_600_000L) {
            return Math.max(1L, millis / 3_600_000L) + " 小时";
        }
        if (millis >= 60_000L) {
            return Math.max(1L, millis / 60_000L) + " 分钟";
        }
        return Math.max(1L, millis / 1000L) + " 秒";
    }

    private static long toMillis(long amount, String rawUnit) {
        if (amount <= 0L) {
            return 0L;
        }
        String unit = rawUnit == null ? "" : rawUnit.toLowerCase(Locale.ROOT);
        if (unit.contains("秒") || unit.contains("sec")) {
            return amount * 1000L;
        }
        if (unit.contains("分") || unit.contains("min")) {
            return amount * 60_000L;
        }
        if (unit.contains("时") || unit.contains("hour") || unit.contains("hr")) {
            return amount * 3_600_000L;
        }
        return 0L;
    }

    private static String extractContent(String message, int start, int end) {
        String withoutDuration = message.substring(0, start) + " " + message.substring(end);
        String cleaned = FILLER.matcher(withoutDuration).replaceAll(" ")
                .replaceAll("[，,。.、!！?？;；:：\"'“”]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        // 时间词被摘掉后常留下一个孤立的「后 / 之后」，再去一层
        cleaned = cleaned.replaceAll("^(后|之后|以后|到点|再)\\s*", "").trim();
        return cleaned.isBlank() ? "该做的事情到点了" : cleaned;
    }

    private static void notifyClient(String text) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(Component.literal(text), false);
            }
        });
    }
}
