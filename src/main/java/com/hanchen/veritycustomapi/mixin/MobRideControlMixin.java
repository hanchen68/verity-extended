package com.hanchen.veritycustomapi.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import varmite.verity.entity.verity.VerityEntity;

/**
 * 让玩家真正成为 Verity 的「控制乘客」。
 *
 * <p>🔴 <b>为什么必须注入 {@link Mob} 而不是 {@code Entity}</b>：
 * {@link Mob} 自己重写了 {@code getControllingPassenger()}，而且**从不调用 super**：
 *
 * <pre>
 * public LivingEntity getControllingPassenger() {
 *    if (!this.isNoAi()) {
 *       Entity entity = this.getFirstPassenger();
 *       if (entity instanceof Mob) return (Mob)entity;   // 只认 Mob 乘客
 *    }
 *    return null;                                        // 玩家乘客 → null
 * }
 * </pre>
 *
 * <p>Verity 是 {@code PathfinderMob}，所以写在 {@code Entity} 上的注入对它是死代码。
 * 后果是整条骑乘操控链从第一个判断就断掉：
 * <ul>
 *   <li>{@code LivingEntity.travelRidden()} 的 {@code instanceof Player} 判定不成立，
 *       直接走非骑乘分支，玩家输入根本没被读；</li>
 *   <li>{@code LocalPlayer.tick()} 里发 {@code ServerboundMoveVehiclePacket} 的条件是
 *       {@code entity.isControlledByLocalInstance()}，而它内部正是
 *       {@code getControllingPassenger() instanceof Player ? player.isLocalPlayer() : isEffectiveAi()}，
 *       返回 null 后在客户端算出来是 false —— <b>包压根不会发</b>；</li>
 *   <li>服务端 {@code handleMoveVehicle()} 的 {@code entity.getControllingPassenger() == this.player}
 *       同样不成立，即使发了也不会采纳。</li>
 * </ul>
 *
 * <p>表现就是「骑上去了，但怎么按都不动」。{@code EntityPerchRideMixin} 里那份同名注入保留着，
 * 用来覆盖非 Mob 的载具，两者互不冲突。
 */
@Mixin(Mob.class)
public abstract class MobRideControlMixin {

    @Inject(method = "getControllingPassenger", at = @At("HEAD"), cancellable = true)
    private void verityExt$playerInControl(CallbackInfoReturnable<LivingEntity> cir) {
        Mob self = (Mob) (Object) this;
        if (self instanceof VerityEntity verity
                && verity.getFirstPassenger() instanceof Player player) {
            cir.setReturnValue(player);
        }
    }
}
