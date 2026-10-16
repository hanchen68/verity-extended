package com.hanchen.veritycustomapi.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import varmite.verity.entity.verity.VerityEntity;

/**
 * 被玩家骑乘时接管 Verity 的移动（travel 声明于 LivingEntity），注入体内用 instanceof 守卫。
 * 物理与原版 travel 对齐：地面摩擦、重力 0.08、空气阻力。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityPerchRideMixin {

    /**
     * 站头期间把 Verity 自己的整个 {@code aiStep} 跳过。
     *
     * 这一处同时解决两个问题，所以必须掐在这里而不是 {@code travel}：
     *
     * - 重力与移动：{@code aiStep -> travel} 会带着 Verity 自己的物理把 Verity 从头上的定位带走；
     * - 实体互推：{@code pushEntities()} 就是在 {@code LivingEntity.aiStep()} 里调用的，
     * Verity 贴着玩家时每帧都会把玩家推着走（实测「Verity 会推着我走」）。
     *
     * 位置与速度改由 {@code EntityPerchRideMixin} 每帧统一摆好。
     */
    @Inject(method = "aiStep", at = @At("HEAD"), cancellable = true)
    private void verityExt$skipAiWhilePerched(CallbackInfo ci) {
        LivingEntity selfLiving = (LivingEntity) (Object) this;
        if (!(selfLiving instanceof VerityEntity self)) return;
        if (!com.hanchen.veritycustomapi.ride.HeadPerchState.isPerched(self.getUUID())) return;
        self.setDeltaMovement(Vec3.ZERO);
        ci.cancel();
    }

    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void verityExt$controlledTravel(Vec3 travelVector, CallbackInfo ci) {
        LivingEntity selfLiving = (LivingEntity) (Object) this;
        if (!(selfLiving instanceof VerityEntity self)) return;

        // 站头：Verity 挂在玩家身上（不占乘客位），Verity 自己的移动必须完全停掉。
        // 乘客照样会被 tick，而 tick 会走 aiStep -> travel，重力与移动逻辑
        // 会和「每帧把 Verity 摆到头上的定位」互相打架，表现就是刚站上去就走开/掉下来。
        if (com.hanchen.veritycustomapi.ride.HeadPerchState.isPerched(self.getUUID())) {
            self.setDeltaMovement(Vec3.ZERO);
            ci.cancel();
            return;
        }

        Entity controller = self.getControllingPassenger();
        if (!(controller instanceof Player player)) {
            return;
        }
        LivingEntityRideAccessor playerAccess = (LivingEntityRideAccessor) player;
        LivingEntityRideAccessor selfBody = (LivingEntityRideAccessor) self;
        EntityRideAccessor selfMove = (EntityRideAccessor) self;
        float strafe = playerAccess.verityExt$xxa();
        float forward = playerAccess.verityExt$zza();
        boolean jumping = playerAccess.verityExt$jumping();

        self.setYRot(player.getYRot());
        self.yRotO = self.getYRot();
        self.setXRot(player.getXRot() * 0.5F);
        self.xRotO = self.getXRot();
        com.hanchen.veritycustomapi.ride.VerityStepStore.storeIfAbsent(
                self.getId(), selfMove.verityExt$getMaxUpStep());
        selfMove.verityExt$setMaxUpStep(1.0F);

        if (self.isControlledByLocalInstance()) {
            AttributeInstance speedAttr = self.getAttribute(Attributes.MOVEMENT_SPEED);
            float speed = (float) (speedAttr != null ? speedAttr.getValue() : 0.25F) * 1.8F;
            if (player.isSprinting()) {
                speed *= 1.4F;
            }
            self.setSpeed(speed);
            selfMove.verityExt$moveRelative(speed, new Vec3(strafe, 0.0F, forward));
            self.move(MoverType.SELF, self.getDeltaMovement());

            Vec3 motion = self.getDeltaMovement();
            double y = motion.y;
            if (jumping && self.onGround()) {
                selfBody.verityExt$jumpFromGround();
                y = self.getDeltaMovement().y;
            }
            if (self.isInWater()) {
                motion = motion.scale(selfBody.verityExt$waterSlowDown());
                self.setDeltaMovement(motion);
            } else if (self.isInLava()) {
                if (!self.isNoGravity()) y -= 0.02D;
                self.setDeltaMovement(motion.x * 0.5D, y, motion.z * 0.5D);
            } else {
                BlockPos below = self.blockPosition().below();
                float friction = self.onGround()
                        ? self.level().getBlockState(below).getFriction(self.level(), below, self) * 0.91F
                        : 0.91F;
                if (!self.isNoGravity()) y -= 0.08D;
                self.setDeltaMovement(motion.x * friction, y * 0.98D, motion.z * friction);
            }
        }
        ci.cancel();
    }
}
