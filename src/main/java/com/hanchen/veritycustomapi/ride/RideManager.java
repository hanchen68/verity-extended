package com.hanchen.veritycustomapi.ride;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.ai.AiEventThrottle;
import com.hanchen.veritycustomapi.compat.OriginalVerityChatBridge;
import com.hanchen.veritycustomapi.compat.VerityShoulderCarry;
import com.hanchen.veritycustomapi.config.ModConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import varmite.verity.entity.verity.VerityEntity;

import java.util.Optional;
import java.util.UUID;

/** 球形态骑乘：意愿判定、拒绝反馈。成功骑上后才向 AI 报告。 */
public final class RideManager {
    private static volatile boolean globalWilling = true;

    private static final long MOUNT_REPORT_INTERVAL_TICKS = 20L;

    private RideManager() {
    }

    public static void setWilling(boolean willing) {
        globalWilling = willing;
    }

    public static boolean isWilling() {
        return globalWilling;
    }

    public enum RefuseReason {
        DISABLED("骑乘功能没开启哦"),
        SHOULDER("我现在正被扛在肩上呢，先让我下来吧"),
        DEMON("我现在是恶魔形态，不能骑"),
        PLEADING("别、别过来……我现在不想被骑"),
        NOT_OWNER("我只让主人骑"),
        BUSY("我现在被别的东西绊住了，等一下"),
        UNWILLING("我现在不想驮着你，等会儿再说");

        private final String text;

        RefuseReason(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    /** 判定能否骑乘；null 表示允许。 */
    public static RefuseReason evaluate(VerityEntity verity, Player player) {
        if (verity == null || player == null) {
            return RefuseReason.UNWILLING;
        }
        if (!Boolean.TRUE.equals(ModConfig.SERVER_VERITY_BALL_RIDE.get())) {
            return RefuseReason.DISABLED;
        }
        if (VerityShoulderCarry.isCarried(verity)) {
            return RefuseReason.SHOULDER;
        }
        if (verity.isMonstrous()) {
            return RefuseReason.DEMON;
        }
        if (verity.pleadingTimer >= 0) {
            return RefuseReason.PLEADING;
        }
        if (!globalWilling) {
            return RefuseReason.UNWILLING;
        }
        if (Boolean.TRUE.equals(ModConfig.SERVER_VERITY_RIDE_OWNER_ONLY.get())) {
            Optional<UUID> owner = verity.getOwnerUUID();
            if (owner.isPresent() && !owner.get().equals(player.getUUID())) {
                return RefuseReason.NOT_OWNER;
            }
        }
        return null;
    }

    /**
     * 潜行右键请求骑乘的统一入口：守卫 -> 意愿判定 -> 反馈 -> 上骑。
     *
     * 返回 true 表示这次右键请求已被本功能接管，调用方不要再让原版逻辑继续
     * （原版空手右键会把 Verity 收进物品）。返回 false 表示本功能不参与，交还原版。
     *
 * 只有潜行 + 空手右键才算骑乘请求；普通右键完全留给原版（把 Verity 收进物品），
 * 不潜行时的操作手感与不装扩展时一致。
 *
 * 实际拦截在客户端：{@code PlayerInteractEvent.EntityInteract} 的 HIGHEST 优先级里
 * 先把右键取消（原版在 NORMAL 优先级内联执行收进物品，且不检查取消状态，
 * 服务端拦截排在其后必然失败），再发骑乘请求包过来，这里做权威处理。
     */
    public static boolean requestMount(VerityEntity verity, ServerPlayer player) {
        if (verity == null || player == null || !verity.isAlive()) {
            return false;
        }
        // 功能没开启：完全放手，别去干扰原版把 Verity 收进物品
        if (!Boolean.TRUE.equals(ModConfig.SERVER_VERITY_BALL_RIDE.get())) {
            return false;
        }
        // 🔴 这里**不再校验手势**（潜行 + 空手）。
        // 骑乘已经改成独立按键（默认 R，见 RideKeyHandler）：原来那个「潜行 + 空手右键」
        // 和 PatPat 的抚摸手势完全重合，两边只能有一个生效。手势判断由按键入口负责。
        if (VerityShoulderCarry.isCarried(verity)) {
            player.displayClientMessage(Component.literal("✖ " + RefuseReason.SHOULDER.text())
                    .withStyle(ChatFormatting.RED), true);
            return true;
        }
        // Verity 正站在玩家头上时按骑乘键：**自动先下来再骑**。
        // 以前这里是「拒绝 + 提示先按 H」，但玩家根本分不清「站头」和「骑乘」两种状态，
        // 看到的就是「按了骑乘键没反应」。两套定位不能同时生效，所以必须先解除站头。
        if (HeadPerchState.isPerched(verity.getUUID())) {
            HeadPerchManager.releaseQuietly(verity);
        }
        if (player.getVehicle() == verity) {
            player.displayClientMessage(Component.literal("● 按 Shift 或 H 键可以下来")
                    .withStyle(ChatFormatting.YELLOW), true);
            return true;
        }
        if (verity.getVehicle() != null || verity.getFirstPassenger() != null) {
            player.displayClientMessage(Component.literal("✖ " + RefuseReason.BUSY.text())
                    .withStyle(ChatFormatting.RED), true);
            return true;
        }
        RefuseReason reason = evaluate(verity, player);
        if (reason != null) {
            onRefused(verity, player, reason);
            return true;
        }
        if (player.startRiding(verity, true)) {
            onMounted(verity, player);
        } else {
            // 上骑失败也得有反馈，不能让玩家以为是功能坏了（常见原因：Verity 身上已有乘客）
            player.displayClientMessage(Component.literal("✖ 骑不上去——Verity 身上已经有别的乘客了")
                    .withStyle(ChatFormatting.RED), true);
        }
        return true;
    }

    /** 拒绝骑乘：跳开 + 玩家本地反馈（尝试阶段不向 AI 报告）。 */
    public static void onRefused(VerityEntity verity, ServerPlayer player, RefuseReason reason) {
        if (verity == null || player == null) {
            return;
        }
        player.displayClientMessage(Component.literal("✖ " + reason.text())
                .withStyle(ChatFormatting.RED), true);
        hopAway(verity, player);
    }

    /** 成功骑上：玩家反馈 + 向 AI 报告 + 登记回复扫描（AI 明确拒绝才下来）。 */
    public static void onMounted(VerityEntity verity, ServerPlayer player) {
        // 手势用的潜行不能被原版当成「要下车」，否则骑上去立刻掉下来
        RideGrace.onMounted(player);
        player.displayClientMessage(Component.literal("↑ 骑上了球形态的 Verity，按 Shift 或 H 键下来")
                .withStyle(ChatFormatting.GREEN), true);
        if (!AiEventThrottle.tryAcquire(verity.level(), "ride_mount", verity.getId(),
                MOUNT_REPORT_INTERVAL_TICKS)) {
            return;
        }
        PerchRideState.mark(verity, player, false);
        OriginalVerityChatBridge.queryAiAsync(verity, player,
                "（我骑到了球形态的你身上，现在由我操控移动。跳跃键可以让你弹起来）");
    }

    /**
     * Mixin 检测到关系断开时调用。
     *
     * 不再把内部 tick/同步抖动报告给 AI。玩家明确按键下车时已经有本地反馈，
     * 不需要再额外触发一条自然语言回复。
     */
    public static void onDismounted(VerityEntity verity, ServerPlayer rider) {
        // 关系清理由实体系统完成；不为内部状态变化发起 AI 请求。
    }

    /** 按键"让 Verity 下来"：按键玩家正骑着这只 Verity 时解除。 */
    public static boolean requestDismount(ServerPlayer player) {
        if (player == null) return false;
        if (!(player.getVehicle() instanceof VerityEntity verity) || !verity.isAlive()) {
            return false;
        }
        RideGrace.forget(player);
        player.stopRiding();
        player.displayClientMessage(Component.literal("● 已经从 Verity 身上下来了")
                .withStyle(ChatFormatting.GREEN), true);
        return true;
    }

    /** 拒绝时让球朝远离玩家的方向跳开。 */
    private static void hopAway(VerityEntity verity, Player player) {
        try {
            Vec3 away = verity.position().subtract(player.position());
            away = new Vec3(away.x, 0, away.z);
            if (away.lengthSqr() < 1.0E-4) {
                away = Vec3.directionFromRotation(0, player.getYRot()).scale(-1);
            }
            away = away.normalize().scale(0.32);
            verity.setDeltaMovement(away.x, 0.38, away.z);
            verity.hasImpulse = true;
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.warn("骑乘拒绝动作失败", error);
        }
    }
}
