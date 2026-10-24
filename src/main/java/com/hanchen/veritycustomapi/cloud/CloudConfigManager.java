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
                OllamaProxyMod.LOGGER.warn("云端配置线程未启动: cloud.enable=false 但凭据仍存在；"
                        + "如需云端请在「多人游戏设置/云端与账户」中重新开启");
            } else {
                OllamaProxyMod.LOGGER.info("云端配置未启用，使用本地 TOML 配置");
            }
            return;
        }
        String apiKey = ConfigManager.getCloudApiKey();
        OllamaProxyMod.LOGGER.info("云端配置线程启动: generation={}, cloud.enable=true, 凭据存在={}",
                currentGeneration, !apiKey.isBlank());
        if (ConfigManager.isCloudFallbackToLocal()) {
            CloudConfig cached = SNAPSHOTS.load(apiKey);
            if (cached != null) {
                applyOnServerThread(cached, currentGeneration, false);
                // 措辞只说「读到了快照」：真正有没有写进内存由 apply 决定，
                // 这里同步打「已加载」会在被去重跳过时变成假日志（曾经因此误判很久）。
                OllamaProxyMod.LOGGER.info("已读取云端配置磁盘快照，版本 {}", cached.data.version);
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

    public static boolean isActive() {
        return active;
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

    /**
     * 配置保存后同步云端拉取线程的状态。
     *
     * <p>🔴 这里必须处理「玩家自己把开关关掉」的情况：
     * 以前只在 {@code active} 时重配周期，关掉开关后线程虽然不再拉取
     * （{@code requestPull} 会检查 {@code isCloudEnabled()}），
     * 但**内存里的云端配置与覆盖还留着**——所有 getter 仍返回云端下发的值，
     * 玩家在本地改了也会被覆盖抢回去。表现就是「我把云端关了，设置却还是云端那套、改不动」。
     *
     * <p>所以关掉时必须走 {@link #stop()}：它除了停线程，还会
     * {@code clearCloudConfig()}（含 {@code CloudOverrides.clear()}），
     * 把覆盖值还原成本地值，让运行时立刻回到本地配置。
     */
    public static synchronized void onConfigSaved() {
        if (!ConfigManager.isCloudEnabled()) {
            // 关掉了：停线程 + 清内存配置 + 撤覆盖。
            // 加短路是因为这个方法每次保存配置都会调；已经在关闭状态时
            // 再跑一遍 stop() 只会白增 generation、反复 shutdown 线程池。
            if (active || executor != null) {
                stop();
            }
            return;
        }
        if (!active) {
            // 之前 stop 过（玩家关过又打开）：重新拉起，否则要重启游戏才生效
            startClient();
            return;
        }
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
        // 配置被清空，去重标记必须一起作废：否则紧接着的重新 start
        // 会把「应用快照」和「网络拉取」都当成重复下发跳过，内存配置永远补不回来。
        resetAppliedTracking();
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

    /** 上一次真正应用过的配置标识；用于「同版本短时间重复拉取」去重。 */
    private static volatile String lastAppliedId;
    private static volatile Integer lastAppliedVersion;
    private static volatile long lastAppliedAt;

    /** 上一次应用是来自网络（true）还是磁盘快照（false）。 */
    private static volatile boolean lastAppliedFromNetwork;

    /** 同版本重复应用的抑制窗口（毫秒）。启动那一分钟里客户端、服务端、几次 refresh 会拿到同一份配置。 */
    private static final long DUPLICATE_APPLY_WINDOW_MS = 60_000L;

    private static boolean isDuplicateWithinWindow(CloudConfig config) {
        if (config == null || config.data == null) return false;
        // 🔴 内存里已经没有云端配置时，绝不能算「重复」。
        //
        // 这条是 2026-10-25 那个「进存档后提示云端配置不同步 / API 地址未配置」的根因：
        // 进存档时客户端会 stop() → clearCloudConfig() 把内存配置清空，
        // 但 lastAppliedId/Version/At 这三个去重标记**没有跟着清**。
        // 紧接着服务端 start()，先应用磁盘快照、再拉网络，两次都落在 60 秒窗口内，
        // 于是两次都被判「已应用过」而静默跳过——cloudConfig 一直是 null，
        // 内置线路拿不到地址，聊天直接报「API 地址未配置」。
        // 而日志里那句「已加载云端配置磁盘快照」是调用方打的，跳过了也照打，极具误导性。
        //
        // 判据用「内存里到底有没有配置」而不是「标记清没清」，
        // 这样任何路径把配置清掉（stop / 手动清 / 异常）都不会再被错误抑制。
        if (!ConfigManager.isCloudConfigLoaded()) return false;
        // 只压网络重复拉取。快照里没有内置线路密钥（按设计不落盘），
        // 网络那次必须照常应用，否则密钥要等到下一次轮询才回来。
        if (!lastAppliedFromNetwork) return false;
        String id = config.data.config_id;
        Integer version = config.data.version;
        if (id == null || version == null) return false;
        if (!id.equals(lastAppliedId) || !version.equals(lastAppliedVersion)) return false;
        return System.currentTimeMillis() - lastAppliedAt < DUPLICATE_APPLY_WINDOW_MS;
    }

    /** 内存配置被清掉后，去重标记必须一起作废，否则下一次下发会被误判成「已应用过」。 */
    private static void resetAppliedTracking() {
        lastAppliedId = null;
        lastAppliedVersion = null;
        lastAppliedAt = 0L;
        lastAppliedFromNetwork = false;
    }

    private static boolean apply(CloudConfig config, boolean persist) {
        if (isDuplicateWithinWindow(config)) {
            OllamaProxyMod.LOGGER.debug("云端配置 {} v{} 已在 {} 秒内应用过，跳过这次重复下发",
                    config.data.config_id, config.data.version, DUPLICATE_APPLY_WINDOW_MS / 1000L);
            return true;
        }
        if (!ConfigManager.applyCloudConfig(config)) {
            return false;
        }
        lastAppliedId = config.data.config_id;
        lastAppliedVersion = config.data.version;
        lastAppliedAt = System.currentTimeMillis();
        lastAppliedFromNetwork = persist;
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
