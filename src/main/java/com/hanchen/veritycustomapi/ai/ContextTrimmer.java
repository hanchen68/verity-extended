package com.hanchen.veritycustomapi.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.client.ConversationLogStore;
import com.hanchen.veritycustomapi.cloud.UsageMeter;
import com.hanchen.veritycustomapi.compat.VerityStructureDetector;
import com.hanchen.veritycustomapi.memory.OffloadStore;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文裁剪：最近 KEEP_TURNS 轮逐字保留，更早的消息剥离思维链字段、截断超长的 tool 结果
 * 与 assistant 正文、旧截图换成占位；更早的轮次整体转存 OffloadStore，可用 recall_offload 按关键词捞回。
 * 裁剪边界落在 user 消息上，保证 tool_calls 与 tool 配对完整；解析失败原样返回。窗口仅 6.0 侧启用。
 */
public final class ContextTrimmer {
    /** 保留完整的最近对话轮数（含当前轮），更早的消息才做裁剪。 */
    private static final int KEEP_TURNS = 2;
    /** 滑动窗口保留的最近轮数，与 ConversationContext.RECENT_KEEP_TURNS 对齐。 */
    static final int WINDOW_TURNS = 12;
    /** 窗口 token 预算（中文约 0.75 token/字）：从最新往回累计，装不下的更早轮一并转存。 */
    private static final int WINDOW_BUDGET_TOKENS = 6_000;
    /** 旧轮 tool 结果保留长度：只留结论段，一次大段 JSON 往往上千 token。 */
    private static final int TOOL_OUTPUT_LIMIT = 320;
    /** 旧轮 assistant 正文保留长度。 */
    private static final int ASSISTANT_HISTORY_LIMIT = 800;
    private static final String TOOL_CUT_MARKER = "…[已截断省 token，完整结果可重新调用获取]";
    private static final String ASSISTANT_CUT_MARKER = "…[历史回复已截断省 token]";
    private static final String IMAGE_PLACEHOLDER = "[旧截图已省略]";
    /** 本请求是否发生过窗口转存；代理据此决定是否挂 recall_offload 工具。 */
    private static final ThreadLocal<Boolean> OFFLOADED_THIS_REQUEST = new ThreadLocal<>();

    private ContextTrimmer() {
    }

    /** 代理转发前的机械裁剪；没有可裁剪内容时原样返回（返回同一字符串）。 */
    public static String trim(String rawBody) {
        if (!isEnabled() || rawBody == null || rawBody.isBlank()) {
            return rawBody;
        }
        try {
            JsonObject body = JsonParser.parseString(rawBody).getAsJsonObject();
            if (!body.has("messages") || !body.get("messages").isJsonArray()) {
                return rawBody;
            }
            boolean changed = isWindowEnabled() && applyWindow(body);
            JsonArray messages = body.getAsJsonArray("messages");
            int protectFrom = protectedStart(messages);
            for (int i = 0; i < messages.size(); i++) {
                JsonElement element = messages.get(i);
                if (!element.isJsonObject()) continue;
                JsonElement trimmed = trimEntry(element.getAsJsonObject(), i < protectFrom);
                if (trimmed != element) {
                    messages.set(i, trimmed);
                    changed = true;
                }
            }
            if (!changed) {
                return rawBody;
            }
            String result = body.toString();
            OllamaProxyMod.LOGGER.debug("【省 token】上下文瘦身：字符 {} -> {}（最近 {} 轮完整保留）",
                    rawBody.length(), result.length(), KEEP_TURNS);
            return result;
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.warn("机械裁剪失败，原样转发: {}", error.toString());
            return rawBody;
        }
    }

    /** 单条消息裁剪；返回原对象表示未改动。 */
    private static JsonElement trimEntry(JsonObject message, boolean older) {
        JsonObject current = message;
        if ("assistant".equals(roleOf(current))) {
            JsonObject stripped = stripReasoning(current);
            if (stripped != current) current = stripped;
            if (older) {
                JsonObject trimmed = trimAssistantText(current);
                if (trimmed != current) current = trimmed;
            }
        } else if ("tool".equals(roleOf(current))) {
            if (older) {
                JsonObject trimmed = trimTool(current);
                if (trimmed != current) current = trimmed;
            }
        } else if (older) {
            JsonObject replaced = replaceImages(current);
            if (replaced != current) current = replaced;
        }
        return current == message ? message : current;
    }

    /** 历史轮剥离思维链字段。 */
    private static JsonObject stripReasoning(JsonObject message) {
        if (!message.has("reasoning_content") && !message.has("reasoning")) {
            return message;
        }
        JsonObject stripped = message.deepCopy();
        stripped.remove("reasoning_content");
        stripped.remove("reasoning");
        return stripped;
    }

    /** 旧轮 assistant 正文超长截断；多模态数组不动。 */
    private static JsonObject trimAssistantText(JsonObject message) {
        JsonElement content = message.get("content");
        if (content == null || !content.isJsonPrimitive()) {
            return message;
        }
        String text = content.getAsString();
        if (text.length() <= ASSISTANT_HISTORY_LIMIT) {
            return message;
        }
        return withContent(message, text.substring(0, ASSISTANT_HISTORY_LIMIT) + ASSISTANT_CUT_MARKER);
    }

    /** 旧轮 tool 长输出截断。 */
    private static JsonObject trimTool(JsonObject message) {
        JsonElement content = message.get("content");
        if (content == null || !content.isJsonPrimitive()) {
            return message;
        }
        String text = content.getAsString();
        if (text.length() <= TOOL_OUTPUT_LIMIT) {
            return message;
        }
        return withContent(message, text.substring(0, TOOL_OUTPUT_LIMIT) + TOOL_CUT_MARKER);
    }

    /** 旧轮多模态消息里的图片换成文本占位。 */
    private static JsonObject replaceImages(JsonObject message) {
        JsonElement content = message.get("content");
        if (content == null || !content.isJsonArray()) {
            return message;
        }
        boolean changed = false;
        JsonArray rewritten = new JsonArray();
        for (JsonElement part : content.getAsJsonArray()) {
            if (part.isJsonObject() && isImagePart(part.getAsJsonObject())) {
                JsonObject placeholder = new JsonObject();
                placeholder.addProperty("type", "text");
                placeholder.addProperty("text", IMAGE_PLACEHOLDER);
                rewritten.add(placeholder);
                changed = true;
            } else {
                rewritten.add(part);
            }
        }
        if (!changed) {
            return message;
        }
        JsonObject replaced = message.deepCopy();
        replaced.add("content", rewritten);
        return replaced;
    }

    private static boolean isImagePart(JsonObject part) {
        JsonElement type = part.get("type");
        return type != null && type.isJsonPrimitive()
                && ("image_url".equals(type.getAsString()) || "image".equals(type.getAsString()));
    }

    /** 找最近 KEEP_TURNS+1 条 user 消息中最早一条的位置。 */
    private static int protectedStart(JsonArray messages) {
        int found = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            JsonElement element = messages.get(i);
            if (element.isJsonObject() && "user".equals(roleOf(element.getAsJsonObject()))) {
                found++;
                if (found == KEEP_TURNS + 1) {
                    return i;
                }
            }
        }
        return 0;
    }

    /** 本轮请求是否发生过窗口转存；代理据此决定是否挂 recall_offload 工具。取走即清。 */
    public static boolean consumeOffloadMarker() {
        Boolean marked = OFFLOADED_THIS_REQUEST.get();
        OFFLOADED_THIS_REQUEST.remove();
        return Boolean.TRUE.equals(marked);
    }

    /** 滑动窗口只在 6.0 侧启用：6.2+ 原版自带记忆提炼，扩展的摘要与转存捞回在那边不生效。 */
    private static boolean isWindowEnabled() {
        if (!isEnabled()) {
            return false;
        }
        try {
            return !VerityStructureDetector.hasRefinedMemorySystem();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 滑动窗口：最近 WINDOW_TURNS 轮且 token 不超预算的部分逐字保留，更早的轮次整体转存并替换成占位消息；切点落在 user 边界，前导 system 保留。 */
    private static boolean applyWindow(JsonObject body) {
        JsonArray messages = body.getAsJsonArray("messages");
        List<int[]> turns = collectTurns(messages, leadingSystemCount(messages));
        int total = turns.size();
        if (total == 0) {
            return false;
        }
        // 从最新往回累计：轮数或 token 预算出界的第一轮即转存边界，它和更旧的轮全部舍弃
        int keepFrom = 0;
        long running = 0;
        for (int j = total - 1; j >= 0; j--) {
            running += spanTokens(messages, turns.get(j)[0], turns.get(j)[1]);
            boolean beyondCount = (total - j) > WINDOW_TURNS;
            // 最近 KEEP_TURNS 轮不受预算约束：窗口再满也不能裁掉正在进行的对话
            boolean beyondBudget = running > WINDOW_BUDGET_TOKENS && j < total - KEEP_TURNS;
            if (beyondCount || beyondBudget) {
                keepFrom = Math.max(keepFrom, j + 1);
            }
        }
        if (keepFrom == 0) {
            return false;
        }
        // keepFrom 是保留区起点：转存的是它之前的所有轮，占位消息插在前导 system 之后
        int evictFrom = turns.get(0)[0];
        int evictTo = turns.get(keepFrom)[0];
        int before = messages.size();
        offloadTurns(messages, turns, keepFrom);
        JsonArray windowed = new JsonArray();
        for (int i = 0; i < evictFrom; i++) {
            windowed.add(messages.get(i));
        }
        JsonObject placeholder = new JsonObject();
        placeholder.addProperty("role", "system");
        placeholder.addProperty("content", "（更早的 " + keepFrom
                + " 轮对话已转存，需要原文可调用 recall_offload 工具，支持关键词检索）");
        windowed.add(placeholder);
        for (int i = evictTo; i < messages.size(); i++) {
            windowed.add(messages.get(i));
        }
        body.add("messages", windowed);
        OllamaProxyMod.LOGGER.debug("【省 token】滑动窗口：{} 条旧消息已转存（{} -> {} 条，窗口 {} 轮/{} token）",
                evictTo - evictFrom, before, windowed.size(), WINDOW_TURNS, WINDOW_BUDGET_TOKENS);
        return true;
    }

    /** 前导 system 消息条数（前缀缓存段，永远原样保留）。 */
    private static int leadingSystemCount(JsonArray messages) {
        int count = 0;
        while (count < messages.size()) {
            JsonElement element = messages.get(count);
            if (element.isJsonObject() && "system".equals(roleOf(element.getAsJsonObject()))) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    /** 按轮收集消息区间：每轮从一条 user 消息开始，到下一条 user 消息之前结束。 */
    private static List<int[]> collectTurns(JsonArray messages, int from) {
        List<int[]> turns = new ArrayList<>();
        int size = messages.size();
        int start = -1;
        for (int i = from; i < size; i++) {
            JsonElement element = messages.get(i);
            if (element.isJsonObject() && "user".equals(roleOf(element.getAsJsonObject()))) {
                if (start >= 0) {
                    turns.add(new int[]{start, i});
                }
                start = i;
            }
        }
        if (start >= 0) {
            turns.add(new int[]{start, size});
        }
        return turns;
    }

    private static long spanTokens(JsonArray messages, int from, int to) {
        long tokens = 0;
        for (int i = from; i < to; i++) {
            JsonElement element = messages.get(i);
            if (element.isJsonObject()) {
                tokens += UsageMeter.estimateTokens(element.toString());
            }
        }
        return tokens;
    }

    /** 把转存区间的 user/assistant 原文交给 OffloadStore（异步落盘），失败不影响本次对话。 */
    private static void offloadTurns(JsonArray messages, List<int[]> turns, int keepFrom) {
        try {
            List<OffloadStore.Turn> evicted = new ArrayList<>(keepFrom);
            for (int t = 0; t < keepFrom; t++) {
                int[] span = turns.get(t);
                String user = "";
                StringBuilder assistant = new StringBuilder();
                for (int i = span[0]; i < span[1]; i++) {
                    JsonElement element = messages.get(i);
                    if (!element.isJsonObject()) continue;
                    JsonObject message = element.getAsJsonObject();
                    String role = roleOf(message);
                    if ("user".equals(role)) {
                        user = textOf(message);
                    } else if ("assistant".equals(role)) {
                        String text = textOf(message);
                        if (!text.isBlank()) {
                            if (assistant.length() > 0) assistant.append('\n');
                            assistant.append(text);
                        }
                    }
                }
                evicted.add(new OffloadStore.Turn(System.currentTimeMillis(), user, assistant.toString()));
            }
            OffloadStore.recordTurns(ConversationLogStore.currentWorldName(), evicted);
            OFFLOADED_THIS_REQUEST.set(Boolean.TRUE);
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.warn("窗口外对话转存失败（不影响本次对话）: {}", error.toString());
        }
    }

    /** 取消息正文的纯文本：字符串直接返回，多模态数组拼接 text 片段。 */
    private static String textOf(JsonObject message) {
        JsonElement content = message.get("content");
        if (content == null || content.isJsonNull()) {
            return "";
        }
        if (content.isJsonPrimitive()) {
            return content.getAsString();
        }
        if (!content.isJsonArray()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (JsonElement part : content.getAsJsonArray()) {
            if (!part.isJsonObject()) continue;
            JsonElement piece = part.getAsJsonObject().get("text");
            if (piece != null && piece.isJsonPrimitive()) {
                if (text.length() > 0) text.append('\n');
                text.append(piece.getAsString());
            }
        }
        return text.toString();
    }

    private static JsonObject withContent(JsonObject message, String newContent) {
        JsonObject rewritten = message.deepCopy();
        rewritten.addProperty("content", newContent);
        return rewritten;
    }

    private static String roleOf(JsonObject message) {
        JsonElement role = message.get("role");
        return role != null && role.isJsonPrimitive() ? role.getAsString() : "";
    }

    /** 配置不可用（自检环境）时按默认开启处理。 */
    private static boolean isEnabled() {
        try {
            return ConfigManager.isConversationTrimEnabled();
        } catch (Throwable ignored) {
            return true;
        }
    }
}