package com.hanchen.veritycustomapi.pmx;

import java.util.List;

/**
 * 刚体间碰撞的纯数学部分。MMD 刚体分球/盒/胶囊，各带碰撞组和 16 位掩码，双方掩码都放行对方组才会碰。
 * 求解器靠旋转而不是平移驱动骨骼：身体相对骨骼有偏移，单位倾斜绕 X 会让身体移动 (0, -offsetZ, offsetY)，
 * 绕 Z 移动 (-offsetY, offsetX, 0)；拿这两个向量对目标位移做最小二乘求 X/Z 倾角，退化时就不修正。
 */
public final class MmdRigidBodyCollision {
    /** 单次碰撞允许增加的最大倾斜（弧度），防接触面突变。 */
    public static final float MAX_TILT_STEP = 0.35F;
    private static final float EPSILON = 1.0E-6F;

    private MmdRigidBodyCollision() {
    }

    /**
     * PMX 形状的近似碰撞半径：盒存半长，球/胶囊存半径，只有胶囊要算（半径 + 圆柱段的一半）。
     */
    public static float radius(int shape, float width, float height, float depth) {
        float w = finite(Math.abs(width));
        float h = finite(Math.abs(height));
        float d = finite(Math.abs(depth));
        return switch (shape) {
            case 1 -> Math.max(w, Math.max(h, d));
            case 2 -> w + h * 0.5F;
            default -> w;
        };
    }

    /** 两个刚体能不能接触，按 MMD 的组/掩码过滤。 */
    public static boolean shouldCollide(int groupA, int maskA, int groupB, int maskB) {
        if (groupA < 0 || groupA > 15 || groupB < 0 || groupB > 15) return false;
        return (maskA & (1 << groupB)) != 0 && (maskB & (1 << groupA)) != 0;
    }

    /**
     * 一次修正里本刚体该承担的份额，按逆质量在两者间分（轻的被重的推开），两边都没有效质量就取 0.5。
     */
    public static float share(float inverseMass, float inverseMassOther) {
        float a = Math.max(0.0F, finite(inverseMass));
        float b = Math.max(0.0F, finite(inverseMassOther));
        float total = a + b;
        if (total <= EPSILON) return 0.5F;
        return a / total;
    }

    /** @return 1/mass，静态或没质量的刚体返回 0 */
    public static float inverseMass(float mass) {
        if (!Float.isFinite(mass) || mass <= EPSILON) return 0.0F;
        return 1.0F / mass;
    }

    /**
     * 求把刚体中心沿给定方向移动最合适的 X、Z 倾角。
     *
     * @param offsetX  刚体中心相对骨骼的偏移（模型空间）
     * @param offsetY  刚体中心相对骨骼的偏移（模型空间）
     * @param offsetZ  刚体中心相对骨骼的偏移（模型空间）
     * @param dirX     刚体需移动的单位方向
     * @param dirY     刚体需移动的单位方向
     * @param dirZ     刚体需移动的单位方向
     * @param distance 刚体需移动的距离（模型单位）
     * @return {@code {tiltX, tiltZ}}（弧度），各自钳制到 {@link #MAX_TILT_STEP}
     */
    public static float[] solveTilt(float offsetX, float offsetY, float offsetZ,
                                    float dirX, float dirY, float dirZ, float distance) {
        if (!Float.isFinite(distance) || distance <= 0.0F) return ZERO;
        float ox = finite(offsetX);
        float oy = finite(offsetY);
        float oz = finite(offsetZ);
        // 单位倾斜绕 X、绕 Z 的扫动向量。
        float exX = 0.0F, exY = -oz, exZ = oy;
        float ezX = -oy, ezY = ox, ezZ = 0.0F;
        float tx = finite(dirX) * distance;
        float ty = finite(dirY) * distance;
        float tz = finite(dirZ) * distance;
        float aa = exX * exX + exY * exY + exZ * exZ;
        float bb = ezX * ezX + ezY * ezY + ezZ * ezZ;
        float at = exX * tx + exY * ty + exZ * tz;
        float bt = ezX * tx + ezY * ty + ezZ * tz;
        // 刚体落在旋转轴上时这个倾斜推不动它，对应扫动向量就退化了，改用还有效的那个轴。
        if (aa <= EPSILON && bb <= EPSILON) return ZERO;
        if (bb <= EPSILON) return new float[]{clampTilt(at / aa), 0.0F};
        if (aa <= EPSILON) return new float[]{0.0F, clampTilt(bt / bb)};
        float ab = exX * ezX + exY * ezY + exZ * ezZ;
        float determinant = aa * bb - ab * ab;
        if (Math.abs(determinant) <= EPSILON * aa * bb) {
            // 两个扫动向量平行、合成不了，取较大的那个。
            return aa >= bb
                    ? new float[]{clampTilt(at / aa), 0.0F}
                    : new float[]{0.0F, clampTilt(bt / bb)};
        }
        float tiltX = (bb * at - ab * bt) / determinant;
        float tiltZ = (aa * bt - ab * at) / determinant;
        return new float[]{clampTilt(tiltX), clampTilt(tiltZ)};
    }

    /**
     * 每根骨骼在模型空间里的静止位置，沿父链累加出来。
     *
     * @return {@code 3 * bones.size()} 个 float，每根骨骼 {@code {x, y, z}}
     */
    public static float[] boneRestPositions(List<MmdModelData.Bone> bones) {
        int count = bones.size();
        float[] result = new float[count * 3];
        boolean[] resolved = new boolean[count];
        int[] guard = new int[count];
        for (int bone = 0; bone < count; bone++) {
            resolve(bones, bone, result, resolved, guard);
        }
        return result;
    }

    private static void resolve(List<MmdModelData.Bone> bones, int index,
                                float[] result, boolean[] resolved, int[] guard) {
        if (index < 0 || index >= bones.size() || resolved[index]) return;
        if (guard[index]++ > bones.size()) {
            // 父链成环了：把这根骨骼当根，让遍历能收尾。
            MmdModelData.Bone bone = bones.get(index);
            result[index * 3] = finite(bone.x());
            result[index * 3 + 1] = finite(bone.y());
            result[index * 3 + 2] = finite(bone.z());
            resolved[index] = true;
            return;
        }
        MmdModelData.Bone bone = bones.get(index);
        int parent = bone.parentIndex();
        if (parent < 0 || parent >= bones.size() || parent == index) {
            result[index * 3] = finite(bone.x());
            result[index * 3 + 1] = finite(bone.y());
            result[index * 3 + 2] = finite(bone.z());
            resolved[index] = true;
            return;
        }
        resolve(bones, parent, result, resolved, guard);
        result[index * 3] = result[parent * 3] + finite(bone.x());
        result[index * 3 + 1] = result[parent * 3 + 1] + finite(bone.y());
        result[index * 3 + 2] = result[parent * 3 + 2] + finite(bone.z());
        resolved[index] = true;
    }

    /**
     * 去掉倾斜速度里让两刚体继续靠近的分量，并按需加上反弹。
     *
     * @param restitution {@code 0} 是完全停止，最大 {@code 1} 是完全反弹
     * @return 约束后的 {@code {velocityX, velocityZ}}
     */
    public static float[] cancelApproach(float velocityX, float velocityZ,
                                         float tiltX, float tiltZ, float restitution) {
        float vx = finite(velocityX);
        float vz = finite(velocityZ);
        float nx = finite(tiltX);
        float nz = finite(tiltZ);
        float lengthSquared = nx * nx + nz * nz;
        if (lengthSquared < EPSILON) return new float[]{vx, vz};
        float normalX = nx / (float) Math.sqrt(lengthSquared);
        float normalZ = nz / (float) Math.sqrt(lengthSquared);
        float closing = vx * normalX + vz * normalZ;
        if (closing >= 0.0F) return new float[]{vx, vz};
        float bounce = Math.max(0.0F, Math.min(1.0F, finite(restitution)));
        float impulse = -(1.0F + bounce) * closing;
        return new float[]{vx + impulse * normalX, vz + impulse * normalZ};
    }

    private static float clampTilt(float value) {
        if (!Float.isFinite(value)) return 0.0F;
        return Math.max(-MAX_TILT_STEP, Math.min(MAX_TILT_STEP, value));
    }

    private static float finite(float value) {
        return Float.isFinite(value) ? value : 0.0F;
    }

    private static final float[] ZERO = {0.0F, 0.0F};
}
