package com.hanchen.veritycustomapi.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import varmite.verity.entity.verity.VerityEntity;

/**
 * 站头：把 Verity 自己的整个 {@code aiStep} 跳过；骑乘的操控交给
 * {@link LivingEntityRiddenControlMixin}（照抄原版马的三段式）。
 *
 * <p>🔴 <b>这里不再接管 {@code travel}</b>。曾经在这里自己算位移（速度 ×1.8、手写摩擦与重力、
 * 每 tick 硬赋旋转），表现是「骑上去能动，但一卡一卡」。原因是每 tick 前进近一整格、
 * 扫掠包围盒跨格导致频繁卡边，且旋转零插值。现在物理完全交回原版：
 * 原版怎么让马跑，就怎么让 Verity 跑，见 {@link LivingEntityRiddenControlMixin}。
 *
 * <p>站头这一处必须留在 {@code aiStep} HEAD 而不是 {@code travel}，它同时解决两件事：
 * <ul>
 *   <li>重力与移动：{@code aiStep -> travel} 会带着 Verity 自己的物理把 Verity 从头上的定位带走；</li>
 *   <li>实体互推：{@code pushEntities()} 就是在 {@code LivingEntity.aiStep()} 里调用的，
 *       Verity 贴着玩家时每帧都会把玩家推着走（实测「Verity 会推着我走」）。</li>
 * </ul>
 * 位置改由 {@code EntityPerchRideMixin} 每帧统一摆好。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityPerchRideMixin {

    @Inject(method = "aiStep", at = @At("HEAD"), cancellable = true)
    private void verityExt$skipAiWhilePerched(CallbackInfo ci) {
        LivingEntity selfLiving = (LivingEntity) (Object) this;
        if (!(selfLiving instanceof VerityEntity self)) return;
        if (com.hanchen.veritycustomapi.ride.HeadPerchState.isPerched(self.getUUID())) {
            self.setDeltaMovement(Vec3.ZERO);
            ci.cancel();
            return;
        }
        // 骑乘时把骑手的跳跃键同步到 Verity 自己身上。
        // 原版 LivingEntity.aiStep() 的 jump 分支判的是 this.jumping（含 noJumpDelay 与水中起跳），
        // 不在这里同步的话，Verity 永远不会跳——它的 jumping 一直是自己的 AI 值。
        if (self.getFirstPassenger() instanceof Player rider) {
            ((LivingEntityRideAccessor) selfLiving).verityExt$setJumping(
                    ((LivingEntityRideAccessor) rider).verityExt$jumping());
        }
    }
}
