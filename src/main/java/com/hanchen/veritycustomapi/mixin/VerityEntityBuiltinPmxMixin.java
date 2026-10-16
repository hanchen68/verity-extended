package com.hanchen.veritycustomapi.mixin;

import com.hanchen.veritycustomapi.pmx.client.BuiltinPmxRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import varmite.verity.entity.utils.SphereEntityRenderer;
import varmite.verity.entity.verity.VerityEntity;

/** 普通 VerityEntity 的可选 PMX 渲染路径。不启用时仍走原来的球体渲染。 */
@Mixin(value = SphereEntityRenderer.class, remap = false)
public abstract class VerityEntityBuiltinPmxMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true, remap = false)
    private void verityExtended$renderBuiltinPmx(VerityEntity entity, float entityYaw,
                                                 float partialTick, PoseStack poseStack,
                                                 MultiBufferSource buffers, int packedLight,
                                                 CallbackInfo ci) {
        if (BuiltinPmxRenderer.render(entity, entityYaw, poseStack, buffers, packedLight)) {
            ci.cancel();
        }
    }
}
