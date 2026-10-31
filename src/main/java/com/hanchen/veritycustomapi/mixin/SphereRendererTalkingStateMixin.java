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
            ),
            // 🔴 必须 require = 0：6.0.0 整活 demo（VBM 预览版）把绘制调用换成了 21 参重载，
            // 找不到这个 18 参调用点时 Redirect 会抛 InjectionError，而 renderer 是在
            // 注册渲染器时就被变换的 —— 直接把游戏崩在启动阶段（2026-10-31 实测）。
            // 下面 verityExtended$applyAudioLevelVbm 负责那一版。
            require = 0
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
            float growthScale = VerityGrowthState.getClientScale(entity);
            groundedYOffset = VerityGrowthState.getGroundedYOffset(entity);
            renderStretchY *= growthScale;
            renderStretchXZ *= growthScale;

            // 统一的「活着」动画：呼吸 / 走路 / 下落 / 落地 / 转向倾斜。
            // 弹簧驱动（欠阻尼），所以落地会自己回弹、停下会晃两下，不需要为每个动作写曲线。
            //
            // 🔴 整段必须 try/catch：渲染线程里抛异常 = 这个实体这一帧画不出来，
            // 玩家看到的就是「球体隐身」。任何异常都退回原版参数，宁可不动也不能消失。
            try {
                com.hanchen.veritycustomapi.client.BallAnimator.Pose alive =
                        com.hanchen.veritycustomapi.client.BallAnimator.sample(
                                entity,
                                partialTick == null ? 0.0F : partialTick,
                                viewerX, viewerY, viewerZ);
                if (alive != null
                        && Float.isFinite(alive.stretchY) && alive.stretchY > 0.1F
                        && Float.isFinite(alive.stretchXZ) && alive.stretchXZ > 0.1F
                        && Float.isFinite(alive.rollAdd)
                        && Double.isFinite(alive.yOffset)) {
                    renderStretchY *= alive.stretchY;
                    renderStretchXZ *= alive.stretchXZ;
                    renderRoll += alive.rollAdd;
                    renderY += alive.yOffset;
                }
            } catch (Throwable ignored) {
                // 动画出问题不能影响渲染本身
            }
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

    /**
     * 6.0.0 整活 demo（VBM 预览版）的绘制调用：多了一个 float（压扁相关）和结尾的 F,I,F。
     *
     * <p>这里把前 12 个参数原样转给 18 参重载，多出来的三个（压扁/贴脸用的）不参与——
     * 扩展的语音弹动、体型缩放、Q 弹、摸头缩放因此在这一版上仍然生效。
     * 基础版没有这个调用点，{@code require = 0} 会让它安静跳过。
     */
    @Redirect(
            method = "render(Lvarmite/verity/entity/verity/VerityEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lvarmite/verity/entity/utils/SphereRenderHelper;renderEntityBillboard(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/resources/ResourceLocation;IDDDDDDFFFIIIIFFFIF)V"
            ),
            require = 0
    )
    private void verityExtended$applyAudioLevelVbm(
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
            float flatExtra,
            int red,
            int green,
            int blue,
            int alpha,
            float rollAngle,
            float bodyYaw,
            float extraFloat,
            int extraInt,
            float extraFloat2
    ) {
        verityExtended$applyAudioLevel(poseStack, bufferSource, texture, packedLight,
                entityX, entityY, entityZ, viewerX, viewerY, viewerZ,
                stretchY, stretchXZ, red, green, blue, alpha, rollAngle, bodyYaw);
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
