package com.hanchen.veritycustomapi.cloud;

import com.hanchen.veritycustomapi.util.Silent;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.hanchen.veritycustomapi.compat.ForgePaths;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class PendingBillingStore {
    private static final Gson GSON = new Gson();
    private final Path path;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    PendingBillingStore() {
        this(ForgePaths.config("verity_extended", "billing-pending.json"));
    }

    PendingBillingStore(Path path) {
        this.path = path;
        load();
    }

    synchronized void put(Entry entry) {
        entries.put(entry.key(), entry);
        save();
    }

    synchronized void remove(String key) {
        if (entries.remove(key) != null) save();
    }

    synchronized List<Entry> snapshot() {
        return new ArrayList<>(entries.values());
    }

    private void load() {
        if (!Files.isRegularFile(path)) return;
        try {
            State state = GSON.fromJson(Files.readString(path), State.class);
            if (state == null || (state.schema_version != 1 && state.schema_version != 2) || state.entries == null) return;
            for (Entry entry : state.entries) {
                if (entry == null) continue;
                if (state.schema_version == 1) entry.legacy_unbound = true;
                if (entry.valid()) entries.put(entry.key(), entry);
            }
        } catch (Exception ignored) { Silent.ignore("PendingBillingStore", ignored); }
    }

    private void save() {
        State state = new State();
        state.schema_version = 2;
        state.entries = new ArrayList<>(entries.values());
        try {
            CloudCredentialStore.atomicWrite(path, GSON.toJson(state));
        } catch (Exception error) {
            throw new IllegalStateException("无法保存待处理计费任务", error);
        }
    }

    static final class Entry {
        String operation;
        String reservation_id;
        String request_id;
        String route_id;
        String service;
        String tier;
        Boolean is_clone;
        JsonObject usage;
        String reason;
        String api_url;
        String endpoint_url;
        String account_fingerprint;
        String config_id;
        String client_id;
        boolean legacy_unbound;
        long balance_sequence;
        int attempts;
        long next_attempt_at;

        String key() {
            return operation + ":" + request_id;
        }

        boolean valid() {
            if (request_id == null || request_id.length() < 8 || route_id == null || route_id.isBlank()) return false;
            if (!legacy_unbound && (api_url == null || api_url.isBlank() || endpoint_url == null
                    || endpoint_url.isBlank() || account_fingerprint == null || account_fingerprint.isBlank()
                    || client_id == null || client_id.isBlank())) return false;
            if ("deduct".equals(operation)) {
                return service != null && !service.isBlank() && tier != null && !tier.isBlank() && usage != null;
            }
            return ("settle".equals(operation) || "release".equals(operation))
                    && reservation_id != null && !reservation_id.isBlank()
                    && (legacy_unbound || service != null && !service.isBlank() && tier != null && !tier.isBlank());
        }
    }

    private static final class State {
        int schema_version;
        List<Entry> entries;
    }
}
