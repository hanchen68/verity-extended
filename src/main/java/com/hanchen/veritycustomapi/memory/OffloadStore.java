package com.hanchen.veritycustomapi.memory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.client.ConversationLogStore;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 窗口外对话的逐字转存（config/verity_extended/context/offload/<世界>.json）。每世界按时间保序，
 * 最多 MAX_TURNS_PER_WORLD 条，单条 user/assistant 各截 800 字；recall_offload 从最新往回取，总输出不超 4000 字。
 * 热路径只入队，单线程异步落盘；配置目录不可用（自检环境）就只走内存。
 */
public final class OffloadStore {
    /** 一轮被转存的对话。 */
    public record Turn(long at, String user, String assistant) {
    }

    private static final int MAX_TURNS_PER_WORLD = 240;
    private static final int MAX_TEXT_CHARS = 800;
    private static final int MAX_REPLY_CHARS = 4000;
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private static final Gson GSON = new Gson();
    /** key 是净化后的世界名，跟文件名一致；写入只在单线程 worker 里发生。 */
    private static final Map<String, CopyOnWriteArrayList<Turn>> CACHE = new ConcurrentHashMap<>();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Verity-Offload-Store");
        thread.setDaemon(true);
        return thread;
    });

    static {
        load();
    }

    private OffloadStore() {
    }

    /** 收下一批被窗口裁掉的轮次，异步去重、截断再落盘；立刻返回，不阻塞对话。 */
    public static void recordTurns(String world, List<Turn> turns) {
        if (turns == null || turns.isEmpty()) {
            return;
        }
        String key = worldKey(world);
        WORKER.execute(() -> {
            CopyOnWriteArrayList<Turn> list = CACHE.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>());
            for (Turn turn : turns) {
                if (isDuplicate(list, turn)) {
                    continue;
                }
                list.add(new Turn(turn.at(), clip(turn.user(), MAX_TEXT_CHARS), clip(turn.assistant(), MAX_TEXT_CHARS)));
            }
            while (list.size() > MAX_TURNS_PER_WORLD) {
                list.remove(0);
            }
            save();
        });
    }

    /**
     * 按关键词捞回当前世界的转存对话；关键词为空就给最近的记录。
     * 从最新往回取到预算用满，输出按时间正序排，方便直接读。
     */
    public static String recall(String keyword) {
        String key = worldKey(ConversationLogStore.currentWorldName());
        List<Turn> list = CACHE.get(key);
        if (list == null || list.isEmpty()) {
            return "没有可捞回的转存对话。";
        }
        String needle = keyword == null ? "" : keyword.strip();
        List<Turn> picked = new ArrayList<>();
        int total = 0;
        for (int i = list.size() - 1; i >= 0 && total < MAX_REPLY_CHARS; i--) {
            Turn turn = list.get(i);
            if (!needle.isEmpty() && !turn.user().contains(needle) && !turn.assistant().contains(needle)) {
                continue;
            }
            String rendered = render(turn);
            if (total + rendered.length() > MAX_REPLY_CHARS) {
                break;
            }
            picked.add(turn);
            total += rendered.length();
        }
        if (picked.isEmpty()) {
            return "转存记录里没有找到与「" + needle + "」相关的内容。";
        }
        StringBuilder reply = new StringBuilder("共捞回 ").append(picked.size())
                .append(" 轮转存对话（按时间正序）：\n\n");
        for (int i = picked.size() - 1; i >= 0; i--) {
            reply.append(render(picked.get(i))).append('\n');
        }
        return reply.toString().strip();
    }

    private static String render(Turn turn) {
        StringBuilder line = new StringBuilder("[").append(TIME_FORMAT.format(
                LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(turn.at()), ZoneId.systemDefault())))
                .append("]\n玩家：").append(turn.user());
        if (!turn.assistant().isBlank()) {
            line.append("\nVerity：").append(turn.assistant());
        }
        return line.toString();
    }

    /** 近期已经有完全相同的一轮就跳过（对话重建之类的场景会把同一批旧轮再发一遍）。 */
    private static boolean isDuplicate(List<Turn> list, Turn candidate) {
        int checked = 0;
        for (int i = list.size() - 1; i >= 0 && checked < 50; i--, checked++) {
            Turn turn = list.get(i);
            if (turn.user().equals(candidate.user()) && turn.assistant().equals(candidate.assistant())) {
                return true;
            }
        }
        return false;
    }

    private static String clip(String text, int limit) {
        String value = text == null ? "" : text;
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    /** 世界名转文件名和缓存 key：把路径非法字符换掉。 */
    private static String worldKey(String world) {
        String value = world == null ? "" : world.strip();
        if (value.isBlank()) {
            value = "默认世界";
        }
        return value.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /** 配置目录拿不到时返回 null（自检环境），那就只走内存、不落盘。 */
    private static Path directory() {
        try {
            return FMLPaths.CONFIGDIR.get().resolve("verity_extended")
                    .resolve("context").resolve("offload");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void load() {
        Path dir = directory();
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (var files = Files.list(dir)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                String key = file.getFileName().toString().replaceFirst("\\.json$", "");
                try {
                    JsonArray array = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), JsonArray.class);
                    if (array == null) {
                        continue;
                    }
                    CopyOnWriteArrayList<Turn> list = new CopyOnWriteArrayList<>();
                    for (JsonElement element : array) {
                        if (!element.isJsonObject()) continue;
                        JsonObject object = element.getAsJsonObject();
                        list.add(new Turn(
                                object.has("t") ? object.get("t").getAsLong() : 0L,
                                object.has("u") && !object.get("u").isJsonNull() ? object.get("u").getAsString() : "",
                                object.has("a") && !object.get("a").isJsonNull() ? object.get("a").getAsString() : ""));
                    }
                    if (!list.isEmpty()) {
                        CACHE.put(key, list);
                    }
                } catch (Exception error) {
                    OllamaProxyMod.LOGGER.warn("转存记录 {} 读取失败，跳过", file.getFileName(), error);
                }
            }
        } catch (IOException error) {
            OllamaProxyMod.LOGGER.warn("转存目录读取失败，按空缓存继续", error);
        }
    }

    private static void save() {
        Path dir = directory();
        if (dir == null) {
            return;
        }
        try {
            Files.createDirectories(dir);
            for (Map.Entry<String, CopyOnWriteArrayList<Turn>> entry : CACHE.entrySet()) {
                JsonArray array = new JsonArray();
                for (Turn turn : entry.getValue()) {
                    JsonObject object = new JsonObject();
                    object.addProperty("t", turn.at());
                    object.addProperty("u", turn.user());
                    object.addProperty("a", turn.assistant());
                    array.add(object);
                }
                Files.writeString(dir.resolve(entry.getKey() + ".json"),
                        GSON.toJson(array), StandardCharsets.UTF_8);
            }
        } catch (IOException error) {
            OllamaProxyMod.LOGGER.warn("转存记录写入失败", error);
        }
    }
}
