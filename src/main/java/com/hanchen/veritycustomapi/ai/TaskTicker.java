package com.hanchen.veritycustomapi.ai;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 把 {@link TaskQueue} 挂到服务端生命周期上。
 *
 * <p>单独一个类而不是塞进 {@code OllamaProxyMod}：任务队列和主类的职责完全无关，
 * 混在一起会让主类的构造器继续膨胀（已经够长了）。
 *
 * <p>每 tick 只推进**一步**，所以这里的开销是常数级：没有任务时直接返回，
 * 有任务时一次方块操作——不会因为「AI 在后台干活」而掉 TPS。
 */
@Mod.EventBusSubscriber(modid = OllamaProxyMod.MOD_ID)
public final class TaskTicker {

    private TaskTicker() {
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        try {
            // 采集作业先推进：它自己管导航/朝向/破坏的节奏
            com.hanchen.veritycustomapi.behavior.VerityMiningJob.tick(event.getServer());
            TaskQueue.tick(event.getServer());
        } catch (Throwable error) {
            // 任务系统出问题绝不能影响主循环
            OllamaProxyMod.LOGGER.warn("[任务] tick 异常: {}", error.toString());
        }
    }

    @SubscribeEvent
    public static void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        try {
            if (event.getEntity() != null) {
                TaskQueue.onPlayerLeave(event.getEntity().getUUID());
                com.hanchen.veritycustomapi.behavior.VerityMiningJob
                        .onPlayerLeave(event.getEntity().getUUID());
            }
        } catch (Throwable ignored) {
            // 下线清理失败不影响退出
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        TaskQueue.clear();
        LocalToolContext.clear();
        com.hanchen.veritycustomapi.behavior.VeritySchedule.clear();
        com.hanchen.veritycustomapi.behavior.VerityMiningJob.clear();
    }
}
