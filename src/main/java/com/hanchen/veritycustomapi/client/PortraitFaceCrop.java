package com.hanchen.veritycustomapi.client;

import com.hanchen.veritycustomapi.util.Silent;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.skin.SkinManager;
import com.hanchen.veritycustomapi.skin.VeritySkinTexture;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * 表情贴图里「脸」的取样范围。原版贴图是 1024×1024 纯色底、脸只占中间四分之一左右，
 * 第一次用到时扫像素找出脸部包围盒，再取外扩正方形当头像取样框。
 * 结果是归一化的 {u0,v0,u1,v1}，按贴图缓存、只算一次；出任何岔子就退回整张贴图。
 */
public final class PortraitFaceCrop {
    /** 不裁切：直接用整张贴图。 */
    private static final float[] FULL = {0.0F, 0.0F, 1.0F, 1.0F};

    /** 判定「与底色不同」的单通道差值阈值。 */
    private static final int COLOR_TOLERANCE = 32;
    /** 低于这个 alpha 的像素当作背景（透明底贴图）。 */
    private static final int OPAQUE_ALPHA = 8;
    /** 取样框相对脸的放大比例，留一点余量，免得圆形遮罩把脸的边缘切掉。 */
    private static final float PADDING = 1.4F;
    /** 检测到的内容小于整图的这个比例时认为只是噪点，不敢放大。 */
    private static final float MIN_CONTENT_RATIO = 0.05F;
    /** 内容本来就占满整张图时不需要裁切（贴图天生就是一张脸的构图）。 */
    private static final float FULL_COVERAGE_RATIO = 0.9F;

    /** 资源包贴图内容稳定，按 ResourceLocation 缓存。 */
    private static final Map<ResourceLocation, float[]> RESOURCE_CACHE = new HashMap<>();
    /**
     * 自定义皮肤是动态纹理：换皮肤 / 换表情都会换掉像素，
     * 所以按像素对象缓存，旧对象被回收时缓存条目自动消失。
     */
    private static final Map<NativeImage, float[]> PIXEL_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private PortraitFaceCrop() {
    }

    /** @return 归一化的 {@code {u0, v0, u1, v1}}；无法判断时返回整张贴图。 */
    public static float[] of(ResourceLocation texture) {
        if (texture == null) {
            return FULL;
        }
        NativeImage skinPixels = skinPixelsOf(texture);
        if (skinPixels != null) {
            return byPixels(skinPixels);
        }
        synchronized (RESOURCE_CACHE) {
            float[] cached = RESOURCE_CACHE.get(texture);
            if (cached != null) {
                return cached;
            }
        }
        float[] computed = fromResourcePack(texture);
        synchronized (RESOURCE_CACHE) {
            RESOURCE_CACHE.put(texture, computed);
        }
        return computed;
    }

    private static float[] byPixels(NativeImage pixels) {
        float[] cached = PIXEL_CACHE.get(pixels);
        if (cached != null) {
            return cached;
        }
        float[] computed = detect(pixels);
        PIXEL_CACHE.put(pixels, computed);
        return computed;
    }

    /** 自定义皮肤的贴图是动态纹理、不在资源包里，只能向 SkinManager 借当前像素。 */
    private static NativeImage skinPixelsOf(ResourceLocation texture) {
        try {
            VeritySkinTexture skin = SkinManager.getInstance().getSkinTexture();
            if (skin != null && texture.equals(skin.getTextureId())) {
                return skin.getPixels();
            }
        } catch (Throwable ignored) { Silent.ignore("PortraitFaceCrop", ignored); }
        return null;
    }

    private static float[] fromResourcePack(ResourceLocation texture) {
        try {
            Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(texture);
            if (resource.isEmpty()) {
                return FULL;
            }
            InputStream stream = resource.get().open();
            NativeImage image = null;
            try {
                image = NativeImage.read(stream);
                return detect(image);
            } finally {
                if (image != null) {
                    image.close();
                }
                stream.close();
            }
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.debug("头像脸型检测失败，改用整张贴图: {}", error.toString());
            return FULL;
        }
    }

    private static float[] detect(NativeImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (width <= 0 || height <= 0) {
            return FULL;
        }
        int background = image.getPixelRGBA(0, 0);
        int minX = width;
        int minY = height;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = image.getPixelRGBA(x, y);
                if (((pixel >>> 24) & 0xFF) < OPAQUE_ALPHA) {
                    continue;
                }
                if (!differsFrom(pixel, background)) {
                    continue;
                }
                if (x < minX) minX = x;
                if (x > maxX) maxX = x;
                if (y < minY) minY = y;
                if (y > maxY) maxY = y;
            }
        }
        if (maxX < 0) {
            // 整张都是底色（例如 noface），圆框里就是一颗纯色圆
            return FULL;
        }
        int contentWidth = maxX - minX + 1;
        int contentHeight = maxY - minY + 1;
        if (contentWidth < width * MIN_CONTENT_RATIO || contentHeight < height * MIN_CONTENT_RATIO) {
            return FULL;
        }
        if (contentWidth >= width * FULL_COVERAGE_RATIO && contentHeight >= height * FULL_COVERAGE_RATIO) {
            return FULL;
        }
        int side = Math.min(Math.min(width, height),
                (int) Math.round(Math.max(contentWidth, contentHeight) * PADDING));
        int centerX = (minX + maxX + 1) / 2;
        int centerY = (minY + maxY + 1) / 2;
        int x0 = Math.max(0, Math.min(width - side, centerX - side / 2));
        int y0 = Math.max(0, Math.min(height - side, centerY - side / 2));
        return new float[]{
                x0 / (float) width,
                y0 / (float) height,
                (x0 + side) / (float) width,
                (y0 + side) / (float) height
        };
    }

    /**
     * 两像素是否明显不同。只比较三个颜色通道，
     * 不关心 {@link NativeImage} 具体是 ABGR 还是 RGBA 排列。
     */
    private static boolean differsFrom(int pixel, int background) {
        return Math.abs((pixel & 0xFF) - (background & 0xFF)) > COLOR_TOLERANCE
                || Math.abs(((pixel >>> 8) & 0xFF) - ((background >>> 8) & 0xFF)) > COLOR_TOLERANCE
                || Math.abs(((pixel >>> 16) & 0xFF) - ((background >>> 16) & 0xFF)) > COLOR_TOLERANCE;
    }
}
