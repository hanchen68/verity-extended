package com.hanchen.veritycustomapi.pmx;

import java.util.List;

/**
 * 解析后的 MMD 模型。除了渲染要的几何，还带着 PMX/PMD 定义的 morph（表情）、刚体、关节和 IK 数据，
 * 这样运行时才能真的驱动表情帧、物理和反向运动学。
 */
public record MmdModelData(float[] positions, float[] normals, float[] uvs,
                           int[] boneIndices, float[] boneWeights, int[] indices,
                           List<Material> materials, List<Bone> bones,
                           List<Morph> morphs, List<RigidBody> rigidBodies, List<Joint> joints,
                           float minY, float maxY) {
    public int vertexCount() {
        return positions.length / 3;
    }

    public boolean hasPhysics() {
        return !rigidBodies.isEmpty();
    }

    public boolean hasIk() {
        for (Bone bone : bones) {
            if (bone.ik() != null) return true;
        }
        return false;
    }

    /**
     * 一次绘制调用：一段索引范围，外加用到的颜色和贴图。sphereMode 是 PMX 球面贴图模式；
     * 球面贴图靠顶点 UV 加 UV1 morph 偏移来索引，是 MMD 那种标志性着色的来源。
     *
     * @param sphereMode {@link #SPHERE_NONE}、{@link #SPHERE_MULTIPLY}、{@link #SPHERE_ADD} 或 {@link #SPHERE_SUBTEXTURE}
     */
    public record Material(int firstIndex, int indexCount, String texturePath,
                           float red, float green, float blue, float alpha,
                           String sphereTexturePath, int sphereMode) {
        public static final int SPHERE_NONE = 0;
        public static final int SPHERE_MULTIPLY = 1;
        public static final int SPHERE_ADD = 2;
        /** 用球面贴图的 alpha 在两半贴图之间选。 */
        public static final int SPHERE_SUBTEXTURE = 3;

        /** 不带球面贴图的便捷构造。 */
        public Material(int firstIndex, int indexCount, String texturePath,
                        float red, float green, float blue, float alpha) {
            this(firstIndex, indexCount, texturePath, red, green, blue, alpha, "", SPHERE_NONE);
        }

        public boolean hasSphereMap() {
            return sphereMode != SPHERE_NONE
                    && sphereTexturePath != null && !sphereTexturePath.isBlank();
        }
    }

    /** 一根骨骼，外加可选的 IK 链。 */
    public record Bone(String name, int parentIndex, float x, float y, float z, Ik ik) {
        public Bone(String name, int parentIndex, float x, float y, float z) {
            this(name, parentIndex, x, y, z, null);
        }

        /**
         * IK 定义：targetBone 是必须够到本骨骼位置的末端骨骼，links 是靠旋转到位的那串。
         */
        public record Ik(int targetBone, int iterations, float angleLimit, List<Link> links) { }

        /** IK 链里的一个可旋转环节，可以限制在旋转盒内。 */
        public record Link(int boneIndex, boolean limited,
                           float lowerX, float lowerY, float lowerZ,
                           float upperX, float upperY, float upperZ) { }
    }

    /**
     * morph（表情）。加载时已经把组 morph 展平了，所以各偏移列表里已经带上本 morph 的增量。
     */
    public record Morph(String name, List<VertexOffset> vertexOffsets, List<UvOffset> uvOffsets,
                        List<BoneOffset> boneOffsets, List<UvChannelOffset> uvChannelOffsets,
                        List<MaterialOffset> materialOffsets, List<Impulse> impulses) {
        /** 只带三种基础偏移的便捷构造。 */
        public Morph(String name, List<VertexOffset> vertexOffsets, List<UvOffset> uvOffsets,
                     List<BoneOffset> boneOffsets) {
            this(name, vertexOffsets, uvOffsets, boneOffsets, List.of(), List.of(), List.of());
        }

        public boolean isEmpty() {
            return vertexOffsets.isEmpty() && uvOffsets.isEmpty() && boneOffsets.isEmpty()
                    && uvChannelOffsets.isEmpty() && materialOffsets.isEmpty() && impulses.isEmpty();
        }
    }

    public record VertexOffset(int vertexIndex, float x, float y, float z) { }

    /** 加在顶点基础 UV 通道上的偏移。 */
    public record UvOffset(int vertexIndex, float u, float v) { }

    /**
     * 加在附加 UV 通道上的偏移（PMX morph 类型 4–7，即 UV1–UV4），channel 从 1 开始数。
     * 原版顶点格式只有一对 UV（第二对给光照贴图用），所以这些偏移只给自定义 AvatarRenderer 用。
     */
    public record UvChannelOffset(int channel, int vertexIndex, float u, float v) { }

    public record BoneOffset(int boneIndex, float x, float y, float z,
                             float qx, float qy, float qz, float qw) { }

    /**
     * PMX 材质 morph 偏移（类型 8）。只留原版顶点格式表达得了的通道：漫反射色（含 alpha）
     * 和贴图色调（内置渲染器没法单独调色，折进顶点色）；其余色调在解析时就消费掉，为的是保持字节流对齐。
     *
     * @param calcMode {@link #MODE_MULTIPLY}(0) 或 {@link #MODE_ADD}(1)
     */
    public record MaterialOffset(int materialIndex, int calcMode,
                                 float diffuseR, float diffuseG, float diffuseB, float diffuseA,
                                 float textureR, float textureG, float textureB, float textureA) {
        public static final int MODE_MULTIPLY = 0;
        public static final int MODE_ADD = 1;

        public boolean additive() {
            return calcMode == MODE_ADD;
        }
    }

    /**
     * PMX 冲量 morph 偏移（类型 10）。冲量作用在刚体上，转成那个刚体所驱动骨骼的一次性速度脉冲。
     */
    public record Impulse(int rigidBodyIndex, boolean local,
                          float vx, float vy, float vz,
                          float torqueX, float torqueY, float torqueZ) { }

    /** PMX/PMD rigid body（刚体）。 */
    public record RigidBody(String name, int boneIndex, int group, int mask, int shape,
                            float width, float height, float depth,
                            float x, float y, float z, float rx, float ry, float rz,
                            float mass, float linearDamping, float angularDamping,
                            float restitution, float friction, int physicsMode) {
        /** 物理模式 2 是骨骼物理：刚体当成动态弹簧跟着骨骼。 */
        public boolean bonePhysics() {
            return physicsMode == 2;
        }

        /**
         * 模式 1 是普通物理，模式 2 还会贴合骨骼位置，这两种都参与模拟；
         * 模式 0 只跟着骨骼、保持静态，交给 VMD 姿态定。
         */
        public boolean dynamic() {
            return physicsMode == 1 || physicsMode == 2;
        }
    }

    /** PMX/PMD 关节，用来限制两个刚体之间的相对运动。 */
    public record Joint(String name, int type, int rigidBodyA, int rigidBodyB,
                        float x, float y, float z, float rx, float ry, float rz,
                        float lowerX, float lowerY, float lowerZ,
                        float upperX, float upperY, float upperZ,
                        float springX, float springY, float springZ,
                        float springRx, float springRy, float springRz) { }
}
