package com.hanchen.veritycustomapi.resource;

import com.hanchen.veritycustomapi.util.Silent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hanchen.veritycustomapi.compat.ForgePaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

public final class ResourceCacheIndex {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final String accountId;
    private final Path root;
    private final Path indexFile;
    private IndexData data;

    public ResourceCacheIndex(String apiKey) throws ResourceException {
        if (apiKey == null || apiKey.isBlank()) throw new ResourceException(401, "请先完成云端配对");
        this.accountId = fingerprint(apiKey);
        this.root = ForgePaths.config("verity_extended", "resource-cache", accountId);
        this.indexFile = root.resolve("index.json");
        this.data = load();
    }

    public String accountId() { return accountId; }
    public Path root() { return root; }
    public Path skins() { return root.resolve("skins"); }
    public Path voices() { return root.resolve("voices"); }
    public Path mmd() { return root.resolve("mmd"); }
    public Path installedMmd() { return mmd().resolve("installed"); }
    public synchronized Entry get(String key) { return data.entries.get(key); }
    public synchronized Map<String, Entry> entries() { return Map.copyOf(data.entries); }

    public synchronized void put(String key, Entry entry) throws IOException {
        data.entries.put(key, entry);
        save();
    }

    public synchronized void remove(String key) throws IOException {
        data.entries.remove(key);
        save();
    }

    public long sizeBytes() {
        if (!Files.exists(root)) return 0;
        try (var files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).mapToLong(path -> {
                try { return Files.size(path); } catch (IOException ignored) { return 0; }
            }).sum();
        } catch (IOException ignored) {
            return 0;
        }
    }

    public void clear() throws IOException {
        if (Files.exists(root)) {
            try (var paths = Files.walk(root)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (IOException error) { throw new RuntimeException(error); }
                });
            } catch (RuntimeException error) {
                if (error.getCause() instanceof IOException io) throw io;
                throw error;
            }
        }
        data = new IndexData();
    }

    private IndexData load() {
        try {
            if (Files.isRegularFile(indexFile)) {
                IndexData loaded = GSON.fromJson(Files.readString(indexFile, StandardCharsets.UTF_8), IndexData.class);
                if (loaded != null && loaded.entries != null) return loaded;
            }
        } catch (Exception ignored) { Silent.ignore("ResourceCacheIndex", ignored); }
        return new IndexData();
    }

    private void save() throws IOException {
        Files.createDirectories(root);
        Path temp = indexFile.resolveSibling("index.json.tmp");
        Files.writeString(temp, GSON.toJson(data), StandardCharsets.UTF_8);
        try {
            Files.move(temp, indexFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            Files.move(temp, indexFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String fingerprint(String key) throws ResourceException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder();
            for (int i = 0; i < 12; i++) value.append(String.format("%02x", digest[i]));
            return value.toString();
        } catch (Exception error) {
            throw new ResourceException("无法建立账号缓存隔离", error);
        }
    }

    private static final class IndexData {
        Map<String, Entry> entries = new HashMap<>();
    }

    public record Entry(String type, String resourceId, String displayName, String relativePath,
                        String sha256, long size, long updatedAt, boolean owner, boolean publicResource,
                        boolean platform) { }
}
