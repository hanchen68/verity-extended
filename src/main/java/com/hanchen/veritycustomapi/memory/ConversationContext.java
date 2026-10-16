package com.hanchen.veritycustomapi.memory;

import com.google.gson.Gson;
import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.client.ConversationLogStore;
import com.hanchen.veritycustomapi.cloud.BillingService;
import com.hanchen.veritycustomapi.cloud.UsageMeter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 分层上下文：最近 {@value #RECENT_KEEP_TURNS} 轮逐字保留，交给主链路的窗口承载；
 * 更早的轮次异步压成滚动摘要。摘要和记忆注入各占一块固定预算，热路径上不阻塞。
 */
public final class ConversationContext {
    /** 逐字保留的最近轮数，超出的部分进摘要。 */
    static final int RECENT_KEEP_TURNS = 12;
    /** 历史不够这个轮数就不摘要：前期对话本来就短，逐字够了。 */
    private static final int SUMMARIZE_MIN_TURNS = 16;
    /** 单次摘要请求的输入字符上限，免得摘要请求自己把窗口撑爆。 */
    private static final int MAX_SUMMARY_INPUT_CHARS = 6000;
    /** 注入块里摘要段占的字符预算。 */
    static final int SUMMARY_BUDGET_CHARS = 800;
    /** 摘要刷新的最小间隔，兜底限流：世界刚加载、对话很少时也不会空转。 */
    private static final long REFRESH_MIN_INTERVAL_MS = 60_000L;

    private static final String SUMMARY_SYSTEM_PROMPT =
            "把「Verity 游戏助手与玩家的历史对话」浓缩成第三人称事实清单，供后续对话作为背景。"
                    + "保留：玩家偏好、重要约定、地点与项目、情绪变化、未完结的话题。"
                    + "去掉寒暄、重复与琐碎细节。用简短条目输出，总长不超过 300 字，不要评论。";

    private static final Gson GSON = new Gson();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Verity-Context-Summary");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean();
    private static final AtomicLong LAST_REFRESH_AT = new AtomicLong();

    private ConversationContext() {
    }

    /**
     * 取当前世界的滚动摘要。这是热路径，只查缓存、不阻塞；
     * 距上次刷新够久了，顺带排一个后台刷新。
     */
    public static String rollingSummary() {
        String world = ConversationLogStore.currentWorldName();
        ConversationSummaryStore.Summary summary = ConversationSummaryStore.get(world);
        long now = System.currentTimeMillis();
        if (now - LAST_REFRESH_AT.get() >= REFRESH_MIN_INTERVAL_MS
                && IN_FLIGHT.compareAndSet(false, true)) {
            LAST_REFRESH_AT.set(now);
            WORKER.execute(() -> {
                try {
                    refresh(world);
                } catch (Throwable error) {
                    OllamaProxyMod.LOGGER.warn("滚动摘要生成失败", error);
                } finally {
                    IN_FLIGHT.set(false);
                }
            });
        }
        return summary == null ? "" : summary.text();
    }

    /** 后台生成或增补摘要：读日志，挑出逐字窗口之外的轮次，增量喂给 LLM。 */
    private static void refresh(String world) {
        if (!ConfigManager.isConversationLogEnabled()) {
            return;
        }
        List<ConversationLogStore.Entry> worldTurns = new ArrayList<>();
        for (ConversationLogStore.Entry entry : ConversationLogStore.load(0)) {
            // 旧版本记录没有世界字段，当作当前世界
            if (entry.world() == null || entry.world().isBlank() || entry.world().equals(world)) {
                worldTurns.add(entry);
            }
        }
        if (worldTurns.size() < SUMMARIZE_MIN_TURNS) {
            return;
        }
        int verbatimFrom = worldTurns.size() - RECENT_KEEP_TURNS;
        List<ConversationLogStore.Entry> coverable = worldTurns.subList(0, verbatimFrom);
        ConversationSummaryStore.Summary previous = ConversationSummaryStore.get(world);
        int alreadySeen = previous == null ? 0 : Math.min(previous.seenEntries(), coverable.size());
        List<ConversationLogStore.Entry> newTurns = coverable.subList(alreadySeen, coverable.size());
        if (previous != null && newTurns.isEmpty()) {
            return;
        }

        SummaryInput input = buildSummaryInput(previous, newTurns);
        if (input == null) {
            return;
        }
        String summarized = requestSummary(routeOrSkip(), input.text());
        if (summarized == null || summarized.isBlank()) {
            return;
        }
        // 只把真正进了输入的轮次记成已覆盖，被预算截断的旧轮次留给下次，
        // 免得「记了账却没写进摘要」这种静默丢信息
        ConversationSummaryStore.put(world, new ConversationSummaryStore.Summary(
                clip(summarized.strip(), SUMMARY_BUDGET_CHARS), input.lastCoveredAt(),
                System.currentTimeMillis(), alreadySeen + input.includedTurns()));
        OllamaProxyMod.LOGGER.info("滚动摘要已更新（累计覆盖 {} 轮）", alreadySeen + input.includedTurns());
    }

    /** 摘要请求的输入，以及它覆盖的范围。 */
    private record SummaryInput(String text, int includedTurns, long lastCoveredAt) {
    }

    /** 拼「旧摘要 + 新增轮次」，超输入预算就从最旧的一头丢。 */
    private static SummaryInput buildSummaryInput(ConversationSummaryStore.Summary previous,
                                                  List<ConversationLogStore.Entry> newTurns) {
        StringBuilder input = new StringBuilder();
        if (previous != null && !previous.text().isBlank()) {
            input.append("已有摘要：\n").append(previous.text()).append("\n\n");
        }
        List<ConversationLogStore.Entry> ordered = new ArrayList<>(newTurns);
        List<String> lines = new ArrayList<>(ordered.size());
        int total = input.length();
        // 从最新往回收集，保证预算内装的是最近发生的事
        for (int i = ordered.size() - 1; i >= 0; i--) {
            ConversationLogStore.Entry entry = ordered.get(i);
            String line = "玩家：" + oneLine(entry.user()) + "\nVerity：" + oneLine(entry.assistant()) + "\n\n";
            if (total + line.length() > MAX_SUMMARY_INPUT_CHARS) {
                break;
            }
            lines.add(0, line);
            total += line.length();
        }
        if (lines.isEmpty()) {
            // 预算被旧摘要占满了，这轮没有能并进来的新对话
            return null;
        }
        if (lines.size() < ordered.size()) {
            OllamaProxyMod.LOGGER.warn("本轮有 {} 轮超出摘要输入预算，暂不标记为已覆盖", ordered.size() - lines.size());
        }
        input.append("需要并入摘要的新增对话：\n");
        for (String line : lines) {
            input.append(line);
        }
        long lastCoveredAt = ordered.get(ordered.size() - lines.size()).at();
        return new SummaryInput(input.toString(), lines.size(), lastCoveredAt);
    }

    private static ConfigManager.ChatRoute routeOrSkip() {
        List<ConfigManager.ChatRoute> routes = ConfigManager.snapshotChatRoutes();
        return routes.isEmpty() ? null : routes.get(0);
    }

    /** 发一次摘要请求；计费逻辑和记忆自动写入保持一致，失败返回 null。 */
    private static String requestSummary(ConfigManager.ChatRoute route, String input) {
        if (route == null || route.model() == null || route.model().isBlank()) {
            return null;
        }
        boolean paidBuiltin = "builtin".equals(route.source()) && "paid".equalsIgnoreCase(route.tier());
        String requestId = BillingService.requestId();
        BillingService.Reservation reservation = null;
        String requestBody = null;
        String responseBody;
        try {
            requestBody = GSON.toJson(ChatCompletionClient.buildRequest(
                    route.model(), SUMMARY_SYSTEM_PROMPT, input));
            if (paidBuiltin) {
                int required = Math.max(1, Math.max(route.cost(), Math.max(
                        ConfigManager.getMinDeductCost(), ConfigManager.getChatCost())));
                reservation = BillingService.reservePaidChat(requestId, route.id(), required);
                if (!reservation.success()) {
                    OllamaProxyMod.LOGGER.info("滚动摘要跳过：{}", reservation.error());
                    return null;
                }
            }
            responseBody = ChatCompletionClient.complete(route, requestBody);
        } catch (Exception error) {
            if (reservation != null) {
                BillingService.releasePaidChat(reservation, "upstream_error");
            }
            OllamaProxyMod.LOGGER.warn("滚动摘要请求失败", error);
            return null;
        }
        // 摘要会原样注进提示词，先过一遍统一输出审查（剥格式码、控制字符、截断）
        String text = ChatCompletionClient.sanitizeOutput(
                ChatCompletionClient.extractText(responseBody), 2000);
        if (text.isBlank()) {
            if (reservation != null) {
                BillingService.releasePaidChat(reservation, "invalid_response");
            }
            return null;
        }
        if (reservation != null) {
            try {
                UsageMeter.ChatUsage usage = UsageMeter.chat(requestBody, responseBody, text, false);
                if (usage == null) {
                    BillingService.releasePaidChat(reservation, "missing_usage");
                    return null;
                }
                BillingService.settlePaidChat(reservation, usage);
            } catch (Exception error) {
                // 兜底释放：统计或结算抛异常时，预留不能悬着
                BillingService.releasePaidChat(reservation, "settle_error");
                OllamaProxyMod.LOGGER.warn("滚动摘要用量结算失败，已释放预留", error);
                return null;
            }
        } else if ("builtin".equals(route.source())) {
            String tier = route.tier() != null ? route.tier() : "free";
            BillingService.reportChat(BillingService.requestId(), tier, route.id(),
                    UsageMeter.chat(requestBody, responseBody, text, true));
        }
        return text;
    }

    /** 记忆区预算：和摘要预算相加就是注入块总预算，两边互不挤占。 */
    static int memoryBudgetChars() {
        return 1600;
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.strip().replaceAll("[\\r\\n\\t]+", " ");
    }

    private static String clip(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }
}