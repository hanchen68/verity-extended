package com.hanchen.veritycustomapi.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.hanchen.veritycustomapi.compat.VerityEntityTalkAnimator;
import com.hanchen.veritycustomapi.compat.VerityGrowthState;
import com.hanchen.veritycustomapi.compat.VerityPatPatCompat;
import com.hanchen.veritycustomapi.compat.VeritySubtitleManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import varmite.verity.entity.utils.SphereEntityRenderer;
import varmite.verity.entity.utils.SphereRenderHelper;
import varmite.verity.entity.verity.VerityEntity;

@Mixin(value = SphereEntityRenderer.class, remap = false)
public class SphereRendererTalkingStateMixin {
    private static final ThreadLocal<VerityEntity> VERITY_EXTENDED_ENTITY = new ThreadLocal<>();
    private static final ThreadLocal<Float> VERITY_EXTENDED_PARTIAL_TICK = new ThreadLocal<>();

    @Inject(
            method = "render(Lvarmite/verity/entity/verity/VerityEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("TAIL")
    )
    private void verityExtended$renderSubtitle(
            VerityEntity entity,
            float entityYaw,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight,
            CallbackInfo ci
    ) {
        VERITY_EXTENDED_ENTITY.remove();
        VERITY_EXTENDED_PARTIAL_TICK.remove();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.player.distanceToSqr(entity) > 1024.0D) return;
        String subtitle = VeritySubtitleManager.get(entity.getId());
        if (subtitle == null || subtitle.isBlank()) return;

        Font font = minecraft.font;
        java.util.List<FormattedCharSequence> lines = font.split(Component.literal(subtitle), 180);
        if (lines.isEmpty()) return;

        poseStack.pushPose();
        poseStack.translate(0.0D, entity.getBbHeight() + 0.55D, 0.0D);
        poseStack.mulPose(minecraft.getEntityRenderDispatcher().cameraOrientation());
        poseStack.scale(-0.025F, -0.025F, 0.025F);
        Matrix4f matrix = poseStack.last().pose();
        float firstY = -(lines.size() * 10.0F);
        for (int i = 0; i < lines.size(); i++) {
            FormattedCharSequence line = lines.get(i);
            float x = -font.width(line) / 2.0F;
            font.drawInBatch(line, x, firstY + i * 10.0F, 0xFFFFFFFF, false,
                    matrix, bufferSource, Font.DisplayMode.NORMAL, 0x66000000, packedLight);
        }
        poseStack.popPose();
    }

    @Redirect(
            method = "render(Lvarmite/verity/entity/verity/VerityEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(value = "FIELD", target = "Lvarmite/verity/entity/verity/VerityEntity;clientIsTalking:Z")
    )
    private boolean verityExtended$isVisuallyTalking(VerityEntity entity) {
        VERITY_EXTENDED_ENTITY.set(entity);
        return entity.clientIsTalking || VerityEntityTalkAnimator.isTalking(entity.getId());
    }

    @Inject(
            method = "render(Lvarmite/verity/entity/verity/VerityEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At("HEAD")
    )
    private void verityExtended$capturePatPatFrame(
            VerityEntity entity,
            float entityYaw,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            int packedLight,
            CallbackInfo ci
    ) {
        VERITY_EXTENDED_ENTITY.set(entity);
        VERITY_EXTENDED_PARTIAL_TICK.set(partialTick);
    }

    @Redirect(
            method = "render(Lvarmite/verity/entity/verity/VerityEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lvarmite/verity/entity/utils/SphereRenderHelper;renderEntityBillboard(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/resources/ResourceLocation;IDDDDDDFFIIIIFF)V"
            )
    )
    private void verityExtended$applyAudioLevel(
            PoseStack poseStack,
            MultiBufferSource bufferSource,
            ResourceLocation texture,
            int packedLight,
            double entityX,
            double entityY,
            double entityZ,
            double viewerX,
            double viewerY,
            double viewerZ,
            float stretchY,
            float stretchXZ,
            int red,
            int green,
            int blue,
            int alpha,
            float rollAngle,
            float bodyYaw
    ) {
        VerityEntity entity = VERITY_EXTENDED_ENTITY.get();
        Float partialTick = VERITY_EXTENDED_PARTIAL_TICK.get();
        VERITY_EXTENDED_ENTITY.remove();
        VERITY_EXTENDED_PARTIAL_TICK.remove();
        double groundedYOffset = 0.0D;
        if (entity != null) {
            float level = VerityEntityTalkAnimator.getAudioLevel(entity.getId());
            if (level >= 0.0F) {
                float phase = Math.max(-1.0F, Math.min(1.0F, (stretchY - 1.0F) / 0.12F));
                float amplitude = 0.015F + 0.165F * level;
                stretchY = 1.0F + phase * amplitude;
                stretchXZ = 1.0F + (1.0F - stretchY) * 0.5F;
            }
            float growthScale = VerityGrowthState.getClientScale(entity);
            groundedYOffset = VerityGrowthState.getGroundedYOffset(entity);
            stretchY *= growthScale;
            stretchXZ *= growthScale;
        }
        poseStack.pushPose();
        poseStack.translate(0.0D, groundedYOffset, 0.0D);
        if (entity != null) {
            VerityPatPatCompat.applyScale(entity, poseStack, partialTick == null ? 0.0F : partialTick);
        }
        SphereRenderHelper.renderEntityBillboard(
                poseStack,
                bufferSource,
                texture,
                packedLight,
                entityX,
                entityY,
                entityZ,
                viewerX,
                viewerY,
                viewerZ,
                stretchY,
                stretchXZ,
                red,
                green,
                blue,
                alpha,
                rollAngle,
                bodyYaw
        );
        poseStack.popPose();
    }
}
