package com.hanchen.veritycustomapi.mixin;

import com.hanchen.veritycustomapi.ride.RideGrace;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 拦住「刚骑上去就被潜行踢下来」。
 *
 * 原版 {@code Player.rideTick()} 每 tick 判一次 {@code wantsToStopRiding()}（实现就是
 * {@code isShiftKeyDown()}），成立就 {@code stopRiding()}。而我们的骑乘手势是「潜行 + 右键」，
 * 玩家骑上去时手还按着潜行 —— 于是立刻被判定为要下车。
 * 这里把判定接到 {@link RideGrace} 上：上骑后的那一小段与「潜行还没松开」期间不算下车请求。
 *
 * 注入点是这个受保护方法本身，而不是 {@code rideTick}：这样只影响「为什么想下车」这一个判断，
 * 原版其余的下车流程（真的按了潜行、坐船坐矿车等）完全不变。
 */
@Mixin(Player.class)
public abstract class PlayerRideGraceMixin {

    @Inject(method = "wantsToStopRiding", at = @At("HEAD"), cancellable = true)
    private void verityExt$blockSneakDismountRightAfterMount(CallbackInfoReturnable<Boolean> cir) {
        Player self = (Player) (Object) this;
        if (RideGrace.blocksSneakDismount(self)) {
            cir.setReturnValue(false);
        }
    }
}
