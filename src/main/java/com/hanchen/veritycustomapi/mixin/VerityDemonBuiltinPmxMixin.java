package com.hanchen.veritycustomapi.mixin;

import com.hanchen.veritycustomapi.pmx.client.BuiltinPmxRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import varmite.verity.entity.demon.VerityDemonEntity;
import varmite.verity.entity.demon.rendering.VerityDemonRenderer;

/** 可选的 PMX 渲染路径。默认关闭，不启用时原渲染器照旧。 */
@Mixin(value = VerityDemonRenderer.class, remap = false)
public abstract class VerityDemonBuiltinPmxMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true, remap = false)
    private void verityExtended$renderBuiltinPmx(VerityDemonEntity entity, float entityYaw,
                                                 float partialTick, PoseStack poseStack,
                                                 MultiBufferSource buffers, int packedLight,
                                                 CallbackInfo ci) {
        if (BuiltinPmxRenderer.render(entity, entityYaw, poseStack, buffers, packedLight)) {
            ci.cancel();
        }
    }
}
