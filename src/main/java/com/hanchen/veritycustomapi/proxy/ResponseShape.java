package com.hanchen.veritycustomapi.proxy;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * OpenAI Chat 响应的「组装」与「抽取」。
 *
 * <p>抽自 {@code OllamaProxyServer.ChatProxyHandler}。这块代码在 3000 行的处理器里，
 * 但**一行都不碰 HTTP**：输入是 JSON/字符串，输出也是 JSON/字符串。
 * 它被三条完全不同的路径共用（透传、缓冲转流式、SSE 聚合），
 * 混在处理器里时改一个字段名要在三个地方分别确认，很容易只改到其中两条。
 *
 * <p>约定：**任何解析失败都不抛异常给调用方**——响应组装/抽取发生在请求链路的末端，
 * 这里抛出去只会让玩家看到连接被重置。拿不到就返回空串，由上层决定兜底文案。
 */
public final class ResponseShape {

    private static final Gson GSON = new Gson();

    private ResponseShape() {
    }

    /**
     * 把一段纯文本包成 OpenAI 的 {@code choices[].message.content} 响应。
     *
     * <p>必须是**纯文本**：Verity 会把 content 原样打印到聊天栏，塞 JSON 进去玩家会看到一坨括号。
     */
    public static String buildTextResponse(String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", content);

        JsonObject choice = new JsonObject();
        choice.add("message", message);
        choice.addProperty("finish_reason", "stop");
        choice.addProperty("index", 0);

        JsonArray choices = new JsonArray();
        choices.add(choice);

        JsonObject result = new JsonObject();
        result.addProperty("id", "chatcmpl-verity-proxy");
        result.addProperty("object", "chat.completion");
        result.addProperty("created", System.currentTimeMillis() / 1000L);
        result.addProperty("model", "custom");
        result.add("choices", choices);

        JsonObject usage = new JsonObject();
        usage.addProperty("prompt_tokens", 0);
        usage.addProperty("completion_tokens", 0);
        usage.addProperty("total_tokens", 0);
        result.add("usage", usage);
        return GSON.toJson(result);
    }

    /**
     * 把一次性 Chat 响应转换成一帧 SSE，供「客户端要流式、上游给的是整包」的场景使用。
     *
     * <p>tool_calls / function_call 原样放进 delta——少了它们，靠工具调用完成的回复会变成空消息。
     */
    public static String toBufferedSse(String responseJson) {
        try {
            JsonObject response = JsonParser.parseString(responseJson).getAsJsonObject();
            JsonObject originalChoice = response.getAsJsonArray("choices").get(0).getAsJsonObject();
            JsonObject message = originalChoice.getAsJsonObject("message");
            JsonObject delta = new JsonObject();
            delta.addProperty("role", message.has("role") ? message.get("role").getAsString() : "assistant");
            if (message.has("content")) delta.add("content", message.get("content"));
            if (message.has("tool_calls")) delta.add("tool_calls", message.get("tool_calls"));
            if (message.has("function_call")) delta.add("function_call", message.get("function_call"));

            JsonObject choice = new JsonObject();
            choice.add("delta", delta);
            choice.add("finish_reason", originalChoice.has("finish_reason")
                    ? originalChoice.get("finish_reason") : com.google.gson.JsonNull.INSTANCE);
            choice.addProperty("index", 0);

            JsonObject chunk = new JsonObject();
            if (response.has("id")) chunk.add("id", response.get("id"));
            if (response.has("created")) chunk.add("created", response.get("created"));
            if (response.has("model")) chunk.add("model", response.get("model"));
            chunk.addProperty("object", "chat.completion.chunk");
            JsonArray choices = new JsonArray();
            choices.add(choice);
            chunk.add("choices", choices);
            return "data: " + GSON.toJson(chunk) + "\n\ndata: [DONE]\n\n";
        } catch (Exception error) {
            throw new IllegalArgumentException("无法将 Chat 响应转换为 SSE", error);
        }
    }

    /** 上游没给有效内容时的兜底回复；同样必须是纯文本。 */
    public static String emptyReplyFallback() {
        return buildTextResponse("上游模型本次没有返回有效内容，请重新发送消息。");
    }

    /**
     * 从任意「像响应」的 JSON 里抠出可读正文。
     *
     * <p>依次尝试 OpenAI {@code choices[].message.content}、{@code choices[].text}、
     * Ollama {@code message.content}、{@code response}。
     * content 为空时会去看 {@code reasoning_content} / {@code reasoning}——
     * DeepSeek、GLM 这类推理模型偶尔把正文整段放进思考字段，
     * 不看这里就会把一轮正常回复判成「空回复」。
     */
    public static String extractResponseText(String processedResponse) {
        if (processedResponse == null || processedResponse.isBlank()) return "";
        try {
            JsonObject respJson = GSON.fromJson(processedResponse, JsonObject.class);
            if (respJson == null) return "";

            if (respJson.has("choices") && respJson.get("choices").isJsonArray()
                    && !respJson.getAsJsonArray("choices").isEmpty()) {
                JsonObject choice = respJson.getAsJsonArray("choices").get(0).getAsJsonObject();
                if (choice.has("message") && choice.get("message").isJsonObject()) {
                    JsonObject message = choice.getAsJsonObject("message");
                    String content = extractContentText(message.get("content"));
                    String structured = extractStructuredReply(content);
                    if (!structured.isBlank()) return structured;
                    if (content.isBlank()) {
                        String salvaged = ThinkingTags.strip(extractContentText(message.get("reasoning_content")));
                        if (salvaged.isBlank()) {
                            salvaged = ThinkingTags.strip(extractContentText(message.get("reasoning")));
                        }
                        if (!salvaged.isBlank()) return salvaged;
                    }
                }
                String choiceText = extractContentText(choice.get("text"));
                if (!choiceText.isBlank()) return choiceText;
            }

            if (respJson.has("message")) {
                JsonElement message = respJson.get("message");
                String text = message.isJsonObject()
                        ? extractContentText(message.getAsJsonObject().get("content"))
                        : extractContentText(message);
                if (!text.isBlank()) return text;
            }

            String response = extractContentText(respJson.get("response"));
            return response.isBlank() ? "" : response;
        } catch (Exception error) {
            return "";
        }
    }

    /** content 本身是一层 JSON 信封（{@code {"message":…}} / {@code {"response":…}}）时再往下剥一层。 */
    public static String extractStructuredReply(String content) {
        if (content == null || content.isBlank()) return "";
        try {
            JsonElement parsed = JsonParser.parseString(content);
            if (!parsed.isJsonObject()) return content;
            JsonObject inner = parsed.getAsJsonObject();
            String message = extractContentText(inner.get("message"));
            if (!message.isBlank()) return message;
            String response = extractContentText(inner.get("response"));
            if (!response.isBlank()) return response;
            return "";
        } catch (Exception ignored) {
            return content;
        }
    }

    /** content 可能是字符串、数组（多段）或对象，统一抠成纯文本。 */
    public static String extractContentText(JsonElement content) {
        return ToolCallNormalizer.extractContentText(content);
    }

    /**
     * 响应里有没有「一次真正的助手动作」——即合法的 tool_calls 或 function_call。
     *
     * <p>这是「只请求工具、没有正文」的回复算不算成功的判据。之前没有它，
     * 这类回复会被当成空补全，玩家看到的是一句「没有返回有效内容」。
     */
    public static boolean hasValidAssistantOperation(String response) {
        if (response == null || response.isBlank()) return false;
        try {
            JsonObject root = GSON.fromJson(response, JsonObject.class);
            if (root == null) return false;
            if (root.has("choices") && root.get("choices").isJsonArray()
                    && !root.getAsJsonArray("choices").isEmpty()) {
                JsonObject choice = root.getAsJsonArray("choices").get(0).getAsJsonObject();
                if (choice.has("message") && choice.get("message").isJsonObject()) {
                    JsonObject message = choice.getAsJsonObject("message");
                    return ToolCallNormalizer.hasValidToolCalls(message.get("tool_calls"))
                            || ToolCallNormalizer.hasValidFunctionCall(message.get("function_call"));
                }
            }
            if (root.has("message") && root.get("message").isJsonObject()) {
                JsonObject message = root.getAsJsonObject("message");
                return ToolCallNormalizer.hasValidToolCalls(message.get("tool_calls"))
                        || ToolCallNormalizer.hasValidFunctionCall(message.get("function_call"));
            }
        } catch (Exception error) {
            return false;
        }
        return false;
    }

    /**
     * 计费路径用的「这次付费调用算不算成功」。
     *
     * @return 有正文时返回正文；只有工具调用时返回整包 JSON；都不成立返回 {@code null}
     */
    public static String validPaidResponseText(String response) {
        try {
            JsonObject root = GSON.fromJson(response, JsonObject.class);
            if (root == null) return null;
            String text = extractResponseText(response);
            if (text != null && !text.isBlank()) return text;
            return hasValidAssistantOperation(response) ? GSON.toJson(root) : null;
        } catch (Exception error) {
            return null;
        }
    }

    /**
     * 整包响应里有没有可用内容（正文或工具调用）。
     *
     * <p>与 {@link SseAggregator#hasUsableContent(String)} 的区别：那个专用于 SSE 聚合结果，
     * 这个用于任何形态的响应。两者判据必须一致，否则「流式成功、非流式失败」这种割裂会出现。
     */
    public static boolean hasUsableContent(String response) {
        return response != null
                && (!extractResponseText(response).isBlank() || hasValidAssistantOperation(response));
    }
}
