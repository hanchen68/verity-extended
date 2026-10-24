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
     * 清洗请求历史里「既无正文、也无有效工具调用」的 assistant 消息。
     *
     * <p>原版工具调用时 content 为空，判定须同时看 tool_calls / function_call；
     * 上游空补全会留下空 assistant，之后每次请求都被拒。
     *
     * <p>🔴 <b>未配对但格式合法的 tool_calls 必须保留</b>——不要顺手去校验它和
     * tool 消息的配对关系，那样会把正常历史也删掉。
     *
     * @return 清洗后的请求体；没有需要删除的内容时返回<b>原字符串</b>（不重建 JSON）
     */
    public static String sanitizeAssistantHistory(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) return rawBody;
        try {
            JsonObject body = GSON.fromJson(rawBody, JsonObject.class);
            if (body == null || !body.has("messages") || !body.get("messages").isJsonArray()) {
                return rawBody;
            }
            JsonArray messages = body.getAsJsonArray("messages");
            JsonArray sanitized = new JsonArray();
            int removed = 0;
            for (JsonElement element : messages) {
                if (!element.isJsonObject()) {
                    sanitized.add(element);
                    continue;
                }
                JsonObject message = element.getAsJsonObject();
                String role = extractContentText(message.get("role"));
                boolean invalidAssistant = "assistant".equals(role)
                        && extractContentText(message.get("content")).isBlank()
                        && !hasValidToolCalls(message.get("tool_calls"))
                        && !hasValidFunctionCall(message.get("function_call"));
                if (invalidAssistant) {
                    removed++;
                } else {
                    sanitized.add(element);
                }
            }
            if (removed == 0) return rawBody;
            body.add("messages", sanitized);
            LOGGER.warn("已从请求历史中移除 {} 条无内容且无工具调用的 assistant 消息", removed);
            return GSON.toJson(body);
        } catch (Exception error) {
            LOGGER.warn("assistant 历史清洗失败，将保留原请求体: {}", error.toString());
            return rawBody;
        }
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
