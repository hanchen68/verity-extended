package com.hanchen.veritycustomapi.mmd;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/** 一个可直接播放的 MMD 文件夹项目。 */
public record MmdDanceProject(
        String id,
        String displayName,
        Path directory,
        List<MotionAsset> motions,
        VmdClip cameraClip,
        Path cameraFile,
        Path audioFile,
        List<Path> contents,
        float cameraScale,
        LoopMode loopMode,
        boolean legacy,
        boolean manifestConfigured) {

    public MmdDanceProject {
        motions = List.copyOf(motions);
        contents = List.copyOf(contents);
        cameraScale = Float.isFinite(cameraScale) && cameraScale > 0.0f ? cameraScale : 0.1f;
        loopMode = loopMode == null ? LoopMode.ONCE : loopMode;
    }

    public VmdClip motionClip() {
        return motions.isEmpty() ? null : motions.get(0).clip();
    }

    public Path motionFile() {
        return motions.isEmpty() ? null : motions.get(0).file();
    }

    public MotionAsset motionForIndex(int index) {
        return motions.get(Math.floorMod(index, motions.size()));
    }

    public float durationSeconds() {
        float duration = cameraClip == null ? 0.0f : cameraClip.getDurationSeconds();
        for (MotionAsset motion : motions) {
            duration = Math.max(duration, motion.clip().getDurationSeconds());
        }
        return duration;
    }

    public String contentSummary() {
        String motion = motions.isEmpty() ? "无动作" : "动作: " + motions.stream()
                .map(asset -> asset.file().getFileName().toString())
                .collect(Collectors.joining(", "));
        String camera = cameraFile == null ? "无镜头" : "镜头: " + cameraFile.getFileName();
        String audio = audioFile == null ? "无音频" : "音频: " + audioFile.getFileName();
        return motion + " · " + camera + " · " + audio;
    }

    public String fileListSummary() {
        return contents.stream().map(path -> path.getFileName().toString()).collect(Collectors.joining(" · "));
    }

    public record MotionAsset(Path file, VmdClip clip) {}

    public enum LoopMode {
        ONCE,
        PROJECT,
        INDEPENDENT;

        public static LoopMode parse(String value) {
            if (value == null || value.isBlank()) return ONCE;
            if ("project".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value)) return PROJECT;
            if ("independent".equalsIgnoreCase(value)) return INDEPENDENT;
            return ONCE;
        }
    }
}
