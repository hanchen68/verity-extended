package com.hanchen.veritycustomapi.mixin;

import com.hanchen.veritycustomapi.ride.VerityStepStore;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import varmite.verity.entity.verity.VerityEntity;

/**
 * 骑乘操控：**照抄原版 {@code AbstractHorse} 的三段式**，不再自己写物理。
 *
 * <p>原版 {@code LivingEntity.aiStep()} 的骑乘链路是：
 * <pre>
 * travelRidden(player, input)
 *    ├─ getRiddenInput(player, input)      ← 骑手给的方向
 *    ├─ tickRidden(player, input)          ← 骑乘专属每 tick（旋转/特效）
 *    └─ if (isControlledByLocalInstance())
 *          setSpeed(getRiddenSpeed(player));
 *          travel(input);                  ← 原版完整物理（摩擦/重力/水/攀爬）
 * </pre>
 *
 * <p>🔴 <b>为什么不能自己写 travel</b>：我们原来在 {@code travel} HEAD 直接 cancel 掉、
 * 手算位移，和原版 {@code AbstractHorse} 有三处偏离：
 * <ul>
 *   <li>速度用了 {@code 属性 × 1.8}，稳态 0.99 格/tick（≈20 m/s），
 *       而原版马是 0.73 格/tick（≈14.6 m/s）；</li>
 *   <li>漏了 {@code getFrictionInfluencedSpeed}（原版按方块摩擦补偿，普通方块是 1.0 倍）；</li>
 *   <li>每 tick 把 {@code yRot/xRot} 硬赋成骑手的值，还把 {@code yRotO/xRotO} 一起对齐
 *       → 渲染时零插值。</li>
 * </ul>
 *
 * <p>⚠️ 不过「移动一卡一卡」的**真正根因不在物理**，而在乘客定位：当时
 * {@code EntityPerchRideMixin} 接管了 {@code positionRider} 并调 {@code rider.moveTo(...)}，
 * 而 {@code Entity.moveTo} 会走 {@code setOldPosAndRot()}，把玩家的 {@code xo/yo/zo}
 * 每 tick 对齐到当前位置 → 位置与视角的渲染插值全部失效，镜头只能按 20tps 跳。
 * 位移数值当时是完全正常的。现已改为注入 {@code getPassengersRidingOffset()}。
 *
 * <p>现在这里只提供三个「数据源」，物理完全交给原版：原版怎么让马跑，就怎么让 Verity 跑。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityRiddenControlMixin {

    /**
     * 骑乘速度倍率。1.0 = 原版马的手感（Verity 的 MOVEMENT_SPEED 属性是 0.25，
     * 经原版 {@code getFrictionInfluencedSpeed} 后稳态约 0.73 格/tick）。
     * 之前用 1.8，等于比最快的马还快 36%，是抖动的主要来源。
     */
    @Unique
    private static final float VERITY_EXT_RIDE_SPEED_FACTOR = 1.0F;

    @Unique
    private static final float VERITY_EXT_RIDE_SPRINT_FACTOR = 1.3F;

    /** 侧移照抄原版马：横向输入只给一半，避免贴着墙横向「蹭」得飞快。 */
    @Unique
    private static final float VERITY_EXT_RIDE_STRAFE_FACTOR = 0.5F;

    /** 上台阶高度：默认 0.6 上不去整格，改成 1.0 让球能滚上台阶。 */
    @Unique
    private static final float VERITY_EXT_RIDE_STEP_HEIGHT = 1.0F;

    @Unique
    private static boolean verityExt$isRiddenBy(LivingEntity self, Player player) {
        return self instanceof VerityEntity verity && verity.getControllingPassenger() == player;
    }

    /** 骑手给的方向：横向减半、纵向全给，与原版马一致。 */
    @Inject(method = "getRiddenInput", at = @At("HEAD"), cancellable = true)
    private void verityExt$riderInput(Player player, Vec3 fallback, CallbackInfoReturnable<Vec3> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!verityExt$isRiddenBy(self, player)) {
            return;
        }
        LivingEntityRideAccessor input = (LivingEntityRideAccessor) player;
        cir.setReturnValue(new Vec3(
                input.verityExt$xxa() * VERITY_EXT_RIDE_STRAFE_FACTOR,
                0.0D,
                input.verityExt$zza()));
    }

    /** 骑乘速度：默认实现返回 {@code this.getSpeed()}（此时是 0，等于不动），必须自己给。 */
    @Inject(method = "getRiddenSpeed", at = @At("HEAD"), cancellable = true)
    private void verityExt$riderSpeed(Player player, CallbackInfoReturnable<Float> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!verityExt$isRiddenBy(self, player)) {
            return;
        }
        AttributeInstance speed = self.getAttribute(Attributes.MOVEMENT_SPEED);
        float base = (float) (speed != null ? speed.getValue() : 0.25D);
        cir.setReturnValue(base * VERITY_EXT_RIDE_SPEED_FACTOR
                * (player.isSprinting() ? VERITY_EXT_RIDE_SPRINT_FACTOR : 1.0F));
    }

    /**
     * 骑乘每 tick：旋转平滑 + 上台阶。
     *
     * <p>旋转严格照抄 {@code AbstractHorse.tickRidden}：每 tick 只走差值的一半并 clamp 到 ±85°，
     * 再把 {@code yRotO/xRotO} 对齐。这样 20tps 的离散转动会被摊成视觉上的连续转动；
     * 直接赋成骑手的值就会「一顿一顿」。
     */
    @Inject(method = "tickRidden", at = @At("TAIL"))
    private void verityExt$rideTick(Player player, Vec3 input, CallbackInfo ci) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (!verityExt$isRiddenBy(self, player)) {
            return;
        }

        float deltaYaw = Mth.wrapDegrees(player.getYRot() - self.getYRot());
        self.setYRot(self.getYRot() + Mth.clamp(deltaYaw, -85.0F, 85.0F) * 0.5F);
        self.yRotO = self.getYRot();

        float deltaPitch = Mth.wrapDegrees(player.getXRot() * 0.5F - self.getXRot());
        self.setXRot(self.getXRot() + Mth.clamp(deltaPitch, -85.0F, 85.0F) * 0.5F);
        self.xRotO = self.getXRot();

        EntityRideAccessor move = (EntityRideAccessor) self;
        VerityStepStore.storeIfAbsent(self.getId(), move.verityExt$getMaxUpStep());
        move.verityExt$setMaxUpStep(VERITY_EXT_RIDE_STEP_HEIGHT);
    }
}
