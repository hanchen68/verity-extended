package com.hanchen.veritycustomapi.mixin;

import com.hanchen.veritycustomapi.compat.VerityPatPatCompat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.lopymine.patpat.client.render.PatPatClientRenderer", remap = false)
public abstract class PatPatClientRendererMixin {
    @Inject(method = "registerClientPacket", at = @At("TAIL"), remap = false, require = 0)
    private static void verityExtended$onLocalPat(@Coerce Object packet, CallbackInfo ci) {
        VerityPatPatCompat.onClientPat(packet);
    }
}
