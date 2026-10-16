package com.hanchen.veritycustomapi.pmx;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MmdModelLoader {
    private static final int MAX_VERTICES = 500_000;
    private static final int MAX_INDICES = 3_000_000;
    private static final int MAX_BONES = 16_384;
    private static final int MAX_MATERIALS = 16_384;
    private static final int MAX_MORPHS = 65_536;
    private static final int MAX_MORPH_OFFSETS = 2_000_000;
    private static final int MAX_RIGID_BODIES = 65_536;
    private static final int MAX_JOINTS = 65_536;
    private static final int MAX_TEXT_BYTES = 1_048_576;

    private MmdModelLoader() {
    }

    public static MmdModelData load(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length < 4) {
            throw new IOException("MMD model header is too short");
        }
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (lower.endsWith(".pmx")) {
            return readPmx(buffer);
        }
        if (lower.endsWith(".pmd")) {
            return readPmd(buffer);
        }
        throw new IOException("Unsupported MMD model format");
    }

    private static MmdModelData readPmx(ByteBuffer buffer) throws IOException {
        require(buffer, 9);
        byte[] magic = new byte[4];
        buffer.get(magic);
        if (!"PMX ".equals(new String(magic, StandardCharsets.US_ASCII))) {
            throw new IOException("Invalid PMX magic");
        }
        float version = buffer.getFloat();
        if (version < 2.0F || version > 2.1F) {
            throw new IOException("Unsupported PMX version");
        }
        int headerSize = Byte.toUnsignedInt(buffer.get());
        if (headerSize < 8 || headerSize > 64) {
            throw new IOException("Invalid PMX globals size");
        }
        require(buffer, headerSize);
        byte[] globals = new byte[headerSize];
        buffer.get(globals);
        Charset textCharset = globals[0] == 0 ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_8;
        int additionalUv = Byte.toUnsignedInt(globals[1]);
        int vertexIndexSize = validIndexSize(globals[2]);
        int textureIndexSize = validIndexSize(globals[3]);
        int materialIndexSize = validIndexSize(globals[4]);
        int boneIndexSize = validIndexSize(globals[5]);
        int morphIndexSize = validIndexSize(globals[6]);
        int rigidIndexSize = validIndexSize(globals[7]);
        for (int i = 0; i < 4; i++) readText(buffer, textCharset);

        int vertexCount = count(buffer, MAX_VERTICES, "PMX vertices");
        if (vertexCount == 0) throw new IOException("PMX model has no vertices");
        float[] positions = new float[vertexCount * 3];
        float[] normals = new float[vertexCount * 3];
        float[] uvs = new float[vertexCount * 2];
        int[] boneIndices = filled(vertexCount * 4, -1);
        float[] boneWeights = new float[vertexCount * 4];
        float minY = Float.POSITIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            require(buffer, 32 + additionalUv * 16 + 2);
            int p = vertex * 3;
            positions[p] = finite(buffer.getFloat());
            positions[p + 1] = finite(buffer.getFloat());
            positions[p + 2] = finite(buffer.getFloat());
            minY = Math.min(minY, positions[p + 1]);
            maxY = Math.max(maxY, positions[p + 1]);
            normals[p] = finite(buffer.getFloat());
            normals[p + 1] = finite(buffer.getFloat());
            normals[p + 2] = finite(buffer.getFloat());
            int uv = vertex * 2;
            uvs[uv] = finite(buffer.getFloat());
            uvs[uv + 1] = 1.0F - finite(buffer.getFloat());
            skip(buffer, additionalUv * 16);
            int deform = Byte.toUnsignedInt(buffer.get());
            int b = vertex * 4;
            switch (deform) {
                case 0 -> {
                    boneIndices[b] = readSignedIndex(buffer, boneIndexSize);
                    boneWeights[b] = 1.0F;
                }
                case 1, 3 -> {
                    boneIndices[b] = readSignedIndex(buffer, boneIndexSize);
                    boneIndices[b + 1] = readSignedIndex(buffer, boneIndexSize);
                    float weight = clamp01(buffer.getFloat());
                    boneWeights[b] = weight;
                    boneWeights[b + 1] = 1.0F - weight;
                    if (deform == 3) skip(buffer, 36);
                }
                case 2, 4 -> {
                    for (int i = 0; i < 4; i++) boneIndices[b + i] = readSignedIndex(buffer, boneIndexSize);
                    for (int i = 0; i < 4; i++) boneWeights[b + i] = clamp01(buffer.getFloat());
                    normalizeWeights(boneWeights, b);
                }
                default -> throw new IOException("Unsupported PMX vertex deform");
            }
            finite(buffer.getFloat());
        }

        int indexCount = count(buffer, MAX_INDICES, "PMX indices");
        if (indexCount == 0 || indexCount % 3 != 0) throw new IOException("PMX indices are not triangles");
        int[] indices = new int[indexCount];
        for (int i = 0; i < indexCount; i++) {
            indices[i] = readUnsignedIndex(buffer, vertexIndexSize);
            if (indices[i] < 0 || indices[i] >= vertexCount) throw new IOException("PMX index out of range");
        }
        int textureCount = count(buffer, 65_536, "PMX textures");
        List<String> textures = new ArrayList<>(textureCount);
        for (int i = 0; i < textureCount; i++) textures.add(readText(buffer, textCharset));

        int materialCount = count(buffer, MAX_MATERIALS, "PMX materials");
        List<MmdModelData.Material> materials = new ArrayList<>(materialCount);
        int firstIndex = 0;
        for (int i = 0; i < materialCount; i++) {
            readText(buffer, textCharset);
            readText(buffer, textCharset);
            require(buffer, 70);
            float red = clamp01(buffer.getFloat());
            float green = clamp01(buffer.getFloat());
            float blue = clamp01(buffer.getFloat());
            float alpha = clamp01(buffer.getFloat());
            skip(buffer, 12 + 4 + 12 + 1 + 16 + 4);
            int textureIndex = readSignedIndex(buffer, textureIndexSize);
            int sphereIndex = readSignedIndex(buffer, textureIndexSize);
            int sphereMode = Byte.toUnsignedInt(buffer.get());
            int toonFlag = Byte.toUnsignedInt(buffer.get());
            if (toonFlag == 0) readSignedIndex(buffer, textureIndexSize); else skip(buffer, 1);
            readText(buffer, textCharset);
            int surfaceCount = count(buffer, indexCount, "PMX material indices");
            if (surfaceCount % 3 != 0 || firstIndex + surfaceCount > indexCount) {
                throw new IOException("PMX material index range is not triangular");
            }
            String texture = textureIndex >= 0 && textureIndex < textures.size() ? textures.get(textureIndex) : "";
            String sphere = sphereIndex >= 0 && sphereIndex < textures.size() ? textures.get(sphereIndex) : "";
            materials.add(new MmdModelData.Material(firstIndex, surfaceCount, texture, red, green, blue, alpha,
                    sphere, sphereMode));
            firstIndex += surfaceCount;
        }

        int boneCount = count(buffer, MAX_BONES, "PMX bones");
        List<MmdModelData.Bone> bones = new ArrayList<>(boneCount);
        for (int i = 0; i < boneCount; i++) {
            String name = readText(buffer, textCharset);
            readText(buffer, textCharset);
            require(buffer, 12 + boneIndexSize + 6);
            float x = finite(buffer.getFloat());
            float y = finite(buffer.getFloat());
            float z = finite(buffer.getFloat());
            int parent = readSignedIndex(buffer, boneIndexSize);
            buffer.getInt();
            int flags = Short.toUnsignedInt(buffer.getShort());
            if ((flags & 0x0001) != 0) readSignedIndex(buffer, boneIndexSize); else skip(buffer, 12);
            if ((flags & 0x0100) != 0 || (flags & 0x0200) != 0) {
                readSignedIndex(buffer, boneIndexSize);
                skip(buffer, 4);
            }
            if ((flags & 0x0400) != 0) skip(buffer, 12);
            if ((flags & 0x0800) != 0) skip(buffer, 24);
            if ((flags & 0x2000) != 0) skip(buffer, 4);
            MmdModelData.Bone.Ik ik = null;
            if ((flags & 0x0020) != 0) {
                int ikTarget = readSignedIndex(buffer, boneIndexSize);
                int ikIterations = buffer.getInt();
                float ikAngleLimit = finite(buffer.getFloat());
                int linkCount = count(buffer, MAX_BONES, "PMX IK links");
                List<MmdModelData.Bone.Link> links = new ArrayList<>(linkCount);
                for (int link = 0; link < linkCount; link++) {
                    int linkBone = readSignedIndex(buffer, boneIndexSize);
                    require(buffer, 1);
                    boolean limited = buffer.get() != 0;
                    float lowerX = 0.0F;
                    float lowerY = 0.0F;
                    float lowerZ = 0.0F;
                    float upperX = 0.0F;
                    float upperY = 0.0F;
                    float upperZ = 0.0F;
                    if (limited) {
                        lowerX = finite(buffer.getFloat());
                        lowerY = finite(buffer.getFloat());
                        lowerZ = finite(buffer.getFloat());
                        upperX = finite(buffer.getFloat());
                        upperY = finite(buffer.getFloat());
                        upperZ = finite(buffer.getFloat());
                    }
                    if (linkBone >= 0 && linkBone < boneCount) {
                        links.add(new MmdModelData.Bone.Link(linkBone, limited,
                                lowerX, lowerY, lowerZ, upperX, upperY, upperZ));
                    }
                }
                if (ikTarget >= 0 && ikTarget < boneCount && !links.isEmpty()) {
                    ik = new MmdModelData.Bone.Ik(ikTarget,
                            Math.max(1, Math.min(64, ikIterations)), Math.abs(ikAngleLimit),
                            List.copyOf(links));
                }
            }
            bones.add(new MmdModelData.Bone(name, validParent(parent, i), x, y, z, ik));
        }
        if (materials.isEmpty()) {
            materials.add(new MmdModelData.Material(0, indexCount, "", 1, 1, 1, 1));
        }
        List<MmdModelData.Morph> morphs = readPmxMorphs(buffer, textCharset, vertexIndexSize,
                boneIndexSize, morphIndexSize, materialIndexSize, rigidIndexSize, vertexCount,
                bones.size(), materials.size());
        skipPmxDisplayFrames(buffer, textCharset, boneIndexSize, morphIndexSize);
        List<MmdModelData.RigidBody> rigidBodies = readPmxRigidBodies(buffer, textCharset,
                boneIndexSize, bones.size());
        List<MmdModelData.Joint> joints = readPmxJoints(buffer, textCharset, rigidIndexSize, rigidBodies.size());
        return new MmdModelData(positions, normals, uvs, boneIndices, boneWeights, indices,
                List.copyOf(materials), List.copyOf(bones), morphs, rigidBodies, joints, minY, maxY);
    }

    private record GroupRef(int morphIndex, float weight) { }

    private record RawMorph(String name, List<GroupRef> groups,
                            List<MmdModelData.VertexOffset> vertexOffsets,
                            List<MmdModelData.UvOffset> uvOffsets,
                            List<MmdModelData.BoneOffset> boneOffsets,
                            List<MmdModelData.UvChannelOffset> uvChannelOffsets,
                            List<MmdModelData.MaterialOffset> materialOffsets,
                            List<MmdModelData.Impulse> impulses) { }

    private static List<MmdModelData.Morph> readPmxMorphs(ByteBuffer buffer, Charset charset,
                                                          int vertexIndexSize, int boneIndexSize,
                                                          int morphIndexSize, int materialIndexSize,
                                                          int rigidIndexSize, int vertexCount,
                                                          int boneCount, int materialCount) throws IOException {
        int morphCount = count(buffer, MAX_MORPHS, "PMX morphs");
        List<RawMorph> raw = new ArrayList<>(morphCount);
        for (int i = 0; i < morphCount; i++) {
            String name = readText(buffer, charset);
            readText(buffer, charset);
            require(buffer, 2);
            buffer.get();
            int type = Byte.toUnsignedInt(buffer.get());
            int offsets = count(buffer, MAX_MORPH_OFFSETS, "PMX morph offsets");
            List<GroupRef> groups = new ArrayList<>();
            List<MmdModelData.VertexOffset> vertices = new ArrayList<>();
            List<MmdModelData.UvOffset> uvs = new ArrayList<>();
            List<MmdModelData.BoneOffset> bones = new ArrayList<>();
            List<MmdModelData.UvChannelOffset> uvChannels = new ArrayList<>();
            List<MmdModelData.MaterialOffset> materialOffsets = new ArrayList<>();
            List<MmdModelData.Impulse> impulses = new ArrayList<>();
            switch (type) {
                case 0, 9 -> {
                    for (int offset = 0; offset < offsets; offset++) {
                        int index = readSignedIndex(buffer, morphIndexSize);
                        float weight = finite(buffer.getFloat());
                        if (index >= 0 && index < morphCount) groups.add(new GroupRef(index, weight));
                    }
                }
                case 1 -> {
                    for (int offset = 0; offset < offsets; offset++) {
                        int index = readUnsignedIndex(buffer, vertexIndexSize);
                        float x = finite(buffer.getFloat());
                        float y = finite(buffer.getFloat());
                        float z = finite(buffer.getFloat());
                        if (index < vertexCount) vertices.add(new MmdModelData.VertexOffset(index, x, y, z));
                    }
                }
                case 2 -> {
                    for (int offset = 0; offset < offsets; offset++) {
                        int index = readSignedIndex(buffer, boneIndexSize);
                        float x = finite(buffer.getFloat());
                        float y = finite(buffer.getFloat());
                        float z = finite(buffer.getFloat());
                        float qx = finite(buffer.getFloat());
                        float qy = finite(buffer.getFloat());
                        float qz = finite(buffer.getFloat());
                        float qw = finite(buffer.getFloat());
                        if (index >= 0 && index < boneCount) {
                            bones.add(new MmdModelData.BoneOffset(index, x, y, z, qx, qy, qz, qw));
                        }
                    }
                }
                case 3 -> {
                    // 基础 UV morph：后面那两个 float 是没用到的填充。
                    for (int offset = 0; offset < offsets; offset++) {
                        int index = readUnsignedIndex(buffer, vertexIndexSize);
                        float u = finite(buffer.getFloat());
                        float v = finite(buffer.getFloat());
                        finite(buffer.getFloat());
                        finite(buffer.getFloat());
                        if (index < vertexCount) uvs.add(new MmdModelData.UvOffset(index, u, v));
                    }
                }
                // 附加 UV 通道（UV1–UV4）。原版没有对应的顶点通道，所以解析出来给自定义渲染器用，
                // 而不是直接丢掉。
                case 4, 5, 6, 7 -> {
                    int channel = type - 3;
                    for (int offset = 0; offset < offsets; offset++) {
                        int index = readUnsignedIndex(buffer, vertexIndexSize);
                        float u = finite(buffer.getFloat());
                        float v = finite(buffer.getFloat());
                        finite(buffer.getFloat());
                        finite(buffer.getFloat());
                        if (index < vertexCount) {
                            uvChannels.add(new MmdModelData.UvChannelOffset(channel, index, u, v));
                        }
                    }
                }
                case 8 -> {
                    for (int offset = 0; offset < offsets; offset++) {
                        int index = readSignedIndex(buffer, materialIndexSize);
                        int calcMode = Byte.toUnsignedInt(buffer.get());
                        float diffuseR = finite(buffer.getFloat());
                        float diffuseG = finite(buffer.getFloat());
                        float diffuseB = finite(buffer.getFloat());
                        float diffuseA = finite(buffer.getFloat());
                        skip(buffer, 3 * 4);                       // 高光 RGB
                        finite(buffer.getFloat());                 // 高光强度
                        skip(buffer, 3 * 4);                       // 环境光 RGB
                        skip(buffer, 4 * 4);                       // 描边颜色
                        finite(buffer.getFloat());                 // 描边宽度
                        float textureR = finite(buffer.getFloat());
                        float textureG = finite(buffer.getFloat());
                        float textureB = finite(buffer.getFloat());
                        float textureA = finite(buffer.getFloat());
                        skip(buffer, 4 * 4);                       // 球面色调
                        skip(buffer, 4 * 4);                       // toon 色调
                        if (index >= 0 && index < materialCount) {
                            materialOffsets.add(new MmdModelData.MaterialOffset(index, calcMode,
                                    diffuseR, diffuseG, diffuseB, diffuseA,
                                    textureR, textureG, textureB, textureA));
                        }
                    }
                }
                case 10 -> {
                    for (int offset = 0; offset < offsets; offset++) {
                        int index = readSignedIndex(buffer, rigidIndexSize);
                        boolean local = buffer.get() != 0;
                        float vx = finite(buffer.getFloat());
                        float vy = finite(buffer.getFloat());
                        float vz = finite(buffer.getFloat());
                        float tx = finite(buffer.getFloat());
                        float ty = finite(buffer.getFloat());
                        float tz = finite(buffer.getFloat());
                        if (index >= 0) {
                            impulses.add(new MmdModelData.Impulse(index, local, vx, vy, vz, tx, ty, tz));
                        }
                    }
                }
                default -> throw new IOException("Unsupported PMX morph type");
            }
            raw.add(new RawMorph(name, List.copyOf(groups), List.copyOf(vertices),
                    List.copyOf(uvs), List.copyOf(bones), List.copyOf(uvChannels),
                    List.copyOf(materialOffsets), List.copyOf(impulses)));
        }
        return flattenMorphs(raw, vertexCount, boneCount, materialCount);
    }

    /**
     * 把组 morph 展开成具体的顶点/UV/骨骼/材质/冲量偏移，这样渲染器就不用再去走 morph 图；
     * 对畸形文件还带环路保护。
     */
    private static List<MmdModelData.Morph> flattenMorphs(List<RawMorph> raw, int vertexCount,
                                                          int boneCount, int materialCount) {
        List<MmdModelData.Morph> result = new ArrayList<>(raw.size());
        for (int index = 0; index < raw.size(); index++) {
            Accumulator accumulator = new Accumulator();
            boolean[] visited = new boolean[raw.size()];
            expand(raw, index, 1.0F, visited, 0, accumulator, vertexCount, boneCount, materialCount);
            result.add(new MmdModelData.Morph(raw.get(index).name(),
                    List.copyOf(accumulator.vertices), List.copyOf(accumulator.uvs),
                    List.copyOf(accumulator.bones), List.copyOf(accumulator.uvChannels),
                    List.copyOf(accumulator.materialOffsets), List.copyOf(accumulator.impulses)));
        }
        return List.copyOf(result);
    }

    private static final class Accumulator {
        private final List<MmdModelData.VertexOffset> vertices = new ArrayList<>();
        private final List<MmdModelData.UvOffset> uvs = new ArrayList<>();
        private final List<MmdModelData.BoneOffset> bones = new ArrayList<>();
        private final List<MmdModelData.UvChannelOffset> uvChannels = new ArrayList<>();
        private final List<MmdModelData.MaterialOffset> materialOffsets = new ArrayList<>();
        private final List<MmdModelData.Impulse> impulses = new ArrayList<>();
    }

    private static void expand(List<RawMorph> raw, int index, float weight, boolean[] visited, int depth,
                               Accumulator out, int vertexCount, int boneCount, int materialCount) {
        if (index < 0 || index >= raw.size() || depth > 8 || visited[index] || weight == 0.0F) return;
        visited[index] = true;
        RawMorph morph = raw.get(index);
        for (MmdModelData.VertexOffset offset : morph.vertexOffsets()) {
            if (offset.vertexIndex() >= vertexCount) continue;
            out.vertices.add(new MmdModelData.VertexOffset(offset.vertexIndex(),
                    offset.x() * weight, offset.y() * weight, offset.z() * weight));
        }
        for (MmdModelData.UvOffset offset : morph.uvOffsets()) {
            if (offset.vertexIndex() >= vertexCount) continue;
            out.uvs.add(new MmdModelData.UvOffset(offset.vertexIndex(),
                    offset.u() * weight, offset.v() * weight));
        }
        for (MmdModelData.BoneOffset offset : morph.boneOffsets()) {
            if (offset.boneIndex() >= boneCount) continue;
            out.bones.add(new MmdModelData.BoneOffset(offset.boneIndex(),
                    offset.x() * weight, offset.y() * weight, offset.z() * weight,
                    offset.qx(), offset.qy(), offset.qz(), offset.qw()));
        }
        for (MmdModelData.UvChannelOffset offset : morph.uvChannelOffsets()) {
            if (offset.vertexIndex() >= vertexCount) continue;
            out.uvChannels.add(new MmdModelData.UvChannelOffset(offset.channel(), offset.vertexIndex(),
                    offset.u() * weight, offset.v() * weight));
        }
        for (MmdModelData.MaterialOffset offset : morph.materialOffsets()) {
            if (offset.materialIndex() >= materialCount) continue;
            out.materialOffsets.add(new MmdModelData.MaterialOffset(offset.materialIndex(),
                    offset.calcMode(),
                    offset.diffuseR() * weight, offset.diffuseG() * weight,
                    offset.diffuseB() * weight, offset.diffuseA() * weight,
                    offset.textureR() * weight, offset.textureG() * weight,
                    offset.textureB() * weight, offset.textureA() * weight));
        }
        for (MmdModelData.Impulse impulse : morph.impulses()) {
            out.impulses.add(new MmdModelData.Impulse(impulse.rigidBodyIndex(), impulse.local(),
                    impulse.vx() * weight, impulse.vy() * weight, impulse.vz() * weight,
                    impulse.torqueX() * weight, impulse.torqueY() * weight, impulse.torqueZ() * weight));
        }
        for (GroupRef reference : morph.groups()) {
            expand(raw, reference.morphIndex(), weight * reference.weight(), visited, depth + 1,
                    out, vertexCount, boneCount, materialCount);
        }
        visited[index] = false;
    }

    private static void skipPmxDisplayFrames(ByteBuffer buffer, Charset charset,
                                             int boneIndexSize, int morphIndexSize) throws IOException {
        int frameCount = count(buffer, MAX_MORPHS, "PMX display frames");
        for (int frame = 0; frame < frameCount; frame++) {
            readText(buffer, charset);
            readText(buffer, charset);
            require(buffer, 1);
            buffer.get();
            int entries = count(buffer, MAX_MORPH_OFFSETS, "PMX display frame entries");
            for (int entry = 0; entry < entries; entry++) {
                require(buffer, 1);
                readSignedIndex(buffer, morphIndexSize);
                buffer.get();
            }
        }
    }

    private static List<MmdModelData.RigidBody> readPmxRigidBodies(ByteBuffer buffer, Charset charset,
                                                                   int boneIndexSize, int boneCount)
            throws IOException {
        int rigidCount = count(buffer, MAX_RIGID_BODIES, "PMX rigid bodies");
        List<MmdModelData.RigidBody> bodies = new ArrayList<>(rigidCount);
        for (int i = 0; i < rigidCount; i++) {
            String name = readText(buffer, charset);
            readText(buffer, charset);
            int boneIndex = readSignedIndex(buffer, boneIndexSize);
            require(buffer, 1 + 2 + 1 + 12 + 12 + 12 + 4 * 5 + 1);
            int group = Byte.toUnsignedInt(buffer.get());
            int mask = Short.toUnsignedInt(buffer.getShort());
            int shape = Byte.toUnsignedInt(buffer.get());
            float width = finite(buffer.getFloat());
            float height = finite(buffer.getFloat());
            float depth = finite(buffer.getFloat());
            float x = finite(buffer.getFloat());
            float y = finite(buffer.getFloat());
            float z = finite(buffer.getFloat());
            float rx = finite(buffer.getFloat());
            float ry = finite(buffer.getFloat());
            float rz = finite(buffer.getFloat());
            float mass = finite(buffer.getFloat());
            float linearDamping = finite(buffer.getFloat());
            float angularDamping = finite(buffer.getFloat());
            float restitution = finite(buffer.getFloat());
            float friction = finite(buffer.getFloat());
            int physicsMode = Byte.toUnsignedInt(buffer.get());
            bodies.add(new MmdModelData.RigidBody(name, boneIndex >= 0 && boneIndex < boneCount ? boneIndex : -1,
                    group, mask, shape, width, height, depth, x, y, z, rx, ry, rz,
                    mass, linearDamping, angularDamping, restitution, friction, physicsMode));
        }
        return List.copyOf(bodies);
    }

    private static List<MmdModelData.Joint> readPmxJoints(ByteBuffer buffer, Charset charset,
                                                          int rigidIndexSize, int rigidCount)
            throws IOException {
        int jointCount = count(buffer, MAX_JOINTS, "PMX joints");
        List<MmdModelData.Joint> joints = new ArrayList<>(jointCount);
        for (int i = 0; i < jointCount; i++) {
            String name = readText(buffer, charset);
            readText(buffer, charset);
            require(buffer, 1);
            int type = Byte.toUnsignedInt(buffer.get());
            int bodyA = readSignedIndex(buffer, rigidIndexSize);
            int bodyB = readSignedIndex(buffer, rigidIndexSize);
            require(buffer, 12 * 8);
            float x = finite(buffer.getFloat());
            float y = finite(buffer.getFloat());
            float z = finite(buffer.getFloat());
            float rx = finite(buffer.getFloat());
            float ry = finite(buffer.getFloat());
            float rz = finite(buffer.getFloat());
            float lowerX = finite(buffer.getFloat());
            float lowerY = finite(buffer.getFloat());
            float lowerZ = finite(buffer.getFloat());
            float upperX = finite(buffer.getFloat());
            float upperY = finite(buffer.getFloat());
            float upperZ = finite(buffer.getFloat());
            float rotLowerX = finite(buffer.getFloat());
            float rotLowerY = finite(buffer.getFloat());
            float rotLowerZ = finite(buffer.getFloat());
            float rotUpperX = finite(buffer.getFloat());
            float rotUpperY = finite(buffer.getFloat());
            float rotUpperZ = finite(buffer.getFloat());
            float springX = finite(buffer.getFloat());
            float springY = finite(buffer.getFloat());
            float springZ = finite(buffer.getFloat());
            float springRx = finite(buffer.getFloat());
            float springRy = finite(buffer.getFloat());
            float springRz = finite(buffer.getFloat());
            joints.add(new MmdModelData.Joint(name, type,
                    bodyA >= 0 && bodyA < rigidCount ? bodyA : -1,
                    bodyB >= 0 && bodyB < rigidCount ? bodyB : -1,
                    x, y, z, rx, ry, rz, lowerX, lowerY, lowerZ, upperX, upperY, upperZ,
                    springX, springY, springZ, springRx, springRy, springRz));
        }
        return List.copyOf(joints);
    }

    private static MmdModelData readPmd(ByteBuffer buffer) throws IOException {
        require(buffer, 3 + 4 + 20 + 256 + 4);
        byte[] magic = new byte[3];
        buffer.get(magic);
        if (!"Pmd".equals(new String(magic, StandardCharsets.US_ASCII))) {
            throw new IOException("Invalid PMD magic");
        }
        float version = buffer.getFloat();
        if (version < 0.9F || version > 1.1F) throw new IOException("Unsupported PMD version");
        readFixed(buffer, 20, Charset.forName("Shift_JIS"));
        readFixed(buffer, 256, Charset.forName("Shift_JIS"));
        int vertexCount = count(buffer, MAX_VERTICES, "PMD vertices");
        float[] positions = new float[vertexCount * 3];
        float[] normals = new float[vertexCount * 3];
        float[] uvs = new float[vertexCount * 2];
        int[] boneIndices = filled(vertexCount * 4, -1);
        float[] boneWeights = new float[vertexCount * 4];
        float minY = Float.POSITIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            require(buffer, 38);
            int p = vertex * 3;
            positions[p] = finite(buffer.getFloat());
            positions[p + 1] = finite(buffer.getFloat());
            positions[p + 2] = finite(buffer.getFloat());
            minY = Math.min(minY, positions[p + 1]);
            maxY = Math.max(maxY, positions[p + 1]);
            normals[p] = finite(buffer.getFloat());
            normals[p + 1] = finite(buffer.getFloat());
            normals[p + 2] = finite(buffer.getFloat());
            int uv = vertex * 2;
            uvs[uv] = finite(buffer.getFloat());
            uvs[uv + 1] = 1.0F - finite(buffer.getFloat());
            int b = vertex * 4;
            boneIndices[b] = Short.toUnsignedInt(buffer.getShort());
            boneIndices[b + 1] = Short.toUnsignedInt(buffer.getShort());
            float weight = Byte.toUnsignedInt(buffer.get()) / 100.0F;
            boneWeights[b] = weight;
            boneWeights[b + 1] = 1.0F - weight;
            buffer.get();
        }
        int indexCount = count(buffer, MAX_INDICES, "PMD indices");
        if (indexCount == 0 || indexCount % 3 != 0) throw new IOException("PMD indices are not triangles");
        int[] indices = new int[indexCount];
        require(buffer, indexCount * 2);
        for (int i = 0; i < indexCount; i++) {
            indices[i] = Short.toUnsignedInt(buffer.getShort());
            if (indices[i] >= vertexCount) throw new IOException("PMD index out of range");
        }
        int materialCount = count(buffer, MAX_MATERIALS, "PMD materials");
        List<MmdModelData.Material> materials = new ArrayList<>(materialCount);
        int firstIndex = 0;
        for (int i = 0; i < materialCount; i++) {
            require(buffer, 70);
            float red = clamp01(buffer.getFloat());
            float green = clamp01(buffer.getFloat());
            float blue = clamp01(buffer.getFloat());
            float alpha = clamp01(buffer.getFloat());
            skip(buffer, 4 + 12 + 12 + 1 + 1);
            int surfaceCount = buffer.getInt();
            String rawTexture = readFixed(buffer, 20, Charset.forName("Shift_JIS"));
            // PMD 把球面贴图和基础贴图塞在同一个字段里，写成 "base*sphere"。PMD 没有模式字节，
            // MMD 把它在那儿找到的东西一律当乘法球面。
            String texture = rawTexture;
            String sphere = "";
            int star = rawTexture.indexOf('*');
            if (star >= 0) {
                texture = rawTexture.substring(0, star);
                sphere = rawTexture.substring(star + 1);
            }
            if (surfaceCount < 0 || surfaceCount % 3 != 0 || firstIndex + surfaceCount > indexCount) {
                throw new IOException("PMD material index range is not triangular");
            }
            materials.add(new MmdModelData.Material(firstIndex, surfaceCount, texture, red, green, blue, alpha,
                    sphere, sphere.isBlank() ? MmdModelData.Material.SPHERE_NONE
                            : MmdModelData.Material.SPHERE_MULTIPLY));
            firstIndex += surfaceCount;
        }
        if (!materials.isEmpty() && firstIndex != indexCount) {
            throw new IOException("PMD materials do not cover all indices");
        }
        require(buffer, 2);
        int boneCount = Short.toUnsignedInt(buffer.getShort());
        if (boneCount > MAX_BONES) throw new IOException("Too many PMD bones");
        List<MmdModelData.Bone> bones = new ArrayList<>(boneCount);
        for (int i = 0; i < boneCount; i++) {
            require(buffer, 39);
            String name = readFixed(buffer, 20, Charset.forName("Shift_JIS"));
            int parent = Short.toUnsignedInt(buffer.getShort());
            buffer.getShort();
            buffer.get();
            buffer.getShort();
            float x = finite(buffer.getFloat());
            float y = finite(buffer.getFloat());
            float z = finite(buffer.getFloat());
            bones.add(new MmdModelData.Bone(name, parent == 0xFFFF ? -1 : validParent(parent, i), x, y, z));
        }
        if (materials.isEmpty()) materials.add(new MmdModelData.Material(0, indexCount, "", 1, 1, 1, 1));
        Charset shiftJis = Charset.forName("Shift_JIS");
        List<MmdModelData.Morph> morphs = readPmdMorphs(buffer, shiftJis, vertexCount);
        List<MmdModelData.RigidBody> rigidBodies = readPmdRigidBodies(buffer, shiftJis, bones.size());
        List<MmdModelData.Joint> joints = readPmdJoints(buffer, shiftJis, rigidBodies.size());
        return new MmdModelData(positions, normals, uvs, boneIndices, boneWeights, indices,
                List.copyOf(materials), List.copyOf(bones), morphs, rigidBodies, joints, minY, maxY);
    }

    private static int shortCount(ByteBuffer buffer, int maximum, String subject) throws IOException {
        require(buffer, 2);
        int value = Short.toUnsignedInt(buffer.getShort());
        if (value > maximum) throw new IOException("Invalid " + subject + " count");
        return value;
    }

    private static List<MmdModelData.Morph> readPmdMorphs(ByteBuffer buffer, Charset charset, int vertexCount)
            throws IOException {
        int morphCount = shortCount(buffer, MAX_MORPHS, "PMD morphs");
        List<MmdModelData.Morph> morphs = new ArrayList<>(morphCount);
        for (int i = 0; i < morphCount; i++) {
            String name = readFixed(buffer, 20, charset);
            require(buffer, 1);
            buffer.get();
            int offsets = count(buffer, MAX_MORPH_OFFSETS, "PMD morph offsets");
            List<MmdModelData.VertexOffset> vertices = new ArrayList<>(offsets);
            require(buffer, (long) offsets * 16);
            for (int offset = 0; offset < offsets; offset++) {
                int index = buffer.getInt();
                float x = finite(buffer.getFloat());
                float y = finite(buffer.getFloat());
                float z = finite(buffer.getFloat());
                if (index >= 0 && index < vertexCount) {
                    vertices.add(new MmdModelData.VertexOffset(index, x, y, z));
                }
            }
            morphs.add(new MmdModelData.Morph(name, List.copyOf(vertices), List.of(), List.of()));
        }
        return List.copyOf(morphs);
    }

    private static List<MmdModelData.RigidBody> readPmdRigidBodies(ByteBuffer buffer, Charset charset,
                                                                   int boneCount) throws IOException {
        int rigidCount = shortCount(buffer, MAX_RIGID_BODIES, "PMD rigid bodies");
        List<MmdModelData.RigidBody> bodies = new ArrayList<>(rigidCount);
        for (int i = 0; i < rigidCount; i++) {
            String name = readFixed(buffer, 20, charset);
            require(buffer, 4 + 1 + 2 + 1 + 12 + 12 + 12 + 4 * 5 + 1);
            int boneIndex = buffer.getInt();
            int group = Byte.toUnsignedInt(buffer.get());
            int mask = Short.toUnsignedInt(buffer.getShort());
            int shape = Byte.toUnsignedInt(buffer.get());
            float width = finite(buffer.getFloat());
            float height = finite(buffer.getFloat());
            float depth = finite(buffer.getFloat());
            float x = finite(buffer.getFloat());
            float y = finite(buffer.getFloat());
            float z = finite(buffer.getFloat());
            float rx = finite(buffer.getFloat());
            float ry = finite(buffer.getFloat());
            float rz = finite(buffer.getFloat());
            float mass = finite(buffer.getFloat());
            float linearDamping = finite(buffer.getFloat());
            float angularDamping = finite(buffer.getFloat());
            float restitution = finite(buffer.getFloat());
            float friction = finite(buffer.getFloat());
            int physicsMode = Byte.toUnsignedInt(buffer.get());
            bodies.add(new MmdModelData.RigidBody(name, boneIndex >= 0 && boneIndex < boneCount ? boneIndex : -1,
                    group, mask, shape, width, height, depth, x, y, z, rx, ry, rz,
                    mass, linearDamping, angularDamping, restitution, friction, physicsMode));
        }
        return List.copyOf(bodies);
    }

    private static List<MmdModelData.Joint> readPmdJoints(ByteBuffer buffer, Charset charset, int rigidCount)
            throws IOException {
        int jointCount = shortCount(buffer, MAX_JOINTS, "PMD joints");
        List<MmdModelData.Joint> joints = new ArrayList<>(jointCount);
        for (int i = 0; i < jointCount; i++) {
            String name = readFixed(buffer, 20, charset);
            require(buffer, 1 + 8 + 12 * 8);
            int type = Byte.toUnsignedInt(buffer.get());
            int bodyA = buffer.getInt();
            int bodyB = buffer.getInt();
            float x = finite(buffer.getFloat());
            float y = finite(buffer.getFloat());
            float z = finite(buffer.getFloat());
            float rx = finite(buffer.getFloat());
            float ry = finite(buffer.getFloat());
            float rz = finite(buffer.getFloat());
            float lowerX = finite(buffer.getFloat());
            float lowerY = finite(buffer.getFloat());
            float lowerZ = finite(buffer.getFloat());
            float upperX = finite(buffer.getFloat());
            float upperY = finite(buffer.getFloat());
            float upperZ = finite(buffer.getFloat());
            float rotLowerX = finite(buffer.getFloat());
            float rotLowerY = finite(buffer.getFloat());
            float rotLowerZ = finite(buffer.getFloat());
            float rotUpperX = finite(buffer.getFloat());
            float rotUpperY = finite(buffer.getFloat());
            float rotUpperZ = finite(buffer.getFloat());
            float springX = finite(buffer.getFloat());
            float springY = finite(buffer.getFloat());
            float springZ = finite(buffer.getFloat());
            float springRx = finite(buffer.getFloat());
            float springRy = finite(buffer.getFloat());
            float springRz = finite(buffer.getFloat());
            joints.add(new MmdModelData.Joint(name, type,
                    bodyA >= 0 && bodyA < rigidCount ? bodyA : -1,
                    bodyB >= 0 && bodyB < rigidCount ? bodyB : -1,
                    x, y, z, rx, ry, rz, lowerX, lowerY, lowerZ, upperX, upperY, upperZ,
                    springX, springY, springZ, springRx, springRy, springRz));
        }
        return List.copyOf(joints);
    }

    private static String readText(ByteBuffer buffer, Charset charset) throws IOException {
        require(buffer, 4);
        int length = buffer.getInt();
        if (length < 0 || length > MAX_TEXT_BYTES) throw new IOException("Invalid PMX text length");
        require(buffer, length);
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        return new String(bytes, charset).replace("\0", "").strip();
    }

    private static String readFixed(ByteBuffer buffer, int length, Charset charset) throws IOException {
        require(buffer, length);
        byte[] bytes = new byte[length];
        buffer.get(bytes);
        int end = 0;
        while (end < bytes.length && bytes[end] != 0) end++;
        return new String(bytes, 0, end, charset).strip();
    }

    private static int count(ByteBuffer buffer, int maximum, String subject) throws IOException {
        require(buffer, 4);
        int value = buffer.getInt();
        if (value < 0 || value > maximum) throw new IOException("Invalid " + subject + " count");
        return value;
    }

    private static int validIndexSize(byte raw) throws IOException {
        int value = Byte.toUnsignedInt(raw);
        if (value != 1 && value != 2 && value != 4) throw new IOException("Invalid PMX index size");
        return value;
    }

    private static int readSignedIndex(ByteBuffer buffer, int size) throws IOException {
        require(buffer, size);
        return switch (size) {
            case 1 -> buffer.get();
            case 2 -> buffer.getShort();
            default -> buffer.getInt();
        };
    }

    private static int readUnsignedIndex(ByteBuffer buffer, int size) throws IOException {
        require(buffer, size);
        return switch (size) {
            case 1 -> Byte.toUnsignedInt(buffer.get());
            case 2 -> Short.toUnsignedInt(buffer.getShort());
            default -> buffer.getInt();
        };
    }

    private static int validParent(int parent, int current) {
        return parent >= 0 && parent < current ? parent : -1;
    }

    private static int[] filled(int length, int value) {
        int[] result = new int[length];
        java.util.Arrays.fill(result, value);
        return result;
    }

    private static void normalizeWeights(float[] values, int offset) {
        float total = values[offset] + values[offset + 1] + values[offset + 2] + values[offset + 3];
        if (total <= 0.00001F) {
            values[offset] = 1.0F;
            return;
        }
        for (int i = 0; i < 4; i++) values[offset + i] /= total;
    }

    private static float finite(float value) throws IOException {
        if (!Float.isFinite(value)) throw new IOException("MMD model contains non-finite values");
        return value;
    }

    private static float clamp01(float value) throws IOException {
        return Math.max(0.0F, Math.min(1.0F, finite(value)));
    }

    private static void require(ByteBuffer buffer, long bytes) throws IOException {
        if (bytes < 0 || bytes > Integer.MAX_VALUE || buffer.remaining() < bytes) {
            throw new IOException("MMD model ended unexpectedly");
        }
    }

    private static void skip(ByteBuffer buffer, long bytes) throws IOException {
        if (bytes < 0 || bytes > Integer.MAX_VALUE) throw new IOException("MMD model section is too large");
        require(buffer, (int) bytes);
        buffer.position(buffer.position() + (int) bytes);
    }
}
