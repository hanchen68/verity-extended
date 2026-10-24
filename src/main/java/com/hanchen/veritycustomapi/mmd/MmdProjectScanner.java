package com.hanchen.veritycustomapi.mmd;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hanchen.veritycustomapi.compat.ForgePaths;
import com.hanchen.veritycustomapi.util.Log;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * MMD 舞蹈项目的目录扫描与项目装配。
 *
 * <p>抽自 {@code MmdPlaybackManager}。抽出的理由：这是**一整段只依赖文件系统、
 * 不依赖播放状态**的逻辑（约 330 行、24 个方法），却和播放头、骨骼绑定、
 * OpenAL 通道挤在同一个类里。结果是「扫描目录发现少了一个项目」这种问题
 * 必须启动游戏、进世界才能查；独立出来后可以对着一个临时目录直接跑。
 *
 * <p>三类项目来源，优先级从高到低：
 * <ol>
 *   <li><b>项目文件夹</b>（{@code config/verity_extended/mmd/<名字>/}）：
 *       内含 vmd / 音频 / 可选的 {@code manifest.json}；</li>
 *   <li><b>散装 VMD</b>（旧格式，直接放根目录）：按单动作项目处理；</li>
 *   <li><b>云端缓存</b>（当前账号已安装的 MMD 资源包）：只在本地未命中时补充。</li>
 * </ol>
 *
 * <p>🔴 一个容易踩空的点：{@link #attachLegacyStageAnimCameras} 只在
 * 「还有项目没有镜头」时才去扫外部 StageAnim 目录——那是递归遍历整个目录树，
 * 无脑每次都跑会让进入界面的耗时随目录规模线性上涨。
 */
public final class MmdProjectScanner {

    private static final Logger LOGGER = LogManager.getLogger("VerityExt-MMD");

    private MmdProjectScanner() {
    }

    static void scanProjectDirectory(Path directory, Map<String, MmdDanceProject> projects, boolean includeLegacy) {
        if (!Files.isDirectory(directory)) return;
        try (Stream<Path> stream = Files.list(directory)) {
            List<Path> entries = stream.sorted((a, b) -> a.getFileName().toString()
                    .compareToIgnoreCase(b.getFileName().toString())).toList();
            for (Path entry : entries) {
                MmdDanceProject project = Files.isDirectory(entry) ? loadFolderProject(entry)
                        : includeLegacy && isVmd(entry) ? loadLegacyProject(entry) : null;
                if (project != null) putProject(projects, project);
            }
        } catch (IOException e) {
            LOGGER.error("扫描 MMD 目录失败: {}", directory, e);
        }
    }

    static MmdDanceProject loadFolderProject(Path directory) {
        List<Path> contents;
        try (Stream<Path> stream = Files.list(directory)) {
            contents = stream.filter(Files::isRegularFile)
                    .sorted((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            LOGGER.error("读取 MMD 项目文件夹失败: {}", directory, e);
            return null;
        }

        List<ClipCandidate> candidates = new ArrayList<>();
        for (Path file : contents) {
            if (!isVmd(file)) continue;
            try {
                candidates.add(new ClipCandidate(file, VmdClip.load(file)));
            } catch (Exception e) {
                LOGGER.error("加载项目内 VMD 失败: {}", file, e);
            }
        }
        if (candidates.isEmpty()) {
            LOGGER.warn("跳过没有 VMD 的项目文件夹: {}", directory.getFileName());
            return null;
        }

        ProjectManifest manifest = readManifest(directory);
        List<ClipCandidate> motions = selectMotions(directory, candidates, manifest);
        if (motions.isEmpty()) {
            LOGGER.warn("跳过没有人物动作轨的项目文件夹: {}", directory.getFileName());
            return null;
        }
        ClipCandidate camera = selectCamera(directory, candidates, manifest);
        Path audio = manifest == null ? selectProjectAudio(directory, contents, motions.get(0).file())
                : resolveManifestFile(directory, manifest.audio(), MmdProjectScanner::isAudio, "audio");
        if (manifest != null && manifest.audio() == null) {
            audio = selectProjectAudio(directory, contents, motions.get(0).file());
        }

        String id = directory.getFileName().toString();
        String displayName = manifest == null || manifest.name() == null || manifest.name().isBlank()
                ? id : manifest.name().trim();
        List<MmdDanceProject.MotionAsset> motionAssets = motions.stream()
                .map(candidate -> new MmdDanceProject.MotionAsset(candidate.file(), candidate.clip())).toList();
        MmdDanceProject project = new MmdDanceProject(id, displayName, directory, motionAssets,
                camera == null ? null : camera.clip(), camera == null ? null : camera.file(), audio, contents,
                manifest == null ? 0.1f : manifest.scale(),
                manifest == null ? MmdDanceProject.LoopMode.ONCE : MmdDanceProject.LoopMode.parse(manifest.loop()),
                false, manifest != null);
        LOGGER.info("加载 MMD 项目 [{}]: {}", id, project.contentSummary());
        return project;
    }

    static List<ClipCandidate> selectMotions(Path directory, List<ClipCandidate> candidates,
                                                      ProjectManifest manifest) {
        if (manifest != null && !manifest.motions().isEmpty()) {
            List<ClipCandidate> selected = new ArrayList<>();
            for (String name : manifest.motions()) {
                ClipCandidate candidate = findCandidate(directory, candidates, name, "motion");
                if (candidate != null && !candidate.clip().getBoneTracks().isEmpty()) selected.add(candidate);
            }
            return selected;
        }
        return candidates.stream().filter(candidate -> !candidate.clip().getBoneTracks().isEmpty())
                .sorted((a, b) -> Integer.compare(motionScore(b), motionScore(a))).toList();
    }

    static ClipCandidate selectCamera(Path directory, List<ClipCandidate> candidates,
                                               ProjectManifest manifest) {
        if (manifest != null && manifest.camera() != null) {
            ClipCandidate selected = findCandidate(directory, candidates, manifest.camera(), "camera");
            return selected != null && selected.clip().hasCameraTrack() ? selected : null;
        }
        return candidates.stream().filter(candidate -> candidate.clip().hasCameraTrack())
                .max((a, b) -> Integer.compare(cameraScore(a), cameraScore(b))).orElse(null);
    }

    static ClipCandidate findCandidate(Path directory, List<ClipCandidate> candidates,
                                               String name, String field) {
        Path selected = resolveManifestFile(directory, name, MmdProjectScanner::isVmd, field);
        if (selected == null) return null;
        for (ClipCandidate candidate : candidates) {
            if (candidate.file().equals(selected)) return candidate;
        }
        return null;
    }

    static Path resolveManifestFile(Path directory, String name,
                                            java.util.function.Predicate<Path> validator, String field) {
        return com.hanchen.veritycustomapi.mmd.MmdManifestParsing.resolveManifestFile(directory, name, validator, field);
    }

    static ProjectManifest readManifest(Path directory) {
        Path file = directory.resolve("manifest.json");
        if (!Files.isRegularFile(file)) return null;
        try (java.io.Reader reader = Files.newBufferedReader(file, java.nio.charset.StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            List<String> motions = new ArrayList<>();
            JsonElement motionElement = root.has("motions") ? root.get("motions") : root.get("motion");
            if (motionElement != null && motionElement.isJsonArray()) {
                for (JsonElement element : motionElement.getAsJsonArray()) {
                    if (element.isJsonPrimitive()) motions.add(element.getAsString());
                }
            } else if (motionElement != null && motionElement.isJsonPrimitive()) {
                motions.add(motionElement.getAsString());
            }
            return new ProjectManifest(jsonString(root, "name"), motions,
                    jsonString(root, "camera"), jsonString(root, "audio"),
                    jsonFloat(root, "scale", 0.1f), jsonString(root, "loop"));
        } catch (Exception e) {
            LOGGER.error("读取 MMD 项目清单失败，将回退自动识别: {}", file, e);
            return null;
        }
    }

    static String jsonString(JsonObject root, String name) {
        return com.hanchen.veritycustomapi.mmd.MmdManifestParsing.jsonString(root, name);
    }

    static float jsonFloat(JsonObject root, String name, float fallback) {
        return com.hanchen.veritycustomapi.mmd.MmdManifestParsing.jsonFloat(root, name, fallback);
    }

    static MmdDanceProject loadLegacyProject(Path vmdFile) {
        try {
            VmdClip clip = VmdClip.load(vmdFile);
            String id = vmdFile.getFileName().toString();
            Path audio = findSiblingAudio(vmdFile);
            List<Path> contents = audio == null ? List.of(vmdFile) : List.of(vmdFile, audio);
            return new MmdDanceProject(id, id, vmdFile.getParent(),
                    List.of(new MmdDanceProject.MotionAsset(vmdFile, clip)),
                    clip.hasCameraTrack() ? clip : null, clip.hasCameraTrack() ? vmdFile : null,
                    audio, contents, 0.1f, MmdDanceProject.LoopMode.ONCE, true, false);
        } catch (Exception e) {
            LOGGER.error("加载旧格式 VMD 失败: {}", vmdFile, e);
            return null;
        }
    }

    static void putProject(Map<String, MmdDanceProject> projects, MmdDanceProject project) {
        if (projects.keySet().stream().anyMatch(key -> key.equalsIgnoreCase(project.id()))) {
            LOGGER.warn("MMD 项目名称冲突，已跳过: {}", project.id());
            return;
        }
        projects.put(project.id(), project);
    }

    static int motionScore(ClipCandidate candidate) {
        int mapped = 0;
        for (String bone : candidate.clip().getBoneTracks().keySet()) {
            if (MmdBoneMap.translate(bone) != null) mapped++;
        }
        int score = mapped * 1000 + candidate.clip().getBoneTracks().size() * 10;
        String name = candidate.file().getFileName().toString().toLowerCase();
        if (name.contains("motion") || name.contains("dance") || name.contains("动作") || name.contains("動作")) score += 100;
        if (name.contains("camera") || name.contains("镜头") || name.contains("カメラ")) score -= 200;
        return score;
    }

    static int cameraScore(ClipCandidate candidate) {
        int score = candidate.clip().getCameraFrames().size();
        if (candidate.clip().getBoneTracks().isEmpty()) score += 100_000;
        String name = candidate.file().getFileName().toString().toLowerCase();
        if (name.contains("camera") || name.contains("cam") || name.contains("镜头") || name.contains("カメラ")) score += 10_000;
        return score;
    }

    static Path selectProjectAudio(Path directory, List<Path> contents, Path motionFile) {
        List<Path> audioFiles = contents.stream().filter(MmdProjectScanner::isAudio)
                .sorted((a, b) -> Integer.compare(audioPriority(a), audioPriority(b))).toList();
        if (audioFiles.isEmpty()) return null;
        String folderBase = directory.getFileName().toString();
        String motionBase = baseName(motionFile);
        for (Path audio : audioFiles) if (baseName(audio).equalsIgnoreCase(folderBase)) return audio;
        for (Path audio : audioFiles) if (baseName(audio).equalsIgnoreCase(motionBase)) return audio;
        return audioFiles.get(0);
    }

    static int audioPriority(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".wav")) return 0;
        if (name.endsWith(".ogg")) return 1;
        return 2;
    }

    static boolean isVmd(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().toLowerCase().endsWith(".vmd");
    }

    static boolean isAudio(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return Files.isRegularFile(path) && (name.endsWith(".wav") || name.endsWith(".ogg") || name.endsWith(".mp3"));
    }

    static String baseName(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    static Path findSiblingAudio(Path vmdPath) {
        String base = baseName(vmdPath);
        try (Stream<Path> stream = Files.list(vmdPath.getParent())) {
            return stream.filter(MmdProjectScanner::isAudio)
                    .filter(path -> baseName(path).equalsIgnoreCase(base))
                    .min((a, b) -> Integer.compare(audioPriority(a), audioPriority(b))).orElse(null);
        } catch (IOException ignored) {
            return null;
        }
    }

    static List<Path> legacyStageAnimRoots() {
        Set<Path> roots = new LinkedHashSet<>();
        Path gameDirectory = ForgePaths.game(".").toAbsolutePath().normalize();
        Path current = gameDirectory;
        for (int depth = 0; current != null && depth < 3; depth++, current = current.getParent()) {
            roots.add(current.resolve("3d-skin").resolve("StageAnim").normalize());
        }
        String userHome = System.getProperty("user.home", "").trim();
        if (!userHome.isEmpty()) {
            Path home = Path.of(userHome).toAbsolutePath().normalize();
            roots.add(home.resolve("3d-skin").resolve("StageAnim").normalize());
            roots.add(home.resolve(".minecraft").resolve("3d-skin").resolve("StageAnim").normalize());
        }
        LOGGER.info("MMD 外部镜头候选目录: {}", roots);
        return List.copyOf(roots);
    }

    static void attachLegacyStageAnimCameras(Map<String, MmdDanceProject> projects, Path stageAnimRoot) {
        if (!Files.isDirectory(stageAnimRoot) || projects.values().stream().allMatch(project -> project.cameraClip() != null)) {
            return;
        }

        List<LegacyCameraSource> sources = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(stageAnimRoot, 2)) {
            List<Path> directories = stream.filter(Files::isDirectory)
                    .sorted((a, b) -> a.toString().compareToIgnoreCase(b.toString())).toList();
            for (Path directory : directories) {
                LegacyCameraSource source = loadLegacyCameraSource(directory);
                if (source != null) sources.add(source);
            }
        } catch (IOException e) {
            LOGGER.warn("扫描旧 StageAnim 镜头目录失败: {}", stageAnimRoot, e);
            return;
        }
        if (sources.isEmpty()) return;

        Map<String, MmdDanceProject> replacements = new HashMap<>();
        for (Map.Entry<String, MmdDanceProject> entry : projects.entrySet()) {
            MmdDanceProject project = entry.getValue();
            if (project.cameraClip() != null) continue;
            LegacyCameraSource source = sources.stream()
                    .filter(candidate -> matchesLegacyCamera(project, candidate))
                    .findFirst().orElse(null);
            if (source == null) continue;

            List<Path> contents = new ArrayList<>(project.contents());
            if (!contents.contains(source.cameraFile())) contents.add(source.cameraFile());
            replacements.put(entry.getKey(), new MmdDanceProject(project.id(), project.displayName(), project.directory(),
                    project.motions(), source.cameraClip(), source.cameraFile(), project.audioFile(), contents,
                    project.cameraScale(), project.loopMode(), project.legacy(), project.manifestConfigured()));
            LOGGER.info("已为 MMD 项目 [{}] 关联旧 StageAnim 镜头: {}", project.id(), source.cameraFile());
        }
        projects.putAll(replacements);
    }

    static LegacyCameraSource loadLegacyCameraSource(Path directory) {
        try (Stream<Path> stream = Files.list(directory)) {
            List<ClipCandidate> candidates = new ArrayList<>();
            for (Path file : stream.filter(MmdProjectScanner::isVmd)
                    .sorted((a, b) -> a.toString().compareToIgnoreCase(b.toString())).toList()) {
                candidates.add(new ClipCandidate(file, VmdClip.load(file)));
            }
            ClipCandidate camera = selectCamera(directory, candidates, null);
            List<Path> motions = candidates.stream()
                    .filter(candidate -> !candidate.clip().getBoneTracks().isEmpty())
                    .map(ClipCandidate::file).toList();
            return camera == null ? null
                    : new LegacyCameraSource(directory.getFileName().toString(), camera.file(), camera.clip(), motions);
        } catch (Exception e) {
            LOGGER.warn("读取旧 StageAnim 镜头项目失败: {}", directory, e);
            return null;
        }
    }

    static boolean matchesLegacyCamera(MmdDanceProject project, LegacyCameraSource source) {
        String projectId = project.id().toLowerCase(java.util.Locale.ROOT);
        String projectBase = projectId.endsWith(".vmd") ? projectId.substring(0, projectId.length() - 4) : projectId;
        String sourceName = source.name().toLowerCase(java.util.Locale.ROOT);
        if (projectBase.equals(sourceName) || project.displayName().equalsIgnoreCase(source.name())) return true;
        if (project.directory() != null && project.directory().getFileName() != null
                && project.directory().getFileName().toString().equalsIgnoreCase(source.name())) return true;
        for (MmdDanceProject.MotionAsset motion : project.motions()) {
            for (Path legacyMotion : source.motionFiles()) {
                if (sameFileContent(motion.file(), legacyMotion)) return true;
            }
        }
        return false;
    }

    static boolean sameFileContent(Path first, Path second) {
        try {
            return Files.size(first) == Files.size(second) && Files.mismatch(first, second) == -1L;
        } catch (IOException ignored) {
            return false;
        }
    }

    record ClipCandidate(Path file, VmdClip clip) {}

    record LegacyCameraSource(String name, Path cameraFile, VmdClip cameraClip, List<Path> motionFiles) {}

    record ProjectManifest(String name, List<String> motions, String camera,
                                   String audio, float scale, String loop) {}}
