package com.hanchen.veritycustomapi.client;

import com.hanchen.veritycustomapi.util.Silent;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.cloud.BillingService;
import com.hanchen.veritycustomapi.cloud.UsageMeter;
import com.hanchen.veritycustomapi.memory.ChatCompletionClient;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Verity 日记：把最近的对话丢给模型，写成一篇第一人称的当天记录。
 * 只在玩家主动要时才生成，不挂在退出世界上（那会儿服务器正在关，请求发不出去）。
 */
public final class DiaryManager {
    /** 一次生成最多喂多少条对话记录。 */
    private static final int MAX_SOURCE_ENTRIES = 60;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final Gson GSON = new Gson();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Verity-Diary");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean GENERATING = new AtomicBoolean();

    /** 一篇日记。 */
    public record Entry(long at, String world, String text) {
    }

    private DiaryManager() {
    }

    public static boolean isGenerating() {
        return GENERATING.get();
    }

    /** 按时间正序读取全部日记。 */
    public static List<Entry> load() {
        Path file = storePath();
        if (!Files.exists(file)) {
            return List.of();
        }
        List<Entry> entries = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line == null || line.isBlank()) {
                    continue;
                }
                try {
                    JsonObject object = JsonParser.parseString(line).getAsJsonObject();
                    String text = object.has("d") && !object.get("d").isJsonNull() ? object.get("d").getAsString() : "";
                    if (text.isBlank()) {
                        continue;
                    }
                    long at = object.has("t") ? object.get("t").getAsLong() : 0L;
                    String world = object.has("w") && !object.get("w").isJsonNull() ? object.get("w").getAsString() : "";
                    entries.add(new Entry(at, world, text));
                } catch (Exception ignored) { Silent.ignore("DiaryManager", ignored); }
            }
        } catch (IOException error) {
            OllamaProxyMod.LOGGER.warn("日记读取失败: {}", error.toString());
        }
        return entries;
    }

    /**
     * 导入外部日记：按「时间 + 正文」去重后追加到本地 JSONL。
     *
     * @return 实际新增篇数
     */
    public static int importEntries(List<Entry> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return 0;
        }
        Set<String> existing = new HashSet<>();
        for (Entry entry : load()) {
            existing.add(fingerprint(entry));
        }
        StringBuilder data = new StringBuilder();
        int added = 0;
        for (Entry entry : incoming) {
            if (entry == null || entry.text() == null || entry.text().isBlank()) {
                continue;
            }
            if (!existing.add(fingerprint(entry))) {
                continue;
            }
            JsonObject object = new JsonObject();
            object.addProperty("t", entry.at());
            object.addProperty("w", entry.world() == null ? "" : entry.world());
            object.addProperty("d", entry.text());
            data.append(GSON.toJson(object)).append(System.lineSeparator());
            added++;
        }
        if (added == 0) {
            return 0;
        }
        Path file = storePath();
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, data.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException error) {
            OllamaProxyMod.LOGGER.warn("日记导入失败: {}", error.toString());
            return 0;
        }
        return added;
    }

    private static String fingerprint(Entry entry) {
        return entry.at() + "\u0000" + entry.text();
    }

    public static Path storePath() {
        return FMLPaths.CONFIGDIR.get().resolve("verity_extended").resolve("diary.jsonl");
    }

    /**
     * 异步生成一篇新日记。
     *
     * @param status 状态回调，会在主线程被调用
     */
    public static void requestGenerate(Consumer<String> status) {
        if (!GENERATING.compareAndSet(false, true)) {
            notify(status, "§7【日记】正在写了，稍等一下");
            return;
        }
        List<ConversationLogStore.Entry> source = recentEntries();
        if (source.isEmpty()) {
            GENERATING.set(false);
            notify(status, "§c【日记】还没有对话记录，先和 Verity 聊几句吧");
            return;
        }
        notify(status, "§b【日记】正在整理最近的对话…");
        WORKER.execute(() -> {
            try {
                String text = generate(source, status);
                if (text != null && !text.isBlank()) {
                    append(text);
                    notify(status, "§b【日记】写好了，打开对话记录界面就能看到");
                }
            } catch (Throwable error) {
                OllamaProxyMod.LOGGER.warn("日记生成失败: {}", error.toString());
                notify(status, "§c【日记】生成失败：" + error.getMessage());
            } finally {
                GENERATING.set(false);
            }
        });
    }

    private static List<ConversationLogStore.Entry> recentEntries() {
        List<ConversationLogStore.Entry> all = ConversationLogStore.load(0);
        if (all.isEmpty()) {
            return List.of();
        }
        String world = ConversationLogStore.currentWorldName();
        List<ConversationLogStore.Entry> filtered = new ArrayList<>();
        for (ConversationLogStore.Entry entry : all) {
            // 归属世界为空的历史记录一并算进来，避免早期数据被当成不存在
            if (world == null || world.isBlank() || entry.world() == null
                    || entry.world().isBlank() || world.equals(entry.world())) {
                filtered.add(entry);
            }
        }
        if (filtered.isEmpty()) {
            filtered.addAll(all);
        }
        if (filtered.size() > MAX_SOURCE_ENTRIES) {
            return new ArrayList<>(filtered.subList(filtered.size() - MAX_SOURCE_ENTRIES, filtered.size()));
        }
        return filtered;
    }

    private static String generate(List<ConversationLogStore.Entry> source, Consumer<String> status) throws Exception {
        List<ConfigManager.ChatRoute> routes = ConfigManager.snapshotChatRoutes();
        if (routes.isEmpty()) {
            throw new IllegalStateException("没有可用的对话服务");
        }
        ConfigManager.ChatRoute route = routes.get(0);
        StringBuilder transcript = new StringBuilder();
        for (ConversationLogStore.Entry entry : source) {
            transcript.append("玩家: ").append(entry.user())
                    .append("\nVerity: ").append(entry.assistant()).append("\n\n");
        }
        String today = LocalDate.now().toString();
        String system = "你是 Verity，正在写自己的日记。用第一人称、口语化、自然的语气，"
                + "以「今天和玩家一起……」的方式记录这一天发生的事。"
                + "只写事件和感受，不要编造记录里没有的内容，不要输出 JSON 或标题以外的格式。";
        String user = "日期：" + today + "\n下面是最近的对话记录，请据此写一篇 150-300 字的日记：\n\n"
                + transcript + "\n只输出日记正文。";
        String requestJson = ChatCompletionClient.toJson(
                ChatCompletionClient.buildRequest(route.model(), system, user));

        boolean paidBuiltin = "builtin".equals(route.source()) && "paid".equalsIgnoreCase(route.tier());
        String requestId = BillingService.requestId();
        BillingService.Reservation reservation = null;
        if (paidBuiltin) {
            int required = Math.max(1, Math.max(route.cost(), Math.max(
                    ConfigManager.getMinDeductCost(), ConfigManager.getChatCost())));
            reservation = BillingService.reservePaidChat(requestId, route.id(), required);
            if (!reservation.success()) {
                notify(status, "§c【日记】" + reservation.error());
                return null;
            }
        }
        String responseBody;
        try {
            responseBody = ChatCompletionClient.complete(route, requestJson);
        } catch (Exception error) {
            if (reservation != null) {
                BillingService.releasePaidChat(reservation, "upstream_error");
            }
            throw error;
        }
        String text = ChatCompletionClient.extractText(responseBody).strip();
        if (text.isBlank()) {
            if (reservation != null) BillingService.releasePaidChat(reservation, "empty_response");
            return null;
        }
        if (reservation != null) {
            UsageMeter.ChatUsage usage = UsageMeter.chat(requestJson, responseBody, text, false);
            if (usage == null) {
                BillingService.releasePaidChat(reservation, "missing_usage");
                // 星绒 API 迁移用户由上游结算，缺少 usage 不应中断日记生成
                if (!reservation.legacySkipped()) return null;
            } else {
                BillingService.settlePaidChat(reservation, usage);
            }
        } else if ("builtin".equals(route.source())) {
            String tier = route.tier() != null ? route.tier() : "free";
            BillingService.reportChat(BillingService.requestId(), tier, route.id(),
                    UsageMeter.chat(requestJson, responseBody, text, true));
        }
        return text;
    }

    private static void append(String text) {
        Path file = storePath();
        try {
            Files.createDirectories(file.getParent());
            JsonObject object = new JsonObject();
            object.addProperty("t", System.currentTimeMillis());
            object.addProperty("w", ConversationLogStore.currentWorldName());
            object.addProperty("d", text);
            Files.writeString(file, GSON.toJson(object) + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException error) {
            OllamaProxyMod.LOGGER.warn("日记写入失败: {}", error.toString());
        }
    }

    public static String formatTime(long millis) {
        return TIME.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()));
    }

    /** 状态回调必须回到客户端线程，否则调用方会在工作线程里改界面。 */
    private static void notify(Consumer<String> status, String message) {
        if (status == null) {
            return;
        }
        net.minecraft.client.Minecraft minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        minecraft.execute(() -> status.accept(message));
    }
}
