package com.hanchen.veritycustomapi.pmx;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.mmd.VmdClip;
import com.hanchen.veritycustomapi.config.ModConfig;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 可选、自包含的 PMX 模型加载器。故意和现有的 Verity/MMD 渲染器隔开：功能关着的时候既不返回模型，也不碰文件系统。
 */
public final class BuiltinPmxModelManager {
    private static final BuiltinPmxModelManager INSTANCE = new BuiltinPmxModelManager();
    private static final long MAX_MODEL_BYTES = 512L * 1024L * 1024L;

    private volatile String loadedKey = "";
    private volatile BuiltinPmxModel loaded;
    private volatile String lastFailureKey = "";

    private BuiltinPmxModelManager() {
    }

    public static BuiltinPmxModelManager get() {
        return INSTANCE;
    }

    /** 功能关着，或者没配到有效模型时，返回 null。 */
    public BuiltinPmxModel current() {
        if (!ModConfig.ENABLE_BUILTIN_PMX.get()) return null;
        String configured = ModConfig.BUILTIN_PMX_MODEL.get();
        Path directory;
        if (configured != null && !configured.isBlank()) {
            Path configuredPath = Path.of(configured).toAbsolutePath().normalize();
            directory = configuredPath.isAbsolute() && configuredPath.startsWith(root())
                    ? configuredPath : root().resolve(configured).normalize();
            if (!directory.startsWith(root()) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(directory)) return null;
        } else {
            // 图省事的自动发现：没配模型名时，确定性地挑第一个有效的模型目录。
            // 功能开关关着的时候老行为不受影响，
            // 因为那种情况这个方法在扫描之前就返回了。
            directory = discoverDirectory(root());
            if (directory == null) return null;
        }
        Path model = findModel(directory);
        if (model == null) return null;
        String key = model.toAbsolutePath().normalize() + ":" + lastModified(model);
        if (key.equals(loadedKey)) return loaded;
        synchronized (this) {
            if (key.equals(loadedKey)) return loaded;
            loadedKey = key;
            loaded = null;
            try {
                if (Files.size(model) > MAX_MODEL_BYTES) throw new IOException("PMX model exceeds size limit");
                MmdModelData data = MmdModelLoader.load(model);
                VmdClip motion = findMotion(directory);
                loaded = new BuiltinPmxModel(directory, model, data, motion);
                lastFailureKey = "";
                OllamaProxyMod.LOGGER.info("内置 PMX 模型已加载：{}", model);
            } catch (Exception error) {
                if (!key.equals(lastFailureKey)) {
                    lastFailureKey = key;
                    OllamaProxyMod.LOGGER.warn("内置 PMX 模型加载失败，继续使用原版模型：{}", model, error);
                }
            }
            return loaded;
        }
    }

    public void reload() {
        synchronized (this) {
            loadedKey = "";
            loaded = null;
            lastFailureKey = "";
        }
    }

    public static Path root() {
        return FMLPaths.CONFIGDIR.get().resolve("verity_extended").resolve("pmx").toAbsolutePath().normalize();
    }

    public static boolean isSafeName(String value) {
        return value != null && value.matches("[\\p{L}\\p{N}_.-]{1,96}")
                && !value.equals(".") && !value.equals("..");
    }

    private static Path discoverDirectory(Path root) {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) return null;
        try (var entries = Files.list(root)) {
            return entries
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(path -> isSafeName(path.getFileName().toString()))
                    .sorted((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()))
                    .filter(path -> findModel(path) != null)
                    .findFirst().orElse(null);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static Path findModel(Path directory) {
        for (String name : new String[]{"model.pmx", "model.pmd"}) {
            Path candidate = directory.resolve(name).normalize();
            if (candidate.startsWith(directory) && Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(candidate)) return candidate;
        }
        try (var entries = Files.list(directory)) {
            return entries
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(BuiltinPmxModelManager::isModelFile)
                    .sorted((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()))
                    .findFirst().orElse(null);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static boolean isModelFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".pmx") || name.endsWith(".pmd");
    }

    private static VmdClip findMotion(Path directory) {
        try (var entries = Files.list(directory)) {
            return entries.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".vmd"))
                    .sorted((a, b) -> a.getFileName().toString().compareToIgnoreCase(b.getFileName().toString()))
                    .findFirst().map(path -> {
                        try {
                            return VmdClip.load(path);
                        } catch (Exception error) {
                            OllamaProxyMod.LOGGER.warn("PMX 动作 VMD 加载失败：{}", path, error);
                            return null;
                        }
                    }).orElse(null);
        } catch (IOException ignored) {
            return null;
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
        } catch (IOException ignored) {
            return -1L;
        }
    }
}
