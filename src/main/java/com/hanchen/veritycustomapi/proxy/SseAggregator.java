package com.hanchen.veritycustomapi.proxy;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把上游 SSE 流（{@code data: {...}}）聚合成一次性 OpenAI Chat 响应。
 *
 * <p>抽自 {@code OllamaProxyServer.ChatProxyHandler}。这是整条链路里最容易「静默出错」的一块：
 * 聚合结果不对时不会抛异常，只会变成一句「上游模型本次没有返回有效内容」，
 * 玩家和日志都看不出到底丢在哪一步。独立成类后可以单独喂字符串验证。
 *
 * <p>🔴 三个必须记住的协议细节：
 * <ol>
 *   <li><b>tool_calls 必须聚合</b>。流式协议里它按 index 分片下发：id/type/name 只在首片，
 *       arguments 逐片累加。不做聚合，「模型只请求工具调用、没有正文」的响应会被判成空回复。</li>
 *   <li><b>reasoning 不能丢</b>。DeepSeek / GLM 等推理模型把正文放进
 *       {@code reasoning_content} / {@code reasoning}，content 一直是空串。
 *       只用 content 判空，会把一整轮正常回复判成空。</li>
 *   <li><b>缺 id 要补</b>。少数中转站会丢掉 tool_call 的 id，Verity 就无法把工具结果配回去。
 *       补一个稳定 id（按 index 生成）即可，只要同一段历史里前后一致，上游就接受。</li>
 * </ol>
 */
public final class SseAggregator {

    private static final Gson GSON = new Gson();
    private static final Logger LOGGER = OllamaProxyMod.LOGGER;

    private SseAggregator() {
    }

    /**
     * @param raw 上游原始响应文本（可能是 SSE，也可能直接是一整包 JSON）
     * @return 一次性 Chat 响应 JSON；无法识别返回 {@code null}
     */
    public static String aggregate(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        // 上游直接返回一次性 JSON（无 data: 前缀）：原样透传
        if (!trimmed.startsWith("data:") && !trimmed.contains("\ndata:") && trimmed.startsWith("{")) {
            return trimmed;
        }

        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        Map<Integer, JsonObject> toolCallsByIndex = new LinkedHashMap<>();
        String model = null;
        String finishReason = null;
        String responseId = null;
        long created = 0L;
        int totalChunks = 0;
        boolean seenAny = false;

        for (String line : trimmed.split("\\r?\\n")) {
            if (!line.startsWith("data:")) continue;
            String payload = line.substring(5).trim();
            if (payload.isEmpty()) continue;
            if ("[DONE]".equals(payload)) break;
            seenAny = true;
            try {
                JsonObject obj = GSON.fromJson(payload, JsonObject.class);
                if (obj == null) continue;
                if (responseId == null && obj.has("id") && obj.get("id").isJsonPrimitive()) {
                    responseId = obj.get("id").getAsString();
                }
                if (created == 0L && obj.has("created") && obj.get("created").isJsonPrimitive()) {
                    created = obj.get("created").getAsLong();
                }
                if (model == null && obj.has("model") && obj.get("model").isJsonPrimitive()) {
                    model = obj.get("model").getAsString();
                }
                if (!obj.has("choices")) continue;
                JsonArray choices = obj.getAsJsonArray("choices");
                if (choices.isEmpty()) continue;
                JsonObject choice = choices.get(0).getAsJsonObject();
                if (finishReason == null && choice.has("finish_reason")
                        && !choice.get("finish_reason").isJsonNull()) {
                    finishReason = choice.get("finish_reason").getAsString();
                }
                if (choice.has("delta")) {
                    JsonObject delta = choice.getAsJsonObject("delta");
                    if (delta.has("content") && !delta.get("content").isJsonNull()) {
                        content.append(delta.get("content").getAsString());
                        totalChunks++;
                    }
                    appendText(reasoning, delta, "reasoning_content");
                    appendText(reasoning, delta, "reasoning");
                    mergeToolCallDeltas(toolCallsByIndex, delta.get("tool_calls"));
                } else if (choice.has("message")) {
                    // 兼容个别服务返回的 message 字段
                    JsonObject message = choice.getAsJsonObject("message");
                    if (message.has("content") && !message.get("content").isJsonNull()) {
                        content.append(message.get("content").getAsString());
                        totalChunks++;
                    }
                    appendText(reasoning, message, "reasoning_content");
                    appendText(reasoning, message, "reasoning");
                    mergeToolCallDeltas(toolCallsByIndex, message.get("tool_calls"));
                }
            } catch (Exception ignore) {
                // 单个 chunk 解析失败不影响整体
            }
        }

        if (!seenAny) return null;

        String text = content.toString();
        if (text.isBlank() && !reasoning.isEmpty()) {
            // 极少数情况下推理模型把正文整段放进 reasoning_content，content 一直是空串。
            // 用它兜底总好过直接告诉玩家「没有有效内容」，同时去掉思考标签避免把内心独白念出来。
            String salvaged = ThinkingTags.strip(reasoning.toString()).trim();
            if (!salvaged.isBlank()) {
                text = salvaged;
                LOGGER.warn("上游 content 为空，已改用 reasoning 内容兜底（{} 字符）", salvaged.length());
            }
        }

        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", text);
        if (!toolCallsByIndex.isEmpty()) {
            JsonArray toolCalls = new JsonArray();
            for (Map.Entry<Integer, JsonObject> entry : toolCallsByIndex.entrySet()) {
                JsonObject merged = entry.getValue();
                JsonObject call = new JsonObject();
                // index 只是流式分片的产物，非流式 schema 里没有，去掉避免下游解析器困惑
                call.addProperty("type", merged.has("type") ? merged.get("type").getAsString() : "function");
                if (merged.has("id") && !merged.get("id").getAsString().isBlank()) {
                    call.addProperty("id", merged.get("id").getAsString());
                } else {
                    // 少数中转站会把 id 丢掉。没有 id 时 Verity 无法把工具结果配回去，
                    // 会被判成空回复；这里补一个稳定 id，只要请求历史里的配对一致，上游就接受。
                    String synthesized = "call_verity_" + entry.getKey();
                    call.addProperty("id", synthesized);
                    LOGGER.warn("上游 tool_calls 分片缺少 id，已补合成 id={}", synthesized);
                }
                call.add("function", merged.has("function") ? merged.get("function") : new JsonObject());
                toolCalls.add(call);
            }
            message.add("tool_calls", toolCalls);
            LOGGER.info("流式聚合得到工具调用: {}", ToolCallNormalizer.describe(toolCalls));
        }

        JsonObject choice = new JsonObject();
        choice.add("message", message);
        choice.addProperty("finish_reason", finishReason != null ? finishReason : "stop");
        choice.addProperty("index", 0);

        JsonArray choices = new JsonArray();
        choices.add(choice);

        JsonObject result = new JsonObject();
        if (responseId != null) result.addProperty("id", responseId);
        if (model != null) result.addProperty("model", model);
        if (created > 0L) result.addProperty("created", created);
        result.add("choices", choices);
        result.addProperty("object", "chat.completion");
        LOGGER.info("SSE 聚合 chunks={}, contentLength={}, reasoningLength={}, toolCalls={}",
                totalChunks, text.length(), reasoning.length(), toolCallsByIndex.size());
        return GSON.toJson(result);
    }

    /**
     * 聚合结果里有没有「玩家能看到的东西」。
     *
     * <p>既没正文也没工具调用就是空补全，调用方据此决定要不要重试。
     * 注意这里**必须**把 tool_calls 算作有效内容，否则「只请求工具」的回复会被误判成空。
     */
    public static boolean hasUsableContent(String aggregated) {
        if (aggregated == null || aggregated.isBlank()) return false;
        try {
            JsonObject json = GSON.fromJson(aggregated, JsonObject.class);
            if (json == null || !json.has("choices")) return false;
            JsonArray choices = json.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) return false;
            JsonObject choice = choices.get(0).getAsJsonObject();
            if (!choice.has("message")) return false;
            JsonObject message = choice.getAsJsonObject("message");
            JsonElement content = message.get("content");
            if (content != null && !content.isJsonNull() && content.isJsonPrimitive()
                    && !content.getAsString().isBlank()) {
                return true;
            }
            JsonElement toolCalls = message.get("tool_calls");
            if (toolCalls != null && toolCalls.isJsonArray() && !toolCalls.getAsJsonArray().isEmpty()) {
                return true;
            }
            JsonElement functionCall = message.get("function_call");
            return functionCall != null && functionCall.isJsonObject();
        } catch (Exception error) {
            return false;
        }
    }

    private static void appendText(StringBuilder target, JsonObject source, String field) {
        if (source == null || !source.has(field)) return;
        JsonElement value = source.get(field);
        if (value == null || value.isJsonNull() || !value.isJsonPrimitive()) return;
        target.append(value.getAsString());
    }

    /**
     * 把流式 tool_calls 分片按 index 合并成完整的工具调用。
     * 协议：首片带 id/type/function.name，后续片只带 function.arguments 的增量。
     *
     * <p>行为与原 {@code ChatProxyHandler.mergeToolCallDeltas} 逐字一致：
     * 缺 index 时按 0 处理；合并后的对象里会留一个 {@code index} 字段
     * （组装最终消息时才丢弃）。**不要在这里「顺手清理」字段**——
     * 每一处清理都要和下游解析器的容忍度对照，改错就是整条工具链路静默失效。
     */
    private static void mergeToolCallDeltas(Map<Integer, JsonObject> target, JsonElement toolCalls) {
        if (toolCalls == null || !toolCalls.isJsonArray()) return;
        for (JsonElement element : toolCalls.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject call = element.getAsJsonObject();
            int index = call.has("index") && call.get("index").isJsonPrimitive()
                    ? call.get("index").getAsInt() : 0;
            JsonObject merged = target.computeIfAbsent(index, key -> {
                JsonObject created = new JsonObject();
                created.addProperty("index", key);
                return created;
            });
            if (call.has("id") && !call.get("id").isJsonNull()) {
                merged.addProperty("id", call.get("id").getAsString());
            }
            if (call.has("type") && !call.get("type").isJsonNull()) {
                merged.addProperty("type", call.get("type").getAsString());
            }
            if (call.has("function") && call.get("function").isJsonObject()) {
                JsonObject incoming = call.getAsJsonObject("function");
                JsonObject function = merged.has("function") && merged.get("function").isJsonObject()
                        ? merged.getAsJsonObject("function") : new JsonObject();
                if (incoming.has("name") && !incoming.get("name").isJsonNull()) {
                    function.addProperty("name", incoming.get("name").getAsString());
                }
                if (incoming.has("arguments") && !incoming.get("arguments").isJsonNull()) {
                    String existing = function.has("arguments") ? function.get("arguments").getAsString() : "";
                    function.addProperty("arguments", existing + incoming.get("arguments").getAsString());
                }
                merged.add("function", function);
            }
        }
    }
}
