package com.hanchen.veritycustomapi.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.hanchen.veritycustomapi.compat.VerityEntityTalkAnimator;
import com.hanchen.veritycustomapi.compat.VerityGrowthState;
import com.hanchen.veritycustomapi.compat.VerityPatPatCompat;
import com.hanchen.veritycustomapi.compat.VeritySubtitleManager;
import com.hanchen.veritycustomapi.config.ModConfig;
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
        float renderStretchY = stretchY;
        float renderStretchXZ = stretchXZ;
        float renderRoll = rollAngle;
        double renderY = entityY;
        if (entity != null) {
            float level = VerityEntityTalkAnimator.getAudioLevel(entity.getId());
            if (level >= 0.0F) {
                float phase = Math.max(-1.0F, Math.min(1.0F, (stretchY - 1.0F) / 0.12F));
                float amplitude = 0.015F + 0.165F * level;
                renderStretchY = 1.0F + phase * amplitude;
                renderStretchXZ = 1.0F + (1.0F - renderStretchY) * 0.5F;
            }
            float growthScale = VerityGrowthState.getClientScale(entity);
            groundedYOffset = VerityGrowthState.getGroundedYOffset(entity);
            if (jellyEnabled() && (entity.clientIsTalking || VerityEntityTalkAnimator.isTalking(entity.getId()))) {
                float time = entity.tickCount + (partialTick == null ? 0.0F : partialTick);
                double amplitude = jellyAmplitude();
                double speed = jellySpeed();
                double wave;
                if (jellyChaos()) {
                    // 鬼畜模式：三个不成比例的频率叠在一起，节奏被打散，看着像抽搐而不是呼吸
                    speed *= 2.6D;
                    amplitude *= 1.6D;
                    wave = Math.sin(time * speed)
                            + 0.65D * Math.sin(time * speed * 1.9D + 0.7D)
                            + 0.45D * Math.sin(time * speed * 3.3D + 2.1D);
                    renderRoll += (float) (Math.sin(time * speed * 0.8D) * 0.22D * (amplitude / 0.45D));
                    renderY += Math.abs(Math.sin(time * speed * 1.3D)) * 0.09D * (amplitude / 0.45D);
                } else {
                    // 主弹 + 二次抖动，叠加后有果冻那种回弹的余韵
                    wave = Math.sin(time * speed) + 0.35D * Math.sin(time * speed * 1.7D + 1.3D);
                }
                float jellyY = (float) (1.0D + wave * amplitude);
                renderStretchY = jellyY;
                renderStretchXZ = (float) (1.0D + (1.0D - jellyY) * jellySquash());
            }
            renderStretchY *= growthScale;
            renderStretchXZ *= growthScale;
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
                renderY,
                entityZ,
                viewerX,
                viewerY,
                viewerZ,
                renderStretchY,
                renderStretchXZ,
                red,
                green,
                blue,
                alpha,
                renderRoll,
                bodyYaw
        );
        poseStack.popPose();
    }

    /** Q弹总开关；配置没加载出来时保持原版手感。 */
    private static boolean jellyEnabled() {
        try {
            return ModConfig.VERITY_JELLY_ENABLED.get();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static double jellyAmplitude() {
        try {
            return ModConfig.VERITY_JELLY_AMPLITUDE.get();
        } catch (Throwable ignored) {
            return 0.45D;
        }
    }

    private static double jellySpeed() {
        try {
            return ModConfig.VERITY_JELLY_SPEED.get();
        } catch (Throwable ignored) {
            return 0.75D;
        }
    }

    /** 鬼畜模式：在 Q 弹基础上再翻倍幅度和速度，并叠加甩头与弹跳。 */
    private static boolean jellyChaos() {
        try {
            return ModConfig.VERITY_JELLY_CHAOS.get();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static double jellySquash() {
        try {
            return ModConfig.VERITY_JELLY_SQUASH.get();
        } catch (Throwable ignored) {
            return 0.85D;
        }
    }
}
