package com.hanchen.veritycustomapi.resource;

import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.cloud.CloudMmdProject;
import com.hanchen.veritycustomapi.mmd.MmdPlaybackManager;
import com.hanchen.veritycustomapi.mmd.VmdClip;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.Charset;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

public final class MmdResourceManager {
    private static final long MAX_DOWNLOAD = 256L * 1024 * 1024;
    private static final long MAX_ENTRY = 192L * 1024 * 1024;
    private static final long MAX_TOTAL = 512L * 1024 * 1024;
    private static final int MAX_ENTRIES = 128;
    private static final Set<String> ALLOWED = Set.of(".vmd", ".json", ".wav", ".ogg", ".mp3");

    private final CloudResourceClient client;
    private final ResourceCacheIndex index;

    public MmdResourceManager() throws ResourceException {
        String key = ConfigManager.getCloudApiKey();
        client = new CloudResourceClient(ConfigManager.getCloudApiUrl(), key, ConfigManager.getCloudTimeoutSeconds());
        index = new ResourceCacheIndex(key);
    }

    public Path download(CloudMmdProject project) throws Exception {
        String id = requireId(project.id);
        if (project.format != null && !project.format.isBlank() && !"zip".equalsIgnoreCase(project.format)) {
            throw new ResourceException(0, "暂不支持此 MMD 资源格式");
        }
        Path archives = index.mmd().resolve("archives");
        Files.createDirectories(archives);
        Path candidate = archives.resolve(id + ".candidate");
        Path archive = archives.resolve(id + ".zip");
        try {
            Files.deleteIfExists(candidate.resolveSibling(candidate.getFileName() + ".part"));
            client.download(project.downloadUrl, "mmd/projects/" + id + "/download", candidate, MAX_DOWNLOAD);
            validateArchive(candidate);
            String hash = sha256(candidate);
            if (project.sha256 != null && !project.sha256.isBlank() && !hash.equalsIgnoreCase(project.sha256.trim())) {
                throw new ResourceException(0, "MMD 文件校验失败，请重新下载");
            }
            atomicReplace(candidate, archive);
            install(archive, index.installedMmd().resolve(id));
            index.put("mmd:" + id, new ResourceCacheIndex.Entry("mmd", id, displayName(project),
                    index.root().relativize(archive).toString(), hash, Files.size(archive), System.currentTimeMillis(),
                    project.isOwner == 1, project.isPublic == 1, project.isGlobal == 1));
        } finally {
            Files.deleteIfExists(candidate);
        }
        MmdPlaybackManager.get().scanAndLoad();
        return index.installedMmd().resolve(id);
    }

    public boolean isCached(String id) {
        return validId(id) && Files.isDirectory(index.installedMmd().resolve(id));
    }

    public void deleteCached(String id) throws Exception {
        id = requireId(id);
        deleteTree(index.installedMmd().resolve(id));
        Files.deleteIfExists(index.mmd().resolve("archives").resolve(id + ".zip"));
        index.remove("mmd:" + id);
        MmdPlaybackManager.get().scanAndLoad();
    }

    public static void validateArchive(Path archive) throws ResourceException {
        if (!Files.isRegularFile(archive)) throw new ResourceException(0, "MMD ZIP 不存在");
        int count = 0;
        int vmdCount = 0;
        long total = 0;
        try (ZipFile zip = openZip(archive)) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                if (++count > MAX_ENTRIES) throw new ResourceException(413, "MMD ZIP 文件数量过多");
                String raw = safeEntry(entry);
                if (raw.indexOf('/') >= 0) throw new ResourceException(0, "MMD ZIP 文件必须直接放在压缩包根目录");
                String lower = raw.toLowerCase(Locale.ROOT);
                if (ALLOWED.stream().noneMatch(lower::endsWith)) throw new ResourceException(0, "MMD ZIP 包含不支持的文件");
                long size = entry.getSize();
                if (size < 0 || size > MAX_ENTRY) throw new ResourceException(413, "MMD 项目中的单个文件过大");
                total += size;
                if (total > MAX_TOTAL) throw new ResourceException(413, "MMD ZIP 解压内容过大");
                if (entry.getCompressedSize() > 0 && size / entry.getCompressedSize() > 100) {
                    throw new ResourceException(413, "MMD ZIP 压缩比异常");
                }
                if (lower.endsWith(".vmd")) vmdCount++;
            }
        } catch (ResourceException error) {
            throw error;
        } catch (IOException error) {
            throw new ResourceException("MMD ZIP 格式错误或文件损坏", error);
        }
        if (vmdCount == 0) throw new ResourceException(0, "MMD 项目至少需要一个 VMD 动作文件");
    }

    private static void install(Path archive, Path target) throws Exception {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Path backup = target.resolveSibling(target.getFileName() + ".old");
        deleteTree(temp);
        deleteTree(backup);
        Files.createDirectories(temp);
        try {
            try (ZipFile zip = openZip(archive)) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    String name = safeEntry(entry);
                    Path output = temp.resolve(name).normalize();
                    if (!output.getParent().equals(temp)) throw new ResourceException(0, "MMD ZIP 包含不安全路径");
                    try (InputStream input = zip.getInputStream(entry); var stream = Files.newOutputStream(output)) {
                        input.transferTo(stream);
                    }
                }
            }
            boolean hasMotion = false;
            try (var files = Files.list(temp)) {
                for (Path file : files.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".vmd")).toList()) {
                    if (!VmdClip.load(file).getBoneTracks().isEmpty()) hasMotion = true;
                }
            }
            if (!hasMotion) throw new ResourceException(0, "MMD 项目中没有可用的人物动作轨");
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) atomicReplace(target, backup);
            try {
                atomicReplace(temp, target);
                deleteTree(backup);
            } catch (Exception error) {
                if (Files.exists(backup)) atomicReplace(backup, target);
                throw error;
            }
        } catch (Exception error) {
            deleteTree(temp);
            throw error;
        }
    }

    private static ZipFile openZip(Path archive) throws IOException {
        try {
            return new ZipFile(archive.toFile());
        } catch (ZipException error) {
            String message = error.getMessage();
            if (message == null || !message.contains("bad entry name")) throw error;
            return new ZipFile(archive.toFile(), Charset.forName("GB18030"));
        }
    }

    private static String safeEntry(ZipEntry entry) throws ResourceException {
        String raw = entry.getName().replace('\\', '/');
        if (raw.isBlank() || raw.startsWith("/") || raw.contains("../") || raw.matches("^[A-Za-z]:.*")) {
            throw new ResourceException(0, "MMD ZIP 包含不安全路径");
        }
        return raw;
    }

    private static String requireId(String id) throws ResourceException {
        if (!validId(id)) throw new ResourceException(0, "资源标识无效");
        return id;
    }

    private static boolean validId(String id) {
        return id != null && id.matches("[\\p{L}\\p{N}._-]{1,96}") && !id.equals(".") && !id.equals("..");
    }

    private static String displayName(CloudMmdProject project) {
        return project.name == null || project.name.isBlank() ? project.id : project.name;
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
