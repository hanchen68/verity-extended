package com.hanchen.veritycustomapi.proxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hanchen.veritycustomapi.OllamaProxyMod;

/**
 * 「清空上下文」：把请求里的历史对话整段丢掉，只留前导 system 与最后一条 user。
 *
 * <p><b>为什么需要它</b>：{@link ToolCallNormalizer} 已经把「tool_calls / tool 配对」
 * 两个方向都修好了，正常情况不会再 400。但历史一旦被某种没预料到的形态污染
 * （模型输出畸形、上游中途断流、原版执行工具失败……），自动修复也可能判断不出来，
 * 这时最干脆的解法就是**把上下文清掉**——模型看不见脏历史，自然不会再被它卡住。
 *
 * <p>这是个**持续开关**，不是一次性动作：Verity 自己的对话历史在它那边，
 * 我们只清一次的话，下一个请求它又会把整段历史带上。所以 {@code clear} 之后
 * 每个请求都会丢掉历史，直到 {@code restore}。
 *
 * <p>保留前导 system 是必须的：人设、工具声明、长期记忆摘要全在那里，
 * 丢掉它等于把 Verity 变成失忆的陌生人，而不是「忘了刚才聊了什么」。
 */
public final class ContextReset {

    /** 是否处于「丢弃历史」模式。命令线程写、代理线程读，必须 volatile。 */
    private static volatile boolean suppressed;

    /** 已经丢过多少次历史（只用于 /verity-context status 展示）。 */
    private static volatile int droppedRequests;

    private ContextReset() {
    }

    /** 进入丢弃历史模式。 */
    public static void suppress() {
        suppressed = true;
        droppedRequests = 0;
        OllamaProxyMod.LOGGER.info("【上下文】已开启「丢弃历史」模式：后续请求只保留 system 与最后一条 user");
    }

    /** 退出丢弃历史模式，恢复正常历史。 */
    public static void restore() {
        suppressed = false;
        OllamaProxyMod.LOGGER.info("【上下文】已恢复正常历史（共丢弃过 {} 次请求的历史）", droppedRequests);
    }

    public static boolean isSuppressed() {
        return suppressed;
    }

    public static int droppedRequests() {
        return droppedRequests;
    }

    /**
     * 服务器停止时复位。
     *
     * <p>🔴 这是 static 状态，**必须跨存档清掉**：`/verity-context clear` 是针对
     * 「这个存档的对话历史被污染了」开的一次性处置，如果带到下一个存档，
     * 玩家会看到「换个存档 Verity 完全不记事」——而他已经忘了自己开过这个开关。
     *
     * <p>同理也不能指望服务端重启后还记得：代理和服务器同生命周期，重启即复位。
     */
    public static void reset() {
        if (suppressed) {
            OllamaProxyMod.LOGGER.info("【上下文】服务器停止，已复位「丢弃历史」模式");
        }
        suppressed = false;
        droppedRequests = 0;
    }

    /**
     * 原地清空历史：保留前导 system + 从最后一条 user 开始往后的消息。
     *
     * <p>只读 {@code messages}，不碰其它字段。没有 user 消息时（原版的记忆提炼/日记
     * 这类内部调用）原样返回，避免把内部提示词请求改坏。
     *
     * @return 是否真的改动过
     */
    public static boolean applyInPlace(JsonObject body) {
        if (!suppressed || body == null) {
            return false;
        }
        if (!body.has("messages") || !body.get("messages").isJsonArray()) {
            return false;
        }
        try {
            JsonArray messages = body.getAsJsonArray("messages");
            int leadingSystem = 0;
            while (leadingSystem < messages.size() && "system".equals(roleOf(messages.get(leadingSystem)))) {
                leadingSystem++;
            }
            int lastUser = -1;
            for (int i = messages.size() - 1; i >= 0; i--) {
                if ("user".equals(roleOf(messages.get(i)))) {
                    lastUser = i;
                    break;
                }
            }
            if (lastUser < leadingSystem) {
                return false;
            }
            // 本来就没有历史（只有 system + 这一条 user）就不用动
            if (lastUser == leadingSystem && messages.size() == lastUser + 1) {
                return false;
            }
            JsonArray kept = new JsonArray();
            for (int i = 0; i < leadingSystem; i++) {
                kept.add(messages.get(i));
            }
            for (int i = lastUser; i < messages.size(); i++) {
                kept.add(messages.get(i));
            }
            if (kept.size() == messages.size()) {
                return false;
            }
            int dropped = messages.size() - kept.size();
            body.add("messages", kept);
            droppedRequests++;
            OllamaProxyMod.LOGGER.info("【上下文】已丢弃 {} 条历史消息（保留 {} 条 system + 本轮），{} -> {}",
                    dropped, leadingSystem, messages.size(), kept.size());
            return true;
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.warn("清空上下文失败，保留原请求体: {}", error.toString());
            return false;
        }
    }

    private static String roleOf(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return "";
        }
        JsonElement role = element.getAsJsonObject().get("role");
        return role != null && role.isJsonPrimitive() ? role.getAsString() : "";
    }
}
