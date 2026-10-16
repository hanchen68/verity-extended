package com.hanchen.veritycustomapi.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.NetworkSecurity;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.compat.OriginalVerityAiLock;
import com.hanchen.veritycustomapi.cloud.BillingService;
import com.hanchen.veritycustomapi.cloud.UsageMeter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

import com.hanchen.veritycustomapi.compat.RuntimePlatform;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class VerityAwarenessManager {
    private static final Gson GSON = new Gson();
    private static final AtomicBoolean REQUEST_IN_FLIGHT = new AtomicBoolean(false);
    private static final AtomicLong REQUEST_GENERATION = new AtomicLong();
    private static final AtomicReference<HttpURLConnection> CURRENT_CONNECTION = new AtomicReference<>();
    private static final Object REQUEST_STATE_LOCK = new Object();
    private static volatile Thread currentRequestThread;
    private static volatile long lastTriggerTime;
    private static volatile long loginAt;
    private static volatile boolean wasPaused;
    private static volatile boolean wasDead;
    private static volatile boolean wasSleeping;
    private static volatile boolean lowHealthArmed = true;
    private static volatile boolean stateInitialized;
    private static volatile boolean pendingLoginEvent;
    private static volatile Object trayIcon;

    // ===== 事件状态快照（边沿触发，避免同一状态反复触发） =====
    private static volatile boolean wasDay = true;
    private static volatile boolean wasRaining;
    private static volatile boolean wasThundering;
    private static volatile String lastDimension = "";
    private static volatile boolean hungerArmed = true;
    private static volatile int lastExperienceLevel = -1;
    private static volatile float lastHealth = -1.0F;
    /** 已经提醒过的最低血量档位：0=正常 1=≤50% 2=≤25% 3=≤10%。 */
    private static volatile int reportedHealthTier;
    /** 累计的「无事发生」时长，用于定时闲聊。 */
    private static volatile long idleAccumulatedMs;
    private static volatile long lastTickMillis;
    /** 最近一次战斗（出手或被伤害）的时间戳。 */
    private static volatile long lastCombatAt;
    /** 高优先级事件的排队位，只保留一个，避免堆积。 */
    private static final AtomicReference<DeferredEvent> DEFERRED = new AtomicReference<>();

    /** 饥饿触发阈值（饱食度 0-20）。 */
    private static final int HUNGER_TRIGGER_THRESHOLD = 6;
    /** 饥饿重新武装阈值：吃回这个水平以上才会再次提醒。 */
    private static final int HUNGER_ARM_THRESHOLD = 10;
    /** 达到这个优先级的事件不会被战斗静默压掉（死亡与各档血量告急）。 */
    private static final int COMBAT_SILENCE_EXEMPT_PRIORITY = 70;

    /** 一次排队等待发送的主动事件。 */
    private record DeferredEvent(String name, String prompt, boolean withCamera, int priority) {
    }

    /**
     * 事件优先级，数值越大越重要。上个请求还没回来时，只有更高优先级的事件才挤得掉排队位。
     */
    private static int priorityOf(String eventName) {
        return switch (eventName) {
            case "玩家死亡" -> 100;
            case "定时提醒" -> 95;
            case "生命值危急" -> 92;
            case "生命值过低" -> 84;
            case "生命值偏低" -> 76;
            case "玩家睡觉", "玩家醒来" -> 64;
            case "维度切换" -> 58;
            case "玩家饥饿" -> 52;
            case "雷暴天气" -> 46;
            case "开始下雨", "雨停了" -> 42;
            case "天亮了", "天黑了" -> 38;
            case "经验升级" -> 32;
            case "打开暂停菜单" -> 26;
            case "进入世界" -> 20;
            case "定时闲聊" -> 12;
            default -> 16;
        };
    }

    private VerityAwarenessManager() {
    }

    public static void onLogin() {
        resetState();
        lastTriggerTime = 0L;
        loginAt = System.currentTimeMillis();
        pendingLoginEvent = true;
        OllamaProxyMod.LOGGER.info("主动感知登录检查: enabled={}, worldEvent={}",
                ConfigManager.isProactiveAwarenessEnabled(), ConfigManager.isProactiveWorldEventEnabled());
    }

    public static void onLogout() {
        OllamaProxyMod.LOGGER.info("主动感知退出检查: enabled={}, worldEvent={}",
                ConfigManager.isProactiveAwarenessEnabled(), ConfigManager.isProactiveWorldEventEnabled());
        // 退出世界时不触发主动感知请求（服务器正在关闭，请求无法完成）
        resetState();
    }

    public static void onClientTick(Minecraft minecraft) {
        if (minecraft.player == null || !ConfigManager.isProactiveAwarenessEnabled()) {
            resetState();
            return;
        }

        boolean paused = minecraft.isPaused() || minecraft.screen instanceof PauseScreen;
        float health = getPlayerHealth(minecraft);
        float maxHealth = getPlayerMaxHealth(minecraft);
        boolean dead = health <= 0.0F;
        boolean sleeping = isPlayerSleeping(minecraft);
        float healthRatio = maxHealth > 0.0F ? health / maxHealth : 1.0F;

        boolean day = isDay(minecraft);
        boolean raining = isRaining(minecraft);
        boolean thundering = isThundering(minecraft);
        String dimension = currentDimension(minecraft);
        int foodLevel = currentFoodLevel(minecraft);
        int experienceLevel = currentExperienceLevel(minecraft);

        if (!stateInitialized) {
            wasPaused = paused;
            wasDead = dead;
            wasSleeping = sleeping;
            lowHealthArmed = healthRatio > 0.35F;
            wasDay = day;
            wasRaining = raining;
            wasThundering = thundering;
            lastDimension = dimension;
            hungerArmed = foodLevel > HUNGER_ARM_THRESHOLD;
            lastExperienceLevel = experienceLevel;
            lastHealth = health;
            reportedHealthTier = 0;
            idleAccumulatedMs = 0L;
            lastTickMillis = System.currentTimeMillis();
            stateInitialized = true;
            return;
        }

        // 血量下降即认为进入了战斗（被怪物打、摔伤、着火都会体现为掉血）
        float previousHealth = lastHealth;
        if (previousHealth >= 0.0F && health < previousHealth - 0.05F) {
            lastCombatAt = System.currentTimeMillis();
        }
        lastHealth = health;

        // 定时闲聊计时：暂停时不累计，避免挂着菜单回来立刻被搭话
        long tickNow = System.currentTimeMillis();
        if (!paused && lastTickMillis > 0L) {
            idleAccumulatedMs += Math.max(0L, tickNow - lastTickMillis);
        }
        lastTickMillis = tickNow;

        long loginElapsed = tickNow - loginAt;
        if (pendingLoginEvent && loginElapsed > 2500L) {
            boolean waitingForCloud = ConfigManager.isCloudEnabled() && !ConfigManager.isCloudConfigLoaded();
            if (!waitingForCloud || loginElapsed > 15000L) {
                pendingLoginEvent = false;
                if (ConfigManager.isProactiveWorldEventEnabled()) {
                    trigger("进入世界", "玩家刚刚进入 Minecraft 世界。请根据当前游戏事件和人设，简短打招呼或吐槽一句，不要执行任何游戏动作。");
                }
            }
        }

        if (paused && !wasPaused && ConfigManager.isProactivePauseEventEnabled()) {
            trigger("打开暂停菜单", "玩家刚刚打开暂停菜单，可能要暂时离开。请简短吐槽或提醒一句，不要执行任何游戏动作。");
        }
        if (dead && !wasDead && ConfigManager.isProactiveDeathEventEnabled()) {
            trigger("玩家死亡", "玩家刚刚死亡。请给出一句简短反应，可以关心或吐槽，但不要执行任何游戏动作。");
        }
        if (sleeping != wasSleeping && ConfigManager.isProactiveSleepEventEnabled()) {
            trigger(sleeping ? "玩家睡觉" : "玩家醒来", sleeping
                    ? "玩家刚刚上床睡觉。请简短说一句晚安或吐槽，不要执行任何游戏动作。"
                    : "玩家刚刚从床上醒来。请简短说一句早安或吐槽，不要执行任何游戏动作。");
        }

        triggerHealthEvents(health, maxHealth, healthRatio);

        // 昼夜交替：白天 / 夜晚各触发一次
        if (day != wasDay) {
            wasDay = day;
            if (ConfigManager.isProactiveDayNightEventEnabled()) {
                trigger(day ? "天亮了" : "天黑了", day
                        ? "天刚刚亮。请简短说一句与清晨或新的一天有关的话，不要执行任何游戏动作。"
                        : "天刚刚黑。请简短提醒玩家注意怪物或早点休息，不要执行任何游戏动作。");
            }
        }

        // 天气变化：雷暴优先于普通降雨，避免同一刻弹出两条
        if (thundering && !wasThundering) {
            if (ConfigManager.isProactiveWeatherEventEnabled()) {
                trigger("雷暴天气", "外面打起了雷。请简短吐槽或提醒一句，不要执行任何游戏动作。");
            }
        } else if (raining != wasRaining && !thundering) {
            if (ConfigManager.isProactiveWeatherEventEnabled()) {
                trigger(raining ? "开始下雨" : "雨停了", raining
                        ? "外面开始下雨了。请简短说一句和下雨有关的话，不要执行任何游戏动作。"
                        : "雨停了。请简短说一句，不要执行任何游戏动作。");
            }
        }
        wasRaining = raining;
        wasThundering = thundering;

        // 维度切换
        if (!dimension.isEmpty() && !dimension.equals(lastDimension)) {
            String previousDimension = lastDimension;
            lastDimension = dimension;
            if (!previousDimension.isEmpty() && ConfigManager.isProactiveDimensionEventEnabled()) {
                trigger("维度切换", "玩家从" + describeDimension(previousDimension) + "来到了"
                        + describeDimension(dimension) + "。请简短说一句符合新环境的反应，不要执行任何游戏动作。");
            }
        }

        // 饥饿：跌到阈值提醒一次，回到安全线以上才重新武装
        if (foodLevel <= HUNGER_TRIGGER_THRESHOLD && hungerArmed) {
            hungerArmed = false;
            if (ConfigManager.isProactiveHungerEventEnabled()) {
                trigger("玩家饥饿", "玩家当前饥饿值为 " + foodLevel + "/20。请简短提醒他吃点东西，不要执行任何游戏动作。");
            }
        } else if (foodLevel > HUNGER_ARM_THRESHOLD) {
            hungerArmed = true;
        }

        // 经验升级
        if (lastExperienceLevel >= 0 && experienceLevel > lastExperienceLevel
                && ConfigManager.isProactiveLevelEventEnabled()) {
            trigger("经验升级", "玩家升到了 " + experienceLevel + " 级。请简短祝贺或吐槽一句，不要执行任何游戏动作。");
        }
        lastExperienceLevel = experienceLevel;

        // 定时闲聊：长时间没有事件时主动开口
        long idleInterval = ConfigManager.getProactiveIdleIntervalMinutes() * 60_000L;
        if (ConfigManager.isProactiveIdleChatEnabled() && idleAccumulatedMs >= idleInterval) {
            idleAccumulatedMs = 0L;
            trigger("定时闲聊", "已经有一段时间没发生特别的事了。请根据当前游戏状态和人设主动说一句简短的闲聊，不要执行任何游戏动作。");
        }

        wasPaused = paused;
        wasDead = dead;
        wasSleeping = sleeping;
    }

    /**
     * 低血量提醒。开了分级后按 50% / 25% / 10% 三档各提醒一次，
     * 血量回到 60% 以上才重新武装，免得在危险区反复刷屏。
     */
    private static void triggerHealthEvents(float health, float maxHealth, float healthRatio) {
        if (!ConfigManager.isProactiveHealthTiersEnabled()) {
            if (healthRatio <= 0.25F && lowHealthArmed) {
                lowHealthArmed = false;
                if (ConfigManager.isProactiveLowHealthEventEnabled()) {
                    trigger("生命值过低", "玩家当前生命值为 " + health + "/" + maxHealth
                            + "。请简短警告或吐槽一句，不要执行任何游戏动作。");
                }
            } else if (healthRatio > 0.35F) {
                lowHealthArmed = true;
            }
            return;
        }
        if (healthRatio > 0.6F) {
            reportedHealthTier = 0;
            return;
        }
        int tier = healthRatio <= 0.1F ? 3 : healthRatio <= 0.25F ? 2 : healthRatio <= 0.5F ? 1 : 0;
        if (tier == 0 || tier <= reportedHealthTier) {
            return;
        }
        reportedHealthTier = tier;
        if (!ConfigManager.isProactiveLowHealthEventEnabled()) {
            return;
        }
        String healthText = health + "/" + maxHealth;
        switch (tier) {
            case 1 -> trigger("生命值偏低", "玩家当前生命值为 " + healthText
                    + "，已经掉到一半以下。请简短提醒一句，不要执行任何游戏动作。");
            case 2 -> trigger("生命值过低", "玩家当前生命值为 " + healthText
                    + "。请简短警告或吐槽一句，不要执行任何游戏动作。");
            default -> trigger("生命值危急", "玩家当前生命值只剩 " + healthText
                    + "，非常危险。请简短、急促地警告一句，不要执行任何游戏动作。");
        }
    }

    public static void onConfigSaved() {
        resetState();
    }

    /**
     * 手动触发一次「看一眼摄像头」（快捷键 / 配置界面预览用）。
     * 忽略冷却、也忽略主动感知总开关——玩家明确发起的动作，不该被随机事件那个开关拦下。
     */
    public static void triggerCameraLook() {
        if (!ConfigManager.isCameraEnabled()) {
            showStatus("§c【视觉】未启用，请到 配置 → 摄像头视觉 打开");
            return;
        }
        trigger("摄像头观察",
                "现在请看一眼刚采集到的画面（可能是玩家的真实摄像头画面，也可能是他当前的游戏内屏幕画面），"
                        + "用一句自然的话回应你看到的内容，不要执行任何游戏动作。",
                true, true);
    }

    /**
     * 定时提醒到点：玩家自己设的约定，同样忽略冷却和总开关（跟「看一眼摄像头」一个道理）。
     */
    public static void triggerReminder(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        triggerInternal("定时提醒",
                "这是你之前答应玩家、现在到点要提醒他的事：" + text
                        + "。请用一句自然的话提醒他，不要执行任何游戏动作。",
                ConfigManager.isCameraSendOnProactiveEvent() && ConfigManager.isCameraEnabled(),
                true, true);
    }

    private static void trigger(String eventName, String eventPrompt) {
        trigger(eventName, eventPrompt, ConfigManager.isCameraSendOnProactiveEvent()
                && ConfigManager.isCameraEnabled());
    }

    /**
     * @param withCamera 是否在本次请求里附带一张摄像头画面（需要视觉模型）
     */
    private static void trigger(String eventName, String eventPrompt, boolean withCamera) {
        trigger(eventName, eventPrompt, withCamera, false);
    }

    /**
     * @param withCamera     是否在本次请求里附带一张摄像头画面（需要视觉模型）
     * @param manual         手动触发（快捷键）：忽略全局冷却，也不受主动感知总开关约束
     */
    private static void trigger(String eventName, String eventPrompt, boolean withCamera, boolean manual) {
        triggerInternal(eventName, eventPrompt, withCamera, manual, false);
    }

    /**
     * @param skipCooldown 该事件在排队前已经通过过冷却检查，补发时不再重复判断，
     * 否则「排队」等于永远发不出去
     */
    private static void triggerInternal(String eventName, String eventPrompt, boolean withCamera,
                                        boolean manual, boolean skipCooldown) {
        if (!manual && !ConfigManager.isProactiveAwarenessEnabled()) {
            OllamaProxyMod.LOGGER.debug("主动感知跳过事件 {}: 总开关关闭", eventName);
            return;
        }
        // 战斗静默只压掉闲聊类事件：死亡与血量告急恰恰是战斗中最该出声的时刻
        if (!manual && !isCombatSilenceExempt(eventName) && inCombatSilence()) {
            OllamaProxyMod.LOGGER.debug("主动感知跳过事件 {}: 战斗中静默", eventName);
            return;
        }
        long now = System.currentTimeMillis();
        long cooldown = ConfigManager.getProactiveCooldownSeconds() * 1000L;
        long remaining = cooldown - (now - lastTriggerTime);
        if (!manual && !skipCooldown && remaining > 0L) {
            OllamaProxyMod.LOGGER.debug("主动感知跳过事件 {}: 冷却剩余 {} 秒", eventName, Math.max(1L, remaining / 1000L));
            return;
        }
        // 原版 6.2 起 AI 请求全局串行（AiManager REQUEST_LOCK）：原版后台请求与玩家消息共用同一把锁，
        // 这里主动让路避免拖慢玩家回复；旧版无此锁，判定恒为 false，行为不变。
        if (!manual && OriginalVerityAiLock.isRequestInFlight()) {
            if (ConfigManager.isProactiveDeferEventsEnabled()) {
                deferEvent(eventName, eventPrompt, withCamera);
            } else {
                OllamaProxyMod.LOGGER.info("主动感知跳过事件 {}: 原版 AI 请求进行中，主动让路", eventName);
            }
            return;
        }
        if (!REQUEST_IN_FLIGHT.compareAndSet(false, true)) {
            if (!manual && ConfigManager.isProactiveDeferEventsEnabled()) {
                deferEvent(eventName, eventPrompt, withCamera);
            } else {
                OllamaProxyMod.LOGGER.info("主动感知跳过事件 {}: 上一个请求仍在进行", eventName);                if (manual) {
                    showStatus("§7【视觉】上一次观察还没结束，稍后再试");
                }
            }
            return;
        }
        OllamaProxyMod.LOGGER.info("主动感知触发事件: {}, cooldown={}s, withCamera={}",
                eventName, ConfigManager.getProactiveCooldownSeconds(), withCamera);
        long generation = REQUEST_GENERATION.incrementAndGet();

        ConfigManager.ChatRoute route;
        try {
            route = primaryChatRoute();
            if ("builtin-paid".equals(route.id()) && ConfigManager.isCreditsExhausted()) {
                throw new IllegalStateException("额度不足，请前往云端积分中心充值");
            }
        } catch (Exception e) {
            REQUEST_IN_FLIGHT.set(false);
            String errorDetail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            OllamaProxyMod.LOGGER.warn("主动事件线路解析失败: {}: {}", eventName, errorDetail);
            showStatus("§c【主动感知】" + errorDetail);
            return;
        }

        final ConfigManager.ChatRoute finalRoute = route;
        AtomicBoolean completed = new AtomicBoolean(false);
        Thread requestThread = new Thread(() -> {
            OllamaProxyMod.LOGGER.info("主动感知后台线程启动: {}", eventName);
            BillingService.Reservation reservation = null;
            try {
                // 采集画面（工作线程内执行，避免阻塞主线程）
                CameraCaptureService.CaptureResult capture = null;
                if (withCamera && ConfigManager.isCameraEnabled()) {
                    capture = CameraCaptureService.capture();
                    if (capture.ok()) {
                        OllamaProxyMod.LOGGER.info("主动感知已附带画面: backend={}, 主画面~{} bytes, 含游戏画面={}",
                                capture.backend(), capture.base64Jpeg().length() * 3 / 4, capture.hasScreen());
                    } else {
                        OllamaProxyMod.LOGGER.warn("主动感知画面采集失败({}): {}", capture.backend(), capture.error());
                    }
                }

                JsonObject requestBody = buildRequestBody(eventName, eventPrompt,
                        selectModelFor(finalRoute, capture), capture);
                OllamaProxyMod.LOGGER.info("主动感知请求体已构建: {}, bodySize={}, hasImage={}",
                        eventName, GSON.toJson(requestBody).getBytes(StandardCharsets.UTF_8).length,
                        capture != null && capture.ok());

                String requestId = BillingService.requestId();
                boolean paidBuiltin = "builtin".equals(finalRoute.source()) && "paid".equalsIgnoreCase(finalRoute.tier());
                if (paidBuiltin) {
                    int required = Math.max(1, Math.max(finalRoute.cost(), Math.max(
                            ConfigManager.getMinDeductCost(), ConfigManager.getChatCost())));
                    reservation = BillingService.reservePaidChat(requestId, finalRoute.id(), required);
                    if (!reservation.success()) throw new IllegalStateException(reservation.error());
                }
                String responseBody = sendDirectChatRequest(requestBody, finalRoute, generation);
                String requestJson = GSON.toJson(requestBody);
                String text = sanitizeDisplayText(extractText(responseBody));
                if (generation != REQUEST_GENERATION.get() || text.isBlank()) {
                    if (reservation != null) BillingService.releasePaidChat(reservation,
                            generation != REQUEST_GENERATION.get() ? "cancelled" : "empty_response");
                    reservation = null;
                    throw new IllegalStateException("主动感知响应已取消或为空");
                }
                if (reservation != null) {
                    UsageMeter.ChatUsage usage = UsageMeter.chat(requestJson, responseBody, text, false);
                    if (usage == null) {
                        BillingService.releasePaidChat(reservation, "missing_usage");
                        // 星绒 API 迁移用户由上游结算，缺少 usage 不应中断主动感知
                        if (!reservation.legacySkipped()) {
                            reservation = null;
                            throw new IllegalStateException("付费主动感知响应缺少真实 usage");
                        }
                    } else {
                        BillingService.settlePaidChat(reservation, usage);
                    }
                    reservation = null;
                } else if ("builtin".equals(finalRoute.source())) {
                    String tier = finalRoute.tier() != null ? finalRoute.tier() : "free";
                    BillingService.reportChat(BillingService.requestId(), tier, finalRoute.id(),
                            UsageMeter.chat(requestJson, responseBody, text, true));
                }
                if (generation == REQUEST_GENERATION.get() && completed.compareAndSet(false, true)) {
                    completeAwarenessEvent(eventName, text, false, generation);
                }
            } catch (Exception e) {
                if (reservation != null) BillingService.releasePaidChat(reservation, "upstream_error");
                String errorDetail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                if (generation == REQUEST_GENERATION.get()) {
                    OllamaProxyMod.LOGGER.warn("主动事件请求失败: {}: {}", eventName, errorDetail);
                }
                if (generation == REQUEST_GENERATION.get() && completed.compareAndSet(false, true)) {
                    completeAwarenessEvent(eventName, buildLocalFallback(eventName), true, generation);
                }
            } finally {
                if (currentRequestThread == Thread.currentThread() && generation == REQUEST_GENERATION.get()) {
                    REQUEST_IN_FLIGHT.set(false);
                    currentRequestThread = null;
                    drainDeferred();
                }
            }
        }, "Verity-Awareness-" + eventName);
        requestThread.setDaemon(true);
        currentRequestThread = requestThread;
        requestThread.start();
        final long watchdogMillis = watchdogTimeoutMillis(withCamera);
        Thread timeoutThread = new Thread(() -> {
            try {
                requestThread.join(watchdogMillis);
                if (requestThread.isAlive() && currentRequestThread == requestThread && completed.compareAndSet(false, true)) {
                    OllamaProxyMod.LOGGER.warn("主动事件请求超时，使用本地兜底: {}", eventName);
                    disconnectCurrentConnection();
                    requestThread.interrupt();
                    REQUEST_IN_FLIGHT.set(false);
                    currentRequestThread = null;
                    completeAwarenessEvent(eventName, buildLocalFallback(eventName), true, generation);
                    drainDeferred();
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "Verity-Awareness-Timeout-" + eventName);
        timeoutThread.setDaemon(true);
        timeoutThread.start();
    }

    /** 带画面时采集更费时间，看门狗超时要相应放宽。 */
    private static long watchdogTimeoutMillis(boolean withCamera) {
        long base = 10000L;
        if (withCamera && ConfigManager.isCameraEnabled()) {
            // 每个来源各自可能占用一次采集超时：摄像头与游戏画面都要预留
            if (ConfigManager.isCameraSourceCamera()) {
                base += Math.max(0, ConfigManager.getCameraCaptureTimeoutMs());
            }
            if (ConfigManager.isCameraSourceScreen()) {
                base += Math.max(0, ConfigManager.getCameraCaptureTimeoutMs());
            }
        }
        return base;
    }

    /**
     * 上个请求还在跑时，把更重要的事件塞进排队位。
     * 只留一个槽：优先级不高于已有排队事件的新事件直接丢掉，免得堆起来连番轰炸。
     */
    private static void deferEvent(String eventName, String eventPrompt, boolean withCamera) {
        DeferredEvent candidate = new DeferredEvent(eventName, eventPrompt, withCamera, priorityOf(eventName));
        while (true) {
            DeferredEvent current = DEFERRED.get();
            if (current != null && current.priority() >= candidate.priority()) {
                OllamaProxyMod.LOGGER.info("主动感知事件 {} 优先级不高于排队中的 {}，丢弃",
                        eventName, current.name());
                return;
            }
            if (DEFERRED.compareAndSet(current, candidate)) {
                OllamaProxyMod.LOGGER.info("主动感知事件 {} 已排队（{}），等待上一个请求结束",
                        eventName, current == null ? "空槽" : "挤掉 " + current.name());
                return;
            }
        }
    }

    /** 补发排队中的事件。补发时跳过冷却判断，因为它在排队前已经通过过一次。 */
    private static void drainDeferred() {
        DeferredEvent deferred = DEFERRED.getAndSet(null);
        if (deferred == null) {
            return;
        }
        Minecraft.getInstance().execute(() -> triggerInternal(
                deferred.name(), deferred.prompt(), deferred.withCamera(), false, true));
    }

    /** 优先级足够高的事件不受战斗静默影响（死亡、各档血量告急、手动观察）。 */
    private static boolean isCombatSilenceExempt(String eventName) {
        return priorityOf(eventName) >= COMBAT_SILENCE_EXEMPT_PRIORITY;
    }

    /** 战斗静默：刚出手或刚挨打的一段时间内不打扰玩家。 */
    private static boolean inCombatSilence() {
        int seconds = ConfigManager.getProactiveCombatSilenceSeconds();
        if (seconds <= 0) {
            return false;
        }
        long last = lastCombatAt;
        return last > 0L && System.currentTimeMillis() - last < seconds * 1000L;
    }

    /** 玩家出手攻击时调用，用于开启战斗静默窗口。 */
    public static void onPlayerAttack() {
        lastCombatAt = System.currentTimeMillis();
    }

    private static void completeAwarenessEvent(String eventName, String text, boolean fallback, long generation) {
        if (generation != REQUEST_GENERATION.get()) {
            return;
        }
        Minecraft.getInstance().execute(() -> {
            if (generation != REQUEST_GENERATION.get()) {
                return;
            }
            if (!fallback && text != null && !text.isBlank()) {
                lastTriggerTime = System.currentTimeMillis();
                // 真正聊过之后重新开始计时，避免刚说完就又被「定时闲聊」搭话
                idleAccumulatedMs = 0L;
            }
            OllamaProxyMod.LOGGER.info("主动感知事件完成: {}, fallback={}, 回复长度={}", eventName, fallback, text == null ? 0 : text.length());
            showResponse(eventName, text);
        });
    }

    private static String buildLocalFallback(String eventName) {
        return switch (eventName) {
            case "进入世界" -> "已进入世界，我会先观察游戏事件，等接口恢复后再继续智能吐槽。";
            case "打开暂停菜单" -> "你打开了暂停菜单，先歇一下也行。";
            case "玩家死亡" -> "这波有点痛，重整一下再来。";
            case "生命值偏低" -> "血量掉到一半以下了，注意点。";
            case "生命值过低" -> "血量很危险，先保命。";
            case "生命值危急" -> "血量快没了，快撤！";
            case "玩家睡觉" -> "晚安，睡一觉刷新状态。";
            case "玩家醒来" -> "醒了，新的冒险开始。";
            case "天亮了" -> "天亮了，可以出去走走了。";
            case "天黑了" -> "天黑了，小心刷怪。";
            case "开始下雨" -> "下雨了，记得别在雷雨天乱跑。";
            case "雨停了" -> "雨停了，空气清爽。";
            case "雷暴天气" -> "打雷了，尽量别站在空旷地方。";
            case "维度切换" -> "换了个地方，先看看周围环境。";
            case "玩家饥饿" -> "肚子饿了吧，先吃点东西。";
            case "经验升级" -> "升级了，继续加油。";
            case "定时闲聊" -> "没什么事，就是看看你在忙什么。";
            case "定时提醒" -> "你之前让我提醒你的事情到点了，记得处理一下。";
            case "摄像头观察" -> "画面我暂时看不清，等视觉接口恢复再仔细看你。";
            default -> "我检测到了这个事件，但 AI 服务暂时没有响应。";
        };
    }

    private static ConfigManager.ChatRoute primaryChatRoute() {
        java.util.List<ConfigManager.ChatRoute> routes = ConfigManager.snapshotChatRoutes();
        if (routes.isEmpty()) throw new IllegalArgumentException("主动感知 Chat API 地址为空");
        return routes.get(0);
    }

    /**
     * 选本次请求用哪个模型：带图时优先用配置里的视觉模型（填了的话），否则用线路默认模型。
     */
    private static String selectModelFor(ConfigManager.ChatRoute route,
                                         CameraCaptureService.CaptureResult capture) {
        boolean hasImage = capture != null && capture.ok();
        if (hasImage) {
            String visionModel = ConfigManager.getCameraVisionModel();
            if (visionModel != null && !visionModel.isBlank()) {
                return visionModel.trim();
            }
        }
        return route.model();
    }

    /**
     * 拼请求体。{@code capture} 非空且成功时，用 OpenAI 视觉格式
     * （content 数组 + image_url data URI）把画面一起发过去。
     */
    private static JsonObject buildRequestBody(String eventName, String eventPrompt, String model,
                                               CameraCaptureService.CaptureResult capture) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", false);

        boolean hasImage = capture != null && capture.ok();
        boolean hasCamera = hasImage && capture.hasCamera();
        boolean hasScreen = hasImage && capture.hasScreen();
        boolean hasAttachedScreen = hasImage && capture.hasAttachedScreen();
        boolean allowSee = ConfigManager.isCameraAllowModelToSee();

        StringBuilder text = new StringBuilder();
        text.append("[Verity 主动事件：").append(eventName).append("]\n")
                .append(eventPrompt)
                .append("\n当前游戏状态：").append(buildGameStateSummary());
        if (hasImage && allowSee) {
            text.append("\n附带的图片是刚刚采集到的真实画面：");
            if (hasCamera) {
                text.append("第 1 张来自玩家摄像头（真实世界）。");
            } else if (hasScreen) {
                text.append("第 1 张是玩家当前的游戏内画面（Minecraft 屏幕截图）。");
            }
            if (hasAttachedScreen) {
                text.append("第 2 张是玩家当前的游戏内画面（Minecraft 屏幕截图）。");
            }
            text.append("请结合画面内容自然回应，可以描述你看到的人、环境或游戏里的场景，"
                    + "但不要编造画面里没有的东西。");
        } else if (hasImage) {
            text.append("\n（附带了参考图片，但你不必描述它。）");
        } else {
            text.append("\n要求：只基于这些游戏内事件和状态回应；禁止假装看到了真实屏幕、截图或摄像头画面。");
        }
        text.append("\n回复必须简短自然，像主动吐槽或提醒。只输出玩家可见的自然语言正文；"
                + "不要输出 JSON，也不要输出 action、actions、emotion、variant、karma_change 等协议字段。");

        JsonArray messages = new JsonArray();
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");

        if (hasImage) {
            JsonArray content = new JsonArray();
            JsonObject textPart = new JsonObject();
            textPart.addProperty("type", "text");
            textPart.addProperty("text", text.toString());
            content.add(textPart);

            content.add(imagePart(capture.dataUri()));
            if (hasAttachedScreen) {
                content.add(imagePart(capture.screenDataUri()));
            }

            userMessage.add("content", content);
        } else {
            userMessage.addProperty("content", text.toString());
        }

        messages.add(userMessage);
        body.add("messages", messages);
        return body;
    }

    /** 构造一条 OpenAI 多模态 image_url 片段。 */
    private static JsonObject imagePart(String dataUri) {
        JsonObject imagePart = new JsonObject();
        imagePart.addProperty("type", "image_url");
        JsonObject imageUrl = new JsonObject();
        imageUrl.addProperty("url", dataUri);
        imagePart.add("image_url", imageUrl);
        return imagePart;
    }

    /** 状态摘要统一走 PlayerStateSnapshot，省得和代理层那边各写一份。 */
    private static String buildGameStateSummary() {
        StringBuilder summary = new StringBuilder(PlayerStateSnapshot.gameStateSummary());
        if (ConfigManager.isInventoryContextEnabled()) {
            summary.append("，背包=")
                    .append(PlayerStateSnapshot.inventorySummary(ConfigManager.getInventoryMaxTypes()));
        }
        return summary.toString();
    }

    private static String currentDimension(Minecraft minecraft) {
        return PlayerStateSnapshot.dimensionId(minecraft);
    }

    private static String describeDimension(String dimensionId) {
        if (dimensionId == null || dimensionId.isBlank()) {
            return "未知维度";
        }
        return switch (dimensionId) {
            case "minecraft:overworld" -> "主世界";
            case "minecraft:the_nether" -> "下界";
            case "minecraft:the_end" -> "末地";
            default -> dimensionId;
        };
    }

    private static boolean isDay(Minecraft minecraft) {
        return PlayerStateSnapshot.isDay(minecraft);
    }

    private static boolean isRaining(Minecraft minecraft) {
        try {
            return minecraft.level != null && minecraft.level.isRaining();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isThundering(Minecraft minecraft) {
        try {
            return minecraft.level != null && minecraft.level.isThundering();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int currentFoodLevel(Minecraft minecraft) {
        if (minecraft.player == null) {
            return 20;
        }
        try {
            return minecraft.player.getFoodData().getFoodLevel();
        } catch (Throwable ignored) {
            return 20;
        }
    }

    private static int currentExperienceLevel(Minecraft minecraft) {
        if (minecraft.player == null) {
            return 0;
        }
        try {
            return minecraft.player.experienceLevel;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static String sendDirectChatRequest(JsonObject body, ConfigManager.ChatRoute route, long generation) throws Exception {
        OllamaProxyMod.LOGGER.info("主动感知准备直连 Chat API");

        String targetUrl = buildTargetUrl(route.apiUrl());
        URL url = NetworkSecurity.requireSecureUri(targetUrl, "主动感知 Chat API 地址").toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        synchronized (REQUEST_STATE_LOCK) {
            if (generation == REQUEST_GENERATION.get()) {
                CURRENT_CONNECTION.set(conn);
            }
        }
        if (CURRENT_CONNECTION.get() != conn) {
            conn.disconnect();
            throw new InterruptedException("主动感知请求已取消");
        }
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "Java/17 (VerityCustomAPI/Awareness)");
            String apiKey = route.apiKey();
            if (apiKey != null && !apiKey.isBlank()) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("Accept-Encoding", "identity");

            byte[] requestBytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
            if ("builtin".equals(route.source())) {
                OllamaProxyMod.LOGGER.info("主动感知使用云端内置 Chat 线路: routeId={}, tier={}",
                        route.id(), route.tier());
            } else {
                OllamaProxyMod.LOGGER.info("主动感知直连 Chat API: endpoint={}, model={}, bodySize={}",
                        NetworkSecurity.sanitizedEndpoint(targetUrl), route.model(), requestBytes.length);
            }

            try (OutputStream os = conn.getOutputStream()) {
                os.write(requestBytes);
                os.flush();
            }

            int status = conn.getResponseCode();
            InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String responseBody = "";
            if (is != null) {
                try {
                    responseBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                } finally {
                    try { is.close(); } catch (Exception ignored) {}
                }
            }

            OllamaProxyMod.LOGGER.info("主动感知 Chat 线路响应: routeId={}, tier={}, status={}",
                    route.id(), route.tier(), status);
            if (status >= 400) {
                String detail = "builtin".equals(route.source()) ? "" : ": " + summarize(responseBody);
                throw new IllegalStateException("Chat API HTTP " + status + detail);
            }
            return responseBody;
        } finally {
            CURRENT_CONNECTION.compareAndSet(conn, null);
            conn.disconnect();
        }
    }

    private static String buildTargetUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("主动感知 Chat API 地址为空");
        }
        baseUrl = baseUrl.trim();
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        if (baseUrl.endsWith("/chat/completions")) {
            return baseUrl;
        }
        return baseUrl + "/chat/completions";
    }

    private static String extractText(String responseBody) {
        JsonObject response = GSON.fromJson(responseBody, JsonObject.class);
        if (response == null || !response.has("choices") || response.getAsJsonArray("choices").isEmpty()) {
            return "";
        }
        JsonObject choice = response.getAsJsonArray("choices").get(0).getAsJsonObject();
        JsonObject message = choice.has("message") && choice.get("message").isJsonObject()
                ? choice.getAsJsonObject("message") : null;
        String content = "";
        if (message != null && message.has("content") && !message.get("content").isJsonNull()) {
            content = message.get("content").getAsString();
        } else if (choice.has("text") && !choice.get("text").isJsonNull()) {
            content = choice.get("text").getAsString();
        }
        if (content == null || content.isBlank()) {
            return "";
        }
        try {
            JsonObject inner = GSON.fromJson(content, JsonObject.class);
            if (inner != null) {
                if (inner.has("message") && !inner.get("message").isJsonNull()) {
                    return inner.get("message").getAsString();
                }
                if (inner.has("response") && !inner.get("response").isJsonNull()) {
                    return inner.get("response").getAsString();
                }
            }
        } catch (RuntimeException ignored) {
        }
        return content;
    }

    private static String sanitizeDisplayText(String text) {
        if (text == null || text.isBlank()) return "";
        String cleaned = text.trim();
        for (int depth = 0; depth < 3; depth++) {
            cleaned = stripCodeFence(cleaned);
            String unwrapped = unwrapStructuredText(cleaned);
            if (unwrapped.equals(cleaned)) break;
            cleaned = unwrapped.trim();
        }
        cleaned = stripCodeFence(cleaned)
                .replaceAll("\\u001B\\[[;\\d]*[ -/]*[@-~]", "")
                .replaceAll("§.", "")
                .replaceAll("(?m)^\\s{0,3}(?:#{1,6}|>|[-+*]\\s+)\\s*", "")
                .replaceAll("\\[([^]\\r\\n]+)]\\([^)]*\\)", "$1")
                .replace("**", "")
                .replace("__", "")
                .replace("`", "")
                .replaceAll("(?i)(?:[,，;；]?\\s*[\"']?(?:action|actions|emotion|variant|karma_change|camera_look)[\"']?"
                        + "\\s*[:：=]\\s*[\"']?(?:none|null|neutral|false|0(?:\\.0)?)[\"']?\\s*[},]?\\s*)+$", "");
        StringBuilder safe = new StringBuilder(cleaned.length());
        cleaned.codePoints().forEach(value -> {
            if (!Character.isISOControl(value) || value == '\n' || value == '\r' || value == '\t') {
                safe.appendCodePoint(value);
            }
        });
        return safe.toString().replaceAll("\\s+", " ").trim();
    }

    private static String stripCodeFence(String text) {
        String cleaned = text.trim();
        if (!cleaned.startsWith("```")) return cleaned;
        int firstLine = cleaned.indexOf('\n');
        if (firstLine < 0) return cleaned.replace("```", "").trim();
        cleaned = cleaned.substring(firstLine + 1);
        int closing = cleaned.lastIndexOf("```");
        return (closing >= 0 ? cleaned.substring(0, closing) : cleaned).trim();
    }

    private static String unwrapStructuredText(String text) {
        try {
            JsonElement element = JsonParser.parseString(text);
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) return element.getAsString();
            if (element.isJsonArray() && !element.getAsJsonArray().isEmpty()) element = element.getAsJsonArray().get(0);
            if (!element.isJsonObject()) return text;
            JsonObject object = element.getAsJsonObject();
            for (String key : new String[]{"message", "response", "content", "text"}) {
                if (!object.has(key) || object.get(key).isJsonNull()) continue;
                JsonElement value = object.get(key);
                if (value.isJsonPrimitive()) return value.getAsString();
                return GSON.toJson(value);
            }
        } catch (RuntimeException ignored) {
        }
        return text;
    }

    /** 手动摄像头观察与主动事件的提示前缀分开，避免玩家误解触发来源。 */
    private static String displayName(String eventName) {
        return "摄像头观察".equals(eventName) ? "观察" : "感知";
    }

    private static void showResponse(String eventName, String text) {
        if (text == null || text.isBlank()) {
            showStatus("§7" + displayName(eventName) + " · 暂时没有回应");
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            Component message = Component.literal(displayName(eventName) + " · ").withStyle(style -> style.withColor(0x65DDE0))
                    .append(Component.literal(text).withStyle(style -> style.withColor(0xE8FFFF)));
            minecraft.player.displayClientMessage(message, true);
        } else {
            SystemToast.add(
                    minecraft.getToasts(),
                    SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
                    Component.literal("Verity"),
                    Component.literal(text)
            );
        }
        if (ConfigManager.isProactiveDesktopNotificationsEnabled() && !minecraft.isWindowActive() && canUseDesktopNotifications()) {
            showDesktopNotification(text);
        }
    }

    private static void showStatus(String message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal(message), true);
        }
    }

    private static String summarize(String text) {
        if (text == null || text.isBlank()) {
            return "<empty>";
        }
        String cleaned = text.replace('\n', ' ').replace('\r', ' ').trim();
        return cleaned.length() > 240 ? cleaned.substring(0, 240) + "..." : cleaned;
    }

    private static boolean canUseDesktopNotifications() {
        return !RuntimePlatform.isAndroidRuntime() && !RuntimePlatform.hasAndroidMediaClasses();
    }

    private static void showDesktopNotification(String text) {
        try {
            Class<?> systemTrayClass = Class.forName("java.awt.SystemTray");
            boolean supported = (Boolean) systemTrayClass.getMethod("isSupported").invoke(null);
            if (!supported) {
                return;
            }
            if (trayIcon == null) {
                trayIcon = createTrayIcon();
                Object tray = systemTrayClass.getMethod("getSystemTray").invoke(null);
                tray.getClass().getMethod("add", Class.forName("java.awt.TrayIcon")).invoke(tray, trayIcon);
            }
            Class<?> messageTypeClass = Class.forName("java.awt.TrayIcon$MessageType");
            Object noneType = enumValue(messageTypeClass, "NONE");
            trayIcon.getClass().getMethod("displayMessage", String.class, String.class, messageTypeClass)
                    .invoke(trayIcon, "Verity", text, noneType);
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.warn("桌面通知显示失败: {}", error.getMessage());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(Class<?> enumClass, String name) {
        return Enum.valueOf((Class<? extends Enum>) enumClass.asSubclass(Enum.class), name);
    }

    private static Object createTrayIcon() throws Exception {
        Class<?> bufferedImageClass = Class.forName("java.awt.image.BufferedImage");
        Class<?> colorClass = Class.forName("java.awt.Color");
        Class<?> trayIconClass = Class.forName("java.awt.TrayIcon");
        Object image = bufferedImageClass
                .getConstructor(int.class, int.class, int.class)
                .newInstance(16, 16, bufferedImageClass.getField("TYPE_INT_ARGB").getInt(null));
        Object graphics = bufferedImageClass.getMethod("createGraphics").invoke(image);
        Object color = colorClass.getConstructor(int.class, int.class, int.class).newInstance(93, 139, 255);
        graphics.getClass().getMethod("setColor", Class.forName("java.awt.Color")).invoke(graphics, color);
        graphics.getClass().getMethod("fillOval", int.class, int.class, int.class, int.class).invoke(graphics, 1, 1, 14, 14);
        graphics.getClass().getMethod("dispose").invoke(graphics);
        Constructor<?> constructor = trayIconClass.getConstructor(Class.forName("java.awt.Image"), String.class);
        Object icon = constructor.newInstance(image, "Verity");
        Method setImageAutoSize = trayIconClass.getMethod("setImageAutoSize", boolean.class);
        setImageAutoSize.invoke(icon, true);
        return icon;
    }

    private static void resetState() {
        HttpURLConnection connection;
        synchronized (REQUEST_STATE_LOCK) {
            REQUEST_GENERATION.incrementAndGet();
            connection = CURRENT_CONNECTION.getAndSet(null);
        }
        if (connection != null) {
            connection.disconnect();
        }
        Thread requestThread = currentRequestThread;
        if (requestThread != null && requestThread.isAlive()) {
            requestThread.interrupt();
        }
        REQUEST_IN_FLIGHT.set(false);
        currentRequestThread = null;
        DEFERRED.set(null);
        Minecraft minecraft = Minecraft.getInstance();
        wasPaused = minecraft.isPaused() || minecraft.screen instanceof PauseScreen;
        wasDead = getPlayerHealth(minecraft) <= 0.0F;
        wasSleeping = isPlayerSleeping(minecraft);
        float maxHealth = getPlayerMaxHealth(minecraft);
        float health = getPlayerHealth(minecraft);
        lowHealthArmed = minecraft.player == null || maxHealth <= 0.0F || health / maxHealth > 0.35F;
        wasDay = isDay(minecraft);
        wasRaining = isRaining(minecraft);
        wasThundering = isThundering(minecraft);
        lastDimension = currentDimension(minecraft);
        hungerArmed = currentFoodLevel(minecraft) > HUNGER_ARM_THRESHOLD;
        lastExperienceLevel = currentExperienceLevel(minecraft);
        lastHealth = health;
        reportedHealthTier = 0;
        idleAccumulatedMs = 0L;
        lastTickMillis = System.currentTimeMillis();
        lastCombatAt = 0L;
        stateInitialized = false;
    }

    private static void disconnectCurrentConnection() {
        HttpURLConnection connection = CURRENT_CONNECTION.getAndSet(null);
        if (connection != null) {
            connection.disconnect();
        }
    }

    private static float getPlayerHealth(Minecraft minecraft) {
        Object value = callPlayerMethod(minecraft, "getHealth", "m_21223_");
        return value instanceof Number number ? number.floatValue() : 0.0F;
    }

    private static float getPlayerMaxHealth(Minecraft minecraft) {
        Object value = callPlayerMethod(minecraft, "getMaxHealth", "m_21233_");
        return value instanceof Number number ? number.floatValue() : 0.0F;
    }

    private static boolean isPlayerSleeping(Minecraft minecraft) {
        Object value = callPlayerMethod(minecraft, "isSleeping", "m_5803_");
        return value instanceof Boolean bool && bool;
    }

    private static double getPlayerDouble(Minecraft minecraft, String devName, String productionName) {
        Object value = callPlayerMethod(minecraft, devName, productionName);
        return value instanceof Number number ? number.doubleValue() : 0.0D;
    }

    private static Object callPlayerMethod(Minecraft minecraft, String devName, String productionName) {
        if (minecraft.player == null) {
            return null;
        }
        try {
            return minecraft.player.getClass().getMethod(devName).invoke(minecraft.player);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            try {
                return minecraft.player.getClass().getMethod(productionName).invoke(minecraft.player);
            } catch (ReflectiveOperationException | RuntimeException ignoredAgain) {
                return null;
            }
        }
    }
}
