package com.hanchen.veritycustomapi.behavior;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.ai.LocalToolContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Verity 的「自主日程」：按游戏内时段调整行为。
 *
 * <p>现在只剩一件事：<b>天黑后归位</b>——离主人太远就走回身边。
 * 移动交给原版寻路，不直接改坐标（改坐标会杀掉渲染插值，镜头一卡一卡）。
 *
 * <p>🔴 <b>「主动提议」整块已删除</b>（2026-10-31 用户要求）。
 * 原来会在附近有矿/有熟作物时塞一句「附近好像有矿，要我挖吗」，
 * 但冷却表是内存态，每次进世界都会重新触发一次，玩家看到的就是「每次进去都刷一遍」。
 * 这类话让模型在对话里自己说更自然，不需要系统硬塞。
 */
@Mod.EventBusSubscriber(modid = OllamaProxyMod.MOD_ID)
public final class VeritySchedule {

    /** 检查间隔（tick）。5 秒一次，足够响应又不浪费。 */
    private static final int CHECK_INTERVAL = 100;

    /** 归位判定的距离（格）：超过这个距离才往回走。 */
    private static final double RETURN_HOME_DISTANCE_SQR = 12.0D * 12.0D;

    private static int tickCounter;

    private VeritySchedule() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (++tickCounter < CHECK_INTERVAL) return;
        tickCounter = 0;

        try {
            MinecraftServer server = event.getServer();
            if (server == null) return;

            ServerPlayer player = LocalToolContext.current(server);
            if (player == null) return;
            if (!player.isAlive()) return;

            // 战斗/骑乘/对话中不打扰
            if (player.isSleeping()) return;

            if (isNight(player.serverLevel())) {
                maybeReturnHome(player);
            }
        } catch (Throwable error) {
            // 日程系统出问题绝不能影响主循环
            OllamaProxyMod.LOGGER.warn("[日程] 检查异常: {}", error.toString());
        }
    }

    /** 游戏内是否夜间（13000~23000 视为夜）。 */
    private static boolean isNight(ServerLevel level) {
        long time = level.getDayTime() % 24000L;
        return time >= 13000L && time <= 23000L;
    }

    /**
     * 夜间归位：离主人超过 12 格就让原版寻路把 Verity 带回来。
     *
     * <p>只用 {@code getNavigation().moveTo}，不碰 position —— 直接改坐标会杀掉渲染插值。
     */
    private static void maybeReturnHome(ServerPlayer player) {
        try {
            var verity = com.hanchen.veritycustomapi.network.ServerVerityRegistry.findForPlayer(player);
            if (verity == null || !verity.isAlive()) return;
            var autonomous = com.hanchen.veritycustomapi.autonomy.VerityAutonomy.state(verity);
            if (autonomous.enabled || autonomous.suspended || autonomous.waitUntil > verity.level().getGameTime()
                    || autonomous.plan != null) return;
            if (VerityMiningJob.isBusyFor(verity) || verity.isPassenger() || verity.isVehicle()) return;
            if (verity.distanceToSqr(player) <= RETURN_HOME_DISTANCE_SQR) return;
            verity.getNavigation().moveTo(player, 1.0D);
        } catch (Throwable ignored) {
            // 寻路失败无所谓，下一轮再试
        }
    }

    /** 服务器停止时复位计数。 */
    public static void clear() {
        tickCounter = 0;
    }
}
