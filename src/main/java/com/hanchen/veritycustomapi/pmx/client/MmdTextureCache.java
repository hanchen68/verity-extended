package com.hanchen.veritycustomapi.pmx.client;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Arrays;
import java.util.Locale;

public final class MmdTextureCache {
    private static final ResourceLocation FALLBACK =
            ResourceLocation.fromNamespaceAndPath("minecraft", "textures/block/white_concrete.png");
    private static final Map<Path, ResourceLocation> LOCATIONS = new ConcurrentHashMap<>();

    private MmdTextureCache() {
    }

    public static ResourceLocation texture(Path root, String relative) {
        return texture(root, root, relative);
    }

    public static ResourceLocation texture(Path root, Path base, String relative) {
        Optional<Path> path = resolve(root, base, relative);
        if (path.isEmpty()) {
            return FALLBACK;
        }
        return LOCATIONS.computeIfAbsent(path.get(), MmdTextureCache::load);
    }

    public static void clear() {
        Minecraft minecraft = Minecraft.getInstance();
        for (ResourceLocation location : LOCATIONS.values()) {
            if (!location.equals(FALLBACK)) {
                minecraft.getTextureManager().release(location);
            }
        }
        LOCATIONS.clear();
    }

    private static ResourceLocation load(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            NativeImage image = NativeImage.read(input);
            if (image == null || image.getWidth() > 8192 || image.getHeight() > 8192) {
                if (image != null) image.close();
                return FALLBACK;
            }
            String key = Integer.toUnsignedString(path.toAbsolutePath().normalize().toString().hashCode(), 36);
            ResourceLocation location = ResourceLocation.fromNamespaceAndPath("customsoul", "mmd/" + key);
            Minecraft.getInstance().getTextureManager().register(location, new DynamicTexture(image));
            return location;
        } catch (Exception ignored) {
            return FALLBACK;
        }
    }

    /**
     * 把模型相对贴图路径按根目录解析成绝对路径，任何可能跳出模型目录的都拒绝。
     * 球面贴图缓存也共用它——那边要的是文件，不是 GL 纹理。
     */
    public static Optional<Path> resolve(Path root, Path base, String relative) {
        if (root == null || base == null || relative == null || relative.isBlank()) {
            return Optional.empty();
        }
        String value = relative.replace('\\', '/');
        int sphere = value.indexOf('*');
        if (sphere >= 0) value = value.substring(0, sphere);
        if (value.isBlank() || value.startsWith("/") || value.matches("^[A-Za-z]:.*")
                || Arrays.asList(value.split("/")).contains("..")) {
            return Optional.empty();
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalizedBase = base.toAbsolutePath().normalize();
        if (!normalizedBase.startsWith(normalizedRoot)) {
            return Optional.empty();
        }
        Path path = normalizedBase.resolve(value).normalize();
        if (!path.startsWith(normalizedRoot) || Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".bmp") || lower.endsWith(".tga") ? Optional.of(path) : Optional.empty();
    }
}
