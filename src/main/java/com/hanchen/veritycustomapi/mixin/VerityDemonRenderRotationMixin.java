package com.hanchen.veritycustomapi.mixin;

import com.hanchen.veritycustomapi.mmd.MmdPlaybackManager;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import varmite.verity.entity.demon.VerityDemonEntity;
import varmite.verity.entity.demon.rendering.VerityDemonRenderer;

@Mixin(value = VerityDemonRenderer.class, remap = false)
public abstract class VerityDemonRenderRotationMixin {
    @Inject(method = "render(Lvarmite/verity/entity/demon/VerityDemonEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD"), remap = false)
    private void verityExtended$lockMmdRotation(VerityDemonEntity entity, float entityYaw, float partialTick,
                                                PoseStack poseStack, MultiBufferSource bufferSource,
                                                int packedLight, CallbackInfo ci) {
        MmdPlaybackManager.PlaybackState state = MmdPlaybackManager.get().getPlaybackState(entity.getId());
        if (state != null) {
            MmdPlaybackManager.lockEntityRotation(entity, state.lockedYaw);
        }
    }
}
