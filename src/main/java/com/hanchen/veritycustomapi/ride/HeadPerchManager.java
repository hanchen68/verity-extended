package com.hanchen.veritycustomapi.ride;

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

/**
 * Verity 站/蹲在玩家头上：状态切换、意愿判定。
 *
 * 不占乘客位，只记「Verity 挂在谁身上」，每帧由 {@link #placeOnHead} 摆位置并关掉重力。
 * 早期版本把玩家当载具、Verity 当乘客，得一路对抗原版：服务端在乘客不在 tick 列表时会
 * 直接把 Verity {@code stopRiding()}；而乘客照样会被 tick，Verity 自己的 {@code aiStep -> travel}
 * 带着重力与移动逻辑，和「每帧摆到头上的定位」互相打架，表现就是刚站上去就掉下来。
 * 现在再把 Verity 自己的移动掐掉（见 {@code LivingEntityPerchRideMixin}）才稳。
 */
public final class HeadPerchManager {
    private static final double MAX_DISTANCE_SQR = 36.0D;
    private static final long MOUNT_REPORT_INTERVAL_TICKS = 20L;

    private HeadPerchManager() {
    }

    public enum RefuseReason {
        DISABLED("头上陪伴功能没开启哦"),
        SHOULDER("我现在正被扛在肩上呢，先让我下来吧"),
        DEMON("我现在是恶魔形态，没法站到你头上"),
        PLEADING("别、别过来……我现在不想靠近你"),
        NOT_OWNER("我只站主人的头上"),
        TOO_FAR("太远啦，靠近一点我才跳得上去"),
        BUSY("我现在被别的东西绊住了，等一下");

        private final String text;

        RefuseReason(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    /** 按 H 走这里：已经站在你头上就下来，否则判定能不能上去。 */
    public static void toggle(ServerPlayer player) {
        if (player == null) return;
        VerityEntity verity = VerityEntityLookup.find(player);

        if (verity == null) {
            com.hanchen.veritycustomapi.OllamaProxyMod.LOGGER.info(
                    "【站头】没找到属于 {} 的 Verity（可能不在同维度、或归属不是他）",
                    player.getScoreboardName());
            feedback(player, "✖ 现在没找到你的 Verity，走近一点再试试吧", ChatFormatting.RED);
            return;
        }
        com.hanchen.veritycustomapi.OllamaProxyMod.LOGGER.info(
                "【站头】请求：verity={} 距离²={} 站头中={} Verity 的载具={} Verity 的乘客={} 恶魔={} 求饶={} 开关={}",
                verity.getId(), String.format("%.1f", verity.distanceToSqr(player)),
                HeadPerchState.isPerched(verity.getUUID()),
                verity.getVehicle() == null ? "无" : verity.getVehicle().getType().toString(),
                verity.getFirstPassenger() == null ? "无" : verity.getFirstPassenger().getType().toString(),
                verity.isMonstrous(), verity.pleadingTimer,
                ModConfig.SERVER_VERITY_HEAD_PERCH.get());

        // 已经站在你头上 -> 下来
        if (player.getUUID().equals(HeadPerchState.carrierOf(verity.getUUID()))) {
            dismount(verity, player, "● Verity 从你头上跳下来了");
            return;
        }
        if (HeadPerchState.isPerched(verity.getUUID())) {
            feedback(player, "✖ Verity 正站在其他玩家头上，先靠近一点再说吧", ChatFormatting.RED);
            return;
        }
        if (verity.getVehicle() != null) {
            feedback(player, "✖ " + RefuseReason.BUSY.text(), ChatFormatting.RED);
            return;
        }
        if (verity.getFirstPassenger() == player) {
            // 玩家正骑着 Verity 时按同一个键 = 下骑，按键不额外拆一个
            player.stopRiding();
            feedback(player, "● 你从 Verity 身上下来了", ChatFormatting.GREEN);
            return;
        }
        if (verity.getFirstPassenger() != null) {
            feedback(player, "✖ Verity 正被别人骑着，等对方下来后再试", ChatFormatting.RED);
            return;
        }
        if (!Boolean.TRUE.equals(ModConfig.SERVER_VERITY_HEAD_PERCH.get())) {
            feedback(player, "✖ " + RefuseReason.DISABLED.text(), ChatFormatting.RED);
            return;
        }

        RefuseReason reason = evaluate(verity, player);
        if (reason != null) {
            com.hanchen.veritycustomapi.OllamaProxyMod.LOGGER.info("【站头】被拒绝：{}", reason.name());
            feedback(player, "✖ " + reason.text(), ChatFormatting.RED);
            return;
        }
        if (!verity.level().dimension().equals(player.level().dimension())) {
            feedback(player, "✖ Verity 现在在别的维度，先过去再说吧", ChatFormatting.RED);
            return;
        }
        if (verity.distanceToSqr(player) > MAX_DISTANCE_SQR) {
            feedback(player, "✖ " + RefuseReason.TOO_FAR.text(), ChatFormatting.RED);
            return;
        }

        HeadPerchState.perch(verity.getUUID(), player.getUUID());
        placeOnHead(verity, player);
        com.hanchen.veritycustomapi.OllamaProxyMod.LOGGER.info(
                "【站头】已站上 {}（不占乘客位，由每帧定位跟随）", player.getScoreboardName());
        feedback(player, "● Verity 跳到了你头上，再按一次即可下来", ChatFormatting.GREEN);
        reportMount(verity, player);
    }

    /**
     * 把 Verity 摆到玩家头顶并停掉 Verity 自己的移动。
     *
     * 每帧都会被 {@code EntityPerchRideMixin} 调用一次：位置完全跟随玩家，
     * 速度清零，避免 Verity 自己飘走或被别的东西推走。
     */
    public static void placeOnHead(VerityEntity verity, Player player) {
        verity.setNoGravity(true);
        // noPhysics 会让 Entity.push 直接跳过（原版判定是「任一方 noPhysics 就不推」），
        // 与掐掉 aiStep 形成双保险，确保 Verity 不会把玩家推着走
        verity.noPhysics = true;
        verity.setDeltaMovement(Vec3.ZERO);
        verity.setPos(player.getX(),
                player.getY() + player.getBbHeight() - 0.05D,
                player.getZ());
        // 原样复制玩家的旋转，连「上一 tick 值」一起复制。
        // 渲染器的平滑转靠 ?Rot / ?RotO 这对字段插值，只写当前值会把插值抹平，
        // 结果就是每帧硬切——表现出来的正是「一直不停抽搐」。
        verity.setYRot(player.getYRot());
        verity.yRotO = player.yRotO;
        verity.setYHeadRot(player.getYHeadRot());
        verity.yHeadRotO = player.yHeadRotO;
        verity.yBodyRot = player.yBodyRot;
        verity.yBodyRotO = player.yBodyRotO;
        verity.setXRot(player.getXRot());
        verity.xRotO = player.xRotO;
    }

    /** 解除站头并给玩家提示。 */
    public static void dismount(VerityEntity verity, ServerPlayer player, String message) {
        if (verity == null) return;
        HeadPerchState.release(verity.getUUID());
        verity.setNoGravity(false);
        verity.noPhysics = false;
        if (player != null) {
            feedback(player, message, ChatFormatting.GREEN);
        }
    }

    /**
     * 被动解除：Verity 消失了、玩家换维度或死亡等。只清状态与重力，不打扰玩家。
     *
     * 不在这里向 AI 报告——内部状态变化不该变成玩家可见的对话请求。
     */
    public static void releaseQuietly(VerityEntity verity) {
        if (verity == null) return;
        HeadPerchState.release(verity.getUUID());
        verity.setNoGravity(false);
        verity.noPhysics = false;
        // 静默解除原来是完全不记日志的，出了问题只能靠猜；现在记一笔，
        // 区分「玩家按 H 主动下来」和「系统被动解除」
        com.hanchen.veritycustomapi.OllamaProxyMod.LOGGER.info(
                "【站头】被动解除（实体消失/死亡/换维度）：verity={}", verity.getId());
    }

    /** 成功站上之后才向 AI 报告；并登记回复扫描。 */
    private static void reportMount(VerityEntity verity, ServerPlayer player) {
        if (!AiEventThrottle.tryAcquire(verity.level(), "perch_mount", verity.getId(),
                MOUNT_REPORT_INTERVAL_TICKS)) {
            return;
        }
        PerchRideState.mark(verity, player, true);
        // 异步汇报：原版 AI 请求全局串行，无 AI 接入时同步调用会卡服务端主线程
        OriginalVerityChatBridge.queryAiAsync(verity, player,
                "（我轻巧地跳到了你头上蹲着，跟着你到处走。视角和朝向请跟随玩家）");
    }

    /** Mixin 检测到状态被外部清掉时调用；只做事件报告，不设任何自动下落计时。 */
    public static void onDismounted(VerityEntity verity, ServerPlayer carrier) {
        // 关系清理由实体本身完成；不为内部 tick 变化触发 AI 对话。
    }

    /** 按键"让 Verity 下来"：Verity 站在按键玩家头上时解除。 */
    public static boolean requestDismount(ServerPlayer player) {
        if (player == null) return false;
        VerityEntity verity = VerityEntityLookup.find(player);
        if (verity == null || !verity.isAlive()) {
            feedback(player, "✖ 现在没找到你的 Verity，走近一点再试试吧", ChatFormatting.RED);
            return false;
        }
        if (!player.getUUID().equals(HeadPerchState.carrierOf(verity.getUUID()))) {
            feedback(player, "Verity 现在没站在你头上哦", ChatFormatting.YELLOW);
            return false;
        }
        dismount(verity, player, "● Verity 已经从你头上下来了");
        return true;
    }

    private static RefuseReason evaluate(VerityEntity verity, ServerPlayer player) {
        if (VerityShoulderCarry.isCarried(verity)) return RefuseReason.SHOULDER;
        if (verity.isMonstrous()) return RefuseReason.DEMON;
        if (verity.pleadingTimer >= 0) return RefuseReason.PLEADING;
        if (Boolean.TRUE.equals(ModConfig.SERVER_VERITY_RIDE_OWNER_ONLY.get())) {
            Optional<UUID> owner = verity.getOwnerUUID();
            if (owner.isPresent() && !owner.get().equals(player.getUUID())) {
                return RefuseReason.NOT_OWNER;
            }
        }
        return null;
    }

    private static void feedback(ServerPlayer player, String text, ChatFormatting color) {
        player.displayClientMessage(Component.literal(text).withStyle(color), true);
    }
}
