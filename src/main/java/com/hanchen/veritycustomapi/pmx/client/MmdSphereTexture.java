package com.hanchen.veritycustomapi.pmx.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.hanchen.veritycustomapi.pmx.MmdSphereMap;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 球面贴图的 CPU 侧缓存。球面贴图是折进顶点色的、不由着色器采样，所以和基础贴图不一样，
 * 它从不变成 GL 纹理，只要它的像素。缓存设了上限，丢弃时会关掉图像，切多个模型也不会泄漏。
 */
public final class MmdSphereTexture {
    private static final int MAX_CACHED = 64;
    private static final Map<Path, Optional<NativeImage>> IMAGES = new ConcurrentHashMap<>();

    private MmdSphereTexture() {
    }

    /**
     * 为一个顶点采样球面贴图。
     *
     * @return 0..1 的 {@code {r, g, b, a}}；没东西可读时返回 {@code null}
     */
    public static float[] sample(Path root, Path base, String relative, float u, float v) {
        NativeImage image = image(root, base, relative);
        if (image == null) return null;
        int width = image.getWidth();
        int height = image.getHeight();
        if (width <= 0 || height <= 0) return null;
        try {
            return MmdSphereMap.unpack(image.getPixelRGBA(
                    MmdSphereMap.column(u, width), MmdSphereMap.row(v, height)));
        } catch (Throwable failure) {
            return null;
        }
    }

    private static NativeImage image(Path root, Path base, String relative) {
        Optional<Path> path = MmdTextureCache.resolve(root, base, relative);
        if (path.isEmpty()) return null;
        if (IMAGES.size() >= MAX_CACHED) clear();
        return IMAGES.computeIfAbsent(path.get(), MmdSphereTexture::read).orElse(null);
    }

    private static Optional<NativeImage> read(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            NativeImage image = NativeImage.read(input);
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                if (image != null) image.close();
                return Optional.empty();
            }
            return Optional.of(image);
        } catch (Throwable failure) {
            return Optional.empty();
        }
    }

    public static void clear() {
        for (Optional<NativeImage> image : IMAGES.values()) {
            image.ifPresent(NativeImage::close);
        }
        IMAGES.clear();
    }
}
