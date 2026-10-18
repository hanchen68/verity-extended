package com.hanchen.veritycustomapi.cloud;

import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class CloudConfigManager {
    private static final CloudConfigFetcher FETCHER = new CloudConfigFetcher();
    private static final CloudSnapshotStore SNAPSHOTS = new CloudSnapshotStore();
    private static final AtomicLong FETCHING_GENERATION = new AtomicLong(-1L);
    private static final AtomicBoolean AUTH_FAILURE_NOTIFIED = new AtomicBoolean();
    private static volatile ScheduledExecutorService executor;
    private static ScheduledFuture<?> periodicTask;
    private static int periodicIntervalSeconds;
    private static volatile MinecraftServer server;
    private static volatile boolean active;
    private static volatile long generation;
    private static volatile Consumer<Runnable> clientExecutor;

    private CloudConfigManager() {
    }

    public static synchronized void start(MinecraftServer minecraftServer) {
        start(minecraftServer, true);
    }

    private static synchronized void start(MinecraftServer minecraftServer, boolean pullOnStart) {
        if (active) {
            if (minecraftServer != null) {
                server = minecraftServer;
            }
            return;
        }
        active = true;
        long currentGeneration = ++generation;
        server = minecraftServer;
        if (!ConfigManager.isCloudEnabled()) {
            ConfigManager.clearCloudConfig();
            active = false;
            if (!ConfigManager.getCloudApiKey().isBlank()) {
                // 凭据还在但开关是关的：几乎都是之前点过「否/清除」或提示框被 ESC 关掉。
                // 明确写出来，避免玩家以为「云端自己失效了」。
                OllamaProxyMod.LOGGER.warn("检测到已授权凭据，但 cloud.enable=false，云端配置不会加载；"
                        + "如需云端请在「多人游戏设置/云端与账户」中重新开启");
            } else {
                OllamaProxyMod.LOGGER.info("云端配置未启用，使用本地 TOML 配置");
            }
            return;
        }
        String apiKey = ConfigManager.getCloudApiKey();
        if (ConfigManager.isCloudFallbackToLocal()) {
            CloudConfig cached = SNAPSHOTS.load(apiKey);
            if (cached != null) {
                applyOnServerThread(cached, currentGeneration, false);
                OllamaProxyMod.LOGGER.info("已加载云端配置磁盘快照，版本 {}", cached.data.version);
                warnIfSnapshotLacksBuiltinCredentials();
            }
        }
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "Verity-CloudConfig");
                thread.setDaemon(true);
                return thread;
            });
        }
        if (pullOnStart && ConfigManager.isCloudPullOnStartup()) {
            requestPull(0L, currentGeneration);
        }
        int interval = ConfigManager.getCloudPullIntervalSeconds();
        configurePeriodicTask(interval, currentGeneration);
    }

    public static synchronized void startClient() {
        start(null, false);
        refreshNow();
    }

    public static synchronized void startClient(Consumer<Runnable> executor, Runnable reload) {
        clientExecutor = executor;
        CloudRuntimeReload.set(reload);
        start(null, false);
        refreshNow();
    }

    public static synchronized void startClient(
            Consumer<Runnable> executor,
            Runnable reload,
            Consumer<Boolean> completion
    ) {
        clientExecutor = executor;
        CloudRuntimeReload.set(reload);
        start(null, false);
        refreshNow(completion);
    }

    public static synchronized void stopClient() {
        if (server == null) {
            stop();
        }
    }

    public static synchronized void onConfigSaved() {
        if (!active) return;
        configurePeriodicTask(ConfigManager.getCloudPullIntervalSeconds(), generation);
    }

    public static void refreshNow() {
        long currentGeneration = generation;
        requestPull(0L, currentGeneration);
    }

    public static void refreshNow(Consumer<Boolean> completion) {
        long currentGeneration = generation;
        requestPull(0L, currentGeneration, completion);
    }

    public static synchronized void stop() {
        active = false;
        generation++;
        server = null;
        clientExecutor = null;
        CloudRuntimeReload.clear();
        FETCHING_GENERATION.set(-1L);
        ConfigManager.clearCloudConfig();
        if (periodicTask != null) {
            periodicTask.cancel(false);
            periodicTask = null;
        }
        periodicIntervalSeconds = 0;
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private static void requestPull(long delayMillis, long expectedGeneration) {
        requestPull(delayMillis, expectedGeneration, null);
    }

    private static void requestPull(
            long delayMillis,
            long expectedGeneration,
            Consumer<Boolean> completion
    ) {
        ScheduledExecutorService currentExecutor = executor;
        if (!isCurrent(expectedGeneration) || currentExecutor == null || currentExecutor.isShutdown()) {
            complete(completion, false);
            return;
        }
        currentExecutor.schedule(
                () -> pullOnce(expectedGeneration, completion),
                Math.max(0L, delayMillis),
                TimeUnit.MILLISECONDS
        );
    }

    private static void pullOnce(long expectedGeneration) {
        pullOnce(expectedGeneration, null);
    }

    private static void pullOnce(long expectedGeneration, Consumer<Boolean> completion) {
        if (!isCurrent(expectedGeneration) || !ConfigManager.isCloudEnabled()) {
            complete(completion, false);
            return;
        }
        if (!FETCHING_GENERATION.compareAndSet(-1L, expectedGeneration)) {
            if (completion != null) {
                requestPull(100L, expectedGeneration, completion);
            }
            return;
        }
        try {
            CloudConfigFetcher.FetchResult result = FETCHER.fetch(
                    ConfigManager.getCloudApiUrl(),
                    ConfigManager.getCloudApiKey(),
                    ConfigManager.getCloudTimeoutSeconds()
            );
            if (!isCurrent(expectedGeneration)) {
                complete(completion, false);
                return;
            }
            if (result.success) {
                ConfigManager.setCloudAuthFailed(false);
                AUTH_FAILURE_NOTIFIED.set(false);
                applyOnServerThread(result.config, expectedGeneration, true, completion);
            } else {
                if (result.authFailure) {
                    // 文档 §7.1：401 只提示密钥失效，保留最近一次有效缓存，不覆盖本地配置。
                    ConfigManager.setCloudAuthFailed(true);
                    notifyAuthFailure();
                }
                if (!ConfigManager.isCloudFallbackToLocal()) {
                    OllamaProxyMod.LOGGER.warn("云端配置拉取失败且未启用本地回退，继续保留当前运行时配置: {}", result.error);
                }
                complete(completion, false);
            }
        } finally {
            FETCHING_GENERATION.compareAndSet(expectedGeneration, -1L);
        }
    }

    private static void applyOnServerThread(CloudConfig config, long expectedGeneration, boolean persist) {
        applyOnServerThread(config, expectedGeneration, persist, null);
    }

    private static void applyOnServerThread(
            CloudConfig config,
            long expectedGeneration,
            boolean persist,
            Consumer<Boolean> completion
    ) {
        if (!isCurrent(expectedGeneration)) {
            complete(completion, false);
            return;
        }
        MinecraftServer currentServer = server;
        if (currentServer == null) {
            Consumer<Runnable> currentClientExecutor = clientExecutor;
            if (currentClientExecutor == null) {
                complete(completion, false);
                return;
            }
            currentClientExecutor.accept(() -> {
                if (isCurrent(expectedGeneration)) {
                    complete(completion, apply(config, persist));
                } else {
                    complete(completion, false);
                }
            });
            return;
        }
        currentServer.execute(() -> {
            if (isCurrent(expectedGeneration)) {
                complete(completion, apply(config, persist));
            } else {
                complete(completion, false);
            }
        });
    }

    private static boolean apply(CloudConfig config, boolean persist) {
        if (!ConfigManager.applyCloudConfig(config)) {
            return false;
        }
        CloudCredentialStore.getInstance().updateConfigId(config.data.config_id);
        CloudRuntimeReload.notifyApplied();
        configurePeriodicTask(ConfigManager.getCloudPullIntervalSeconds(), generation);
        if (persist) {
            try {
                SNAPSHOTS.save(ConfigManager.getCloudApiKey(), config);
            } catch (RuntimeException ignored) {
                OllamaProxyMod.LOGGER.warn("云端配置已应用，但磁盘快照保存失败");
            }
        }
        return true;
    }

    private static void complete(Consumer<Boolean> completion, boolean success) {
        if (completion != null) {
            completion.accept(success);
        }
    }

    /** 密钥失效只提示一次，避免每轮轮询都刷屏；成功拉取后会自动复位。 */
    private static void notifyAuthFailure() {
        if (!AUTH_FAILURE_NOTIFIED.compareAndSet(false, true)) return;
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> BillingClientNotifier.notifyCloud("配置密钥已失效，请在「云端服务」页重新授权"));
    }

    /**
     * 回退期快照不含密钥（设计如此），内置线路拿不到 Token 会被跳过。
     * 这里只记一条日志、不发客户端提示：该信息只在排查回退问题时有用，发出来只会刷屏。
     */
    private static void warnIfSnapshotLacksBuiltinCredentials() {
        if (!ConfigManager.isEnableBuiltinLlmApis()
                && !ConfigManager.isEnableBuiltinSttApis()
                && !ConfigManager.isEnableBuiltinTtsApis()) {
            return;
        }
        boolean hasBuiltinRoute = ConfigManager.snapshotChatRoutes().stream()
                .anyMatch(route -> "builtin".equals(route.source()));
        if (hasBuiltinRoute) return;
        OllamaProxyMod.LOGGER.warn("云端快照不含内置线路密钥（按设计不落盘），内置对话在重新同步成功前不可用；"
                + "如需立即可用，请在配置页改用自定义 API");
    }

    private static boolean isCurrent(long expectedGeneration) {
        return active && generation == expectedGeneration;
    }

    private static synchronized void configurePeriodicTask(int interval, long expectedGeneration) {
        if (!active || executor == null || executor.isShutdown()) return;
        if (interval <= 0) {
            if (periodicTask != null) periodicTask.cancel(false);
            periodicTask = null;
            periodicIntervalSeconds = 0;
            return;
        }
        if (periodicTask != null && periodicIntervalSeconds == interval) return;
        if (periodicTask != null) periodicTask.cancel(false);
        periodicIntervalSeconds = interval;
        periodicTask = executor.scheduleWithFixedDelay(
                () -> requestPull(FETCHER.getBackoffMillis(), expectedGeneration),
                interval,
                interval,
                TimeUnit.SECONDS
        );
        OllamaProxyMod.LOGGER.info("云端配置定期拉取已启用，间隔 {} 秒", interval);
    }
}
