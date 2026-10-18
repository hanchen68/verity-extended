package com.hanchen.veritycustomapi.ride;

import com.hanchen.veritycustomapi.config.ModConfig;
import com.hanchen.veritycustomapi.network.HeadPerchNetwork;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import varmite.verity.entity.verity.VerityEntity;

/**
 * 潜行右键请求骑乘。
 *
 * 拦截必须在客户端做、而且要用最高优先级取消事件。原版对右键 Verity 的处理写在
 * {@code PlayerInteractEvent.EntityInteract} 里，核对原版实现下来有三个关键事实：
 *
 * - 它对「目标是否 Verity」的分支不做任何潜行检查：空手右键（蹲不蹲都一样）都会走到收进物品；
 * - 收进物品和 discard 就写在它自己的处理器里，不是靠取消事件后的原版逻辑——
 * 所以等它跑完再取消多少次都晚了，实体已经被丢弃；
 * - 它从不检查 {@code isCanceled()}（全类 0 处）——就算我们把事件取消掉，它的代码照样执行。
 *
 * 它注册在默认（NORMAL）优先级，我们旧的 LOWEST 处理器排在它后面才跑，
 * 那时 {@code verity.isAlive()} 已经是 false，只能静默失败——这正是「蹲下右键 Verity 还是被收走」的原因。
 *
 * 唯一可靠的拦截点是客户端：以 {@link EventPriority#HIGHEST} 抢在原版之前把事件取消。
 * 客户端事件被取消后，这条右键根本不会作为交互包发给服务端，原版服务端那一段收进物品的逻辑
 * 也就一次都不会执行。随后我们自己发骑乘请求包，由服务端权威处理。
 *
 * 只有潜行 + 空手右键才请求骑乘；普通右键不取消，完全交给原版（把 Verity 收进物品），
 * 不装扩展时的操作手感一致。服务端会对手势与开关再校验一遍，客户端判断只是预筛。
 */
public final class VerityRideInteraction {

    private VerityRideInteraction() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(event.getTarget() instanceof VerityEntity verity)) return;
        Player player = event.getEntity();

        if (event.getLevel().isClientSide()) {
            // 客户端预筛：手势成立才抢先把这条右键拦下来，改走自己的骑乘请求包。
            // 送礼已改为「潜行 + 丢弃物品」，不在这里抢右键，避免和原版物品交接冲突。
            if (!Boolean.TRUE.equals(ModConfig.SERVER_VERITY_BALL_RIDE.get())) return;
            if (!player.getMainHandItem().isEmpty() || !player.isShiftKeyDown()) return;
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.CONSUME);
            HeadPerchNetwork.sendRideRequest(verity.getId());
            return;
        }

        // 服务端兜底：正常路径下客户端已经拦掉这条右键、改走网络包，这里到不了。
        // 留着是为了兜住「客户端没拦住」的意外（其它模组放行、客户端旧版等）——能救一次是一次。
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        if (!RideManager.requestMount(verity, serverPlayer)) return;
        // 接管：把取消结果固定成「已消耗」，免得原版再把 Verity 收进物品
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.CONSUME);
    }
}
