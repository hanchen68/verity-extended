package com.hanchen.veritycustomapi.memory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.NetworkSecurity;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 扩展层内部用的轻量 OpenAI 兼容调用（记忆抽取、日记生成这些），只管发一次请求、取回正文。
 * 计费和一些业务判断留给调用方，别和代理层的重逻辑搅在一起。
 */
public final class ChatCompletionClient {
    private static final Gson GSON = new Gson();
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 20000;

    private ChatCompletionClient() {
    }

    /** 发一次请求，返回原始响应体；HTTP 错误直接抛异常。 */
    public static String complete(ConfigManager.ChatRoute route, String requestJson) throws Exception {
        return complete(route, requestJson, READ_TIMEOUT_MS);
    }

    /**
     * 同上，但读超时可指定。
     *
     * 朗读路径必须用更短的超时：翻译晚回来一会儿就变成「先安静几秒再开口」，
     * 比不翻译还糟。这类调用宁可早点失败、退回原文。
     */
    public static String complete(ConfigManager.ChatRoute route, String requestJson, int readTimeoutMs)
            throws Exception {
        String target = buildTargetUrl(route.apiUrl());
        URL url = NetworkSecurity.requireSecureUri(target, "扩展请求 API 地址").toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "Java/17 (VerityCustomAPI/Aux)");
            String apiKey = route.apiKey();
            if (apiKey != null && !apiKey.isBlank()) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
            conn.setDoOutput(true);
            conn.setDoInput(true);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(Math.max(1000, readTimeoutMs));
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("Accept-Encoding", "identity");
            byte[] bytes = requestJson.getBytes(StandardCharsets.UTF_8);
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

    /** 从 OpenAI 兼容响应里取正文，content 是字符串或数组两种形态都要兼容。 */
    public static String extractText(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }
        try {
            return extractText(JsonParser.parseString(responseBody).getAsJsonObject());
        } catch (Exception ignored) {
            return "";
        }
    }

    /** 清洗模型输出：剥掉格式码和控制字符、压掉连续空行再截断。入库或展示前都得过这里。 */
    public static String sanitizeOutput(String raw, int maxChars) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String cleaned = raw
                .replaceAll("§[0-9a-fk-orxA-FK-ORX]", "")
                .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "")
                .replaceAll("\\n{3,}", "\n\n")
                .strip();
        return cleaned.length() <= maxChars ? cleaned : cleaned.substring(0, maxChars) + "…";
    }

    public static String extractText(JsonObject root) {
        if (root == null) {
            return "";
        }
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
    }

    /** 拼一条 system + user 的最小请求体。内部调用（摘要、记忆提炼）限 800 token，防它跑飞。 */
    public static JsonObject buildRequest(String model, String systemPrompt, String userPrompt) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", false);
        body.addProperty("temperature", 0.7);
        body.addProperty("max_tokens", 800);
        JsonArray messages = new JsonArray();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            JsonObject system = new JsonObject();
            system.addProperty("role", "system");
            system.addProperty("content", systemPrompt);
            messages.add(system);
        }
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", userPrompt);
        messages.add(user);
        body.add("messages", messages);
        return body;
    }

    public static String toJson(JsonObject object) {
        return GSON.toJson(object);
    }

    private static String buildTargetUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("API 地址为空");
        }
        String trimmed = baseUrl.trim();
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.endsWith("/chat/completions") ? trimmed : trimmed + "/chat/completions";
    }
}
