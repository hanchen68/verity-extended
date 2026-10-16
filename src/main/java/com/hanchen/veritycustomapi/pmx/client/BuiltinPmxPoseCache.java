package com.hanchen.veritycustomapi.pmx.client;

import com.hanchen.veritycustomapi.mmd.VmdClip;
import com.hanchen.veritycustomapi.pmx.MmdModelData;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** 给可选内置渲染器用的 CPU 端 PMX 蒙皮。 */
public final class BuiltinPmxPoseCache {
    private BuiltinPmxPoseCache() {
    }

    public static SkinnedMesh skin(MmdModelData model, VmdClip motion, float time) {
        int bones = model.bones().size();
        Matrix4f[] global = new Matrix4f[bones];
        Matrix4f[] skin = new Matrix4f[bones];
        Matrix3f[] normal = new Matrix3f[bones];
        for (int i = 0; i < bones; i++) {
            MmdModelData.Bone bone = model.bones().get(i);
            float px = bone.parentIndex() >= 0 ? model.bones().get(bone.parentIndex()).x() : 0.0F;
            float py = bone.parentIndex() >= 0 ? model.bones().get(bone.parentIndex()).y() : 0.0F;
            float pz = bone.parentIndex() >= 0 ? model.bones().get(bone.parentIndex()).z() : 0.0F;
            VmdClip.BonePose pose = motion == null ? null : motion.sampleBone(bone.name(), time);
            Quaternionf rotation = pose == null ? new Quaternionf()
                    : new Quaternionf(pose.quaternion()[0], pose.quaternion()[1], pose.quaternion()[2], pose.quaternion()[3]).normalize();
            float tx = pose == null ? 0.0F : pose.position()[0];
            float ty = pose == null ? 0.0F : pose.position()[1];
            float tz = pose == null ? 0.0F : pose.position()[2];
            Matrix4f local = new Matrix4f().translation(bone.x() - px + tx, bone.y() - py + ty,
                    bone.z() - pz + tz).rotate(rotation);
            global[i] = bone.parentIndex() >= 0 ? new Matrix4f(global[bone.parentIndex()]).mul(local) : local;
            skin[i] = new Matrix4f(global[i]).translate(-bone.x(), -bone.y(), -bone.z());
            normal[i] = new Matrix3f(skin[i]);
        }
        float[] positions = new float[model.positions().length];
        float[] normals = new float[model.normals().length];
        Vector3f source = new Vector3f();
        Vector3f sourceNormal = new Vector3f();
        Vector3f transformed = new Vector3f();
        Vector3f transformedNormal = new Vector3f();
        for (int vertex = 0; vertex < model.vertexCount(); vertex++) {
            int p = vertex * 3;
            int b = vertex * 4;
            source.set(model.positions()[p], model.positions()[p + 1], model.positions()[p + 2]);
            sourceNormal.set(model.normals()[p], model.normals()[p + 1], model.normals()[p + 2]);
            float px = 0, py = 0, pz = 0, nx = 0, ny = 0, nz = 0, total = 0;
            for (int influence = 0; influence < 4; influence++) {
                int bone = model.boneIndices()[b + influence];
                float weight = model.boneWeights()[b + influence];
                if (bone < 0 || bone >= bones || weight <= 0.0001F) continue;
                transformed.set(source).mulPosition(skin[bone]);
                transformedNormal.set(sourceNormal).mul(normal[bone]).normalize();
                px += transformed.x * weight; py += transformed.y * weight; pz += transformed.z * weight;
                nx += transformedNormal.x * weight; ny += transformedNormal.y * weight; nz += transformedNormal.z * weight;
                total += weight;
            }
            if (total <= 0.0001F) {
                positions[p] = source.x; positions[p + 1] = source.y; positions[p + 2] = source.z;
                normals[p] = sourceNormal.x; normals[p + 1] = sourceNormal.y; normals[p + 2] = sourceNormal.z;
            } else {
                float inv = 1.0F / total;
                positions[p] = px * inv; positions[p + 1] = py * inv; positions[p + 2] = pz * inv;
                float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                float n = len > 0.0001F ? 1.0F / len : 1.0F;
                normals[p] = nx * n; normals[p + 1] = ny * n; normals[p + 2] = nz * n;
            }
        }
        return new SkinnedMesh(positions, normals);
    }

    public record SkinnedMesh(float[] positions, float[] normals) {
    }
}
