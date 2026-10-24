package com.hanchen.veritycustomapi.proxy;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * 请求体里 {@code stream} 标志的读写。
 *
 * <p>抽自 {@code OllamaProxyServer.ChatProxyHandler}。看着只有三行，但它们是
 * 「代理按非流式处理、客户端可能仍发 stream=true」这套约定的全部入口，
 * 混在 3300 行里容易被顺手改坏。
 *
 * <p>共同约定：**解析失败一律当没改过**，返回原字符串而不是抛异常——
 * 请求体坏掉时宁可原样转发，也不要因为读不懂 stream 就 500。
 */
public final class StreamFlags {
    private static final Gson GSON = new Gson();

    private StreamFlags() {
    }

    /** 客户端请求里的 stream 是不是 true；字段缺失或解析失败都按 false。 */
    public static boolean clientRequestedStream(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) return false;
        try {
            JsonObject json = GSON.fromJson(rawBody, JsonObject.class);
            if (json != null && json.has("stream") && json.get("stream").isJsonPrimitive()) {
                return json.get("stream").getAsBoolean();
            }
        } catch (Exception ignored) {
            // 解析失败按「没要求流式」处理
        }
        return false;
    }

    /**
     * 把请求体的 stream 强制改成 false。
     *
     * <p>代理本身按非流式处理，但仍要接住客户端发来的 stream=true。
     */
    public static String forceNonStream(String rawBody) {
        return withStream(rawBody, false);
    }

    /** 系统提示词注入完再强制 stream=true，让上游按 SSE 吐回来。 */
    public static String ensureStream(String rawBody) {
        return withStream(rawBody, true);
    }

    private static String withStream(String rawBody, boolean stream) {
        try {
            JsonObject json = GSON.fromJson(rawBody, JsonObject.class);
            if (json == null) return rawBody;
            json.addProperty("stream", stream);
            return GSON.toJson(json);
        } catch (Exception ignored) {
            return rawBody;
        }
    }
}
