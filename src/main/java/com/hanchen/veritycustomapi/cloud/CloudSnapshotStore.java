package com.hanchen.veritycustomapi.cloud;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hanchen.veritycustomapi.compat.ForgePaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class CloudSnapshotStore {
    private static final Gson GSON = new Gson();
    private final Path directory;

    public CloudSnapshotStore() {
        this(ForgePaths.config("verity_extended", "cloud-snapshots"));
    }

    CloudSnapshotStore(Path directory) {
        this.directory = directory;
    }

    public CloudConfig load(String apiKey) {
        Path target = target(apiKey);
        if (target == null || !Files.isRegularFile(target)) {
            return null;
        }
        try {
            Snapshot snapshot = GSON.fromJson(Files.readString(target, StandardCharsets.UTF_8), Snapshot.class);
            if (snapshot == null || snapshot.schema_version != 1 || snapshot.config == null
                    || !snapshot.config.isSuccess() || snapshot.config.data.version == null
                    || snapshot.config.data.version < 0) {
                return null;
            }
            return snapshot.config;
        } catch (Exception ignored) {
            return null;
        }
    }

    public void save(String apiKey, CloudConfig config) {
        Path target = target(apiKey);
        if (target == null || config == null || !config.isSuccess()
                || config.data.version == null || config.data.version < 0) {
            return;
        }
        CloudConfig existing = load(apiKey);
        if (existing != null && existing.data.version > config.data.version) {
            return;
        }
        Snapshot snapshot = new Snapshot();
        snapshot.schema_version = 1;
        snapshot.version = config.data.version;
        snapshot.config_id = config.data.config_id;
        snapshot.config = sanitizedCopy(config);
        try {
            CloudCredentialStore.atomicWrite(target, GSON.toJson(snapshot));
        } catch (Exception e) {
            throw new IllegalStateException("无法保存云端配置快照", e);
        }
    }

    /**
     * 写入磁盘快照用的拷贝：必须剥掉所有密钥。快照只保留线路、模型、定价等非敏感元数据，
     * 任何 apiKey 都不落盘（CloudStorageTest 断言保护）；回退时内置线路拿不到 Token 被跳过是刻意取舍。
     */
    static CloudConfig sanitizedCopy(CloudConfig config) {
        JsonElement root = GSON.toJsonTree(config);
        redact(root);
        return GSON.fromJson(root, CloudConfig.class);
    }

    private static void redact(JsonElement element) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonArray()) {
            element.getAsJsonArray().forEach(CloudSnapshotStore::redact);
            return;
        }
        if (!element.isJsonObject()) return;
        JsonObject object = element.getAsJsonObject();
        object.entrySet().removeIf(entry -> isCredentialField(entry.getKey()));
        object.entrySet().forEach(entry -> redact(entry.getValue()));
    }

    private static boolean isCredentialField(String name) {
        String normalized = name == null ? "" : name.replace("_", "").toLowerCase(java.util.Locale.ROOT);
        // overrides 里的键是 TOML 点分路径（"llm.apiKey"），整名比对会漏掉它，
        // 密钥就会跟着快照落盘。这里只看最后一段，两种写法都能拦住。
        int separator = Math.max(normalized.lastIndexOf('.'), normalized.lastIndexOf('/'));
        if (separator >= 0) {
            normalized = normalized.substring(separator + 1);
        }
        return normalized.equals("apikey") || normalized.equals("authorization")
                || normalized.equals("accesstoken") || normalized.equals("secret");
    }

    private Path target(String apiKey) {
        String normalized = apiKey == null ? "" : apiKey.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return directory.resolve(HexFormat.of().formatHex(digest, 0, 16) + ".json");
        } catch (Exception e) {
            throw new IllegalStateException("无法生成云端快照标识", e);
        }
    }

    private static final class Snapshot {
        int schema_version;
        int version;
        String config_id;
        CloudConfig config;
    }
}
