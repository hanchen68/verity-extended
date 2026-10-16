package com.hanchen.veritycustomapi.cloud;

import com.google.gson.JsonObject;
import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class BillingService {
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final Set<String> SUCCEEDED = ConcurrentHashMap.newKeySet();
    private static final ConcurrentLinkedQueue<String> SUBMISSION_ORDER = new ConcurrentLinkedQueue<>();
    private static final int MAX_TRACKED_REQUESTS = 4096;
    private static final int MAX_ATTEMPTS = 3;
    private static final Set<String> PENDING_IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final AtomicLong BALANCE_SEQUENCE = new AtomicLong();
    private static final AtomicLong APPLIED_BALANCE_SEQUENCE = new AtomicLong();
    private static final Object BILLING_IO_LOCK = new Object();
    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Verity-Usage-Billing");
        thread.setDaemon(true);
        return thread;
    });
    private static final CloudBillingClient CLIENT = new CloudBillingClient();

    private BillingService() {
    }

    public static String requestId() {
        return UUID.randomUUID().toString();
    }

    public static void initialize() {
        pending();
        EXECUTOR.execute(BillingService::retryPending);
    }

    public static Reservation reservePaidChat(String requestId, String routeId, int estimatedCost) {
        // 文档 §6.3 / §8 / §9：迁移到星绒 API 的用户由上游星绒 API 扣费，
        // Mod 端不再执行旧 Verity 预授权，直接放行并跳过后续 settle/release。
        if (ConfigManager.isLegacyBillingIgnored()) {
            return Reservation.skipped();
        }
        if (!ConfigManager.requiresPaidChatPreAuthorization()) {
            return Reservation.failure(503, "云端未提供付费 Chat 预授权协议 v2");
        }
        CloudConfig.Billing billing = ConfigManager.getBillingProtocol();
        BillingContext context = currentContext(billing);
        CloudBillingClient.ReserveRequest request = new CloudBillingClient.ReserveRequest(
                "chat", "paid", requireRouteId(routeId), requireRequestId(requestId),
                Math.max(1, estimatedCost), context.clientId());
        long balanceSequence;
        CloudBillingClient.ReserveResult result;
        synchronized (BILLING_IO_LOCK) {
            balanceSequence = BALANCE_SEQUENCE.incrementAndGet();
            result = CLIENT.reserve(context.apiUrl(), billing.reserveUrl,
                    ConfigManager.getCloudApiKey(), request, ConfigManager.getCloudTimeoutSeconds());
        }
        applyBalance(balanceSequence, result.creditsRemaining(), result.creditsTotal());
        if (!result.success()) {
            if (result.status() == 402) applyBalance(balanceSequence, 0.0D,
                    Math.max(0.0D, ConfigManager.getCreditsTotalExact()));
            return Reservation.failure(result.status(), result.error());
        }
        return Reservation.success(result.reservationId(), requestId, routeId, context);
    }

    public static void settlePaidChat(Reservation reservation, UsageMeter.ChatUsage usage) {
        if (reservation == null || reservation.legacySkipped()) return;
        JsonObject value = new JsonObject();
        value.addProperty("input_tokens", Math.max(0, usage.inputTokens()));
        value.addProperty("output_tokens", Math.max(0, usage.outputTokens()));
        value.addProperty("total_tokens", Math.max(usage.totalTokens(),
                Math.max(0, usage.inputTokens()) + Math.max(0, usage.outputTokens())));
        submitOperation(entry("settle", reservation, value, null));
    }

    public static void releasePaidChat(Reservation reservation, String reason) {
        if (reservation == null || reservation.legacySkipped()) return;
        submitOperation(entry("release", reservation, null,
                reason == null || reason.isBlank() ? "upstream_error" : reason));
    }

    private static PendingBillingStore.Entry entry(String operation, Reservation reservation,
                                                    JsonObject usage, String reason) {
        PendingBillingStore.Entry entry = new PendingBillingStore.Entry();
        entry.operation = operation;
        entry.reservation_id = reservation.reservationId();
        entry.request_id = reservation.requestId();
        entry.route_id = reservation.routeId();
        entry.service = "chat";
        entry.tier = "paid";
        entry.usage = usage;
        entry.reason = reason;
        bind(entry, reservation.context(), endpointFor(operation, reservation.context()));
        return entry;
    }

    private static void submitOperation(PendingBillingStore.Entry entry) {
        pending().put(entry);
        scheduleEntry(entry);
    }

    private static void scheduleEntry(PendingBillingStore.Entry entry) {
        if (!PENDING_IN_FLIGHT.add(entry.key())) return;
        EXECUTOR.execute(() -> {
            try {
                executeOperation(entry);
            } finally {
                PENDING_IN_FLIGHT.remove(entry.key());
            }
        });
    }

    private static void executeOperation(PendingBillingStore.Entry entry) {
        String apiKey = ConfigManager.getCloudApiKey();
        if (!matchesCurrentAccount(entry, apiKey)) return;
        long balanceSequence = entry.balance_sequence > 0L
                ? entry.balance_sequence : BALANCE_SEQUENCE.incrementAndGet();
        entry.balance_sequence = balanceSequence;
        if ("deduct".equals(entry.operation)) {
            CloudBillingClient.DeductRequest request = new CloudBillingClient.DeductRequest(
                    entry.service, entry.tier, entry.route_id, entry.request_id, entry.usage,
                    entry.is_clone, entry.client_id);
            CloudBillingClient.DeductResult result;
            synchronized (BILLING_IO_LOCK) {
                balanceSequence = BALANCE_SEQUENCE.incrementAndGet();
                entry.balance_sequence = balanceSequence;
                result = CLIENT.deduct(entry.api_url, entry.endpoint_url, apiKey, request,
                        ConfigManager.getCloudTimeoutSeconds());
            }
            if (result.success()) {
                pending().remove(entry.key());
                markSucceeded(entry.request_id);
                if (matchesCurrentAccount(entry)) {
                    applyBalance(balanceSequence, result.creditsRemaining(), result.creditsTotal());
                }
                OllamaProxyMod.LOGGER.info("云端用量计费成功: service={}, tier={}, cost={}",
                        entry.service, entry.tier, result.cost());
                return;
            }
            handleFailedEntry(entry, result.status(), result.error(), result.retryable());
            return;
        }

        CloudBillingClient.OperationResult result;
        synchronized (BILLING_IO_LOCK) {
            if ("settle".equals(entry.operation)) {
                result = CLIENT.settle(entry.api_url, entry.endpoint_url, apiKey,
                        new CloudBillingClient.SettlementRequest(entry.reservation_id, entry.request_id,
                                entry.service, entry.tier, entry.route_id, entry.usage),
                        ConfigManager.getCloudTimeoutSeconds());
            } else {
                result = CLIENT.release(entry.api_url, entry.endpoint_url, apiKey,
                        new CloudBillingClient.ReleaseRequest(entry.reservation_id, entry.request_id,
                                entry.service, entry.tier, entry.route_id, entry.reason),
                        ConfigManager.getCloudTimeoutSeconds());
            }
        }
        if (matchesCurrentAccount(entry)) {
            applyBalance(balanceSequence, result.creditsRemaining(), result.creditsTotal());
        }
        if (result.success()) {
            pending().remove(entry.key());
            return;
        }
        handleFailedEntry(entry, result.status(), result.error(), result.retryable());
    }

    private static void handleFailedEntry(PendingBillingStore.Entry entry, int status,
                                          String error, boolean retryable) {
        if (!retryable) {
            pending().remove(entry.key());
            IN_FLIGHT.remove(entry.request_id);
            OllamaProxyMod.LOGGER.warn("持久计费任务失败: operation={}, service={}, status={}, reason={}",
                    entry.operation, entry.service, status, error);
            if (matchesCurrentAccount(entry)) {
                if (status == 401) ConfigManager.clearCloudConfig();
                if (status == 402) {
                    applyBalance(entry.balance_sequence, 0.0D,
                            Math.max(0.0D, ConfigManager.getCreditsTotalExact()));
                }
                if (status == 401 || status == 402) CloudConfigManager.refreshNow();
                notifyPlayer("用量同步失败：" + safeError(error));
            }
            return;
        }
        entry.attempts++;
        entry.next_attempt_at = System.currentTimeMillis() + pendingDelay(entry.attempts);
        pending().put(entry);
    }

    private static void retryPending() {
        long now = System.currentTimeMillis();
        for (PendingBillingStore.Entry entry : pending().snapshot()) {
            if (entry.next_attempt_at <= now && matchesCurrentAccount(entry)) scheduleEntry(entry);
        }
    }

    private static PendingBillingStore pending() {
        return PendingHolder.STORE;
    }

    private static final class PendingHolder {
        private static final PendingBillingStore STORE = create();

        private static PendingBillingStore create() {
            PendingBillingStore store = new PendingBillingStore();
            long maxSequence = 0L;
            for (PendingBillingStore.Entry entry : store.snapshot()) {
                maxSequence = Math.max(maxSequence, entry.balance_sequence);
                if ("deduct".equals(entry.operation)) IN_FLIGHT.add(entry.request_id);
            }
            BALANCE_SEQUENCE.accumulateAndGet(maxSequence, Math::max);
            EXECUTOR.scheduleWithFixedDelay(BillingService::retryPending, 5L, 30L, TimeUnit.SECONDS);
            return store;
        }
    }

    static long pendingDelay(int attempt) {
        return Math.min(300_000L, 30_000L << Math.max(0, Math.min(4, attempt - 1)));
    }

    private static void applyBalance(long sequence, Double remaining, Double total) {
        if (remaining == null) return;
        long previous;
        do {
            previous = APPLIED_BALANCE_SEQUENCE.get();
            if (sequence < previous) return;
        } while (!APPLIED_BALANCE_SEQUENCE.compareAndSet(previous, sequence));
        ConfigManager.applyCreditsDeduction(Math.max(0.0D, remaining), total != null
                ? Math.max(0.0D, total) : Math.max(0.0D, ConfigManager.getCreditsTotalExact()));
    }

    private static BillingContext currentContext(CloudConfig.Billing billing) {
        String apiUrl = ConfigManager.getCloudApiUrl();
        String apiKey = ConfigManager.getCloudApiKey();
        if (apiUrl == null || apiUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("云端 API 未配置");
        }
        CloudCredentialStore credentials = CloudCredentialStore.getInstance();
        return new BillingContext(apiUrl.trim(), fingerprint(apiKey), credentials.configId(),
                credentials.clientId(), billing);
    }

    private static void bind(PendingBillingStore.Entry entry, BillingContext context, String endpoint) {
        entry.api_url = context.apiUrl();
        entry.endpoint_url = endpoint;
        entry.account_fingerprint = context.accountFingerprint();
        entry.config_id = context.configId();
        entry.client_id = context.clientId();
        entry.balance_sequence = 0L;
    }

    private static boolean matchesCurrentAccount(PendingBillingStore.Entry entry) {
        return matchesCurrentAccount(entry, ConfigManager.getCloudApiKey());
    }

    private static boolean matchesCurrentAccount(PendingBillingStore.Entry entry, String apiKey) {
        if (entry == null || entry.legacy_unbound || entry.account_fingerprint == null) return false;
        String apiUrl = ConfigManager.getCloudApiUrl();
        if (!entry.account_fingerprint.equals(fingerprint(apiKey))
                || !Objects.equals(normalize(entry.api_url), normalize(apiUrl))) return false;
        String currentConfigId = CloudCredentialStore.getInstance().configId();
        return entry.config_id == null || entry.config_id.isBlank() || currentConfigId == null
                || currentConfigId.isBlank() || entry.config_id.equals(currentConfigId);
    }

    private static String endpointFor(String operation, BillingContext context) {
        CloudConfig.Billing billing = context.billing();
        String configured = billing == null ? null : switch (operation) {
            case "settle" -> billing.settleUrl;
            case "release" -> billing.releaseUrl;
            default -> billing.deductUrl;
        };
        return CloudBillingClient.resolveEndpoint(context.apiUrl(), configured, "/credits/" + operation);
    }

    static String fingerprint(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return "";
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(apiKey.trim().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (Exception error) {
            throw new IllegalStateException("无法生成计费账号指纹", error);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String safeError(String value) {
        if (value == null || value.isBlank()) return "未知错误";
        String singleLine = value.replace('\r', ' ').replace('\n', ' ').trim();
        return singleLine.length() > 160 ? singleLine.substring(0, 160) : singleLine;
    }

    public static void reportChat(String requestId, String tier, UsageMeter.ChatUsage usage) {
        reportChat(requestId, tier, "auto", usage);
    }

    public static void reportChat(String requestId, String tier, String routeId, UsageMeter.ChatUsage usage) {
        if (usage == null) {
            if ("paid".equalsIgnoreCase(tier)) notifyPlayer("付费 Chat 响应缺少真实 usage，用量同步失败");
            return;
        }
        JsonObject value = new JsonObject();
        value.addProperty("input_tokens", usage.inputTokens());
        value.addProperty("output_tokens", usage.outputTokens());
        value.addProperty("total_tokens", usage.totalTokens());
        submit(requestId, "chat", tier, routeId, value, null);
    }

    public static void reportStt(String requestId, String tier, String routeId, double audioSeconds) {
        JsonObject usage = new JsonObject();
        usage.addProperty("audio_seconds", Math.max(0, audioSeconds));
        submit(requestId, "stt", tier, routeId, usage, null);
    }

    public static void reportTts(String requestId, String tier, String routeId, int characters, boolean clone) {
        JsonObject usage = new JsonObject();
        usage.addProperty("characters", Math.max(0, characters));
        submit(requestId, "tts", tier, routeId, usage, Boolean.valueOf(clone));
    }

    private static void submit(String requestId, String service, String tier, String routeId,
                               JsonObject usage, Boolean clone) {
        // 迁移到星绒 API 的用户不再写旧 Verity 账本，避免与上游星绒 API 双重扣费。
        if (ConfigManager.isLegacyBillingIgnored()) return;
        if (!ConfigManager.isCloudEnabled() || !beginSubmission(requestId)) return;
        CloudConfig.Billing billing = ConfigManager.getBillingProtocol();
        BillingContext context;
        try {
            context = currentContext(billing);
        } catch (RuntimeException error) {
            IN_FLIGHT.remove(requestId);
            OllamaProxyMod.LOGGER.warn("创建持久计费任务失败: service={}, reason={}", service, error.getMessage());
            return;
        }
        PendingBillingStore.Entry entry = new PendingBillingStore.Entry();
        entry.operation = "deduct";
        entry.request_id = requireRequestId(requestId);
        entry.route_id = requireRouteId(routeId);
        entry.service = service;
        entry.tier = normalizeTier(tier);
        entry.is_clone = clone;
        entry.usage = usage;
        bind(entry, context, CloudBillingClient.resolveEndpoint(context.apiUrl(),
                billing != null ? billing.deductUrl : null, "/credits/deduct"));
        submitOperation(entry);
    }

    static boolean shouldRetry(CloudBillingClient.DeductResult result, int attempt) {
        return !result.success() && result.retryable() && attempt < MAX_ATTEMPTS;
    }

    static long retryDelayMillis(int attempt, int status) {
        long base = status == 429 ? 2000L : 500L;
        return base << Math.max(0, Math.min(4, attempt - 1));
    }

    static boolean beginSubmission(String requestId) {
        return requestId != null && !requestId.isBlank()
                && !SUCCEEDED.contains(requestId) && IN_FLIGHT.add(requestId);
    }

    static void markSucceeded(String requestId) {
        IN_FLIGHT.remove(requestId);
        if (!SUCCEEDED.add(requestId)) return;
        SUBMISSION_ORDER.add(requestId);
        while (SUBMISSION_ORDER.size() > MAX_TRACKED_REQUESTS) {
            String expired = SUBMISSION_ORDER.poll();
            if (expired != null) SUCCEEDED.remove(expired);
        }
    }

    private static void notifyPlayer(String message) {
        // 客户端通知提取到独立的 @OnlyIn(Dist.CLIENT) 类，避免公共计费服务直接链接客户端类
        // 使用 unsafeRunWhenOn：safeRunWhenOn 的 validateSafeReferent 要求 SafeRunnable 必须是
        // 跨类方法引用（implClass != capturingClass），而此处 Runnable 需要捕获 message 参数，
        // 只能写为内联 lambda。unsafeRunWhenOn 在错误的物理端不会构造内层 Runnable，
        // 因此 BillingClientNotifier 不会被加载，专服边界仍然成立。
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> BillingClientNotifier.notifyPlayer(message));
    }

    private static String normalizeTier(String tier) {
        return "paid".equalsIgnoreCase(tier) ? "paid" : "free";
    }

    static String requireRouteId(String routeId) {
        if (routeId == null || routeId.isBlank() || "auto".equalsIgnoreCase(routeId.trim())) {
            throw new IllegalArgumentException("计费必须使用实际 route_id");
        }
        return routeId.trim();
    }

    static String requireRequestId(String requestId) {
        if (requestId == null || requestId.length() < 8 || requestId.length() > 128) {
            throw new IllegalArgumentException("request_id 长度必须为 8 到 128 字符");
        }
        return requestId;
    }

    private record BillingContext(String apiUrl, String accountFingerprint, String configId,
                                  String clientId, CloudConfig.Billing billing) {
    }

    public record Reservation(boolean success, int status, String reservationId,
                              String requestId, String routeId, String error, BillingContext context,
                              boolean legacySkipped) {
        static Reservation success(String reservationId, String requestId, String routeId,
                                   BillingContext context) {
            return new Reservation(true, 200, reservationId, requestId, routeId, null, context, false);
        }

        /**
         * 星绒 API 迁移用户：无需旧预授权，调用方照常继续，但不再产生 settle / release。
         */
        static Reservation skipped() {
            return new Reservation(true, 200, null, null, null, null, null, true);
        }

        static Reservation failure(int status, String error) {
            return new Reservation(false, status, null, null, null, error, null, false);
        }
    }
}
