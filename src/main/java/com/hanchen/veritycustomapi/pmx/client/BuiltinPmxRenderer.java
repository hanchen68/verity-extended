package com.hanchen.veritycustomapi.pmx.client;

import com.hanchen.veritycustomapi.pmx.BuiltinPmxModel;
import com.hanchen.veritycustomapi.pmx.BuiltinPmxModelManager;
import com.hanchen.veritycustomapi.pmx.MmdModelData;
import com.hanchen.veritycustomapi.pmx.client.MmdTextureCache;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix3f;
import varmite.verity.entity.demon.VerityDemonEntity;
import org.joml.Matrix4f;

/** 可选的 PMX 渲染路径。除非显式开启，否则它不会动现有的渲染器。 */
public final class BuiltinPmxRenderer {
    private BuiltinPmxRenderer() {
    }

    public static boolean render(Entity entity, float entityYaw, PoseStack poseStack,
                                 MultiBufferSource buffers, int packedLight) {
        // 当前这个轻量 PMX 路径是故意关掉的，
        // 要等完整的 MmdSkin Java + Rust/JNI 运行时接进来才开。
        // 姿态和物理没实现全之前，绝不能拿它去替换原渲染器。
        return false;
        /* BuiltinPmxModel loaded = BuiltinPmxModelManager.get().current();
 if (loaded == null) return false;
 MmdModelData model = loaded.data();
 if (model.indices().length == 0 || model.materials().isEmpty()) return false;
 float motionTime = (entity.tickCount + 0.0F) / 20.0F;
 if (loaded.motion() != null && loaded.motion().getDurationSeconds() > 0.0F) {
 motionTime %= loaded.motion().getDurationSeconds();
 }
 BuiltinPmxPoseCache.SkinnedMesh mesh = BuiltinPmxPoseCache.skin(model, loaded.motion(), motionTime);

 float height = Math.max(0.01F, model.maxY() - model.minY());
 // The ordinary Verity is a small 0.5-block sphere; Demon Mode is a 3-block entity.
 // Keep the PMX model's feet on the entity base while matching the original hitbox scale.
 float targetHeight = entity instanceof VerityDemonEntity ? 3.0F : 1.8F;
 float scale = targetHeight / height;
 poseStack.pushPose();
 poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180.0F - entityYaw));
 poseStack.scale(scale, scale, -scale);
 poseStack.translate(0.0D, -model.minY(), 0.0D);
 Matrix4f pose = poseStack.last().pose();
 Matrix3f normal = poseStack.last().normal();

 for (MmdModelData.Material material : model.materials()) {
 ResourceLocation texture = MmdTextureCache.texture(loaded.directory(), loaded.directory(),
 material.texturePath());
 VertexConsumer consumer = buffers.getBuffer(MmdRenderTypes.translucentTriangles(texture));
 int end = Math.min(model.indices().length, material.firstIndex() + material.indexCount());
 for (int index = material.firstIndex(); index + 2 < end; index += 3) {
 vertex(consumer, pose, normal, model, mesh, model.indices()[index + 2], material, packedLight);
 vertex(consumer, pose, normal, model, mesh, model.indices()[index + 1], material, packedLight);
 vertex(consumer, pose, normal, model, mesh, model.indices()[index], material, packedLight);
 }
 }
 poseStack.popPose();
 return true; */
    }

    private static void vertex(VertexConsumer consumer, Matrix4f pose, Matrix3f normal,
                               MmdModelData model, BuiltinPmxPoseCache.SkinnedMesh mesh,
                               int vertex, MmdModelData.Material material, int light) {
        int p = vertex * 3;
        int uv = vertex * 2;
        float alpha = Mth.clamp(material.alpha(), 0.0F, 1.0F);
        consumer.vertex(pose, mesh.positions()[p], mesh.positions()[p + 1], mesh.positions()[p + 2])
                .color(material.red(), material.green(), material.blue(), alpha)
                .uv(model.uvs()[uv], model.uvs()[uv + 1])
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(normal, mesh.normals()[p], mesh.normals()[p + 1], mesh.normals()[p + 2])
                .endVertex();
    }
}
