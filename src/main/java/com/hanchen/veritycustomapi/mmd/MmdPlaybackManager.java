package com.hanchen.veritycustomapi.mmd;

import com.hanchen.veritycustomapi.util.Silent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.compat.ForgePaths;
import com.hanchen.veritycustomapi.compat.RuntimePlatform;
import com.hanchen.veritycustomapi.config.ModConfig;
import com.hanchen.veritycustomapi.mixin.SoundEngineAccessor;
import com.hanchen.veritycustomapi.mixin.SoundManagerAccessor;
import com.hanchen.veritycustomapi.resource.ResourceCacheIndex;
import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.Library;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.BufferUtils;
import software.bernie.geckolib.core.animatable.model.CoreGeoBone;
import software.bernie.geckolib.core.animation.AnimationProcessor;
import software.bernie.geckolib.model.GeoModel;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * MMD 动画播放管理（双端共享）。
 * 每个恶魔实体绑定一个 VmdClip 与播放头（秒），客户端 tick 读取播放头并按 Vmd 关键帧设置 Geckolib 骨骼旋转（骨骼取自 GeoModel.getAnimationProcessor().getBone）。
 * 仅对 varmite.verity.entity.demon.VerityDemonEntity 生效，原版 Verity 不受影响。
 */
public final class MmdPlaybackManager {
    private static final Logger LOGGER = LogManager.getLogger("VerityExt-MMD");
    private static final MmdPlaybackManager INSTANCE = new MmdPlaybackManager();

    public static MmdPlaybackManager get() { return INSTANCE; }

    /** 实体 id -> 播放状态。 */
    private final Map<Integer, PlaybackState> activePlaybacks = new ConcurrentHashMap<>();

    /** 项目 ID（文件夹名或旧 VMD 文件名）-> 可播放的完整舞蹈项目。 */
    private final Map<String, MmdDanceProject> projectCache = new ConcurrentHashMap<>();

    /** 当前播放的项目 ID，供控制界面高亮。 */
    private volatile String currentProjectId;

    /** 桌面端使用 Java Sound Clip；Android/FCL 使用 Minecraft 管理的流式声音通道。 */
    private volatile javax.sound.sampled.Clip currentAudioClip;
    private volatile ChannelAccess.ChannelHandle currentAudioChannel;
    private volatile long currentAudioStartedAtNanos;
    private volatile float currentAudioDurationSeconds;
    private volatile boolean currentAudioLoops;
    private final Object audioLock = new Object();
    private final AtomicLong audioGeneration = new AtomicLong();
    private volatile String currentAudioProjectId;
    private volatile Path currentAudioPath;
    private volatile boolean audioLoading;
    private final ExecutorService audioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Verity-MMD-Audio");
        thread.setDaemon(true);
        return thread;
    });

    /** 播放速度倍率（1.0 = 实时，0.5 = 半速，2.0 = 2 倍速）。可通过 /verity-mmd speed 调整。 */
    private volatile float playbackSpeed = 1.0f;

    /** 播放时是否冻结恶魔 AI。默认 true：禁移、停跳、停目标搜索。 */
    private volatile boolean freezeOnPlay = true;

    /** 记录 MMD 接管前每个实体的 noAi 状态，用于停止播放时恢复。 */
    private final Map<Integer, Boolean> previousNoAi = new ConcurrentHashMap<>();

    /** 播放开始时的位置；冻结开启时每 tick 强制归位，抵消 AI 和服务器位置同步。 */
    private final Map<Integer, Vec3> frozenPositions = new ConcurrentHashMap<>();

    /** 当前镜头绑定的主恶魔；镜头与该实体的播放头严格同步。 */
    private volatile int cameraAnchorEntityId = -1;

    /** 当前等待“按需生成的恶魔”加入世界后自动启动的项目名；null 表示没有 pending。 */
    private volatile String pendingProjectName;
    /** pending 时间戳（ms），超过一定时间后清除，避免永久卡住。 */
    private volatile long pendingProjectExpiresAt;
    /** 已重发生成请求的次数；达到上限后会放弃。 */
    private volatile int pendingRetryCount;

    /** 由本 mod 自动生成的恶魔 ID 集合。删除按钮会清理它们。 */
    private final java.util.Set<Integer> mmdSpawnedDemonIds =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    public String getPendingProject() { return pendingProjectName; }
    /** 登记一个由本 mod 自动生成的恶魔 ID，方便后续一键删除。 */
    public void markSpawnedByMmd(int entityId) { mmdSpawnedDemonIds.add(entityId); }
    /** 当前由本 mod 自动生成的恶魔数量（仅用于 UI 提示）。 */
    public int getSpawnedDemonCount() { return mmdSpawnedDemonIds.size(); }

    private MmdPlaybackManager() {}

    /** 扫描 config/verity_extended/mmd/：每个子文件夹为一个舞蹈项目，根目录散装 VMD 按旧格式加载。 */
    public synchronized void scanAndLoad() {
        Path dir = ForgePaths.config("verity_extended", "mmd");
        LOGGER.info("开始扫描 MMD 项目目录: {}", dir);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOGGER.error("创建 MMD 目录失败: {}", dir, e);
            return;
        }

        Map<String, MmdDanceProject> projects = new HashMap<>();
        scanProjectDirectory(dir, projects, true);
        String apiKey = ConfigManager.getCloudApiKey();
        if (apiKey != null && !apiKey.isBlank()) {
            try {
                scanProjectDirectory(new ResourceCacheIndex(apiKey).installedMmd(), projects, false);
            } catch (Exception e) {
                LOGGER.warn("扫描当前账号的云端 MMD 缓存失败", e);
            }
        }
        List<Path> stageAnimRoots = legacyStageAnimRoots();
        for (Path stageAnimRoot : stageAnimRoots) {
            attachLegacyStageAnimCameras(projects, stageAnimRoot);
        }
        List<String> missingCameras = projects.values().stream()
                .filter(project -> project.cameraClip() == null)
                .map(MmdDanceProject::displayName).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        if (!missingCameras.isEmpty()) {
            // 每次扫描都会算一遍，进一次界面能触发好几次；同样内容没必要重复刷。
            com.hanchen.veritycustomapi.util.Log.throttled("mmd.missing.camera", 60, () ->
                    LOGGER.warn("以下 MMD 项目未找到镜头，将仅播放动作: {}；已搜索 {}",
                            missingCameras, stageAnimRoots));
        }

        projectCache.clear();
        projectCache.putAll(projects);
        LOGGER.info("MMD 扫描完成：{} 个舞蹈项目", projects.size());
    }

    private static void scanProjectDirectory(Path directory, Map<String, MmdDanceProject> projects, boolean includeLegacy) {
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

    private static MmdDanceProject loadFolderProject(Path directory) {
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
                : resolveManifestFile(directory, manifest.audio(), MmdPlaybackManager::isAudio, "audio");
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

    private static List<ClipCandidate> selectMotions(Path directory, List<ClipCandidate> candidates,
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

    private static ClipCandidate selectCamera(Path directory, List<ClipCandidate> candidates,
                                               ProjectManifest manifest) {
        if (manifest != null && manifest.camera() != null) {
            ClipCandidate selected = findCandidate(directory, candidates, manifest.camera(), "camera");
            return selected != null && selected.clip().hasCameraTrack() ? selected : null;
        }
        return candidates.stream().filter(candidate -> candidate.clip().hasCameraTrack())
                .max((a, b) -> Integer.compare(cameraScore(a), cameraScore(b))).orElse(null);
    }

    private static ClipCandidate findCandidate(Path directory, List<ClipCandidate> candidates,
                                               String name, String field) {
        Path selected = resolveManifestFile(directory, name, MmdPlaybackManager::isVmd, field);
        if (selected == null) return null;
        for (ClipCandidate candidate : candidates) {
            if (candidate.file().equals(selected)) return candidate;
        }
        return null;
    }

    private static Path resolveManifestFile(Path directory, String name,
                                            java.util.function.Predicate<Path> validator, String field) {
        return com.hanchen.veritycustomapi.mmd.MmdManifestParsing.resolveManifestFile(directory, name, validator, field);
    }

    private static ProjectManifest readManifest(Path directory) {
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

    private static String jsonString(JsonObject root, String name) {
        return com.hanchen.veritycustomapi.mmd.MmdManifestParsing.jsonString(root, name);
    }

    private static float jsonFloat(JsonObject root, String name, float fallback) {
        return com.hanchen.veritycustomapi.mmd.MmdManifestParsing.jsonFloat(root, name, fallback);
    }

    private static MmdDanceProject loadLegacyProject(Path vmdFile) {
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

    private static void putProject(Map<String, MmdDanceProject> projects, MmdDanceProject project) {
        if (projects.keySet().stream().anyMatch(key -> key.equalsIgnoreCase(project.id()))) {
            LOGGER.warn("MMD 项目名称冲突，已跳过: {}", project.id());
            return;
        }
        projects.put(project.id(), project);
    }

    private static int motionScore(ClipCandidate candidate) {
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

    private static int cameraScore(ClipCandidate candidate) {
        int score = candidate.clip().getCameraFrames().size();
        if (candidate.clip().getBoneTracks().isEmpty()) score += 100_000;
        String name = candidate.file().getFileName().toString().toLowerCase();
        if (name.contains("camera") || name.contains("cam") || name.contains("镜头") || name.contains("カメラ")) score += 10_000;
        return score;
    }

    private static Path selectProjectAudio(Path directory, List<Path> contents, Path motionFile) {
        List<Path> audioFiles = contents.stream().filter(MmdPlaybackManager::isAudio)
                .sorted((a, b) -> Integer.compare(audioPriority(a), audioPriority(b))).toList();
        if (audioFiles.isEmpty()) return null;
        String folderBase = directory.getFileName().toString();
        String motionBase = baseName(motionFile);
        for (Path audio : audioFiles) if (baseName(audio).equalsIgnoreCase(folderBase)) return audio;
        for (Path audio : audioFiles) if (baseName(audio).equalsIgnoreCase(motionBase)) return audio;
        return audioFiles.get(0);
    }

    private static int audioPriority(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        if (name.endsWith(".wav")) return 0;
        if (name.endsWith(".ogg")) return 1;
        return 2;
    }

    private static boolean isVmd(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().toLowerCase().endsWith(".vmd");
    }

    private static boolean isAudio(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return Files.isRegularFile(path) && (name.endsWith(".wav") || name.endsWith(".ogg") || name.endsWith(".mp3"));
    }

    private static String baseName(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static Path findSiblingAudio(Path vmdPath) {
        String base = baseName(vmdPath);
        try (Stream<Path> stream = Files.list(vmdPath.getParent())) {
            return stream.filter(MmdPlaybackManager::isAudio)
                    .filter(path -> baseName(path).equalsIgnoreCase(base))
                    .min((a, b) -> Integer.compare(audioPriority(a), audioPriority(b))).orElse(null);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static List<Path> legacyStageAnimRoots() {
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

    private static void attachLegacyStageAnimCameras(Map<String, MmdDanceProject> projects, Path stageAnimRoot) {
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

    private static LegacyCameraSource loadLegacyCameraSource(Path directory) {
        try (Stream<Path> stream = Files.list(directory)) {
            List<ClipCandidate> candidates = new ArrayList<>();
            for (Path file : stream.filter(MmdPlaybackManager::isVmd)
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

    private static boolean matchesLegacyCamera(MmdDanceProject project, LegacyCameraSource source) {
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

    private static boolean sameFileContent(Path first, Path second) {
        try {
            return Files.size(first) == Files.size(second) && Files.mismatch(first, second) == -1L;
        } catch (IOException ignored) {
            return false;
        }
    }

    private record ClipCandidate(Path file, VmdClip clip) {}

    private record LegacyCameraSource(String name, Path cameraFile, VmdClip cameraClip, List<Path> motionFiles) {}

    private record ProjectManifest(String name, List<String> motions, String camera,
                                   String audio, float scale, String loop) {}

    public Map<String, MmdDanceProject> listProjects() {
        return Collections.unmodifiableMap(projectCache);
    }

    public MmdDanceProject getProject(String name) {
        if (name == null) return null;
        MmdDanceProject direct = projectCache.get(name);
        if (direct != null) return direct;
        for (Map.Entry<String, MmdDanceProject> entry : projectCache.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        }
        return null;
    }

    public float getPlaybackSpeed() {
        return playbackSpeed;
    }

    public void setPlaybackSpeed(float speed) {
        if (speed <= 0.0f) speed = 0.01f;
        if (speed > 10.0f) speed = 10.0f;
        this.playbackSpeed = speed;
    }

    public boolean isFreezeOnPlay() { return freezeOnPlay; }
    public void setFreezeOnPlay(boolean v) { this.freezeOnPlay = v; }

    private static boolean readNoAi(Entity entity) {
        return entity instanceof Mob mob && mob.isNoAi();
    }

    private static void setNoAi(Entity entity, boolean value) {
        if (entity instanceof Mob mob) {
            mob.setNoAi(value);
        }
    }

    public String getCurrentProjectId() {
        return currentProjectId;
    }

    public float getCurrentProgress() {
        if (activePlaybacks.isEmpty()) return 0.0f;
        PlaybackState state = cameraAnchorEntityId >= 0 ? activePlaybacks.get(cameraAnchorEntityId) : null;
        if (state == null) state = activePlaybacks.values().iterator().next();
        if (state == null || state.durationSeconds <= 0.0f) return 0.0f;
        return Math.max(0.0f, Math.min(1.0f, state.playhead / state.durationSeconds));
    }

    public void stopPlaybackForAllDemons() {
        activePlaybacks.clear();
        currentProjectId = null;
        cameraAnchorEntityId = -1;
        frozenPositions.clear();
        // 恢复被冻结的恶魔 AI
        if (!previousNoAi.isEmpty()) {
            try {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                if (mc.level != null) {
                    for (Map.Entry<Integer, Boolean> entry : previousNoAi.entrySet()) {
                        Entity entity = mc.level.getEntity(entry.getKey());
                        if (entity != null && readNoAi(entity) != entry.getValue()) {
                            setNoAi(entity, entry.getValue());
                        }
                    }
                }
            } catch (Throwable t) {
                LOGGER.warn("恢复恶魔 AI 失败: {}", t.toString());
            } finally {
                previousNoAi.clear();
            }
        }
        stopCurrentAudio();
        LOGGER.info("停止所有恶魔实体的 MMD 播放");
    }

    /**
     * 删除本 mod 自动生成的恶魔：优先整合服务器直连（service thread），否则经 C2S 包通知服务端，避免客户端非主线程删除实体。
     * @return 被记录的恶魔数量。
     */
    public int removeSpawnedDemons() {
        if (mmdSpawnedDemonIds.isEmpty()) return 0;
        java.util.List<Integer> ids = new java.util.ArrayList<>(mmdSpawnedDemonIds);
        boolean handled = tryDirectRemoveSpawnedDemons(ids);
        if (!handled) {
            // 联机回退：通过网络通道把删除请求送到服务端
            try {
                com.hanchen.veritycustomapi.network.MmdDemonNetwork.CHANNEL.send(
                        net.minecraftforge.network.PacketDistributor.SERVER.noArg(),
                        new com.hanchen.veritycustomapi.network.MmdRemoveDemonPayload(new java.util.ArrayList<>(ids)));
            } catch (Throwable t) {
                LOGGER.warn("MMD 发送删除恶魔请求失败: {}", t.toString());
            }
        }
        // 客户端这边的播放状态也清掉
        for (int id : ids) {
            stopPlaybackForDemon(id);
        }
        mmdSpawnedDemonIds.clear();
        LOGGER.info("MMD 已清理 {} 个由本 mod 自动生成的恶魔", ids.size());
        return ids.size();
    }

    /** 整合服务器直连删除：在服务端 service.execute 中删除指定 ID 的实体。 */
    private boolean tryDirectRemoveSpawnedDemons(java.util.List<Integer> ids) {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (!mc.hasSingleplayerServer()) return false;
            net.minecraft.server.MinecraftServer server = mc.getSingleplayerServer();
            if (server == null) return false;
            server.execute(() -> com.hanchen.veritycustomapi.network.MmdDemonNetwork.serverSideRemoveDemons(ids));
            return true;
        } catch (Throwable t) {
            LOGGER.warn("整合服务器直连删除恶魔失败: {}", t.toString());
            return false;
        }
    }

    /** 删除 config/verity_extended/mmd/ 下指定名称的舞蹈项目文件夹；返回 true 表示目录原本存在并被清空。 */
    public boolean deleteProject(String projectName) {
        if (projectName == null || projectName.isBlank()) return false;
        MmdDanceProject project = projectCache.get(projectName);
        if (project == null) return false;
        java.nio.file.Path root = project.directory();
        if (root == null) return false;
        try {
            if (!java.nio.file.Files.exists(root)) {
                projectCache.remove(projectName);
                return true;
            }
            // 安全删除：仅删除该目录及其内容
            try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(root)) {
                walk.sorted(java.util.Collections.reverseOrder())
                        .forEach(p -> { try { java.nio.file.Files.deleteIfExists(p); } catch (Throwable ignored) { Silent.ignore("MmdPlaybackManager", ignored); } });
            }
            projectCache.remove(projectName);
            LOGGER.info("MMD 已删除舞蹈项目文件夹: {}", root);
            return true;
        } catch (Throwable t) {
            LOGGER.error("MMD 删除舞蹈项目失败: {}", t.toString());
            return false;
        }
    }

    /** 启动项目音频；桌面使用 Clip，Android/FCL 使用 Minecraft 管理的 OpenAL 流式通道。 */
    private void startAudioForClip(String clipName) {
        MmdDanceProject project = getProject(clipName);
        Path audio = project == null ? null : project.audioFile();
        if (audio == null) {
            stopCurrentAudio();
            LOGGER.info("MMD [{}] 没有项目音频", clipName);
            return;
        }
        audio = audio.toAbsolutePath().normalize();
        if (!Files.isRegularFile(audio)) {
            stopCurrentAudio();
            LOGGER.warn("MMD 音频文件不存在: {}", audio);
            return;
        }
        final Path selectedAudio = audio;
        final long generation;
        synchronized (audioLock) {
            if (clipName.equals(currentAudioProjectId) && selectedAudio.equals(currentAudioPath)
                    && (audioLoading || currentAudioClip != null || currentAudioChannel != null)) {
                return;
            }
            generation = audioGeneration.incrementAndGet();
            closeCurrentAudioLocked();
            currentAudioProjectId = clipName;
            currentAudioPath = selectedAudio;
            audioLoading = true;
        }
        if (RuntimePlatform.isAndroid()) {
            audioExecutor.submit(() -> startMinecraftAudio(project, selectedAudio, generation));
        } else {
            audioExecutor.submit(() -> startDesktopAudio(project, selectedAudio, generation));
        }
    }

    private void startDesktopAudio(MmdDanceProject project, Path selectedAudio, long generation) {
        javax.sound.sampled.Clip clip = null;
        try (javax.sound.sampled.AudioInputStream source =
                     javax.sound.sampled.AudioSystem.getAudioInputStream(selectedAudio.toFile())) {
            javax.sound.sampled.AudioFormat targetFormat = PcmAudioStream.TARGET_FORMAT;
            try (javax.sound.sampled.AudioInputStream pcm =
                         javax.sound.sampled.AudioSystem.getAudioInputStream(targetFormat, source)) {
                clip = javax.sound.sampled.AudioSystem.getClip();
                clip.open(pcm);
            }
            synchronized (audioLock) {
                if (generation != audioGeneration.get()) {
                    closeAudio(clip);
                    return;
                }
                clip.setFramePosition(0);
                if (project.loopMode() != MmdDanceProject.LoopMode.ONCE) {
                    clip.loop(javax.sound.sampled.Clip.LOOP_CONTINUOUSLY);
                } else {
                    clip.start();
                }
                currentAudioClip = clip;
                currentAudioStartedAtNanos = System.nanoTime();
                currentAudioDurationSeconds = clip.getMicrosecondLength() / 1_000_000.0f;
                currentAudioLoops = project.loopMode() != MmdDanceProject.LoopMode.ONCE;
                audioLoading = false;
            }
            LOGGER.info("开始通过 Java Sound 播放 MMD 音频: {}, format={}, frames={}, running={}",
                    selectedAudio, clip.getFormat(), clip.getFrameLength(), clip.isRunning());
        } catch (Throwable error) {
            closeAudio(clip);
            failAudioStart(selectedAudio, generation, error);
        }
    }

    private void startMinecraftAudio(MmdDanceProject project, Path selectedAudio, long generation) {
        PcmAudioStream stream = null;
        ChannelAccess.ChannelHandle handle = null;
        try {
            boolean loops = project.loopMode() != MmdDanceProject.LoopMode.ONCE;
            stream = new PcmAudioStream(selectedAudio, loops);
            Minecraft minecraft = Minecraft.getInstance();
            ChannelAccess channelAccess = ((SoundEngineAccessor) ((SoundManagerAccessor) minecraft.getSoundManager())
                    .verityExtended$getSoundEngine()).verityExtended$getChannelAccess();
            handle = channelAccess.createHandle(Library.Pool.STREAMING).join();
            if (handle == null) throw new IOException("Minecraft 没有可用的流式声音通道");

            PcmAudioStream selectedStream = stream;
            ChannelAccess.ChannelHandle selectedHandle = handle;
            synchronized (audioLock) {
                if (generation != audioGeneration.get()) {
                    selectedStream.close();
                    selectedHandle.execute(Channel::stop);
                    return;
                }
                currentAudioChannel = selectedHandle;
                currentAudioDurationSeconds = selectedStream.durationSeconds();
                currentAudioLoops = loops;
            }
            selectedHandle.execute(channel -> {
                try {
                    synchronized (audioLock) {
                        if (generation != audioGeneration.get() || currentAudioChannel != selectedHandle) {
                            selectedStream.close();
                            channel.stop();
                            return;
                        }
                        channel.setRelative(true);
                        channel.disableAttenuation();
                        channel.setVolume(1.0f);
                        channel.setPitch(1.0f);
                        channel.attachBufferStream(selectedStream);
                        channel.play();
                        currentAudioStartedAtNanos = System.nanoTime();
                        audioLoading = false;
                    }
                    LOGGER.info("开始通过 Minecraft 音频通道播放 MMD 音频: {}, duration={}s, loop={}",
                            selectedAudio, selectedStream.durationSeconds(), loops);
                } catch (Throwable error) {
                    try { selectedStream.close(); } catch (IOException ignored) {}
                    channel.stop();
                    failAudioStart(selectedAudio, generation, error);
                }
            });
        } catch (Throwable error) {
            if (handle != null) handle.execute(Channel::stop);
            if (stream != null) {
                try { stream.close(); } catch (IOException ignored) {}
            }
            failAudioStart(selectedAudio, generation, error);
        }
    }

    private void failAudioStart(Path selectedAudio, long generation, Throwable error) {
        synchronized (audioLock) {
            if (generation == audioGeneration.get()) {
                currentAudioProjectId = null;
                currentAudioPath = null;
                currentAudioChannel = null;
                currentAudioStartedAtNanos = 0L;
                currentAudioDurationSeconds = 0.0f;
                currentAudioLoops = false;
                audioLoading = false;
            }
        }
        LOGGER.error("播放音频失败（MMD 将回退墙钟时间轴）: {}", selectedAudio, error);
    }

    private void stopCurrentAudio() {
        synchronized (audioLock) {
            audioGeneration.incrementAndGet();
            closeCurrentAudioLocked();
            currentAudioProjectId = null;
            currentAudioPath = null;
            audioLoading = false;
        }
    }

    private AudioTimeline currentAudioTimeline(String projectId) {
        synchronized (audioLock) {
            if (projectId == null || !projectId.equals(currentAudioProjectId)) return AudioTimeline.NONE;
            if (audioLoading) return AudioTimeline.LOADING;
            javax.sound.sampled.Clip clip = currentAudioClip;
            if (clip != null && clip.isOpen()) {
                float seconds = Math.max(0.0f, clip.getMicrosecondPosition() / 1_000_000.0f);
                boolean finished = clip.getFrameLength() > 0 && clip.getFramePosition() >= clip.getFrameLength() - 1;
                return new AudioTimeline(true, false, finished, seconds);
            }
            ChannelAccess.ChannelHandle channel = currentAudioChannel;
            if (channel == null || currentAudioStartedAtNanos == 0L) return AudioTimeline.NONE;
            float elapsed = Math.max(0.0f, (System.nanoTime() - currentAudioStartedAtNanos) / 1_000_000_000.0f);
            boolean stopped = channel.isStopped();
            if (currentAudioLoops) {
                if (stopped) return AudioTimeline.NONE;
                float seconds = currentAudioDurationSeconds > 0.0f ? elapsed % currentAudioDurationSeconds : elapsed;
                return new AudioTimeline(true, false, false, seconds);
            }
            boolean finished = stopped || currentAudioDurationSeconds > 0.0f && elapsed >= currentAudioDurationSeconds;
            return new AudioTimeline(true, false, finished, elapsed);
        }
    }

    private void closeCurrentAudioLocked() {
        javax.sound.sampled.Clip clip = currentAudioClip;
        currentAudioClip = null;
        closeAudio(clip);
        ChannelAccess.ChannelHandle channel = currentAudioChannel;
        currentAudioChannel = null;
        if (channel != null && !channel.isStopped()) channel.execute(Channel::stop);
        currentAudioStartedAtNanos = 0L;
        currentAudioDurationSeconds = 0.0f;
        currentAudioLoops = false;
    }

    private static void closeAudio(javax.sound.sampled.Clip clip) {
        if (clip == null) return;
        try {
            clip.stop();
            clip.flush();
            clip.close();
        } catch (Throwable ignored) { Silent.ignore("MmdPlaybackManager", ignored); }
    }

    private static final class PcmAudioStream implements AudioStream {
        private static final javax.sound.sampled.AudioFormat TARGET_FORMAT = new javax.sound.sampled.AudioFormat(
                javax.sound.sampled.AudioFormat.Encoding.PCM_SIGNED, 44100.0f, 16, 2, 4, 44100.0f, false);
        private final Path path;
        private final boolean loop;
        private final float durationSeconds;
        private javax.sound.sampled.AudioInputStream stream;
        private boolean closed;

        private PcmAudioStream(Path path, boolean loop) throws Exception {
            this.path = path;
            this.loop = loop;
            this.durationSeconds = audioDurationSeconds(path);
            reopen();
        }

        @Override
        public javax.sound.sampled.AudioFormat getFormat() {
            return TARGET_FORMAT;
        }

        @Override
        public synchronized ByteBuffer read(int requestedBytes) throws IOException {
            if (closed || requestedBytes <= 0) return null;
            int frameSize = TARGET_FORMAT.getFrameSize();
            int capacity = Math.max(frameSize, requestedBytes - requestedBytes % frameSize);
            ByteBuffer output = BufferUtils.createByteBuffer(capacity);
            byte[] buffer = new byte[Math.min(16384, capacity)];
            int consecutiveEnds = 0;
            while (output.hasRemaining()) {
                int wanted = Math.min(buffer.length, output.remaining());
                int read = stream.read(buffer, 0, wanted);
                if (read > 0) {
                    output.put(buffer, 0, read);
                    consecutiveEnds = 0;
                    continue;
                }
                if (!loop || ++consecutiveEnds > 1) break;
                try {
                    reopen();
                } catch (Exception error) {
                    throw error instanceof IOException io ? io : new IOException("重新打开循环音频失败", error);
                }
            }
            if (output.position() == 0) return null;
            output.flip();
            return output;
        }

        @Override
        public synchronized void close() throws IOException {
            closed = true;
            if (stream != null) {
                stream.close();
                stream = null;
            }
        }

        private synchronized void reopen() throws Exception {
            if (stream != null) stream.close();
            javax.sound.sampled.AudioInputStream source =
                    javax.sound.sampled.AudioSystem.getAudioInputStream(path.toFile());
            try {
                stream = javax.sound.sampled.AudioSystem.getAudioInputStream(TARGET_FORMAT, source);
            } catch (Throwable error) {
                source.close();
                throw error;
            }
        }

        private float durationSeconds() {
            return durationSeconds;
        }

        private static float audioDurationSeconds(Path path) {
            try {
                javax.sound.sampled.AudioFileFormat format = javax.sound.sampled.AudioSystem
                        .getAudioFileFormat(path.toFile());
                Object duration = format.properties().get("duration");
                if (duration instanceof Number number && number.longValue() > 0L) {
                    return number.longValue() / 1_000_000.0f;
                }
                javax.sound.sampled.AudioFormat audioFormat = format.getFormat();
                if (format.getFrameLength() > 0 && audioFormat.getFrameRate() > 0.0f) {
                    return format.getFrameLength() / audioFormat.getFrameRate();
                }
            } catch (Throwable ignored) { Silent.ignore("MmdPlaybackManager", ignored); }
            return 0.0f;
        }
    }

    public void stopPlaybackForDemon(int entityId) {
        activePlaybacks.remove(entityId);
        frozenPositions.remove(entityId);
        Boolean previous = previousNoAi.remove(entityId);
        if (previous != null) {
            try {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                Entity entity = mc.level == null ? null : mc.level.getEntity(entityId);
                if (entity != null && readNoAi(entity) != previous) setNoAi(entity, previous);
            } catch (Throwable ignored) { Silent.ignore("MmdPlaybackManager", ignored); }
        }
        if (cameraAnchorEntityId == entityId) selectCameraAnchor();
        if (activePlaybacks.isEmpty()) {
            currentProjectId = null;
            cameraAnchorEntityId = -1;
            stopCurrentAudio();
        }
    }

    public boolean isPlaying(int entityId) {
        return activePlaybacks.containsKey(entityId);
    }

    /** 在客户端 tick 中推进播放头，与渲染帧率解耦。 */
    public void tickClient() {
        // 处理“按需生成恶魔”的 S2C 回执
        com.hanchen.veritycustomapi.network.ClientMmdAckQueue.flushIfReady();
        // pending 超时：超时后判定是否需要再发一次生成请求
        if (pendingProjectName != null && System.currentTimeMillis() > pendingProjectExpiresAt) {
            if (pendingRetryCount > 0
                    && com.hanchen.veritycustomapi.config.ModConfig.AI_DEMON_AUTO_SPAWN.get()) {
                pendingRetryCount--;
                int retryMs = com.hanchen.veritycustomapi.config.ModConfig.AI_DEMON_SPAWN_RETRY_MS.get();
                pendingProjectExpiresAt = System.currentTimeMillis() + retryMs;
                // 同样：优先整合服务器直连，回退到 SimpleChannel
                boolean handled = tryDirectIntegratedSpawn(pendingProjectName, net.minecraft.client.Minecraft.getInstance().level);
                if (!handled) {
                    try {
                        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                        com.hanchen.veritycustomapi.network.MmdDemonNetwork.CHANNEL.send(
                                net.minecraftforge.network.PacketDistributor.SERVER.noArg(),
                                new com.hanchen.veritycustomapi.network.MmdSpawnDemonPayload(-1,
                                        mc.player.position(),
                                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("verity", "verity_demon")));
                    } catch (Throwable t) {
                        LOGGER.warn("MMD 重发生成请求失败: {}", t.toString());
                    }
                }
                LOGGER.warn("MMD 等待恶魔 ACK 超时，已重发一次生成请求（剩余重试 {} 次）", pendingRetryCount);
            } else {
                LOGGER.warn("MMD 等待恶魔 ACK 超时且已用尽重试，放弃项目 [{}]", pendingProjectName);
                pendingProjectName = null;
            }
        }
        if (activePlaybacks.isEmpty()) return;
        removeMissingEntities();
        if (activePlaybacks.isEmpty()) return;
        lockFrozenEntities();
        long now = System.nanoTime();
        boolean playbackFinished = false;
        for (PlaybackState state : activePlaybacks.values()) {
            AudioTimeline audioTimeline = currentAudioTimeline(state.projectId);
            if (audioTimeline.loading()) {
                state.playhead = 0.0f;
                state.lastTickNanos = now;
                continue;
            }
            if (audioTimeline.active()) {
                state.playhead = audioTimeline.seconds();
                state.lastTickNanos = now;
                if (audioTimeline.finished() && state.loopMode == MmdDanceProject.LoopMode.ONCE) {
                    playbackFinished = true;
                    break;
                }
            } else {
                if (state.lastTickNanos == 0L) {
                    state.lastTickNanos = now;
                    continue;
                }
                float dt = (now - state.lastTickNanos) / 1_000_000_000.0f;
                state.lastTickNanos = now;
                if (dt < 0.0f) dt = 0.0f;
                if (dt > 0.1f) dt = 0.1f;
                state.playhead += dt * playbackSpeed;
            }
            float duration = state.durationSeconds;
            if (state.loopMode == MmdDanceProject.LoopMode.ONCE
                    && (duration <= 0.0f || state.playhead >= duration)) {
                playbackFinished = true;
                break;
            } else if (state.loopMode == MmdDanceProject.LoopMode.PROJECT && duration > 0.0f
                    && state.playhead >= duration) {
                state.playhead %= duration;
            } else if (state.loopMode == MmdDanceProject.LoopMode.INDEPENDENT) {
                // 独立循环：状态播放头持续推进，每条轨在 sampleTime 中独立取模。
                if (state.playhead > 1.0e7f) state.playhead %= 1.0e7f;
            }
        }
        if (playbackFinished) stopPlaybackForAllDemons();
    }

    private void removeMissingEntities() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null) {
            stopPlaybackForAllDemons();
            return;
        }
        for (Integer entityId : new ArrayList<>(activePlaybacks.keySet())) {
            if (mc.level.getEntity(entityId) == null) stopPlaybackForDemon(entityId);
        }
    }

    private void selectCameraAnchor() {
        cameraAnchorEntityId = -1;
        for (Map.Entry<Integer, PlaybackState> entry : activePlaybacks.entrySet()) {
            PlaybackState state = entry.getValue();
            if (state.cameraClip != null && state.cameraClip.hasCameraTrack()) {
                cameraAnchorEntityId = entry.getKey();
                return;
            }
        }
    }

    private void lockFrozenEntities() {
        if (frozenPositions.isEmpty()) return;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null) return;
        for (Map.Entry<Integer, Vec3> entry : frozenPositions.entrySet()) {
            Entity entity = mc.level.getEntity(entry.getKey());
            PlaybackState state = activePlaybacks.get(entry.getKey());
            if (entity == null || state == null) {
                activePlaybacks.remove(entry.getKey());
                frozenPositions.remove(entry.getKey());
                previousNoAi.remove(entry.getKey());
                if (cameraAnchorEntityId == entry.getKey()) cameraAnchorEntityId = -1;
                continue;
            }
            Vec3 anchor = entry.getValue();
            setNoAi(entity, true);
            entity.setDeltaMovement(Vec3.ZERO);
            entity.setPos(anchor.x, anchor.y, anchor.z);
            entity.xo = anchor.x;
            entity.yo = anchor.y;
            entity.zo = anchor.z;
            lockEntityRotation(entity, state.lockedYaw);
        }
    }

    /**
     * MMD 播放是否可用：多人游戏下禁用，单机与自开局域网世界（hasSingleplayerServer 为 true）可用。
     * 禁用原因：MMD 需服务端生成展示实体并由客户端驱动骨骼，要求双端装本 mod 且版本一致。
     */
    public static boolean isPlaybackAvailable() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return false;
            return mc.hasSingleplayerServer() || mc.getCurrentServer() == null;
        } catch (Throwable ignored) {
            // 专用服务端没有客户端类，取不到就当作不可用
            return false;
        }
    }

    /** 多人游戏下 MMD 被禁用时的统一提示文案。 */
    public static final String MULTIPLAYER_DISABLED_MESSAGE =
            "MMD 播放已在多人游戏下禁用，请在单人世界（或自己开的局域网世界）中使用。";

    /** 对给定世界中所有恶魔实体启动播放；调用方负责传入合适的物理端 Level（客户端用 Minecraft.getInstance().level）。 */
    public int startPlaybackForDemons(Level level, String projectName) {
        if (!isPlaybackAvailable()) {
            LOGGER.info("MMD 播放被拒绝：当前处于多人游戏，已按设计禁用");
            return 0;
        }
        MmdDanceProject project = getProject(projectName);
        if (project == null) {
            LOGGER.warn("未找到 MMD 项目: {}", projectName);
            return 0;
        }
        if (level == null || project.motions().isEmpty()) return 0;
        int count = 0;
        java.util.List<Entity> all = getAllEntities(level);
        all.sort((a, b) -> Integer.compare(a.getId(), b.getId()));
        LOGGER.info("startPlaybackForDemons: level={} entities={} isClient={}",
                level, all.size(), level.isClientSide());
        for (Entity e : all) {
            String cname = e.getClass().getName();
            boolean isDemon = isDemonEntity(e);
            if (cname.toLowerCase().contains("demon") || cname.toLowerCase().contains("verity")) {
                LOGGER.info("  candidate: id={} class={} isDemon={} name={}",
                        e.getId(), cname, isDemon, e.getName().getString());
            }
            if (isDemon) {
                if (freezeOnPlay) {
                    boolean prev = readNoAi(e);
                    previousNoAi.putIfAbsent(e.getId(), prev);
                    frozenPositions.putIfAbsent(e.getId(), e.position());
                    setNoAi(e, true);
                    e.setDeltaMovement(Vec3.ZERO);
                } else {
                    frozenPositions.remove(e.getId());
                    Boolean previous = previousNoAi.remove(e.getId());
                    if (previous != null && readNoAi(e) != previous) setNoAi(e, previous);
                }
                MmdDanceProject.MotionAsset motion = project.motionForIndex(count);
                activePlaybacks.put(e.getId(), newPlaybackState(project, motion, e.getYRot()));
                if (cameraAnchorEntityId < 0 && project.cameraClip() != null) {
                    cameraAnchorEntityId = e.getId();
                }
                count++;
            }
        }
        if (count > 0) {
            currentProjectId = project.id();
            startAudioForClip(project.id());
        } else if (level.isClientSide()) {
            // 客户端世界中没有任何恶魔：尝试让服务器按需生成一只。
            sendSpawnRequestIfNeeded(project.id(), level);
        }
        LOGGER.info("启动 MMD 项目 [{}]，作用于 {} 个恶魔实体", project.id(), count);
        return count;
    }

    private static void sendSpawnRequestIfNeeded(String projectName, Level level) {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc.player == null || mc.player.level() != level) return;
            // 标记 pending 项目；超时时间走配置（默认 8 秒，可调）
            MmdPlaybackManager manager = get();
            manager.pendingProjectName = projectName;
            int retryMs = com.hanchen.veritycustomapi.config.ModConfig.AI_DEMON_SPAWN_RETRY_MS.get();
            manager.pendingProjectExpiresAt = System.currentTimeMillis() + retryMs;
            manager.pendingRetryCount = 1;
            // 单机整合服务器与客户端同 JVM，Forge 的 SimpleChannel 有时不会把 C2S 消息派发到服务端 handler，
            // 因此优先同 JVM 直调，最后才回退到网络通道。
            boolean handled = tryDirectIntegratedSpawn(projectName, level);
            if (!handled) {
                com.hanchen.veritycustomapi.network.MmdDemonNetwork.CHANNEL.send(
                        net.minecraftforge.network.PacketDistributor.SERVER.noArg(),
                        new com.hanchen.veritycustomapi.network.MmdSpawnDemonPayload(-1,
                                mc.player.position(),
                                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("verity", "verity_demon")));
                LOGGER.info("MMD 已通过 SimpleChannel 请求服务器按需生成恶魔实体（项目 [{}]，超时 {}ms）", projectName, retryMs);
            } else {
                LOGGER.info("MMD 已通过整合服务器直连生成恶魔（项目 [{}]）", projectName);
            }
        } catch (Throwable t) {
            LOGGER.warn("发送 MMD 生成恶魔请求失败: {}", t.toString());
        }
    }

    /**
     * 单机整合服务器下客户端与服务端同 JVM，可直接经 server.execute 调用生成逻辑以绕过 SimpleChannel；
     * 返回 false 表示非单机，需走网络通道。
     */
    private static boolean tryDirectIntegratedSpawn(String projectName, Level level) {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (!mc.hasSingleplayerServer()) return false;
            net.minecraft.server.MinecraftServer server = mc.getSingleplayerServer();
            if (server == null) return false;
            net.minecraft.server.level.ServerPlayer splayer = server.getPlayerList().getPlayer(mc.player.getUUID());
            if (splayer == null) return false;
            // 直接派发到服务端线程；同 JVM 直接调用即可
            server.execute(() -> {
                try {
                    com.hanchen.veritycustomapi.network.MmdDemonNetwork.serverSideSpawnDemon(
                            splayer,
                            net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("verity", "verity_demon"),
                            mc.player.position());
                } catch (Throwable t) {
                    LOGGER.warn("整合服务器直接生成恶魔失败: {}", t.toString());
                }
            });
            return true;
        } catch (Throwable t) {
            LOGGER.warn("整合服务器直连检测失败: {}", t.toString());
            return false;
        }
    }

    /** 客户端收到服务端 spawn ack 后为单只恶魔启动播放。 */
    public void tryStartForSingleDemon(int entityId, String projectName) {
        if (System.currentTimeMillis() > pendingProjectExpiresAt) {
            LOGGER.info("MMD pending 已过期（{}ms），放弃自动播放", projectName);
            pendingProjectName = null;
            return;
        }
        // 登记这些自动生成的实体，供"删除恶魔"按钮清理。
        if (entityId > 0) mmdSpawnedDemonIds.add(entityId);
        boolean ok = startPlaybackForDemon(entityId, projectName);
        if (ok) {
            LOGGER.info("MMD 收到服务器生成的恶魔 id={}，已加入项目 [{}]", entityId, projectName);
            pendingProjectName = null;
        } else {
            LOGGER.warn("MMD 恶魔 id={} 暂不可用，留待下次 tick 重试", entityId);
            // 不清 pending，等下次 flush 再尝试
            pendingProjectName = projectName;
            pendingProjectExpiresAt = System.currentTimeMillis() + 3_000L;
            // 本帧未取到实体，回滚登记以免误删其他恶魔
            mmdSpawnedDemonIds.remove(entityId);
        }
    }

    /** 通用 Level 全实体枚举：客户端先试 entitiesForRendering()，再回退到以玩家为中心的 4096 方块 AABB 查询。 */
    @SuppressWarnings("unchecked")
    private static java.util.List<Entity> getAllEntities(Level level) {
        // 客户端：尝试 entitiesForRendering
        if (level.isClientSide()) {
            try {
                java.lang.reflect.Method m = level.getClass().getMethod("entitiesForRendering");
                Object result = m.invoke(level);
                if (result instanceof java.lang.Iterable<?> iter) {
                    java.util.List<Entity> list = new java.util.ArrayList<>();
                    for (Object o : iter) list.add((Entity) o);
                    if (!list.isEmpty()) return list;
                }
            } catch (Throwable ignored) { Silent.ignore("MmdPlaybackManager", ignored); }
        }
        // AABB 大范围回退：以当前玩家位置为中心 4096 方块，遍历所有已加载实体
        try {
            Entity reference = null;
            if (level.isClientSide()) {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                reference = mc.player;
            }
            AABB box;
            if (reference != null) {
                BlockPos center = reference.blockPosition();
                box = new AABB(center).inflate(4096, 4096, 4096);
            } else {
                box = new AABB(0, 0, 0, 0, 0, 0).inflate(4096, 4096, 4096);
            }
            java.util.List<Entity> result = level.getEntities((Entity) null, box, e -> true);
            return result;
        } catch (Throwable t) {
            LOGGER.warn("getAllEntities AABB 回退失败: {}", t.toString());
        }
        return java.util.Collections.emptyList();
    }

    public boolean startPlaybackForDemon(int entityId, String projectName) {
        if (!isPlaybackAvailable()) return false;
        MmdDanceProject project = getProject(projectName);
        if (project == null || project.motions().isEmpty()) return false;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        Entity entity = mc.level == null ? null : mc.level.getEntity(entityId);
        if (entity == null) return false;
        float yaw = entity.getYRot();
        if (freezeOnPlay) {
            previousNoAi.putIfAbsent(entityId, readNoAi(entity));
            frozenPositions.putIfAbsent(entityId, entity.position());
            setNoAi(entity, true);
            entity.setDeltaMovement(Vec3.ZERO);
        } else {
            frozenPositions.remove(entityId);
            Boolean previous = previousNoAi.remove(entityId);
            if (previous != null && readNoAi(entity) != previous) setNoAi(entity, previous);
        }
        activePlaybacks.put(entityId, newPlaybackState(project, project.motionForIndex(0), yaw));
        currentProjectId = project.id();
        if (cameraAnchorEntityId < 0 && project.cameraClip() != null) cameraAnchorEntityId = entityId;
        startAudioForClip(project.id());
        return true;
    }

    public PlaybackState getPlaybackState(int entityId) {
        return activePlaybacks.get(entityId);
    }

    public PlaybackState newPlaybackState(MmdDanceProject project, MmdDanceProject.MotionAsset motion,
                                          float lockedYaw) {
        VmdClip clip = motion.clip();
        PlaybackState state = new PlaybackState(project.id(), clip, project.cameraClip(), project.durationSeconds(),
                project.cameraScale(), project.loopMode(), 0.0f, lockedYaw);
        Map<String, String> map = new HashMap<>();
        for (String mmdName : clip.getBoneTracks().keySet()) {
            String demon = MmdBoneMap.translate(mmdName);
            if (demon != null) map.put(mmdName, demon);
        }
        state.boneAssignments = map;

        Map<String, MorphBinding> morphBindings = new HashMap<>();
        for (String morphName : clip.getMorphTracks().keySet()) {
            MorphBinding binding = mapMorph(morphName);
            if (binding != null) morphBindings.put(morphName, binding);
        }
        state.morphBindings = morphBindings;
        LOGGER.info("MMD 骨骼映射: 输入 {} 轨, 命中 {}; 表情 {} 轨, 命中 {}",
                clip.getBoneTracks().size(), map.size(),
                clip.getMorphTracks().size(), morphBindings.size());
        return state;
    }

    /** 渲染 tick 钩子：GeoModel 应用动画后叠加 VMD 角度，由 mixin 在 VerityDemonModel.setCustomAnimations 末尾调用。 */
    public void applyToDemonModel(Entity entity, GeoModel<?> model) {
        if (entity == null || model == null) return;
        PlaybackState state = activePlaybacks.get(entity.getId());
        if (state == null) return;



        AnimationProcessor<?> processor;
        try {
            processor = model.getAnimationProcessor();
        } catch (Throwable t) {
            return;
        }
        if (processor == null) return;

        // 先清除上一渲染帧留下的临时表情，再应用当前骨骼轨和表情。
        resetMorphBones(processor, state);
        for (Map.Entry<String, String> e : state.boneAssignments.entrySet()) {
            String mmdName = e.getKey();
            String demonName = e.getValue();
            VmdClip.BonePose pose = state.clip.sampleBone(mmdName, sampleTime(state, state.clip));
            if (pose == null) continue;
            CoreGeoBone bone = state.boneCache.get(demonName);
            if (bone == null) {
                try {
                    bone = processor.getBone(demonName);
                } catch (Throwable ignored) {
                    continue;
                }
                if (bone == null) continue;
                state.boneCache.put(demonName, bone);
            }
            // MMD 四元数 -> Geckolib 欧拉（弧度，XYZ 顺序，与 Geckolib 默认一致）
            float[] euler = quatToEuler(pose.quaternion());
            bone.setRotX(euler[0]);
            // Body 是模型根骨；禁用它的 Y 轴可防止动作轨带着整只恶魔乱转。
            bone.setRotY("Body".equals(demonName) ? 0.0f : euler[1]);
            bone.setRotZ(euler[2]);
        }
        applyMorphs(processor, state);
    }

    private void applyMorphs(AnimationProcessor<?> processor, PlaybackState state) {
        if (state.morphBindings.isEmpty()) return;
        float time = sampleTime(state, state.clip);
        state.appliedMorphBones.clear();
        for (Map.Entry<String, MorphBinding> entry : state.morphBindings.entrySet()) {
            float weight = Math.max(0.0f, Math.min(1.0f, state.clip.sampleMorph(entry.getKey(), time)));
            MorphBinding binding = entry.getValue();
            String boneName = binding.boneName();
            CoreGeoBone bone = state.boneCache.get(boneName);
            if (bone == null) {
                try {
                    bone = processor.getBone(boneName);
                } catch (Throwable ignored) {
                    continue;
                }
                if (bone == null) continue;
                state.boneCache.put(boneName, bone);
            }
            if (binding.type() == MorphType.JAW) {
                bone.setRotX(weight * 0.35f);
            } else {
                bone.setScaleX(1.0f + weight * binding.scaleX());
                bone.setScaleY(Math.max(0.05f, 1.0f + weight * binding.scaleY()));
                bone.setScaleZ(1.0f);
            }
            state.appliedMorphBones.add(boneName);
        }
    }

    private void resetMorphBones(AnimationProcessor<?> processor, PlaybackState state) {
        if (state.appliedMorphBones.isEmpty()) return;
        for (String boneName : state.appliedMorphBones) {
            CoreGeoBone bone = state.boneCache.get(boneName);
            if (bone == null) continue;
            try {
                bone.setScaleX(1.0f);
                bone.setScaleY(1.0f);
                bone.setScaleZ(1.0f);
                bone.setRotX(0.0f);
            } catch (Throwable ignored) { Silent.ignore("MmdPlaybackManager", ignored); }
        }
    }

    private static MorphBinding mapMorph(String name) {
        String normalized = name == null ? "" : name.trim().toLowerCase();
        if (normalized.isEmpty()) return null;
        if (normalized.contains("笑") || normalized.contains("にこ") || normalized.contains("grin")
                || normalized.contains("smile")) {
            return new MorphBinding("grin", MorphType.SCALE, 0.15f, 0.10f);
        }
        if (normalized.equals("あ") || normalized.equals("い") || normalized.equals("う")
                || normalized.equals("え") || normalized.equals("お")
                || normalized.contains("mouth") || normalized.contains("口")
                || normalized.contains("lips") || normalized.contains("kuchi")) {
            return new MorphBinding("mouth", MorphType.SCALE, 0.15f, -0.70f);
        }
        if (normalized.contains("jaw") || normalized.contains("下顎") || normalized.contains("下颚")) {
            return new MorphBinding("jaw", MorphType.JAW, 0.0f, 0.0f);
        }
        return null;
    }

    private float sampleTime(PlaybackState state, VmdClip clip) {
        float time = state.playhead;
        if (state.loopMode == MmdDanceProject.LoopMode.PROJECT) {
            float duration = state.durationSeconds;
            if (duration > 0.0f && time >= duration) time %= duration;
        } else {
            float duration = clip.getDurationSeconds();
            if (duration > 0.0f) time %= duration;
        }
        return time;
    }

    public CameraSample sampleActiveCamera() {
        int entityId = cameraAnchorEntityId;
        if (entityId < 0) return null;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null) return null;
        Entity anchor = mc.level.getEntity(entityId);
        PlaybackState state = activePlaybacks.get(entityId);
        if (anchor == null || state == null || state.cameraClip == null || !state.cameraClip.hasCameraTrack()) {
            cameraAnchorEntityId = -1;
            return null;
        }
        VmdClip.CameraPose pose = state.cameraClip.sampleCamera(sampleTime(state, state.cameraClip));
        if (pose == null) return null;
        return toMinecraftCamera(anchor.position(), state.lockedYaw, pose, state.cameraScale);
    }

    private static CameraSample toMinecraftCamera(Vec3 anchor, float anchorYaw, VmdClip.CameraPose pose,
                                                  float configuredScale) {
        final double scale = configuredScale;
        double yawRad = Math.toRadians(anchorYaw);
        float[] center = pose.position();
        double localX = center[0] * scale;
        double localZ = -center[2] * scale;
        Vec3 target = anchor.add(
                localX * Math.cos(yawRad) - localZ * Math.sin(yawRad),
                ModConfig.MMD_CAMERA_HEIGHT.get() + center[1] * scale,
                localX * Math.sin(yawRad) + localZ * Math.cos(yawRad));

        float[] rotation = pose.rotation();
        double distance = pose.distance() * scale;
        double cosPitch = Math.cos(rotation[0]);
        // VMD 的 distance 是目标点到镜头的距离；镜头位于相机朝向的反方向。
        double offsetX = -Math.sin(rotation[1]) * cosPitch * distance;
        double offsetY = Math.sin(rotation[0]) * distance;
        double offsetZ = -Math.cos(rotation[1]) * cosPitch * distance;
        double worldX = offsetX * Math.cos(yawRad) - offsetZ * Math.sin(yawRad);
        double worldZ = offsetX * Math.sin(yawRad) + offsetZ * Math.cos(yawRad);
        Vec3 eye = target.add(worldX, offsetY, worldZ);

        Vec3 delta = target.subtract(eye);
        if (delta.lengthSqr() < 1.0E-8) {
            delta = new Vec3(0.0, 0.0, 1.0);
        }
        Vec3 direction = delta.normalize();
        float yaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
        float pitch = (float) Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, -direction.y))));
        float roll = (float) -Math.toDegrees(rotation[2]);
        float fov = Math.max(1.0f, Math.min(179.0f, pose.viewAngle()));
        return new CameraSample(eye, yaw, pitch, roll, fov);
    }

    public static void lockEntityRotation(Entity entity, float yaw) {
        entity.setYRot(yaw);
        entity.yRotO = yaw;
        if (entity instanceof net.minecraft.world.entity.LivingEntity living) {
            living.setYBodyRot(yaw);
            living.yBodyRotO = yaw;
            living.setYHeadRot(yaw);
            living.yHeadRotO = yaw;
        }
    }

    public record CameraSample(Vec3 position, float yaw, float pitch, float roll, float fov) {}

    private enum MorphType { SCALE, JAW }

    private record MorphBinding(String boneName, MorphType type, float scaleX, float scaleY) {}

    private static boolean isDemonEntity(Entity e) {
        String cls = e.getClass().getName();
        String low = cls.toLowerCase();
        // 兼容 srg 重命名：原类名包含 "demon" 即可。
        if (low.contains("veritydemon") || low.contains("demonentity") || low.endsWith(".demonentity")) {
            return true;
        }
        // 兼容原版混淆名 varmite.verity.entity.demon.VerityDemonEntity
        return cls.equals("varmite.verity.entity.demon.VerityDemonEntity");
    }

    /**
     * 四元数 [x, y, z, w] -> 欧拉角 [pitch, yaw, roll]（弧度）。
     */
    public static float[] quatToEuler(float[] q) {
        float x = q[0], y = q[1], z = q[2], w = q[3];
        float norm = (float) Math.sqrt(x * x + y * y + z * z + w * w);
        if (norm > 1e-6f) {
            x /= norm; y /= norm; z /= norm; w /= norm;
        }
        float sinr = 2.0f * (w * x + y * z);
        float cosr = 1.0f - 2.0f * (x * x + y * y);
        float pitch = (float) Math.atan2(sinr, cosr);

        float sinp = 2.0f * (w * y - z * x);
        float yaw;
        if (Math.abs(sinp) >= 1.0f) {
            yaw = (float) Math.copySign(Math.PI / 2.0, sinp);
        } else {
            yaw = (float) Math.asin(sinp);
        }

        float siny = 2.0f * (w * z + x * y);
        float cosy = 1.0f - 2.0f * (y * y + z * z);
        float roll = (float) Math.atan2(siny, cosy);

        return new float[]{pitch, yaw, roll};
    }

    private record AudioTimeline(boolean active, boolean loading, boolean finished, float seconds) {
        private static final AudioTimeline NONE = new AudioTimeline(false, false, false, 0.0f);
        private static final AudioTimeline LOADING = new AudioTimeline(false, true, false, 0.0f);
    }

    /** 单个实体的播放状态。 */
    public static class PlaybackState {
        public final String projectId;
        public final VmdClip clip;
        public final VmdClip cameraClip;
        public final float durationSeconds;
        public final float cameraScale;
        public final MmdDanceProject.LoopMode loopMode;
        public float playhead;
        /** 上次推进的墙钟时间（ns）。0 表示首次调用。 */
        public long lastTickNanos;
        public final float lockedYaw;
        public Map<String, String> boneAssignments;
        /** morph 名 -> 绑定，避免每帧重复扫描+contains。 */
        public Map<String, MorphBinding> morphBindings = java.util.Collections.emptyMap();
        /** 受 morph 影响的骨骼名集合，下一帧开头重置用。 */
        public final java.util.List<String> appliedMorphBones = new java.util.ArrayList<>();
        /** 骨骼名 -> Geckolib 骨骼引用缓存。 */
        public final Map<String, CoreGeoBone> boneCache = new HashMap<>();

        public PlaybackState(String projectId, VmdClip clip, VmdClip cameraClip, float durationSeconds,
                             float cameraScale, MmdDanceProject.LoopMode loopMode,
                             float playhead, float lockedYaw) {
            this.projectId = projectId;
            this.clip = clip;
            this.cameraClip = cameraClip;
            this.durationSeconds = durationSeconds;
            this.cameraScale = cameraScale;
            this.loopMode = loopMode;
            this.playhead = playhead;
            this.lockedYaw = lockedYaw;
            this.lastTickNanos = 0L;
            this.boneAssignments = new HashMap<>();
        }
    }
}
