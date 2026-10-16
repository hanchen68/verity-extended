package com.hanchen.veritycustomapi.mixin;

import com.hanchen.veritycustomapi.ride.RideManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import varmite.verity.entity.verity.VerityEntity;

/** 潜行右键球形态 Verity 请求骑乘；意愿判定与反馈走 RideManager。 */
@Mixin(Mob.class)
public abstract class MobVerityInteractMixin {

    /** 站头/被骑乘时挂起 Verity 的服务端 AI（customServerAiStep 声明于 Mob）。 */
    @Inject(method = "customServerAiStep", at = @At("HEAD"), cancellable = true)
    private void verityExt$suspendAiWhilePerchingOrRiding(CallbackInfo ci) {
        Mob selfMob = (Mob) (Object) this;
        if (!(selfMob instanceof VerityEntity verity)) return;
        if (verity.getVehicle() instanceof Player || verity.getFirstPassenger() instanceof Player) {
            ci.cancel();
        }
    }

    /**
     * 潜行右键球形态 Verity 请求骑乘。
     *
     * 原版会把空手右键在 {@code PlayerInteractEvent.EntityInteract} 阶段就取消掉，
     * 那条路走不到这里；真正生效的入口是 {@code ride/VerityRideInteraction}。
     * 这里保留同一个入口，是为了兼容「没有取消该事件」的构建，两条路共用一份判定，
     * 上骑后 {@code getFirstPassenger()} 非空，重复进入也不会重复触发。
     */
    @Inject(method = "mobInteract", at = @At("HEAD"), cancellable = true)
    private void verityExt$rideRequest(Player player, InteractionHand hand,
                                       CallbackInfoReturnable<InteractionResult> cir) {
        Mob selfMob = (Mob) (Object) this;
        if (!(selfMob instanceof VerityEntity self)) return;
        if (hand != InteractionHand.MAIN_HAND || !(self.level() instanceof ServerLevel)
                || !(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        if (RideManager.requestMount(self, serverPlayer)) {
            cir.setReturnValue(InteractionResult.CONSUME);
        }
    }
}
