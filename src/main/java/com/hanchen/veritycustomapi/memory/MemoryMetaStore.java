package com.hanchen.veritycustomapi.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记忆旁路元数据的持久化：每个世界存档一份 verity_extended_memory_meta.json。
 * 它只描述原版记忆条目，不掺和 verity_memory.json 的读写；元数据丢了或坏了，顶多退回默认分类和重要度。
 */
public final class MemoryMetaStore {
    private static final String FILE_NAME = "verity_extended_memory_meta.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** 命中时间戳的落盘节流：检索太频繁，不值得每次都写文件。 */
    private static final long ACCESS_SAVE_INTERVAL_MS = 5 * 60 * 1000L;

    private static volatile MemoryMetaStore instance;
    private static volatile MinecraftServer boundServer;

    private final File directory;
    private final Map<String, MemoryMeta> entries = new ConcurrentHashMap<>();
    private volatile boolean dirty;
    private volatile long lastSaveAt;

    private MemoryMetaStore(File directory) {
        this.directory = directory;
        if (!directory.exists()) {
            directory.mkdirs();
        }
        load();
    }

    /** 取当前世界的元数据仓库；服务器不可用时返回 null，调用方按「无元数据」降级。 */
    public static MemoryMetaStore get(MinecraftServer server) {
        if (server == null) {
            return null;
        }
        MemoryMetaStore current = instance;
        if (current != null && boundServer == server) {
            return current;
        }
        synchronized (MemoryMetaStore.class) {
            if (instance == null || boundServer != server) {
                instance = new MemoryMetaStore(server.getWorldPath(LevelResource.ROOT).toFile());
                boundServer = server;
                OllamaProxyMod.LOGGER.debug("记忆元数据仓库已加载: {} 条", instance.entries.size());
            }
            return instance;
        }
    }

    /** 退出世界时先落盘再释放，免得把节流期间的改动丢了，也免得带到下一个世界。 */
    public static void clear() {
        synchronized (MemoryMetaStore.class) {
            if (instance != null) {
                instance.flush();
            }
            instance = null;
            boundServer = null;
        }
    }

    /** 读元数据，缺失就按 key 前缀生成一份默认值并落库。 */
    public MemoryMeta meta(String key, long now, String defaultOwner) {
        MemoryMeta meta = entries.get(key);
        if (meta == null) {
            meta = MemoryMeta.defaults(key, now, defaultOwner);
            entries.put(key, meta);
            save();
        }
        return meta;
    }

    /** 只读查询，不生成默认值，给排序路径用。 */
    public MemoryMeta peek(String key) {
        return entries.get(key);
    }

    public Map<String, MemoryMeta> snapshot() {
        return new HashMap<>(entries);
    }

    public void put(String key, MemoryMeta meta) {
        entries.put(key, meta);
        save();
    }

    public void remove(String key) {
        if (entries.remove(key) != null) {
            save();
        }
    }

    /** 记一次检索命中：只改内存，最多每 5 分钟落一次盘，免得检索变成高频 IO。 */
    public void touchAccessed(String key, long now) {
        MemoryMeta meta = entries.get(key);
        if (meta == null) {
            return;
        }
        meta.markAccessed(now);
        dirty = true;
        if (now - lastSaveAt >= ACCESS_SAVE_INTERVAL_MS) {
            save();
        }
    }

    /** 把节流期间攒下的改动写盘，退出世界时调。 */
    public synchronized void flush() {
        if (dirty) {
            save();
        }
    }

    /** 清掉已经不存在的记忆对应的元数据，免得文件无限膨胀。 */
    public void retainOnly(Set<String> keys) {
        boolean changed = false;
        for (String key : new java.util.ArrayList<>(entries.keySet())) {
            if (!keys.contains(key)) {
                entries.remove(key);
                changed = true;
            }
        }
        if (changed) {
            save();
        }
    }

    private void load() {
        try {
            File file = new File(directory, FILE_NAME);
            if (!file.exists()) {
                return;
            }
            String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                if (!entry.getValue().isJsonObject()) {
                    continue;
                }
                MemoryMeta meta = GSON.fromJson(entry.getValue(), MemoryMeta.class);
                if (meta != null) {
                    entries.put(entry.getKey(), meta);
                }
            }
        } catch (Exception error) {
            OllamaProxyMod.LOGGER.warn("记忆元数据读取失败，按空仓库继续: {}", error.toString());
        }
    }

    private synchronized void save() {
        try {
            File file = new File(directory, FILE_NAME);
            JsonObject root = new JsonObject();
            for (Map.Entry<String, MemoryMeta> entry : entries.entrySet()) {
                root.add(entry.getKey(), GSON.toJsonTree(entry.getValue()));
            }
            Files.writeString(file.toPath(), GSON.toJson(root), StandardCharsets.UTF_8);
            dirty = false;
            lastSaveAt = System.currentTimeMillis();
        } catch (Exception error) {
            OllamaProxyMod.LOGGER.warn("记忆元数据写入失败: {}", error.toString());
        }
    }
}
