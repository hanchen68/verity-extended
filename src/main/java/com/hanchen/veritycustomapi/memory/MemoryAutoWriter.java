package com.hanchen.veritycustomapi.memory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.NetworkSecurity;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.cloud.BillingService;
import com.hanchen.veritycustomapi.cloud.UsageMeter;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 对话结束后自动挑出「值得长期记住的事」写进记忆。它单发一次轻量请求，不复用主回复的字段，
 * 免得破坏主回复那套严格 JSON 协议；代价是多一次请求，所以默认关闭，还带最小间隔节流。
 */
public final class MemoryAutoWriter {
    /** 一次最多写几条，免得模型把整段对话都记下来。 */
    private static final int MAX_ENTRIES_PER_RUN = 3;
    /** 单条记忆内容的长度上限。 */
    private static final int MAX_VALUE_LENGTH = 300;
    private static final int MIN_USER_MESSAGE_LENGTH = 4;
    private static final int MIN_ASSISTANT_MESSAGE_LENGTH = 8;

    private static final Gson GSON = new Gson();
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Verity-Memory-Writer");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicLong LAST_RUN_AT = new AtomicLong();
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean();
    /** 让位提示只打一次日志，免得每条对话都刷。 */
    private static final AtomicBoolean DEFERRED_NOTICE_LOGGED = new AtomicBoolean();

    private MemoryAutoWriter() {
    }

    /** 主链路的调用入口，判断这轮对话值不值得提取。必须秒回，请求和解析都丢后台线程。 */
    public static void consider(String userMessage, String assistantMessage) {
        if (!ConfigManager.isMemoryEnabled() || !ConfigManager.isMemoryAutoWriteEnabled()) {
            return;
        }
        // 6.2 起原版自带记忆提炼（MemoryRefiner，每 5 轮把对话提炼成一条长期记忆写进日记库），
        // 扩展的自动写入整体让位：免得两套提炼各请求一次模型，
        // 也不再写出跟新版分类前缀（like_ / dislike_ / place_ / player_traits / note_）对不上的 key。
        if (com.hanchen.veritycustomapi.compat.VerityStructureDetector.hasRefinedMemorySystem()) {
            if (DEFERRED_NOTICE_LOGGED.compareAndSet(false, true)) {
                OllamaProxyMod.LOGGER.info("检测到新版原版自带记忆提炼，扩展的自动记忆写入已让位（旧版保持不变）");
            }
            return;
        }
        if (userMessage == null || assistantMessage == null) {
            return;
        }
        String user = userMessage.strip();
        String assistant = assistantMessage.strip();
        if (user.length() < MIN_USER_MESSAGE_LENGTH || assistant.length() < MIN_ASSISTANT_MESSAGE_LENGTH) {
            return;
        }
        if (!VerityMemoryBridge.isAvailable()) {
            return;
        }
        long now = System.currentTimeMillis();
        long interval = ConfigManager.getMemoryAutoWriteMinIntervalSeconds() * 1000L;
        long last = LAST_RUN_AT.get();
        if (now - last < interval) {
            return;
        }
        if (!LAST_RUN_AT.compareAndSet(last, now)) {
            return;
        }
        if (!IN_FLIGHT.compareAndSet(false, true)) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            IN_FLIGHT.set(false);
            return;
        }
        String ownerId = VerityMemoryBridge.currentOwnerId();
        WORKER.execute(() -> {
            try {
                extract(server, ownerId, user, assistant);
            } catch (Throwable error) {
                OllamaProxyMod.LOGGER.warn("记忆自动提取失败: {}", error.toString());
            } finally {
                IN_FLIGHT.set(false);
            }
        });
    }

    /** 退出世界时清空节流状态，下次进来立刻能提取一次。 */
    public static void resetState() {
        LAST_RUN_AT.set(0L);
        IN_FLIGHT.set(false);
    }

    private static void extract(MinecraftServer server, String ownerId, String user, String assistant) {
        List<ConfigManager.ChatRoute> routes = ConfigManager.snapshotChatRoutes();
        if (routes.isEmpty()) {
            return;
        }
        ConfigManager.ChatRoute route = routes.get(0);
        boolean paidBuiltin = "builtin".equals(route.source()) && "paid".equalsIgnoreCase(route.tier());
        String requestId = BillingService.requestId();

        BillingService.Reservation reservation = null;
        String requestBody = null;
        String responseBody;
        try {
            requestBody = GSON.toJson(buildRequest(route, user, assistant));
            if (paidBuiltin) {
                int required = Math.max(1, Math.max(route.cost(), Math.max(
                        ConfigManager.getMinDeductCost(), ConfigManager.getChatCost())));
                reservation = BillingService.reservePaidChat(requestId, route.id(), required);
                if (!reservation.success()) {
                    OllamaProxyMod.LOGGER.info("记忆自动提取跳过：{}", reservation.error());
                    return;
                }
            }
            responseBody = post(route, requestBody);
        } catch (Exception error) {
            if (reservation != null) {
                BillingService.releasePaidChat(reservation, "upstream_error");
            }
            OllamaProxyMod.LOGGER.warn("记忆自动提取请求失败: {}", error.toString());
            return;
        }

        String text = extractText(responseBody);
        List<Entry> entries = parseEntries(text);
        if (entries.isEmpty()) {
            if (reservation != null) BillingService.releasePaidChat(reservation, "invalid_response");
            return;
        }
        if (reservation != null) {
            UsageMeter.ChatUsage usage = UsageMeter.chat(requestBody, responseBody, text, false);
            if (usage == null) {
                BillingService.releasePaidChat(reservation, "missing_usage");
                // 星绒 API 迁移用户由上游结算，缺 usage 不该中断记忆写入
                if (!reservation.legacySkipped()) return;
            } else {
                BillingService.settlePaidChat(reservation, usage);
            }
        } else if ("builtin".equals(route.source())) {
            String tier = route.tier() != null ? route.tier() : "free";
            BillingService.reportChat(BillingService.requestId(), tier, route.id(),
                    UsageMeter.chat(requestBody, responseBody, text, true));
        }
        server.execute(() -> {
            for (Entry entry : entries) {
                storeEntry(entry, ownerId);
            }
        });
    }

    private static void storeEntry(Entry entry, String ownerId) {
        MemoryCategory category = MemoryCategory.fromId(entry.category());
        String key = buildKey(entry.key(), category, ownerId);
        if (key.isEmpty()) {
            return;
        }
        String value = entry.value() == null ? "" : entry.value().strip();
        if (value.isEmpty()) {
            return;
        }
        if (value.length() > MAX_VALUE_LENGTH) {
            value = value.substring(0, MAX_VALUE_LENGTH);
        }
        Map<String, String> existing = VerityMemoryBridge.snapshot();
        String previous = existing.get(key);
        if (value.equals(previous)) {
            return;
        }
        if (VerityMemoryBridge.write(key, value, category, entry.importance(), ownerId)) {
            OllamaProxyMod.LOGGER.info("记忆已自动写入: key={}, category={}, importance={}{}",
                    key, category.id(), entry.importance(),
                    previous == null ? "" : "（覆盖旧值）");
        }
    }

    /** key 形态：{@code [p<短UUID>_]<分类>_<名称>}，按玩家隔离时不会互相覆盖。 */
    static String buildKey(String rawKey, MemoryCategory category, String ownerId) {
        String slug = VerityMemoryBridge.normalizeKey(rawKey);
        if (slug.isEmpty()) {
            return "";
        }
        String prefixed = slug.startsWith(category.id() + "_") ? slug : category.id() + "_" + slug;
        if (ownerId != null && !ownerId.isEmpty()) {
            String compact = ownerId.replace("-", "");
            String shortId = compact.length() > 8 ? compact.substring(0, 8) : compact;
            prefixed = "p" + shortId + "_" + prefixed;
        }
        return prefixed.length() > 128 ? prefixed.substring(0, 128) : prefixed;
    }

    private static JsonObject buildRequest(ConfigManager.ChatRoute route, String user, String assistant) {
        JsonObject body = new JsonObject();
        body.addProperty("model", route.model());
        body.addProperty("stream", false);
        body.addProperty("temperature", 0.1);

        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content",
                "你负责整理长期记忆。从下面这轮对话里提取「以后还用得上」的稳定信息，例如玩家的偏好、"
                        + "明确的事实、关键地点或约定。不要记录寒暄、一次性提问、临时状态、密钥或隐私信息。"
                        + "没有值得记住的内容时返回空数组。\n"
                        + "只输出 JSON 数组，不要输出解释或代码块围栏。每个元素格式：\n"
                        + "{\"key\":\"英文小写下划线名称\",\"value\":\"简短描述\","
                        + "\"category\":\"pref|fact|place|plan|temp\",\"importance\":1到5的整数}");
        messages.add(system);

        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", "【玩家说】" + user + "\n【Verity 回答】" + assistant);
        messages.add(userMessage);

        body.add("messages", messages);
        return body;
    }

    private static String post(ConfigManager.ChatRoute route, String body) throws Exception {
        String target = buildTargetUrl(route.apiUrl());
        URL url = NetworkSecurity.requireSecureUri(target, "记忆提取 Chat API 地址").toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "Java/17 (VerityCustomAPI/Memory)");
            String apiKey = route.apiKey();
            if (apiKey != null && !apiKey.isBlank()) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(20000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("Accept-Encoding", "identity");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = conn.getOutputStream()) {
                output.write(bytes);
                output.flush();
            }
            int status = conn.getResponseCode();
            InputStream input = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String response = "";
            if (input != null) {
                try (input) {
                    response = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            if (status >= 400) {
                throw new IllegalStateException("HTTP " + status);
            }
            return response;
        } finally {
            conn.disconnect();
        }
    }

    private static String buildTargetUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("记忆提取 Chat API 地址为空");
        }
        String trimmed = baseUrl.trim();
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.endsWith("/chat/completions") ? trimmed : trimmed + "/chat/completions";
    }

    private static String extractText(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            JsonArray choices = root.has("choices") && root.get("choices").isJsonArray()
                    ? root.getAsJsonArray("choices") : null;
            if (choices == null || choices.isEmpty()) {
                return "";
            }
            JsonObject choice = choices.get(0).getAsJsonObject();
            JsonObject message = choice.has("message") && choice.get("message").isJsonObject()
                    ? choice.getAsJsonObject("message") : null;
            if (message != null && message.has("content") && !message.get("content").isJsonNull()) {
                JsonElement content = message.get("content");
                return content.isJsonPrimitive() ? content.getAsString() : GSON.toJson(content);
            }
            return choice.has("text") && !choice.get("text").isJsonNull() ? choice.get("text").getAsString() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    /** 从模型回复里抠出第一个 JSON 数组，容忍代码块围栏和前后那些解释文字。 */
    static List<Entry> parseEntries(String text) {
        List<Entry> entries = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return entries;
        }
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return entries;
        }
        try {
            JsonElement parsed = JsonParser.parseString(text.substring(start, end + 1));
            if (!parsed.isJsonArray()) {
                return entries;
            }
            for (JsonElement element : parsed.getAsJsonArray()) {
                if (entries.size() >= MAX_ENTRIES_PER_RUN) {
                    break;
                }
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject object = element.getAsJsonObject();
                String key = stringField(object, "key");
                String value = stringField(object, "value");
                if (key.isEmpty() || value.isEmpty()) {
                    continue;
                }
                entries.add(new Entry(key, value, stringField(object, "category"), intField(object, "importance")));
            }
        } catch (Exception ignored) {
            // 模型没按要求输出就静默跳过，别影响正常对话
        }
        return entries;
    }

    private static String stringField(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) {
            return "";
        }
        return value.isJsonPrimitive() ? value.getAsString().strip() : "";
    }

    private static int intField(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) {
            return 3;
        }
        try {
            return MemoryMeta.clampImportance(value.getAsInt());
        } catch (RuntimeException ignored) {
            return 3;
        }
    }

    /** 一条待写入的记忆。 */
    record Entry(String key, String value, String category, int importance) {
    }
}
