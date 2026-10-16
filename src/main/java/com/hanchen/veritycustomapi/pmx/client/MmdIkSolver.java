package com.hanchen.veritycustomapi.pmx.client;

import com.hanchen.veritycustomapi.pmx.MmdModelData;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * PMX IK 链用的循环坐标下降（CCD）反向运动学。MMD 拿 IK 驱动腿、脚，有时还有手臂：
 * 一串骨骼要把末端骨骼送到 IK 骨骼的位置。求解器从末端往根遍历声明的环节，逐个朝目标转，
 * 同时守住各环节的旋转限制和 IK 骨骼自身的 X 轴角度限制。
 */
final class MmdIkSolver {
    private static final int MAX_ITERATIONS = 16;
    private static final int MAX_LINKS = 8;
    private static final float TOLERANCE_SQUARED = 1.0E-6F;

    private MmdIkSolver() {
    }

    /**
     * 原地调整 {@code localRotations}，让每条 IK 链都够到目标，再把算出来的骨骼变换写进
     * {@code global} 和 {@code skin}。
     */
    static void solve(MmdModelData model, Quaternionf[] localRotations, Vector3f[] localTranslations,
                      Matrix4f[] global, Matrix4f[] skin, Matrix3f[] normal) {
        for (int ikIndex = 0; ikIndex < model.bones().size(); ikIndex++) {
            MmdModelData.Bone bone = model.bones().get(ikIndex);
            MmdModelData.Bone.Ik ik = bone.ik();
            if (ik == null) continue;
            int targetIndex = ik.targetBone();
            if (targetIndex < 0 || targetIndex >= model.bones().size()) continue;

            if (ik.angleLimit() > 0.0F) {
                clampX(localRotations[ikIndex], ik.angleLimit());
            }
            rebuild(model, localRotations, localTranslations, global, 0);

            Vector3f goal = bonePosition(model, global, ikIndex);
            int links = Math.min(ik.links().size(), MAX_LINKS);
            for (int iteration = 0; iteration < Math.min(ik.iterations(), MAX_ITERATIONS); iteration++) {
                Vector3f tip = bonePosition(model, global, targetIndex);
                if (tip.distanceSquared(goal) < TOLERANCE_SQUARED) break;
                for (int link = links - 1; link >= 0; link--) {
                    rotateLink(model, localRotations, global, ik.links().get(link), targetIndex, goal);
                    rebuild(model, localRotations, localTranslations, global, 0);
                }
            }
            rebuild(model, localRotations, localTranslations, global, 0);
        }
        for (int i = 0; i < skin.length; i++) {
            MmdModelData.Bone bone = model.bones().get(i);
            skin[i] = new Matrix4f(global[i]).translate(-bone.x(), -bone.y(), -bone.z());
            normal[i] = new Matrix3f(skin[i]);
        }
    }

    /** 转一个环节，让末端朝 {@code goal} 靠，再钳到它声明的限制内。 */
    private static void rotateLink(MmdModelData model, Quaternionf[] localRotations, Matrix4f[] global,
                                   MmdModelData.Bone.Link link, int targetIndex, Vector3f goal) {
        int linkIndex = link.boneIndex();
        if (linkIndex < 0 || linkIndex >= model.bones().size()) return;
        Vector3f linkPosition = bonePosition(model, global, linkIndex);
        Vector3f tip = bonePosition(model, global, targetIndex);
        Vector3f toTip = new Vector3f(tip).sub(linkPosition);
        Vector3f toGoal = new Vector3f(goal).sub(linkPosition);
        if (toTip.lengthSquared() < 1.0E-8F || toGoal.lengthSquared() < 1.0E-8F) return;
        toTip.normalize();
        toGoal.normalize();

        Quaternionf delta = new Quaternionf().rotationTo(toTip, toGoal);
        int parent = model.bones().get(linkIndex).parentIndex();
        Quaternionf parentRotation = new Quaternionf();
        if (parent >= 0) {
            new Matrix3f(global[parent]).getUnnormalizedRotation(parentRotation);
            parentRotation.normalize();
        }
        // 把世界空间的修正换到父级坐标系里，再作为局部量施加。
        Quaternionf localDelta = new Quaternionf(parentRotation).conjugate()
                .mul(delta).mul(parentRotation);
        localRotations[linkIndex].premul(localDelta).normalize();
        if (link.limited()) {
            clampToBox(localRotations[linkIndex], link);
        }
    }

    private static Vector3f bonePosition(MmdModelData model, Matrix4f[] global, int index) {
        MmdModelData.Bone bone = model.bones().get(index);
        return global[index].transformPosition(new Vector3f(bone.x(), bone.y(), bone.z()));
    }

    /** 从 {@code from} 起，按局部变换重算骨骼层级。 */
    private static void rebuild(MmdModelData model, Quaternionf[] localRotations,
                                Vector3f[] localTranslations, Matrix4f[] global, int from) {
        for (int i = from; i < model.bones().size(); i++) {
            MmdModelData.Bone bone = model.bones().get(i);
            Vector3f translation = localTranslations[i];
            Quaternionf rotation = localRotations[i];
            Matrix4f local = new Matrix4f()
                    .translation(translation.x, translation.y, translation.z)
                    .rotate(rotation);
            int parent = bone.parentIndex();
            global[i] = parent >= 0 ? new Matrix4f(global[parent]).mul(local) : local;
        }
    }

    private static void clampX(Quaternionf rotation, float limit) {
        Vector3f euler = new Vector3f();
        rotation.getEulerAnglesXYZ(euler);
        float clamped = Math.max(-limit, Math.min(limit, euler.x));
        if (clamped == euler.x) return;
        rotation.set(new Quaternionf().rotationXYZ(clamped, euler.y, euler.z));
    }

    private static void clampToBox(Quaternionf rotation, MmdModelData.Bone.Link link) {
        Vector3f euler = new Vector3f();
        rotation.getEulerAnglesXYZ(euler);
        float x = clamp(euler.x, link.lowerX(), link.upperX());
        float y = clamp(euler.y, link.lowerY(), link.upperY());
        float z = clamp(euler.z, link.lowerZ(), link.upperZ());
        if (x == euler.x && y == euler.y && z == euler.z) return;
        rotation.set(new Quaternionf().rotationXYZ(x, y, z));
    }

    private static float clamp(float value, float lower, float upper) {
        if (lower == 0.0F && upper == 0.0F) return value;
        float low = Math.min(lower, upper);
        float high = Math.max(lower, upper);
        return Math.max(low, Math.min(high, value));
    }
}
