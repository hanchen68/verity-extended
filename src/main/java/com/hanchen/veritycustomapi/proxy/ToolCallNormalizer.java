package com.hanchen.veritycustomapi.proxy;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import org.apache.logging.log4j.Logger;

/**
 * 工具调用与消息内容的规范化。
 *
 * <p>从 {@code OllamaProxyServer.ChatProxyHandler} 里抽出来的纯静态逻辑——
 * 只做 JSON 读写，不碰网络、不碰实例状态，所以能独立成类并单独验证。
 *
 * <p>为什么值得单独一块：这几条判据看着短，但每条都对应一个踩过的坑
 * （空 assistant 会污染历史、未配对 tool_calls 必须保留、content 可能是
 * 字符串/对象/数组三种形态）。混在 3400 行的处理器里没人愿意改，抽出来才好维护。
 */
public final class ToolCallNormalizer {
    private static final Logger LOGGER = OllamaProxyMod.LOGGER;
    private static final Gson GSON = new Gson();

    private ToolCallNormalizer() {
    }

    /**
     * 从 content 里取纯文本。
     *
     * <p>content 有三种形态：字符串、对象（{@code {"text": ...}} 这类外壳）、
     * 数组（多段 part）。这里统一递归取，数组用换行拼接。
     */
    public static String extractContentText(JsonElement content) {
        if (content == null || content.isJsonNull()) return "";
        if (content.isJsonPrimitive()) return content.getAsString();
        if (content.isJsonObject()) {
            JsonObject object = content.getAsJsonObject();
            String[] textFields = {"text", "output_text", "content", "parts", "message", "response"};
            for (String field : textFields) {
                String value = extractContentText(object.get(field));
                if (!value.isBlank()) return value;
            }
            return "";
        }
        if (!content.isJsonArray()) return "";

        StringBuilder text = new StringBuilder();
        for (JsonElement part : content.getAsJsonArray()) {
            String value = extractContentText(part);
            if (!value.isBlank()) {
                if (!text.isEmpty()) text.append('\n');
                text.append(value);
            }
        }
        return text.toString();
    }

    /**
     * 清洗请求历史：删掉「既无正文、也无有效工具调用」的 assistant，
     * 并**顺带修复 tool 消息的配对**。
     *
     * <p>原版工具调用时 content 为空，判定须同时看 tool_calls / function_call；
     * 上游空补全会留下空 assistant，之后每次请求都被拒。
     *
     * <p>🔴 <b>为什么必须一起修配对</b>：删掉一条 assistant 之后，紧跟它的
     * {@code role:"tool"} 消息就成了孤儿，上游会直接 400：
     * <pre>Messages with role 'tool' must be a response to a preceding message with 'tool_calls'</pre>
     * 这个错以前只在「模型给过非法 tool_calls」时偶发，但**只修一半必然复发**——
     * 所以这里同一次遍历里把「没有前置 tool_calls 的 tool 消息」也一并丢弃。
     *
     * <p>注意仍然遵守：<b>格式合法但未配对的 tool_calls 要保留</b>（那是模型的输出，
     * 不是我们的垃圾），只清理孤儿的 tool 结果。
     *
     * @return 清洗后的请求体；没有需要改动的内容时返回<b>原字符串</b>（不重建 JSON）
     */
    public static String sanitizeAssistantHistory(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) return rawBody;
        try {
            JsonObject body = GSON.fromJson(rawBody, JsonObject.class);
            if (body == null) return rawBody;
            return sanitizeInPlace(body) ? GSON.toJson(body) : rawBody;
        } catch (Exception error) {
            LOGGER.warn("assistant 历史清洗失败，将保留原请求体: {}", error.toString());
            return rawBody;
        }
    }

    /**
     * 原地清洗：直接改传入的 {@link JsonObject}，**不做序列化**。
     *
     * <p>给「一次解析、一次序列化」的请求流水线用。原来每个阶段各自
     * {@code fromJson + toJson}，一条请求要解析 6 次、序列化 4 次，
     * 每次都是一次完整的树构建 + 字符串拼接。
     *
     * @return 是否真的改动过（调用方据此决定要不要重新序列化）
     */
    public static boolean sanitizeInPlace(JsonObject body) {
        if (body == null || !body.has("messages") || !body.get("messages").isJsonArray()) {
            return false;
        }
        try {
            JsonArray messages = body.getAsJsonArray("messages");
            JsonArray sanitized = new JsonArray();
            int removedAssistant = 0;
            int removedOrphanTool = 0;
            // 当前「已声明但还没被应答」的 tool_call id 集合。
            // 只在紧随其后的连续 tool 消息里有效；一旦出现别的角色就清空。
            java.util.Set<String> openCallIds = new java.util.HashSet<>();

            for (JsonElement element : messages) {
                if (!element.isJsonObject()) {
                    sanitized.add(element);
                    openCallIds.clear();
                    continue;
                }
                JsonObject message = element.getAsJsonObject();
                String role = extractContentText(message.get("role"));

                if ("assistant".equals(role)) {
                    boolean invalidAssistant = extractContentText(message.get("content")).isBlank()
                            && !hasValidToolCalls(message.get("tool_calls"))
                            && !hasValidFunctionCall(message.get("function_call"));
                    if (invalidAssistant) {
                        removedAssistant++;
                        // 它的 tool 子消息会在下一轮被判成孤儿并丢弃
                        openCallIds.clear();
                        continue;
                    }
                    sanitized.add(element);
                    openCallIds = collectCallIds(message.get("tool_calls"));
                    continue;
                }

                if ("tool".equals(role)) {
                    String callId = extractContentText(message.get("tool_call_id"));
                    // 没有前置 tool_calls 的 tool 消息 = 孤儿，直接丢，否则上游 400
                    if (callId.isBlank() || !openCallIds.contains(callId)) {
                        removedOrphanTool++;
                        continue;
                    }
                    openCallIds.remove(callId);
                    sanitized.add(element);
                    continue;
                }

                sanitized.add(element);
                openCallIds.clear();
            }

            if (removedAssistant == 0 && removedOrphanTool == 0) return false;
            body.add("messages", sanitized);
            LOGGER.warn("已清洗请求历史：移除 {} 条空 assistant、{} 条孤儿 tool 消息",
                    removedAssistant, removedOrphanTool);
            return true;
        } catch (Exception error) {
            LOGGER.warn("assistant 历史清洗失败，保留原请求体: {}", error.toString());
            return false;
        }
    }

    /** 取 tool_calls 里所有非空 id，用于判断后续 tool 消息有没有归属。 */
    private static java.util.Set<String> collectCallIds(JsonElement toolCalls) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        if (toolCalls == null || !toolCalls.isJsonArray()) return ids;
        for (JsonElement element : toolCalls.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            String id = extractContentText(element.getAsJsonObject().get("id"));
            if (!id.isBlank()) ids.add(id);
        }
        return ids;
    }


    /** tool_calls 数组是否格式合法（每项要有 id、type=function、function.name+arguments）。 */
    public static boolean hasValidToolCalls(JsonElement toolCalls) {
        if (toolCalls == null || !toolCalls.isJsonArray() || toolCalls.getAsJsonArray().isEmpty()) {
            return false;
        }
        for (JsonElement element : toolCalls.getAsJsonArray()) {
            if (!element.isJsonObject()) return false;
            JsonObject call = element.getAsJsonObject();
            if (!hasNonBlankPrimitive(call, "id")
                    || !"function".equals(extractContentText(call.get("type")))
                    || !hasNamedFunction(call.get("function"))) {
                return false;
            }
        }
        return true;
    }

    public static boolean hasValidFunctionCall(JsonElement functionCall) {
        return hasNamedFunction(functionCall);
    }

    public static boolean hasNamedFunction(JsonElement function) {
        if (function == null || !function.isJsonObject()) return false;
        JsonObject object = function.getAsJsonObject();
        return hasNonBlankPrimitive(object, "name")
                && object.has("arguments") && object.get("arguments").isJsonPrimitive();
    }

    public static boolean hasNonBlankPrimitive(JsonObject object, String field) {
        return object.has(field) && object.get(field).isJsonPrimitive()
                && !object.get(field).getAsString().isBlank();
    }

    /** 取工具声明里的函数名；不是 {@code {"function":{"name":...}}} 结构时返回空串。 */
    public static String toolNameOf(JsonObject tool) {
        if (tool == null || !tool.has("function") || !tool.get("function").isJsonObject()) {
            return "";
        }
        JsonObject function = tool.getAsJsonObject("function");
        return function.has("name") && function.get("name").isJsonPrimitive()
                ? function.get("name").getAsString() : "";
    }
}
