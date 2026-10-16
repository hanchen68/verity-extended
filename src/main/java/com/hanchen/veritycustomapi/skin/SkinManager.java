package com.hanchen.veritycustomapi.skin;

import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.cloud.CloudConfigFetcher;
import com.hanchen.veritycustomapi.cloud.CloudSkin;
import com.hanchen.veritycustomapi.compat.ForgePaths;
import com.hanchen.veritycustomapi.resource.SkinResourceManager;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;

/**
 * 皮肤管理器：从 config/verity/skins/ 加载 ZIP 皮肤包，
 * 解压 PNG 变体，并在 Verity 实体渲染时应用对应的贴图。
 */
@OnlyIn(Dist.CLIENT)
public class SkinManager {

    /** 皮肤 ZIP 存放目录 */
    private static final Path SKINS_DIR = ForgePaths.config("verity", "skins");
    /** 解压缓存目录 */
    private static final Path EXTRACT_DIR = SKINS_DIR.resolve("_extracted");

    private static volatile SkinManager INSTANCE;
    private final VeritySkinTexture skinTexture = new VeritySkinTexture();
    private volatile String activeSkinName;
    private volatile Path activeExtractDirectory;
    private String pendingVariant;
    private volatile boolean initialized;

    /** 云端皮肤列表缓存 */
    private volatile CloudSkin[] cloudSkins;
    /** 标记云端皮肤最后拉取状态 */
    private volatile String cloudFetchError;
    /** 下载任务是否进行中 */
    private final java.util.concurrent.atomic.AtomicBoolean downloading = new java.util.concurrent.atomic.AtomicBoolean(false);

    private SkinManager() {}

    public static SkinManager getInstance() {
        if (INSTANCE == null) {
            synchronized (SkinManager.class) {
                if (INSTANCE == null) {
                    INSTANCE = new SkinManager();
                }
            }
        }
        return INSTANCE;
    }

    public static Path getSkinsDirectory() {
        return SKINS_DIR;
    }

    /**
     * 初始化：注册动态纹理，加载上次保存的皮肤
     */
    public void init() {
        if (initialized) return;
        try {
            Files.createDirectories(SKINS_DIR);
            Files.createDirectories(EXTRACT_DIR);
        } catch (IOException e) {
            OllamaProxyMod.LOGGER.warn("[SkinManager] 无法创建皮肤目录: {}", e.getMessage());
        }
        skinTexture.init();
        initialized = true;
        OllamaProxyMod.LOGGER.info("[SkinManager] 皮肤系统已初始化，皮肤目录: {}", SKINS_DIR.toAbsolutePath());
        // 从配置加载上次选择的皮肤
        reloadActiveSkin();
    }

    /**
     * 扫描所有可用的皮肤包（ZIP 文件，包含全部 16 种必需变体）
     */
    public List<String> getAvailableSkins() {
        List<String> skins = new ArrayList<>();
        if (!Files.exists(SKINS_DIR)) return skins;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(SKINS_DIR, "*.zip")) {
            for (Path zipPath : stream) {
                String name = zipPath.getFileName().toString().replace(".zip", "");
                if ("Verity".equalsIgnoreCase(name)) continue; // 跳过默认
                boolean valid = validateSkinZip(zipPath);
                if (valid) {
                    skins.add(name);
                }
            }
        } catch (IOException e) {
            OllamaProxyMod.LOGGER.warn("[SkinManager] 扫描皮肤目录失败: {}", e.getMessage());
        }
        return skins;
    }

    /**
     * 验证 ZIP 文件是否包含所有必需的 PNG 变体
     */
    private boolean validateSkinZip(Path zipPath) {
        try {
            SkinResourceManager.validate(zipPath);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 设置当前皮肤（解压 + 缓存）
     */
    public boolean setSkin(String skinName) {
        if (skinName == null || skinName.isBlank() || "Verity".equalsIgnoreCase(skinName)) {
            activeSkinName = null;
            activeExtractDirectory = null;
            pendingVariant = null;
            OllamaProxyMod.LOGGER.info("[SkinManager] 已切换为 Verity 默认皮肤");
            return true;
        }
        if (!isSafeLocalSkinName(skinName)) {
            OllamaProxyMod.LOGGER.warn("[SkinManager] 皮肤名称无效");
            return false;
        }
        Path zipPath = SKINS_DIR.resolve(skinName + ".zip").normalize();
        if (!zipPath.startsWith(SKINS_DIR)) return false;
        if (!Files.exists(zipPath)) {
            OllamaProxyMod.LOGGER.warn("[SkinManager] 皮肤文件不存在: {}", zipPath);
            return false;
        }
        if (!validateSkinZip(zipPath)) {
            OllamaProxyMod.LOGGER.warn("[SkinManager] 皮肤包缺少必需变体或格式无效: {}", zipPath.getFileName());
            return false;
        }
        Path extractTarget = EXTRACT_DIR.resolve(skinName);
        try {
            SkinResourceManager.extractValidated(zipPath, extractTarget);
            activeSkinName = skinName;
            activeExtractDirectory = extractTarget;
            // 立即应用默认变体（neutral）
            pendingVariant = "neutral";
            applyCurrentVariant();
            OllamaProxyMod.LOGGER.info("[SkinManager] 皮肤已切换: {}", skinName);
            return true;
        } catch (Exception e) {
            OllamaProxyMod.LOGGER.error("[SkinManager] 皮肤切换失败: {}", skinName, e);
            return false;
        }
    }

    /**
     * 由 Mixin 调用：记录当前正在请求的表情变体名
     */
    public void setCurrentVariant(String variant) {
        if (activeSkinName == null) return;
        String shortVariant = extractVariantName(variant);
        if (!pendingVariantChanged(shortVariant)) return;
        pendingVariant = shortVariant;
        applyCurrentVariant();
    }

    /**
     * 将当前变体的 PNG 应用到动态纹理
     */
    private void applyCurrentVariant() {
        if (activeSkinName == null || pendingVariant == null) return;
        Path base = activeExtractDirectory != null ? activeExtractDirectory : EXTRACT_DIR.resolve(activeSkinName);
        Path pngPath = base.resolve(pendingVariant + ".png");
        if (!Files.exists(pngPath)) {
            OllamaProxyMod.LOGGER.debug("[SkinManager] 变体 PNG 不存在: {}", pngPath);
            return;
        }
        try (InputStream is = Files.newInputStream(pngPath)) {
            skinTexture.applyTexture(is);
        } catch (Exception e) {
            OllamaProxyMod.LOGGER.warn("[SkinManager] 应用皮肤变体失败: {} -> {}", activeSkinName, pendingVariant, e);
        }
    }

    private boolean pendingVariantChanged(String newVariant) {
        return pendingVariant == null || !pendingVariant.equals(newVariant);
    }

    /**
     * 从完整纹理路径中提取变体名
     * "verity:textures/entity/happy_talking.png" -> "happy_talking"
     */
    private String extractVariantName(String texturePath) {
        if (texturePath == null) return "neutral";
        int lastSlash = texturePath.lastIndexOf('/');
        int lastDot = texturePath.lastIndexOf('.');
        if (lastSlash < 0) return texturePath;
        if (lastDot > lastSlash) {
            return texturePath.substring(lastSlash + 1, lastDot);
        }
        return texturePath.substring(lastSlash + 1);
    }

    public VeritySkinTexture getSkinTexture() {
        return skinTexture;
    }

    public String getActiveSkinName() {
        return activeSkinName;
    }

    public boolean hasActiveSkin() {
        return activeSkinName != null;
    }

    public boolean setPrivateSkin(String displayId, Path archive, Path extractTarget) {
        try {
            com.hanchen.veritycustomapi.resource.SkinResourceManager.extractValidated(archive, extractTarget);
            activeSkinName = displayId;
            activeExtractDirectory = extractTarget;
            pendingVariant = "neutral";
            applyCurrentVariant();
            return true;
        } catch (Exception error) {
            OllamaProxyMod.LOGGER.warn("[SkinManager] 私有缓存皮肤应用失败: {}", error.getMessage());
            return false;
        }
    }

    public void useDefaultSkin() {
        setSkin("Verity");
    }

    private static boolean isSafeLocalSkinName(String value) {
        return value.matches("[\\p{L}\\p{N}._ -]{1,96}") && !value.equals(".") && !value.equals("..");
    }

    /** 返回当前皮肤的 NativeImage 像素（供 Mixin 色调系统使用） */
    public com.mojang.blaze3d.platform.NativeImage getActiveSkinPixels() {
        if (!hasActiveSkin()) return null;
        return skinTexture.getPixels();
    }

    /**
     * 从配置重新加载皮肤（供 onConfigSaved 调用）
     */
    public void reloadActiveSkin() {
        String savedSkin = com.hanchen.veritycustomapi.config.ModConfig.VERITY_SKIN_NAME.get();
        if (savedSkin != null && !savedSkin.isBlank() && !"Verity".equalsIgnoreCase(savedSkin)) {
            if (getAvailableSkins().contains(savedSkin)) {
                setSkin(savedSkin);
            } else {
                OllamaProxyMod.LOGGER.warn("[SkinManager] 配置中的皮肤不可用: {}", savedSkin);
            }
        }
    }

    /**
     * 获取缓存的云端皮肤列表
     */
    public CloudSkin[] getCloudSkins() {
        return cloudSkins != null ? cloudSkins : new CloudSkin[0];
    }

    public String getCloudFetchError() {
        return cloudFetchError;
    }

    public boolean isDownloading() {
        return downloading.get();
    }

    /**
     * 从云端拉取皮肤列表（异步，结果缓存到 cloudSkins）
     */
    public void refreshCloudSkins() {
        refreshCloudSkins(null);
    }

    /**
     * 从云端拉取皮肤列表（异步，完成后在主线程回调）
     */
    public void refreshCloudSkins(Runnable onComplete) {
        if (!ConfigManager.isCloudEnabled()) {
            cloudFetchError = "云端配置未启用";
            if (onComplete != null) {
                net.minecraft.client.Minecraft.getInstance().execute(onComplete);
            }
            return;
        }
        new Thread(() -> {
            try {
                CloudSkin[] skins = new CloudConfigFetcher().fetchSkinList(
                        ConfigManager.getCloudApiUrl(),
                        ConfigManager.getCloudApiKey(),
                        ConfigManager.getCloudTimeoutSeconds()
                );
                if (skins.length == 0) {
                    cloudFetchError = "云端暂无可用皮肤";
                } else {
                    cloudFetchError = null;
                }
                cloudSkins = skins;
                OllamaProxyMod.LOGGER.info("[SkinManager] 云端皮肤列表已更新，共{}个", skins.length);
            } catch (Throwable t) {
                cloudFetchError = "拉取失败: " + t.getMessage();
                OllamaProxyMod.LOGGER.warn("[SkinManager] 云端皮肤列表拉取失败: {}", t.getMessage());
            }
            if (onComplete != null) {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                if (mc != null) mc.execute(onComplete);
            }
        }, "Verity-Skin-CloudFetch").start();
    }

    /**
     * 下载云端皮肤并应用
     * @return 成功返回 true
     */
    public boolean downloadAndApplyCloudSkin(String skinName, String downloadUrl) {
        if (!downloading.compareAndSet(false, true)) {
            OllamaProxyMod.LOGGER.warn("[SkinManager] 皮肤下载任务正在进行中");
            return false;
        }
        try {
            CloudSkin skin = new CloudSkin();
            skin.name = skinName;
            skin.displayName = skinName;
            skin.downloadUrl = downloadUrl;
            new SkinResourceManager().download(skin, true);
            return true;
        } catch (Exception e) {
            OllamaProxyMod.LOGGER.error("[SkinManager] 云端皮肤下载失败: {}", skinName, e);
            return false;
        } finally {
            downloading.set(false);
        }
    }

    public void shutdown() {
        skinTexture.close();
        initialized = false;
    }
}
