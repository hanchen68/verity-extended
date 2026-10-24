package com.hanchen.veritycustomapi.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import varmite.verity.entity.verity.VerityEntity;

/** 站头/骑乘期间挂起 Verity 的服务端 AI。 */
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

}
