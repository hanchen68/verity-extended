package com.hanchen.veritycustomapi.client;

import com.hanchen.veritycustomapi.util.Silent;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地对话记录：每轮问答都落成 JSONL（原版只留 20 条，这里另存一份），
 * 给游戏内查看、导出和生成日记用。写入丢给独立线程，不卡对话链路。
 */
public final class ConversationLogStore {
    private static final Gson GSON = new Gson();
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Verity-Conversation-Log");
        thread.setDaemon(true);
        return thread;
    });
    /** 累计一定条数后才做一次超限整理，避免每次都读整个文件。 */
    private static final AtomicInteger APPENDS_SINCE_TRIM = new AtomicInteger();

    /** 一条对话记录。 */
    public record Entry(long at, String world, String user, String assistant) {
    }

    private ConversationLogStore() {
    }

    public static void record(String user, String assistant) {
        if (!ConfigManager.isConversationLogEnabled()) {
            return;
        }
        if (user == null || user.isBlank() || assistant == null || assistant.isBlank()) {
            return;
        }
        long at = System.currentTimeMillis();
        String world = currentWorldName();
        WORKER.execute(() -> append(new Entry(at, world, user.strip(), assistant.strip())));
    }

    /** 读取最近的记录（按时间正序返回），limit 为 0 表示不限。 */
    public static List<Entry> load(int limit) {
        Path file = filePath();
        if (!Files.exists(file)) {
            return List.of();
        }
        List<Entry> entries = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                Entry entry = parse(line);
                if (entry != null) {
                    entries.add(entry);
                }
            }
        } catch (IOException error) {
            OllamaProxyMod.LOGGER.warn("对话记录读取失败: {}", error.toString());
        }
        if (limit > 0 && entries.size() > limit) {
            return new ArrayList<>(entries.subList(entries.size() - limit, entries.size()));
        }
        return entries;
    }

    /** 导出成人类可读的文本文件，返回导出路径。 */
    public static Path export(List<Entry> entries, String label) {
        Path directory = filePath().getParent().resolve("exports");
        try {
            Files.createDirectories(directory);
        } catch (IOException ignored) {
        }
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
        Path target = directory.resolve("conversations-" + label + "-" + stamp + ".txt");
        StringBuilder text = new StringBuilder();
        text.append("Verity 对话导出 · ").append(TIMESTAMP.format(LocalDateTime.now())).append('\n')
                .append("共 ").append(entries.size()).append(" 条\n\n");
        for (Entry entry : entries) {
            text.append('[').append(formatTime(entry.at())).append(']');
            if (entry.world() != null && !entry.world().isBlank()) {
                text.append(" (").append(entry.world()).append(')');
            }
            text.append('\n').append("玩家: ").append(entry.user())
                    .append('\n').append("Verity: ").append(entry.assistant()).append("\n\n");
        }
        try {
            Files.writeString(target, text.toString(), StandardCharsets.UTF_8);
            return target;
        } catch (IOException error) {
            OllamaProxyMod.LOGGER.warn("对话记录导出失败: {}", error.toString());
            return null;
        }
    }

    /**
     * 导入外部记录：按「时间 + 玩家 + 回复」去重后追加到本地 JSONL。
     *
     * @return 实际新增条数
     */
    public static int importEntries(List<Entry> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return 0;
        }
        Set<String> existing = new HashSet<>();
        for (Entry entry : load(0)) {
            existing.add(fingerprint(entry));
        }
        StringBuilder data = new StringBuilder();
        int added = 0;
        for (Entry entry : incoming) {
            if (entry == null || (isBlank(entry.user()) && isBlank(entry.assistant()))) {
                continue;
            }
            if (!existing.add(fingerprint(entry))) {
                continue;
            }
            JsonObject object = new JsonObject();
            object.addProperty("t", entry.at());
            object.addProperty("w", entry.world() == null ? "" : entry.world());
            object.addProperty("u", entry.user() == null ? "" : entry.user());
            object.addProperty("a", entry.assistant() == null ? "" : entry.assistant());
            data.append(GSON.toJson(object)).append(System.lineSeparator());
            added++;
        }
        if (added == 0) {
            return 0;
        }
        Path file = filePath();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, data.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException error) {
            OllamaProxyMod.LOGGER.warn("对话记录导入失败: {}", error.toString());
            return 0;
        }
        return added;
    }

    private static String fingerprint(Entry entry) {
        return entry.at() + "\u0000" + entry.user() + "\u0000" + entry.assistant();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static Path filePath() {
        return FMLPaths.CONFIGDIR.get().resolve("verity_extended").resolve("conversations.jsonl");
    }

    public static String formatTime(long millis) {
        return TIMESTAMP.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()));
    }

    /** 当前世界名，用于区分不同存档的记录。 */
    public static String currentWorldName() {
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                return server.getWorldData().getLevelName();
            }
        } catch (Throwable ignored) { Silent.ignore("ConversationLogStore", ignored); }
        try {
            return net.minecraft.client.Minecraft.getInstance().level == null
                    ? "" : net.minecraft.client.Minecraft.getInstance().level.dimension().location().getPath();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void append(Entry entry) {
        Path file = filePath();
        try {
            Files.createDirectories(file.getParent());
            JsonObject object = new JsonObject();
            object.addProperty("t", entry.at());
            object.addProperty("w", entry.world());
            object.addProperty("u", entry.user());
            object.addProperty("a", entry.assistant());
            Files.writeString(file, GSON.toJson(object) + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            maybeTrim(file);
        } catch (IOException error) {
            OllamaProxyMod.LOGGER.warn("对话记录写入失败: {}", error.toString());
        }
    }

    /** 超出上限时保留最近的部分并整体重写。 */
    private static void maybeTrim(Path file) {
        int max = Math.max(50, ConfigManager.getConversationLogMaxEntries());
        int appends = APPENDS_SINCE_TRIM.incrementAndGet();
        if (appends < Math.max(20, max / 10)) {
            return;
        }
        APPENDS_SINCE_TRIM.set(0);
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.size() <= max) {
                return;
            }
            List<String> kept = new ArrayList<>(lines.subList(lines.size() - max, lines.size()));
            Files.writeString(file, String.join(System.lineSeparator(), kept) + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            OllamaProxyMod.LOGGER.info("对话记录已整理: {} -> {} 条", lines.size(), kept.size());
        } catch (IOException error) {
            OllamaProxyMod.LOGGER.warn("对话记录整理失败: {}", error.toString());
        }
    }

    private static Entry parse(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            JsonObject object = JsonParser.parseString(line).getAsJsonObject();
            String user = object.has("u") && !object.get("u").isJsonNull() ? object.get("u").getAsString() : "";
            String assistant = object.has("a") && !object.get("a").isJsonNull() ? object.get("a").getAsString() : "";
            if (user.isBlank() && assistant.isBlank()) {
                return null;
            }
            long at = object.has("t") ? object.get("t").getAsLong() : 0L;
            String world = object.has("w") && !object.get("w").isJsonNull() ? object.get("w").getAsString() : "";
            return new Entry(at, world, user, assistant);
        } catch (Exception ignored) {
            return null;
        }
    }
}
