package com.hanchen.veritycustomapi.memory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 滚动对话摘要的落盘存储（config/verity_extended/context/summary.json）。
 * 按世界名分桶，切存档互不串扰；进程内缓存加单线程异步写盘，热路径只碰内存。
 */
public final class ConversationSummaryStore {
    /** 一个世界的摘要条目。seenEntries 是被摘要覆盖过的轮数，增量摘要靠它。 */
    public record Summary(String text, long coveredAt, long updatedAt, int seenEntries) {
    }

    private static final Gson GSON = new Gson();
    private static final Map<String, Summary> CACHE = new ConcurrentHashMap<>();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Verity-Context-Store");
        thread.setDaemon(true);
        return thread;
    });

    static {
        load();
    }

    private ConversationSummaryStore() {
    }

    /** 读某世界的摘要；没生成过返回 null。 */
    public static Summary get(String world) {
        return CACHE.get(world == null || world.isBlank() ? "" : world);
    }

    /** 更新某世界的摘要，异步落盘。 */
    public static void put(String world, Summary summary) {
        CACHE.put(world == null || world.isBlank() ? "" : world, summary);
        WORKER.execute(ConversationSummaryStore::save);
    }

    /** 配置目录拿不到时返回 null（自检环境），那就只走内存、不落盘。 */
    private static Path filePath() {
        try {
            return FMLPaths.CONFIGDIR.get().resolve("verity_extended")
                    .resolve("context").resolve("summary.json");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void load() {
        Path file = filePath();
        if (file == null || !Files.exists(file)) {
            return;
        }
        try {
            JsonObject root = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
            if (root == null) {
                return;
            }
            for (Map.Entry<String, com.google.gson.JsonElement> entry : root.entrySet()) {
                if (!entry.getValue().isJsonObject()) {
                    continue;
                }
                JsonObject object = entry.getValue().getAsJsonObject();
                CACHE.put(entry.getKey(), new Summary(
                        object.has("text") && !object.get("text").isJsonNull()
                                ? object.get("text").getAsString() : "",
                        object.has("coveredAt") ? object.get("coveredAt").getAsLong() : 0L,
                        object.has("updatedAt") ? object.get("updatedAt").getAsLong() : 0L,
                        object.has("seen") ? object.get("seen").getAsInt() : 0));
            }
        } catch (Exception error) {
            OllamaProxyMod.LOGGER.warn("对话摘要读取失败，按空缓存继续", error);
        }
    }

    private static void save() {
        Path file = filePath();
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            for (Map.Entry<String, Summary> entry : CACHE.entrySet()) {
                Summary summary = entry.getValue();
                JsonObject object = new JsonObject();
                object.addProperty("text", summary.text());
                object.addProperty("coveredAt", summary.coveredAt());
                object.addProperty("updatedAt", summary.updatedAt());
                object.addProperty("seen", summary.seenEntries());
                root.add(entry.getKey(), object);
            }
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException error) {
            OllamaProxyMod.LOGGER.warn("对话摘要写入失败", error);
        }
    }
}