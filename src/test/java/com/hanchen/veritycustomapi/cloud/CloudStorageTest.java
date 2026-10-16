package com.hanchen.veritycustomapi.cloud;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public final class CloudStorageTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("verity-cloud-storage-");
        Path credentialsFile = root.resolve("cloud-credentials.json");
        Path legacyClientId = root.resolve("cloud-client-id");
        String clientId = UUID.randomUUID().toString();
        Files.writeString(legacyClientId, clientId, StandardCharsets.UTF_8);

        CloudCredentialStore credentials = new CloudCredentialStore(credentialsFile, legacyClientId);
        require(clientId.equals(credentials.clientId()), "legacy client id was not migrated");
        require(!Files.exists(legacyClientId), "legacy client id was not removed");
        require(credentials.migrateLegacyApiKey("legacy-secret"), "legacy api key was not migrated");
        credentials.saveAuthorization("new-secret", "cfg-7");

        String stored = Files.readString(credentialsFile, StandardCharsets.UTF_8);
        require(stored.contains("new-secret") && stored.contains("cfg-7") && stored.contains(clientId),
                "credential triplet was not persisted");
        require(!Files.exists(credentialsFile.resolveSibling("cloud-credentials.json.tmp")),
                "credential temporary file remained");

        CloudSnapshotStore snapshots = new CloudSnapshotStore(root.resolve("snapshots"));
        CloudConfig runtimeConfig = config("cfg-7", 7);
        runtimeConfig.data.mimo = new CloudConfig.MimoConfig();
        runtimeConfig.data.mimo.apiUrl = "https://api.xiaomimimo.com/v1/chat/completions";
        runtimeConfig.data.mimo.apiKey = "mimo-platform-secret";
        runtimeConfig.data.mimo.routeId = "route_mimo";
        runtimeConfig.data.llm = new CloudConfig.Llm();
        runtimeConfig.data.llm.apiKey = "nested-secret";
        // overrides 的键是 TOML 点分路径，脱敏不能只比对整名，否则 "llm.apiKey" 会带着密钥落盘
        runtimeConfig.data.overrides = new com.google.gson.JsonObject();
        runtimeConfig.data.overrides.addProperty("llm.apiKey", "dotted-secret");
        runtimeConfig.data.overrides.addProperty("llm.apiUrl", "https://dotted.example.com/v1");
        com.google.gson.JsonObject nestedOverrides = new com.google.gson.JsonObject();
        com.google.gson.JsonObject ttsSection = new com.google.gson.JsonObject();
        ttsSection.addProperty("apiKey", "section-secret");
        nestedOverrides.add("tts", ttsSection);
        runtimeConfig.data.overrides.add("tts_route", nestedOverrides.get("tts"));
        snapshots.save("new-secret", runtimeConfig);
        require(snapshots.load("new-secret").data.version == 7, "snapshot was not loaded");
        require(snapshots.load("other-secret") == null, "snapshot leaked across credentials");
        require("mimo-platform-secret".equals(runtimeConfig.data.mimo.apiKey), "runtime MiMo key was cleared");

        Path snapshot = Files.list(root.resolve("snapshots")).findFirst().orElseThrow();
        String snapshotJson = Files.readString(snapshot, StandardCharsets.UTF_8);
        require(!snapshotJson.contains("mimo-platform-secret") && !snapshotJson.contains("nested-secret"),
                "credential was persisted in snapshot");
        require(!snapshotJson.contains("dotted-secret"), "dotted override credential was persisted in snapshot");
        require(!snapshotJson.contains("section-secret"), "nested override credential was persisted in snapshot");
        require(snapshotJson.contains("route_mimo"), "non-secret MiMo route was removed from snapshot");
        require(snapshotJson.contains("https://dotted.example.com/v1"),
                "non-secret dotted override was removed from snapshot");
        CloudConfig loaded = snapshots.load("new-secret");
        require(loaded.data.mimo != null && loaded.data.mimo.apiKey == null, "snapshot MiMo key was restored");
        require(loaded.data.overrides != null
                        && "https://dotted.example.com/v1".equals(loaded.data.overrides.get("llm.apiUrl").getAsString()),
                "snapshot overrides were not round-tripped");

        snapshots.save("new-secret", config("cfg-6", 6));
        require(snapshots.load("new-secret").data.version == 7, "older snapshot replaced newer version");

        Files.writeString(snapshot, "broken", StandardCharsets.UTF_8);
        require(snapshots.load("new-secret") == null, "corrupt snapshot was accepted");

        Path pendingFile = root.resolve("billing-pending.json");
        PendingBillingStore pending = new PendingBillingStore(pendingFile);
        PendingBillingStore.Entry entry = new PendingBillingStore.Entry();
        entry.operation = "settle";
        entry.reservation_id = UUID.randomUUID().toString();
        entry.request_id = UUID.randomUUID().toString();
        entry.route_id = "route-paid";
        entry.service = "chat";
        entry.tier = "paid";
        entry.api_url = "https://verity.xrstudio.cn/api/v1/getconfig";
        entry.endpoint_url = "https://verity.xrstudio.cn/api/v1/credits/settle";
        entry.account_fingerprint = BillingService.fingerprint("new-secret");
        entry.config_id = "cfg-7";
        entry.client_id = clientId;
        entry.usage = new com.google.gson.JsonObject();
        entry.usage.addProperty("total_tokens", 2);
        pending.put(entry);

        PendingBillingStore.Entry deduct = new PendingBillingStore.Entry();
        deduct.operation = "deduct";
        deduct.request_id = UUID.randomUUID().toString();
        deduct.route_id = "route-free";
        deduct.service = "tts";
        deduct.tier = "free";
        deduct.is_clone = false;
        deduct.api_url = entry.api_url;
        deduct.endpoint_url = "https://verity.xrstudio.cn/api/v1/credits/deduct";
        deduct.account_fingerprint = entry.account_fingerprint;
        deduct.config_id = entry.config_id;
        deduct.client_id = clientId;
        deduct.usage = new com.google.gson.JsonObject();
        deduct.usage.addProperty("characters", 12);
        pending.put(deduct);

        PendingBillingStore restored = new PendingBillingStore(pendingFile);
        require(restored.snapshot().size() == 2, "pending settle/deduct tasks were not restored");
        String pendingJson = Files.readString(pendingFile);
        require(pendingJson.contains("\"schema_version\":2"), "pending billing schema was not upgraded");
        require(pendingJson.contains(entry.account_fingerprint), "pending billing account fingerprint missing");
        require(!pendingJson.contains("new-secret") && !pendingJson.contains("api_key"),
                "pending billing persisted credentials");
        pending.remove(entry.key());
        pending.remove(deduct.key());
        require(new PendingBillingStore(pendingFile).snapshot().isEmpty(),
                "completed pending billing tasks were not removed");
    }

    private static CloudConfig config(String configId, int version) {
        CloudConfig config = new CloudConfig();
        config.code = 0;
        config.data = new CloudConfig.Data();
        config.data.config_id = configId;
        config.data.version = version;
        return config;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
