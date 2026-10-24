package com.hanchen.veritycustomapi.client;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.network.VerityHoldPayload;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import varmite.verity.entity.verity.VerityEntity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端：施法时在 Verity 身边画出 Verity"举着"的星之遥控器。
 *
 * Verity 本体是一张会看人的 billboard 贴图（SphereEntityRenderer），没有骨骼和手，
 * 所以这里用相机朝向来算锚点：贴图右侧偏上、稍微靠前，抬手阶段从下往上抬起。
 * 纯视觉，不参与任何判定。
 */
@Mod.EventBusSubscriber(modid = OllamaProxyMod.MOD_ID, value = Dist.CLIENT)
public final class StellarRemoteHandClient {
    /** 抬起用几 tick，放下也用几 tick。 */
    private static final float RAISE_TICKS = 4.0F;
    private static final float LOWER_TICKS = 6.0F;

    private record Hold(ItemStack stack, long startTick, long endTick) {
    }

    private static final Map<Integer, Hold> HOLDS = new ConcurrentHashMap<>();

    private StellarRemoteHandClient() {
    }

    /** 收到服务端的 S2C 包：记下这只 Verity 要举什么、举到什么时候。 */
    public static void accept(VerityHoldPayload payload) {
        if (payload == null || payload.stack().isEmpty()) return;
        Level level = Minecraft.getInstance().level;
        if (level == null) return;
        long now = level.getGameTime();
        HOLDS.put(payload.entityId(),
                new Hold(payload.stack(), now, now + Math.max(1, payload.durationTicks())));
    }

    @SubscribeEvent
    public static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        if (HOLDS.isEmpty()) return;
        Minecraft minecraft = Minecraft.getInstance();
        Level level = minecraft.level;
        if (level == null) return;

        long now = level.getGameTime();
        float partialTick = event.getPartialTick();
        HOLDS.entrySet().removeIf(entry -> entry.getValue().endTick() < now);
        if (HOLDS.isEmpty()) return;

        Camera camera = event.getCamera();
        Vec3 cameraPos = camera.getPosition();
        Vec3 cameraRight = new Vec3(camera.getLeftVector()).scale(-1.0D);
        Vec3 cameraUp = new Vec3(camera.getUpVector());
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        ItemRenderer itemRenderer = minecraft.getItemRenderer();

        for (Map.Entry<Integer, Hold> entry : HOLDS.entrySet()) {
            Entity entity = level.getEntity(entry.getKey());
            if (!(entity instanceof VerityEntity verity) || !verity.isAlive() || verity.isInvisible()) continue;
            Hold hold = entry.getValue();
            float elapsed = (float) (now + partialTick - hold.startTick());
            float remain = (float) (hold.endTick() - (now + partialTick));
            float lift = Math.min(Mth.clamp(elapsed / RAISE_TICKS, 0.0F, 1.0F),
                    Mth.clamp(remain / LOWER_TICKS, 0.0F, 1.0F));

            double x = Mth.lerp(partialTick, verity.xOld, verity.getX());
            double y = Mth.lerp(partialTick, verity.yOld, verity.getY()) + verity.getBbHeight() * 0.5D;
            double z = Mth.lerp(partialTick, verity.zOld, verity.getZ());
            // billboard 没有手：球的视觉半径比碰撞箱大得多，锚点放在球体右外侧，
            // 休息时贴在球腰，抬手时举到球顶侧并往中间收
            Vec3 anchor = new Vec3(x, y, z)
                    .add(cameraRight.scale(0.62D - 0.12D * lift))
                    .add(cameraUp.scale(-0.05D + 0.42D * lift))
                    .subtract(cameraPos);

            poseStack.pushPose();
            poseStack.translate(anchor.x, anchor.y, anchor.z);
            poseStack.mulPose(camera.rotation());
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
            poseStack.mulPose(Axis.XP.rotationDegrees(-25.0F - 45.0F * lift));
            float scale = 0.95F + 0.15F * lift;
            poseStack.scale(scale, scale, scale);
            int light = LevelRenderer.getLightColor(level, BlockPos.containing(x, y, z));
            itemRenderer.renderStatic(hold.stack(), ItemDisplayContext.FIXED, light,
                    OverlayTexture.NO_OVERLAY, poseStack, buffers, level, entity.getId());
            poseStack.popPose();
        }
        buffers.endBatch();
    }
}