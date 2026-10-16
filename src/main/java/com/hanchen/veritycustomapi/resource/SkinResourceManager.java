package com.hanchen.veritycustomapi.resource;

import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.cloud.CloudSkin;
import com.hanchen.veritycustomapi.skin.SkinManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class SkinResourceManager {
    private static final long MAX_DOWNLOAD = 32L * 1024 * 1024;
    private static final long MAX_ENTRY = 8L * 1024 * 1024;
    private static final long MAX_TOTAL = 64L * 1024 * 1024;
    private static final int MAX_ENTRIES = 64;
    private static final Set<String> REQUIRED = Set.of("crazy", "crazy_talking", "evil", "evil_talking",
            "happy", "happy_sleep", "happy_talking", "hurt", "neutral", "neutral_talking", "noface",
            "serious_1", "serious_2", "serious_3", "serious_talking", "smiling_evil");

    private final CloudResourceClient client;
    private final ResourceCacheIndex index;

    public SkinResourceManager() throws ResourceException {
        String key = ConfigManager.getCloudApiKey();
        this.client = new CloudResourceClient(ConfigManager.getCloudApiUrl(), key, ConfigManager.getCloudTimeoutSeconds());
        this.index = new ResourceCacheIndex(key);
    }

    public Path download(CloudSkin skin, boolean apply) throws Exception {
        String id = requireId(skin.name);
        Files.createDirectories(index.skins());
        Path candidate = index.skins().resolve(id + ".candidate");
        Path archive = index.skins().resolve(id + ".zip");
        try {
            client.download(skin.downloadUrl, "skins/download/" + id, candidate, MAX_DOWNLOAD);
            validate(candidate);
            atomicReplace(candidate, archive);
        } finally {
            Files.deleteIfExists(candidate);
        }
        String hash = sha256(archive);
        index.put("skin:" + id, new ResourceCacheIndex.Entry("skin", id, displayName(skin),
                index.root().relativize(archive).toString(), hash, Files.size(archive), System.currentTimeMillis(),
                skin.isOwner == 1, skin.isPublic == 1, skin.isGlobal == 1));
        if (apply && !applyCached(id)) throw new ResourceException(0, "皮肤已缓存，但应用失败");
        return archive;
    }

    public boolean applyCached(String id) throws Exception {
        id = requireId(id);
        Path archive = index.skins().resolve(id + ".zip");
        validate(archive);
        return SkinManager.getInstance().setPrivateSkin(index.accountId() + ":" + id, archive,
                index.skins().resolve("extracted").resolve(id));
    }

    public boolean isCached(String id) {
        return id != null && Files.isRegularFile(index.skins().resolve(id + ".zip"));
    }

    public void deleteCached(String id) throws Exception {
        id = requireId(id);
        if ((index.accountId() + ":" + id).equals(SkinManager.getInstance().getActiveSkinName())) {
            SkinManager.getInstance().useDefaultSkin();
        }
        Files.deleteIfExists(index.skins().resolve(id + ".zip"));
        deleteTree(index.skins().resolve("extracted").resolve(id));
        index.remove("skin:" + id);
    }

    public ResourceCacheIndex index() { return index; }

    public static void validate(Path archive) throws ResourceException {
        if (!Files.isRegularFile(archive)) throw new ResourceException(0, "皮肤 ZIP 不存在");
        Set<String> found = new HashSet<>();
        long total = 0;
        int count = 0;
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                if (++count > MAX_ENTRIES) throw new ResourceException(413, "皮肤 ZIP 文件数量过多");
                String raw = entry.getName().replace('\\', '/');
                if (raw.startsWith("/") || raw.contains("../") || raw.matches("^[A-Za-z]:.*")) {
                    throw new ResourceException(0, "皮肤 ZIP 包含不安全路径");
                }
                String file = raw.substring(raw.lastIndexOf('/') + 1);
                if (!file.toLowerCase().endsWith(".png")) throw new ResourceException(0, "皮肤 ZIP 包含不支持的文件");
                long size = entry.getSize();
                if (size < 0 || size > MAX_ENTRY) throw new ResourceException(413, "皮肤图片过大");
                total += size;
                if (total > MAX_TOTAL) throw new ResourceException(413, "皮肤 ZIP 解压内容过大");
                if (entry.getCompressedSize() > 0 && size / entry.getCompressedSize() > 100) {
                    throw new ResourceException(413, "皮肤 ZIP 压缩比异常");
                }
                String variant = file.substring(0, file.length() - 4).toLowerCase();
                if (!REQUIRED.contains(variant) || !found.add(variant)) throw new ResourceException(0, "皮肤 ZIP 包含重复或未知变体");
                try (InputStream input = zip.getInputStream(entry)) { validatePng(input); }
            }
        } catch (ResourceException error) {
            throw error;
        } catch (IOException error) {
            throw new ResourceException("ZIP 格式错误或文件损坏", error);
        }
        if (!found.equals(REQUIRED)) throw new ResourceException(0, "皮肤 ZIP 缺少必要的表情图片");
    }

    public static void extractValidated(Path archive, Path target) throws Exception {
        validate(archive);
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        deleteTree(temp);
        Files.createDirectories(temp);
        try {
            long total = 0;
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    String raw = entry.getName().replace('\\', '/');
                    String file = raw.substring(raw.lastIndexOf('/') + 1).toLowerCase();
                    Path output = temp.resolve(file).normalize();
                    if (!output.startsWith(temp)) throw new ResourceException(0, "皮肤 ZIP 包含不安全路径");
                    try (InputStream input = zip.getInputStream(entry); var outputStream = Files.newOutputStream(output)) {
                        byte[] buffer = new byte[16384];
                        long entryBytes = 0;
                        int read;
                        while ((read = input.read(buffer)) >= 0) {
                            entryBytes += read;
                            total += read;
                            if (entryBytes > MAX_ENTRY || total > MAX_TOTAL) throw new ResourceException(413, "皮肤 ZIP 解压内容过大");
                            outputStream.write(buffer, 0, read);
                        }
                    }
                }
            }
            deleteTree(target);
            atomicReplace(temp, target);
        } catch (Exception error) {
            deleteTree(temp);
            throw error;
        }
    }

    private static void validatePng(InputStream input) throws IOException, ResourceException {
        byte[] header = input.readNBytes(24);
        byte[] signature = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        if (header.length < 24) throw new ResourceException(0, "皮肤图片内容缺失");
        for (int i = 0; i < signature.length; i++) if (header[i] != signature[i]) throw new ResourceException(0, "皮肤图片格式错误");
        int width = int32(header, 16);
        int height = int32(header, 20);
        if (width < 16 || height < 16 || width > 4096 || height > 4096) throw new ResourceException(0, "皮肤图片尺寸异常");
    }

    private static int int32(byte[] bytes, int offset) {
        return (bytes[offset] & 255) << 24 | (bytes[offset + 1] & 255) << 16
                | (bytes[offset + 2] & 255) << 8 | bytes[offset + 3] & 255;
    }

    private static String requireId(String id) throws ResourceException {
        if (id == null || !id.matches("[\\p{L}\\p{N}._-]{1,96}") || id.equals(".") || id.equals("..")) {
            throw new ResourceException(0, "资源标识无效");
        }
        return id;
    }

    private static String displayName(CloudSkin skin) {
        return skin.displayName == null || skin.displayName.isBlank() ? skin.name : skin.displayName;
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[16384];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static void atomicReplace(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (IOException ignored) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
    }

    private static void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var paths = Files.walk(path)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(value -> {
                try { Files.deleteIfExists(value); } catch (IOException error) { throw new RuntimeException(error); }
            });
        } catch (RuntimeException error) {
            if (error.getCause() instanceof IOException io) throw io;
            throw error;
        }
    }
}
