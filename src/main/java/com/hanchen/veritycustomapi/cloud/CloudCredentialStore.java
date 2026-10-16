package com.hanchen.veritycustomapi.cloud;

import com.google.gson.Gson;
import com.hanchen.veritycustomapi.compat.ForgePaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

public final class CloudCredentialStore {
    private static final Gson GSON = new Gson();

    private final Path target;
    private final Path legacyClientId;
    private Credentials credentials;

    CloudCredentialStore(Path target, Path legacyClientId) {
        this.target = target;
        this.legacyClientId = legacyClientId;
    }

    public static CloudCredentialStore getInstance() {
        return Holder.INSTANCE;
    }

    public synchronized String apiKey() {
        return load().apiKey;
    }

    public synchronized String configId() {
        return load().configId;
    }

    public synchronized String clientId() {
        return load().clientId;
    }

    public synchronized boolean migrateLegacyApiKey(String apiKey) {
        String normalized = normalize(apiKey);
        Credentials current = load();
        if (normalized.isEmpty() || !current.apiKey.isEmpty()) {
            return false;
        }
        write(new Credentials(normalized, current.configId, current.clientId));
        return true;
    }

    public synchronized void saveAuthorization(String apiKey, String configId) {
        String normalized = normalize(apiKey);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("云端配置密钥为空");
        }
        Credentials current = load();
        write(new Credentials(normalized, normalize(configId), current.clientId));
    }

    public synchronized void updateConfigId(String configId) {
        String normalized = normalize(configId);
        Credentials current = load();
        if (normalized.isEmpty() || normalized.equals(current.configId)) {
            return;
        }
        write(new Credentials(current.apiKey, normalized, current.clientId));
    }

    public synchronized void clearAuthorization() {
        Credentials current = load();
        write(new Credentials("", "", current.clientId));
    }

    private Credentials load() {
        if (credentials != null) {
            return credentials;
        }
        Credentials loaded = read();
        if (loaded == null) {
            loaded = new Credentials("", "", readLegacyClientId());
            write(loaded);
        } else if (!validClientId(loaded.clientId)) {
            loaded = new Credentials(loaded.apiKey, loaded.configId, readLegacyClientId());
            write(loaded);
        }
        deleteLegacyClientId();
        credentials = loaded;
        return loaded;
    }

    private Credentials read() {
        if (!Files.isRegularFile(target)) {
            return null;
        }
        try {
            StoredCredentials stored = GSON.fromJson(Files.readString(target, StandardCharsets.UTF_8), StoredCredentials.class);
            if (stored == null || !validClientId(stored.client_id)) {
                return null;
            }
            return new Credentials(normalize(stored.api_key), normalize(stored.config_id), stored.client_id);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String readLegacyClientId() {
        try {
            if (Files.isRegularFile(legacyClientId)) {
                String value = Files.readString(legacyClientId, StandardCharsets.UTF_8).trim();
                if (validClientId(value)) {
                    return value;
                }
            }
        } catch (Exception ignored) {
        }
        return UUID.randomUUID().toString();
    }

    private void write(Credentials value) {
        StoredCredentials stored = new StoredCredentials();
        stored.schema_version = 1;
        stored.api_key = value.apiKey;
        stored.config_id = value.configId;
        stored.client_id = value.clientId;
        try {
            atomicWrite(target, GSON.toJson(stored));
            credentials = value;
        } catch (Exception e) {
            throw new IllegalStateException("无法保存云端专用凭据", e);
        }
    }

    private void deleteLegacyClientId() {
        try {
            Files.deleteIfExists(legacyClientId);
        } catch (Exception ignored) {
        }
    }

    static void atomicWrite(Path target, String content) throws Exception {
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temporary, content, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean validClientId(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private record Credentials(String apiKey, String configId, String clientId) {
    }

    private static final class StoredCredentials {
        int schema_version;
        String api_key;
        String config_id;
        String client_id;
    }

    private static final class Holder {
        private static final CloudCredentialStore INSTANCE = new CloudCredentialStore(
                ForgePaths.config("verity_extended", "cloud-credentials.json"),
                ForgePaths.config("verity_extended", "cloud-client-id")
        );
    }
}
