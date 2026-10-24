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
     * <p>🔴 <b>两个方向都要修</b>，只修一半必然复发：
     * <ul>
     *   <li><b>孤儿 tool</b>：{@code role:"tool"} 前面没有对应的 tool_calls → 丢弃。
     *       上游报 {@code Messages with role 'tool' must be a response to a preceding message with 'tool_calls'}。</li>
     *   <li><b>缺回复的 tool_calls</b>：assistant 声明了 N 个 tool_calls，
     *       后面只跟了 M &lt; N 条 tool → <b>补 M..N 的占位 tool 消息</b>。
     *       上游报 {@code An assistant message with 'tool_calls' must be followed by tool messages
     *       responding to each 'tool_calls' ... (insufficient tool messages following tool_calls message)}。
     *       2026-10-25 用户实测就是这个 400（Verity 执行工具失败时不会回传结果）。</li>
     * </ul>
     *
     * <p>补齐而不是丢弃 assistant：tool_calls 是模型的真实输出，丢掉等于篡改历史；
     * 补一条 {@code {"error":"missing_tool_result"}} 既满足协议又保留上下文。
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
            int addedMissingTool = 0;
            // 当前「已声明但还没被应答」的 tool_call id，保持声明顺序（补位时按原顺序生成）。
            // 只在紧随其后的连续 tool 消息里有效；一旦出现别的角色就结清并清空。
            java.util.Set<String> openCallIds = new java.util.LinkedHashSet<>();

            for (JsonElement element : messages) {
                if (!element.isJsonObject()) {
                    addedMissingTool += closePending(sanitized, openCallIds);
                    sanitized.add(element);
                    continue;
                }
                JsonObject message = element.getAsJsonObject();
                String role = extractContentText(message.get("role"));

                if ("assistant".equals(role)) {
                    boolean invalidAssistant = extractContentText(message.get("content")).isBlank()
                            && !hasValidToolCalls(message.get("tool_calls"))
                            && !hasValidFunctionCall(message.get("function_call"));
                    // 上一条 assistant 的未应答调用到此为止，先补齐
                    addedMissingTool += closePending(sanitized, openCallIds);
                    if (invalidAssistant) {
                        removedAssistant++;
                        // 它的 tool 子消息会在下一轮被判成孤儿并丢弃
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

                addedMissingTool += closePending(sanitized, openCallIds);
                sanitized.add(element);
            }
            // 请求以 tool_calls 结尾（后面什么都没跟）也要补齐
            addedMissingTool += closePending(sanitized, openCallIds);

            if (removedAssistant == 0 && removedOrphanTool == 0 && addedMissingTool == 0) return false;
            body.add("messages", sanitized);
            LOGGER.warn("已修复请求历史：移除 {} 条空 assistant、{} 条孤儿 tool 消息、补齐 {} 条缺失的 tool 结果",
                    removedAssistant, removedOrphanTool, addedMissingTool);
            return true;
        } catch (Exception error) {
            LOGGER.warn("assistant 历史清洗失败，保留原请求体: {}", error.toString());
            return false;
        }
    }

    /**
     * 给 {@code pending} 里剩下的每个 tool_call id 补一条占位 tool 消息，并清空 pending。
     *
     * @return 补了几条
     */
    private static int closePending(JsonArray sanitized, java.util.Set<String> pending) {
        if (pending.isEmpty()) return 0;
        int added = 0;
        for (String callId : pending) {
            JsonObject placeholder = new JsonObject();
            placeholder.addProperty("role", "tool");
            placeholder.addProperty("tool_call_id", callId);
            placeholder.addProperty("content", MISSING_TOOL_RESULT);
            sanitized.add(placeholder);
            added++;
        }
        pending.clear();
        return added;
    }

    /**
     * 占位 tool 结果的正文。
     *
     * <p>写成「合法 JSON + 明确说明缺失」：模型看到它就知道这次调用没有结果，
     * 不会把占位符当成真实数据去引用。
     */
    private static final String MISSING_TOOL_RESULT =
            "{\"error\":\"missing_tool_result\",\"note\":\"the tool was not executed or its result was not returned\"}";

    /** 取 tool_calls 里所有非空 id（保持声明顺序），用于判断后续 tool 消息有没有归属。 */
    private static java.util.Set<String> collectCallIds(JsonElement toolCalls) {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
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

    /**
     * 把工具调用列表压成一行日志，用于排查「一次回复里到底请求了几个工具」。
     *
     * <p>踩过的现象：Verity 一次给玩家好几本附魔书，只有一本是对的。
     * 到底是「模型一次就请求了多个工具调用」，还是「同一份请求被重复执行」，
     * 光看现象分不出来——这行把数量、工具名和参数一起打出来（参数截断），
     * 后几个如果是半截参数，就说明是流式分片被当成了独立调用。
     */
    public static String describe(JsonElement toolCalls) {
        if (toolCalls == null || !toolCalls.isJsonArray()) return "tool_calls=0";
        JsonArray array = toolCalls.getAsJsonArray();
        StringBuilder summary = new StringBuilder("tool_calls=").append(array.size());
        for (int i = 0; i < array.size(); i++) {
            JsonElement element = array.get(i);
            if (!element.isJsonObject()) continue;
            JsonObject call = element.getAsJsonObject();
            String name = "";
            String args = "";
            if (call.has("function") && call.get("function").isJsonObject()) {
                JsonObject function = call.getAsJsonObject("function");
                if (function.has("name") && !function.get("name").isJsonNull()) {
                    name = function.get("name").getAsString();
                }
                if (function.has("arguments") && !function.get("arguments").isJsonNull()) {
                    args = function.get("arguments").getAsString();
                }
            }
            if (args.length() > 300) {
                args = args.substring(0, 300) + "…(共 " + args.length() + " 字符)";
            }
            summary.append(" | #").append(i).append(' ').append(name).append(" args=").append(args);
        }
        return summary.toString();
    }
}
