package com.hanchen.veritycustomapi.mixin;

import com.hanchen.veritycustomapi.compat.VerityEntityTalkAnimator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import varmite.verity.entity.verity.VerityEntity;

@Mixin(value = VerityEntity.class, remap = false)
public class VerityTalkingStateMixin {
    @Redirect(
            method = "getTextureRL",
            at = @At(value = "FIELD", target = "Lvarmite/verity/entity/verity/VerityEntity;clientIsTalking:Z")
    )
    private boolean verityExtended$isVisuallyTalking(VerityEntity entity) {
        return entity.clientIsTalking || VerityEntityTalkAnimator.isTalking(entity.getId());
    }
}
