package com.hanchen.veritycustomapi.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hanchen.veritycustomapi.OllamaProxyMod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 对话与日记的统一导出/导入。
 *
 * <p>导出是结构化 JSON（对话 + 日记），导入时按「时间 + 正文」去重后合并进本地 JSONL；
 * 同时兼容直接选择本地 conversations.jsonl / diary.jsonl。
 */
public final class VerityExportService {
    public static final String FORMAT = "verity_extended.export";
    private static final Gson GSON = new Gson();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private VerityExportService() {
    }

    public record ImportResult(int conversations, int diaries, String message) {
    }

    public static Path exportsDirectory() {
        return ConversationLogStore.filePath().getParent().resolve("exports");
    }

    /** 构建与导出文件相同的 JSON，用于云端上传。 */
    public static String buildExportJson(List<ConversationLogStore.Entry> conversations,
                                         List<DiaryManager.Entry> diaries) {
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT);
        root.addProperty("version", 1);
        root.addProperty("exportedAt", System.currentTimeMillis());
        root.addProperty("world", ConversationLogStore.currentWorldName());

        JsonArray conversationArray = new JsonArray();
        if (conversations != null) {
            for (ConversationLogStore.Entry entry : conversations) {
                JsonObject item = new JsonObject();
                item.addProperty("t", entry.at());
                item.addProperty("w", entry.world() == null ? "" : entry.world());
                item.addProperty("u", entry.user() == null ? "" : entry.user());
                item.addProperty("a", entry.assistant() == null ? "" : entry.assistant());
                conversationArray.add(item);
            }
        }
        root.add("conversations", conversationArray);

        JsonArray diaryArray = new JsonArray();
        if (diaries != null) {
            for (DiaryManager.Entry entry : diaries) {
                JsonObject item = new JsonObject();
                item.addProperty("t", entry.at());
                item.addProperty("w", entry.world() == null ? "" : entry.world());
                item.addProperty("d", entry.text() == null ? "" : entry.text());
                diaryArray.add(item);
            }
        }
        root.add("diaries", diaryArray);
        return GSON.toJson(root);
    }

    /** 导出当前全部对话和日记，返回文件路径；失败返回 null。 */
    public static Path exportAll(List<ConversationLogStore.Entry> conversations,
                                 List<DiaryManager.Entry> diaries, String label) {
        Path directory = exportsDirectory();
        try {
            Files.createDirectories(directory);
        } catch (IOException error) {
            OllamaProxyMod.LOGGER.warn("创建导出目录失败: {}", error.toString());
            return null;
        }
        Path target = directory.resolve("verity-export-" + label + "-"
                + STAMP.format(LocalDateTime.now()) + ".json");
        try {
            Files.writeString(target, buildExportJson(conversations, diaries), StandardCharsets.UTF_8);
            return target;
        } catch (IOException error) {
            OllamaProxyMod.LOGGER.warn("对话/日记导出失败: {}", error.toString());
            return null;
        }
    }

    /** 导出目录下可导入的文件，按修改时间倒序。 */
    public static List<Path> listImportableFiles() {
        Path directory = exportsDirectory();
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase();
                        return name.endsWith(".json") || name.endsWith(".jsonl");
                    })
                    .sorted(Comparator.comparingLong(VerityExportService::modified).reversed())
                    .toList();
        } catch (IOException error) {
            OllamaProxyMod.LOGGER.warn("读取导出目录失败: {}", error.toString());
            return List.of();
        }
    }

    public static Path latestExport() {
        List<Path> files = listImportableFiles();
        return files.isEmpty() ? null : files.get(0);
    }

    /** 导入一个导出文件；支持本扩展的 JSON、原始 JSONL 两种格式。 */
    public static ImportResult importFile(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return new ImportResult(0, 0, "文件不存在");
        }
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException error) {
            return new ImportResult(0, 0, "读取失败: " + error.getMessage());
        }
        return importJson(text);
    }

    /** 导入一段导出 JSON（云端恢复复用）。 */
    public static ImportResult importJson(String text) {
        if (text == null || text.isBlank()) {
            return new ImportResult(0, 0, "云端没有返回备份内容");
        }
        List<ConversationLogStore.Entry> conversations = new ArrayList<>();
        List<DiaryManager.Entry> diaries = new ArrayList<>();
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (parsed.isJsonObject() && FORMAT.equals(string(parsed.getAsJsonObject(), "format"))) {
                readExport(parsed.getAsJsonObject(), conversations, diaries);
            } else {
                readJsonLines(text, conversations, diaries);
            }
        } catch (Exception error) {
            return new ImportResult(0, 0, "无法识别的备份格式");
        }
        if (conversations.isEmpty() && diaries.isEmpty()) {
            return new ImportResult(0, 0, "备份里没有可导入的对话或日记");
        }
        int addedConversations = ConversationLogStore.importEntries(conversations);
        int addedDiaries = DiaryManager.importEntries(diaries);
        return new ImportResult(addedConversations, addedDiaries,
                "导入完成：对话 " + addedConversations + " 条，日记 " + addedDiaries + " 篇（重复项已跳过）");
    }

    private static void readExport(JsonObject root,
                                   List<ConversationLogStore.Entry> conversations,
                                   List<DiaryManager.Entry> diaries) {
        JsonArray conversationArray = root.has("conversations") && root.get("conversations").isJsonArray()
                ? root.getAsJsonArray("conversations") : new JsonArray();
        for (JsonElement element : conversationArray) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            conversations.add(new ConversationLogStore.Entry(
                    number(item, "t"), string(item, "w"), string(item, "u"), string(item, "a")));
        }
        JsonArray diaryArray = root.has("diaries") && root.get("diaries").isJsonArray()
                ? root.getAsJsonArray("diaries") : new JsonArray();
        for (JsonElement element : diaryArray) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            diaries.add(new DiaryManager.Entry(number(item, "t"), string(item, "w"), string(item, "d")));
        }
    }

    private static void readJsonLines(String text,
                                      List<ConversationLogStore.Entry> conversations,
                                      List<DiaryManager.Entry> diaries) {
        for (String line : text.split("\\R")) {
            if (line.isBlank()) continue;
            try {
                JsonObject item = JsonParser.parseString(line).getAsJsonObject();
                if (item.has("d")) {
                    diaries.add(new DiaryManager.Entry(
                            number(item, "t"), string(item, "w"), string(item, "d")));
                } else if (item.has("u") || item.has("a")) {
                    conversations.add(new ConversationLogStore.Entry(
                            number(item, "t"), string(item, "w"), string(item, "u"), string(item, "a")));
                }
            } catch (Exception ignored) {
                // 跳过单行坏数据，不中断整个导入
            }
        }
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : "";
    }

    private static long number(JsonObject object, String key) {
        try {
            return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsLong() : 0L;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static long modified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }
}
