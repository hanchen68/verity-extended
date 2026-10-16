package com.hanchen.veritycustomapi.mmd;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 VMD (Vocaloid Motion Data) 二进制解析器。
 * 只支持 VMD 0002/0001：30 字节签名 + 模型名（0002 为 20 字节，0001 为 10 字节），随后依次是骨骼、表情、镜头三段关键帧。
 * 骨骼帧 111 字节、表情帧 23 字节、镜头帧 61 字节；缺失的 morph/camera 段按无处理。
 */
public final class VmdClip {
    public static final float VMD_FPS = 30.0f;

    private final String sourcePath;
    private final Map<String, List<BoneFrame>> boneTracks;
    private final Map<String, List<MorphFrame>> morphTracks;
    private final List<CameraFrame> cameraFrames;
    private final int totalFrames;

    public VmdClip(String sourcePath,
                   Map<String, List<BoneFrame>> boneTracks,
                   Map<String, List<MorphFrame>> morphTracks,
                   List<CameraFrame> cameraFrames,
                   int totalFrames) {
        this.sourcePath = sourcePath;
        this.boneTracks = boneTracks;
        this.morphTracks = morphTracks;
        this.cameraFrames = cameraFrames;
        this.totalFrames = totalFrames;
    }

    public String getSourcePath() { return sourcePath; }

    public Map<String, List<BoneFrame>> getBoneTracks() {
        return Collections.unmodifiableMap(boneTracks);
    }

    public Map<String, List<MorphFrame>> getMorphTracks() {
        return Collections.unmodifiableMap(morphTracks);
    }

    public List<CameraFrame> getCameraFrames() {
        return Collections.unmodifiableList(cameraFrames);
    }

    public boolean hasCameraTrack() {
        return !cameraFrames.isEmpty();
    }

    public int getTotalFrames() { return totalFrames; }

    public float getDurationSeconds() { return totalFrames / VMD_FPS; }

    /**
     * 读取指定时间点（秒）下骨骼的姿态：rotation (x, y, z, w 四元数) + position (x, y, z)。
     * 该骨骼无关键帧时返回 null。
     */
    public BonePose sampleBone(String boneName, float timeSeconds) {
        List<BoneFrame> frames = boneTracks.get(boneName);
        if (frames == null || frames.isEmpty()) return null;
        float framePos = timeSeconds * VMD_FPS;
        int idx = findSegment(frames, framePos);
        BoneFrame a = frames.get(Math.max(0, idx - 1));
        BoneFrame b = frames.get(Math.min(frames.size() - 1, idx));
        float t;
        if (idx == 0) {
            t = 0.0f;
        } else if (idx >= frames.size()) {
            t = 1.0f;
        } else {
            float span = b.frameNumber - a.frameNumber;
            t = span <= 0.0001f ? 0.0f : (framePos - a.frameNumber) / span;
        }
        // VMD 的插值参数保存在目标关键帧中，四条曲线分别控制 X/Y/Z 位移与旋转。
        float tx = bezierWeight(b.interpolation, 0, t);
        float ty = bezierWeight(b.interpolation, 1, t);
        float tz = bezierWeight(b.interpolation, 2, t);
        float tr = bezierWeight(b.interpolation, 3, t);
        float[] q = slerp(a.quat, b.quat, tr);
        float[] pos = lerp3(a.position, b.position, tx, ty, tz);
        return new BonePose(pos, q);
    }

    /** 读取指定时间点（秒）下表情的权重（0-1）。 */
    public float sampleMorph(String morphName, float timeSeconds) {
        List<MorphFrame> frames = morphTracks.get(morphName);
        if (frames == null || frames.isEmpty()) return 0.0f;
        float framePos = timeSeconds * VMD_FPS;
        int idx = findSegment(frames, framePos);
        MorphFrame a = frames.get(Math.max(0, idx - 1));
        MorphFrame b = frames.get(Math.min(frames.size() - 1, idx));
        if (idx == 0) return a.weight;
        if (idx >= frames.size()) return b.weight;
        float span = b.frameNumber - a.frameNumber;
        float t = span <= 0.0001f ? 0.0f : (framePos - a.frameNumber) / span;
        return a.weight + (b.weight - a.weight) * t;
    }

    /** 采样 VMD 镜头：position 是注视中心，rotation 单位为弧度。 */
    public CameraPose sampleCamera(float timeSeconds) {
        if (cameraFrames.isEmpty()) return null;
        float framePos = timeSeconds * VMD_FPS;
        int idx = findSegment(cameraFrames, framePos);
        CameraFrame a = cameraFrames.get(Math.max(0, idx - 1));
        CameraFrame b = cameraFrames.get(Math.min(cameraFrames.size() - 1, idx));
        if (idx == 0) return new CameraPose(a.position, a.rotation, a.distance, a.viewAngle, a.perspective);
        if (idx >= cameraFrames.size()) return new CameraPose(b.position, b.rotation, b.distance, b.viewAngle, b.perspective);
        float span = b.frameNumber - a.frameNumber;
        float t = span <= 0.0001f ? 0.0f : (framePos - a.frameNumber) / span;
        float tx = cameraBezierWeight(b.interpolation, 0, t);
        float ty = cameraBezierWeight(b.interpolation, 1, t);
        float tz = cameraBezierWeight(b.interpolation, 2, t);
        float tr = cameraBezierWeight(b.interpolation, 3, t);
        float td = cameraBezierWeight(b.interpolation, 4, t);
        float tf = cameraBezierWeight(b.interpolation, 5, t);
        float[] position = lerp3(a.position, b.position, tx, ty, tz);
        float[] rotation = new float[]{
                lerpAngle(a.rotation[0], b.rotation[0], tr),
                lerpAngle(a.rotation[1], b.rotation[1], tr),
                lerpAngle(a.rotation[2], b.rotation[2], tr)
        };
        float distance = a.distance + (b.distance - a.distance) * td;
        float viewAngle = a.viewAngle + (b.viewAngle - a.viewAngle) * tf;
        return new CameraPose(position, rotation, distance, viewAngle,
                t < 1.0f ? a.perspective : b.perspective);
    }

    private static <T extends Frame> int findSegment(List<T> frames, float framePos) {
        // 二分查找：返回第一个 frameNumber > framePos 的索引
        int lo = 0, hi = frames.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            T frame = frames.get(mid);
            int n = readFrameNumber(frame);
            if (n <= framePos) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    private static int readFrameNumber(Frame f) {
        return f.frameNumber();
    }

    private static float[] slerp(float[] q1, float[] q2, float t) {
        // q1, q2: [x, y, z, w]
        float[] a = normalizeQuaternion(q1);
        float[] b = normalizeQuaternion(q2);
        float dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
        if (dot < 0.0f) {
            b[0] = -b[0]; b[1] = -b[1]; b[2] = -b[2]; b[3] = -b[3];
            dot = -dot;
        }
        dot = Math.max(-1.0f, Math.min(1.0f, dot));
        if (dot > 0.9995f) {
            return normalizeQuaternion(new float[]{
                    a[0] + t * (b[0] - a[0]),
                    a[1] + t * (b[1] - a[1]),
                    a[2] + t * (b[2] - a[2]),
                    a[3] + t * (b[3] - a[3])
            });
        }
        float theta0 = (float) Math.acos(dot);
        float sin0 = (float) Math.sin(theta0);
        if (Math.abs(sin0) < 1.0e-6f) return a;
        float s1 = (float) Math.sin((1.0f - t) * theta0) / sin0;
        float s2 = (float) Math.sin(t * theta0) / sin0;
        return normalizeQuaternion(new float[]{
                a[0] * s1 + b[0] * s2,
                a[1] * s1 + b[1] * s2,
                a[2] * s1 + b[2] * s2,
                a[3] * s1 + b[3] * s2
        });
    }

    private static float[] normalizeQuaternion(float[] q) {
        float normSquared = q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3];
        if (!Float.isFinite(normSquared) || normSquared < 1.0e-12f) {
            return new float[]{0.0f, 0.0f, 0.0f, 1.0f};
        }
        float inv = 1.0f / (float) Math.sqrt(normSquared);
        return new float[]{q[0] * inv, q[1] * inv, q[2] * inv, q[3] * inv};
    }

    private static float bezierWeight(byte[] interpolation, int channel, float time) {
        if (interpolation == null || interpolation.length < 16 || time <= 0.0f) return Math.max(0.0f, time);
        if (time >= 1.0f) return 1.0f;
        return solveBezier(time,
                unsigned(interpolation[channel]) / 127.0f,
                unsigned(interpolation[channel + 4]) / 127.0f,
                unsigned(interpolation[channel + 8]) / 127.0f,
                unsigned(interpolation[channel + 12]) / 127.0f);
    }

    private static float cameraBezierWeight(byte[] interpolation, int channel, float time) {
        if (interpolation == null || interpolation.length < 24 || time <= 0.0f) return Math.max(0.0f, time);
        if (time >= 1.0f) return 1.0f;
        int offset = channel * 4;
        return solveBezier(time,
                unsigned(interpolation[offset]) / 127.0f,
                unsigned(interpolation[offset + 1]) / 127.0f,
                unsigned(interpolation[offset + 2]) / 127.0f,
                unsigned(interpolation[offset + 3]) / 127.0f);
    }

    private static float solveBezier(float time, float x1, float y1, float x2, float y2) {
        if (x1 < 0.0f || x1 > 1.0f || x2 < 0.0f || x2 > 1.0f
                || y1 < 0.0f || y1 > 1.0f || y2 < 0.0f || y2 > 1.0f) return time;
        float lo = 0.0f;
        float hi = 1.0f;
        for (int i = 0; i < 12; i++) {
            float u = (lo + hi) * 0.5f;
            if (cubicBezier(u, x1, x2) < time) lo = u; else hi = u;
        }
        return cubicBezier((lo + hi) * 0.5f, y1, y2);
    }

    private static float lerpAngle(float a, float b, float t) {
        float delta = b - a;
        while (delta > Math.PI) delta -= (float) (Math.PI * 2.0);
        while (delta < -Math.PI) delta += (float) (Math.PI * 2.0);
        return a + delta * t;
    }

    private static int unsigned(byte value) {
        return value & 0xFF;
    }

    private static float cubicBezier(float t, float p1, float p2) {
        float inv = 1.0f - t;
        return 3.0f * inv * inv * t * p1 + 3.0f * inv * t * t * p2 + t * t * t;
    }

    private static float[] lerp3(float[] a, float[] b, float tx, float ty, float tz) {
        return new float[]{
                a[0] + (b[0] - a[0]) * tx,
                a[1] + (b[1] - a[1]) * ty,
                a[2] + (b[2] - a[2]) * tz
        };
    }

    public record BonePose(float[] position, float[] quaternion) {}

    public interface Frame {
        int frameNumber();
    }

    public record BoneFrame(int frameNumber, float[] position, float[] quat, byte[] interpolation) implements Frame {
        public BoneFrame {
            position = position.clone();
            quat = quat.clone();
            interpolation = interpolation.clone();
        }
    }

    public record MorphFrame(int frameNumber, float weight) implements Frame {}

    public record CameraFrame(int frameNumber, float distance, float[] position, float[] rotation,
                              byte[] interpolation, int viewAngle, boolean perspective) implements Frame {
        public CameraFrame {
            position = position.clone();
            rotation = rotation.clone();
            interpolation = interpolation.clone();
        }
    }

    public record CameraPose(float[] position, float[] rotation, float distance,
                             float viewAngle, boolean perspective) {
        public CameraPose {
            position = position.clone();
            rotation = rotation.clone();
        }
    }

    /**
     * 从文件加载 VMD 并解析骨骼/表情/镜头轨；缺失 morph/camera 段不抛错。
     */
    public static VmdClip load(Path path) throws IOException {
        byte[] data = Files.readAllBytes(path);
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        // 头部：30 字节签名（"Vocaloid Motion Data 0002" / "Vocaloid Motion Data 0001"）
        if (buf.remaining() < 30) {
            throw new IOException("VMD 文件头过短: " + path);
        }
        byte[] headerBytes = new byte[30];
        buf.get(headerBytes);
        String header = new String(headerBytes, StandardCharsets.US_ASCII)
                .replace("\0", "").trim();
        if (!header.startsWith("Vocaloid Motion Data")) {
            throw new IOException("VMD 文件 magic 错误: " + header);
        }
        // 只接受已知版本，避免未知头部导致后续数据整体错位。
        int modelNameLen;
        if (header.contains("0002")) {
            modelNameLen = 20;
        } else if (header.contains("0001")) {
            modelNameLen = 10;
        } else {
            throw new IOException("不支持的 VMD 版本: " + header);
        }
        if (buf.remaining() < modelNameLen) {
            throw new IOException("VMD 文件过早结束（模型名）");
        }
        byte[] modelNameBytes = new byte[modelNameLen];
        buf.get(modelNameBytes);
        // 骨骼段
        if (buf.remaining() < 4) {
            throw new IOException("VMD 文件过早结束（骨骼计数）");
        }
        int boneCount = buf.getInt();
        if (boneCount < 0 || boneCount > 1_000_000) {
            throw new IOException("VMD 骨骼关键帧数量异常: " + boneCount);
        }
        Map<String, List<BoneFrame>> boneTracks = new HashMap<>();
        for (int i = 0; i < boneCount; i++) {
            if (buf.remaining() < 111) {
                throw new IOException("VMD 骨骼关键帧过早结束（" + i + "/" + boneCount + "）");
            }
            byte[] nameBytes = new byte[15];
            buf.get(nameBytes);
            String name = decodeShiftJis(nameBytes).trim();
            int frameNumber = buf.getInt();
            float px = buf.getFloat();
            float py = buf.getFloat();
            float pz = buf.getFloat();
            float qx = buf.getFloat();
            float qy = buf.getFloat();
            float qz = buf.getFloat();
            float qw = buf.getFloat();
            byte[] interpolation = new byte[64];
            buf.get(interpolation);
            boneTracks.computeIfAbsent(name, k -> new ArrayList<>())
                    .add(new BoneFrame(frameNumber,
                            new float[]{px, py, pz},
                            new float[]{qx, qy, qz, qw},
                            interpolation));
        }
        // 表情段
        Map<String, List<MorphFrame>> morphTracks = new HashMap<>();
        if (buf.remaining() >= 4) {
            int morphCount = buf.getInt();
            if (morphCount < 0 || morphCount > 1_000_000) {
                throw new IOException("VMD 表情关键帧数量异常: " + morphCount);
            }
            if ((long) morphCount * 23L > buf.remaining()) {
                throw new IOException("VMD 表情关键帧数据不完整: " + morphCount);
            }
            for (int i = 0; i < morphCount; i++) {
                byte[] nameBytes = new byte[15];
                buf.get(nameBytes);
                String name = decodeShiftJis(nameBytes).trim();
                int frameNumber = buf.getInt();
                float weight = buf.getFloat();
                morphTracks.computeIfAbsent(name, k -> new ArrayList<>())
                        .add(new MorphFrame(frameNumber, weight));
            }
        }
        // 镜头段：每帧 61 字节。旧版或截断文件没有该段时按无镜头处理。
        List<CameraFrame> cameraFrames = new ArrayList<>();
        if (buf.remaining() >= 4) {
            long cameraCount = Integer.toUnsignedLong(buf.getInt());
            if (cameraCount > 1_000_000L || cameraCount > buf.remaining() / 61L) {
                throw new IOException("VMD 镜头关键帧数量异常或数据不完整: " + cameraCount);
            }
            for (int i = 0; i < (int) cameraCount; i++) {
                int frameNumber = buf.getInt();
                float distance = buf.getFloat();
                float px = buf.getFloat();
                float py = buf.getFloat();
                float pz = buf.getFloat();
                float rx = buf.getFloat();
                float ry = buf.getFloat();
                float rz = buf.getFloat();
                byte[] interpolation = new byte[24];
                buf.get(interpolation);
                long viewAngle = Integer.toUnsignedLong(buf.getInt());
                boolean perspective = buf.get() == 0;
                cameraFrames.add(new CameraFrame(frameNumber, distance,
                        new float[]{px, py, pz}, new float[]{rx, ry, rz}, interpolation,
                        (int) Math.min(179L, viewAngle), perspective));
            }
        }
        int total = 0;
        for (List<BoneFrame> track : boneTracks.values()) {
            track.sort((x, y) -> Integer.compare(x.frameNumber, y.frameNumber));
            if (!track.isEmpty()) {
                total = Math.max(total, track.get(track.size() - 1).frameNumber);
            }
        }
        for (List<MorphFrame> track : morphTracks.values()) {
            track.sort((x, y) -> Integer.compare(x.frameNumber, y.frameNumber));
            if (!track.isEmpty()) {
                total = Math.max(total, track.get(track.size() - 1).frameNumber);
            }
        }
        cameraFrames.sort((x, y) -> Integer.compare(x.frameNumber, y.frameNumber));
        if (!cameraFrames.isEmpty()) {
            total = Math.max(total, cameraFrames.get(cameraFrames.size() - 1).frameNumber);
        }
        return new VmdClip(path.toString(), boneTracks, morphTracks, cameraFrames, total);
    }

    /**
     * 解码 Shift-JIS 名称，失败时依次回退 UTF-8 与 ISO-8859-1；VMD 日文骨骼名多为 Shift-JIS。
     */
    private static String decodeShiftJis(byte[] bytes) {
        int end = bytes.length;
        while (end > 0 && bytes[end - 1] == 0) end--;
        try {
            return new String(bytes, 0, end, "Shift_JIS");
        } catch (Exception ignored) {
            try {
                return new String(bytes, 0, end, "UTF-8");
            } catch (Exception ignored2) {
                return new String(bytes, 0, end, java.nio.charset.StandardCharsets.ISO_8859_1);
            }
        }
    }
}
