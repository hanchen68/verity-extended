package com.hanchen.veritycustomapi;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class OllamaProxyResponseTest {
    private OllamaProxyResponseTest() {
    }

    public static void main(String[] args) {
        assertEquals("none", OllamaProxyServer.extractAction(
                JsonParser.parseString("{\"action\":[]}").getAsJsonObject()));
        assertEquals("none", OllamaProxyServer.extractAction(
                JsonParser.parseString("{\"action\":\"none\"}").getAsJsonObject()));
        assertEquals("follow", OllamaProxyServer.extractAction(
                JsonParser.parseString("{\"action\":[\"follow\"]}").getAsJsonObject()));
        assertEquals("follow", OllamaProxyServer.extractAction(
                JsonParser.parseString("{\"action\":[{\"action\":\"follow\"}]}").getAsJsonObject()));
        assertEquals("follow", OllamaProxyServer.extractAction(
                JsonParser.parseString("{\"action\":{\"action\":\"follow\"}}").getAsJsonObject()));
        assertEquals("vision reply", OllamaProxyServer.ChatProxyHandler.extractContentText(
                JsonParser.parseString("{\"type\":\"text\",\"text\":\"vision reply\"}")));
        assertEquals("vision reply", OllamaProxyServer.ChatProxyHandler.extractContentText(
                JsonParser.parseString("[{\"type\":\"output_text\",\"output_text\":\"vision reply\"}]")));

        verifyAssistantHistorySanitizing();
        verifyOrphanToolMessagesRemoved();
        verifySemanticEmptyResponses();
        verifyUpstreamResponseValidity();
        verifySseAggregation();
        verifyContextTrimming();
        verifySlidingWindow();
        verifyToolListTrimming();
        verifyTailAppend();
        verifyDefaultMaxTokens();
        System.out.println("Ollama proxy response checks passed");
    }

    private static void verifyAssistantHistorySanitizing() {
        String request = "{\"model\":\"test\",\"messages\":["
                + "{\"role\":\"user\",\"content\":\"hello\"},"
                + "{\"role\":\"assistant\",\"content\":\"\"},"
                + "{\"role\":\"assistant\",\"content\":null},"
                + "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"memory\",\"arguments\":\"{}\"}}]},"
                + "{\"role\":\"assistant\",\"content\":null,\"function_call\":{\"name\":\"legacy\",\"arguments\":\"{}\"}},"
                + "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[]}]}";
        JsonObject sanitized = JsonParser.parseString(
                OllamaProxyServer.ChatProxyHandler.sanitizeAssistantHistory(request)).getAsJsonObject();
        JsonArray messages = sanitized.getAsJsonArray("messages");
        assertEquals(3, messages.size());
        assertEquals("user", messages.get(0).getAsJsonObject().get("role").getAsString());
        assertTrue(messages.get(1).getAsJsonObject().has("tool_calls"));
        assertTrue(messages.get(2).getAsJsonObject().has("function_call"));
    }

    /**
     * 孤儿 tool 消息必须被清掉。
     *
     * <p>上游对这种历史直接 400：
     * {@code Messages with role 'tool' must be a response to a preceding message with 'tool_calls'}。
     * 场景一：assistant 被当成「空回复」删掉，它的 tool 结果就成了孤儿；
     * 场景二：tool_call_id 压根对不上。
     * 同时要保证**正常配对不能被误删**。
     */
    private static void verifyOrphanToolMessagesRemoved() {
        // 场景一：空 assistant（无 tool_calls）+ 它的 tool 结果
        String orphaned = "{\"model\":\"test\",\"messages\":["
                + "{\"role\":\"user\",\"content\":\"hi\"},"
                + "{\"role\":\"assistant\",\"content\":\"\"},"
                + "{\"role\":\"tool\",\"tool_call_id\":\"call-x\",\"content\":\"result\"}]}";
        JsonArray cleaned = JsonParser.parseString(
                OllamaProxyServer.ChatProxyHandler.sanitizeAssistantHistory(orphaned))
                .getAsJsonObject().getAsJsonArray("messages");
        assertEquals(1, cleaned.size());
        assertEquals("user", cleaned.get(0).getAsJsonObject().get("role").getAsString());

        // 场景二：tool_call_id 对不上任何前置调用
        String mismatched = "{\"model\":\"test\",\"messages\":["
                + "{\"role\":\"user\",\"content\":\"hi\"},"
                + "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"memory\",\"arguments\":\"{}\"}}]},"
                + "{\"role\":\"tool\",\"tool_call_id\":\"call-OTHER\",\"content\":\"result\"}]}";
        JsonArray cleaned2 = JsonParser.parseString(
                OllamaProxyServer.ChatProxyHandler.sanitizeAssistantHistory(mismatched))
                .getAsJsonObject().getAsJsonArray("messages");
        assertEquals(2, cleaned2.size());
        assertTrue(cleaned2.get(1).getAsJsonObject().has("tool_calls"));

        // 正常配对：一条都不能少
        String paired = "{\"model\":\"test\",\"messages\":["
                + "{\"role\":\"user\",\"content\":\"hi\"},"
                + "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"memory\",\"arguments\":\"{}\"}}]},"
                + "{\"role\":\"tool\",\"tool_call_id\":\"call-1\",\"content\":\"result\"},"
                + "{\"role\":\"assistant\",\"content\":\"done\"}]}";
        JsonArray cleaned3 = JsonParser.parseString(
                OllamaProxyServer.ChatProxyHandler.sanitizeAssistantHistory(paired))
                .getAsJsonObject().getAsJsonArray("messages");
        assertEquals(4, cleaned3.size());
    }

    private static void verifySemanticEmptyResponses() {
        String emptyMessage = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"message\\\":\\\"\\\",\\\"actions\\\":[]}\"}}]}";
        String emptyResponse = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"response\\\":\\\" \\\"}\"}}]}";
        assertEquals("", OllamaProxyServer.ChatProxyHandler.extractResponseText(emptyMessage));
        assertEquals("", OllamaProxyServer.ChatProxyHandler.extractResponseText(emptyResponse));
        assertTrue(!OllamaProxyServer.ChatProxyHandler.extractResponseText(
                OllamaProxyServer.ChatProxyHandler.buildEmptyReplyFallback()).isBlank());
    }

    private static void verifyUpstreamResponseValidity() {
        String text = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hello\"},\"finish_reason\":\"stop\"}]}";
        String toolCall = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"memory\",\"arguments\":\"{}\"}}]},\"finish_reason\":\"tool_calls\"}]}";
        String empty = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":\"stop\"}]}";
        assertEquals("hello", OllamaProxyServer.ChatProxyHandler.validPaidResponseText(text));
        assertTrue(OllamaProxyServer.ChatProxyHandler.hasValidAssistantOperation(toolCall));
        assertTrue(OllamaProxyServer.ChatProxyHandler.validPaidResponseText(toolCall) != null);
        assertTrue(OllamaProxyServer.ChatProxyHandler.validPaidResponseText(empty) == null);
    }

    private static void verifySseAggregation() {
        String emptySse = "data: {\"id\":\"empty\",\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"empty\",\"choices\":[{\"delta\":{\"content\":\"\"},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n";
        String textSse = "data: {\"choices\":[{\"delta\":{\"content\":\"hello \"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"world\"},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n";
        assertEquals("", OllamaProxyServer.ChatProxyHandler.extractResponseText(
                OllamaProxyServer.ChatProxyHandler.aggregateSseResponse(emptySse)));
        assertEquals("hello world", OllamaProxyServer.ChatProxyHandler.extractResponseText(
                OllamaProxyServer.ChatProxyHandler.aggregateSseResponse(textSse)));
        String buffered = OllamaProxyServer.ChatProxyHandler.toBufferedSse(
                "{\"id\":\"chat-test\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"test\","
                        + "\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"buffered reply\"},"
                        + "\"finish_reason\":\"stop\",\"index\":0}]}");
        assertTrue(buffered.contains("chat.completion.chunk") && buffered.endsWith("data: [DONE]\n\n"));
        assertEquals("buffered reply", OllamaProxyServer.ChatProxyHandler.extractResponseText(
                OllamaProxyServer.ChatProxyHandler.aggregateSseResponse(buffered)));
    }

    /** 只裁旧轮：旧工具结果、超长旧回复、旧截图、思维链都会被动，最近两轮完整保留。 */
    private static void verifyContextTrimming() {
        String longAssistant = "A".repeat(2000);
        String longTool = "T".repeat(1000);
        String body = "{\"model\":\"test\",\"messages\":["
                + "{\"role\":\"system\",\"content\":\"big\"},"
                + "{\"role\":\"user\",\"content\":\"u1\"},"
                + "{\"role\":\"assistant\",\"content\":\"" + longAssistant + "\",\"reasoning_content\":\"hidden chain\"},"
                + "{\"role\":\"user\",\"content\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,x\"}},{\"type\":\"text\",\"text\":\"看这个\"}]},"
                + "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"memory\",\"arguments\":\"{}\"}}]},"
                + "{\"role\":\"tool\",\"content\":\"" + longTool + "\"},"
                + "{\"role\":\"user\",\"content\":\"u3\"},"
                + "{\"role\":\"assistant\",\"content\":\"short a3\"},"
                + "{\"role\":\"user\",\"content\":\"u4\"},"
                + "{\"role\":\"assistant\",\"content\":\"short a4\"},"
                + "{\"role\":\"user\",\"content\":\"u5\"}]}";
        JsonObject out = JsonParser.parseString(
                com.hanchen.veritycustomapi.ai.ContextTrimmer.trim(body)).getAsJsonObject();
        JsonArray messages = out.getAsJsonArray("messages");
        assertEquals(11, messages.size());
        // 旧轮长回复被截断、思维链被剥离
        JsonObject oldAssistant = messages.get(2).getAsJsonObject();
        String trimmedAssistant = oldAssistant.get("content").getAsString();
        assertEquals(800 + "…[历史回复已截断省 token]".length(), trimmedAssistant.length());
        assertTrue(trimmedAssistant.endsWith("…[历史回复已截断省 token]"));
        assertTrue(!oldAssistant.has("reasoning_content"));
        // 旧轮截图换成文本占位
        JsonArray oldUserContent = messages.get(3).getAsJsonObject().getAsJsonArray("content");
        assertEquals("[旧截图已省略]", oldUserContent.get(0).getAsJsonObject().get("text").getAsString());
        // 旧轮工具长结果被截断，配对结构不动
        assertTrue(messages.get(4).getAsJsonObject().has("tool_calls"));
        String trimmedTool = messages.get(5).getAsJsonObject().get("content").getAsString();
        assertEquals(320 + "…[已截断省 token，完整结果可重新调用获取]".length(), trimmedTool.length());
        // 最近两轮完整保留
        assertEquals("short a3", messages.get(7).getAsJsonObject().get("content").getAsString());
        assertEquals("u5", messages.get(10).getAsJsonObject().get("content").getAsString());
        // 没有可裁剪内容时原样返回（同一字符串）
        String small = "{\"model\":\"test\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        assertTrue(small.equals(com.hanchen.veritycustomapi.ai.ContextTrimmer.trim(small)));
    }

    /** 滑动窗口把 15 轮裁到 12 轮，被裁的 3 轮换成占位消息并转存，之后能靠 recall 捞回来。 */
    private static void verifySlidingWindow() {
        StringBuilder body = new StringBuilder("{\"model\":\"test\",\"messages\":[{\"role\":\"system\",\"content\":\"PREFIX\"}");
        for (int i = 1; i <= 15; i++) {
            body.append(",{\"role\":\"user\",\"content\":\"u").append(i).append("\"}")
                    .append(",{\"role\":\"assistant\",\"content\":\"a").append(i).append("\"}");
        }
        body.append("]}");
        JsonObject out = JsonParser.parseString(
                com.hanchen.veritycustomapi.ai.ContextTrimmer.trim(body.toString())).getAsJsonObject();
        JsonArray messages = out.getAsJsonArray("messages");
        assertEquals(26, messages.size());
        assertEquals("PREFIX", messages.get(0).getAsJsonObject().get("content").getAsString());
        JsonObject placeholder = messages.get(1).getAsJsonObject();
        assertEquals("system", placeholder.get("role").getAsString());
        String placeholderText = placeholder.get("content").getAsString();
        assertTrue(placeholderText.contains("recall_offload"));
        assertTrue(placeholderText.contains("3"));
        assertEquals("u4", messages.get(2).getAsJsonObject().get("content").getAsString());
        assertEquals("a15", messages.get(25).getAsJsonObject().get("content").getAsString());
        // 转存是异步落盘的，轮询等待 worker 消化完
        String recall = "";
        for (int i = 0; i < 40; i++) {
            recall = com.hanchen.veritycustomapi.memory.OffloadStore.recall("u2");
            if (recall.startsWith("共捞回")) {
                break;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
                return;
            }
        }
        assertTrue(recall.contains("u2"));
        assertTrue(recall.contains("a2"));
    }

    /** 工具清单分两阶段：核心工具常驻，传送/搜索这类按意图装载，无关的裁掉；总数不超过 40 就直接放行。 */
    private static void verifyToolListTrimming() {
        java.util.List<String> toolDefs = new java.util.ArrayList<>();
        String[] core = {"get_own_coords", "change_expression", "start_following",
                "get_kerma", "add_to_memory", "play_sound"};
        String[] intent = {"teleport_to_biome", "web_search", "give_item", "farming_plant"};
        String[] expected = {"teleport_to_biome", "web_search"};
        for (String name : core) {
            toolDefs.add(tool(name));
        }
        for (String name : intent) {
            toolDefs.add(tool(name));
        }
        for (int i = 0; i < 37; i++) {
            toolDefs.add(tool("aux_action_" + (i < 10 ? "0" + i : i)));
        }
        String body = "{\"model\":\"test\",\"messages\":[{\"role\":\"system\",\"content\":\"sys\"},"
                + "{\"role\":\"user\",\"content\":\"帮我传送到白桦林附近，然后查一下附近有没有村庄\"}],"
                + "\"tools\":[" + String.join(",", toolDefs) + "]}";
        JsonObject out = JsonParser.parseString(
                com.hanchen.veritycustomapi.ai.ToolListTrimmer.apply(body)).getAsJsonObject();
        JsonArray kept = out.getAsJsonArray("tools");
        assertEquals(8, kept.size());
        java.util.Set<String> names = new java.util.HashSet<>();
        for (int i = 0; i < kept.size(); i++) {
            names.add(kept.get(i).getAsJsonObject().getAsJsonObject("function").get("name").getAsString());
        }
        for (String name : core) {
            assertTrue(names.contains(name));
        }
        for (String name : expected) {
            assertTrue(names.contains(name));
        }
        assertTrue(!names.contains("give_item") && !names.contains("farming_plant"));
        assertTrue(!names.contains("aux_action_00"));
        // ≤40 个直接放行
        String small = "{\"model\":\"test\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
                + "\"tools\":[" + String.join(",", tool("a"), tool("b"), tool("c")) + "]}";
        assertTrue(small.equals(com.hanchen.veritycustomapi.ai.ToolListTrimmer.apply(small)));
    }

    private static String tool(String name) {
        return "{\"type\":\"function\",\"function\":{\"name\":\"" + name + "\",\"arguments\":\"{}\"}}";
    }

    /** 没带 max_tokens 才补 2048，已经写了的别覆盖。 */
    private static void verifyDefaultMaxTokens() {
        String body = "{\"model\":\"test\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        JsonObject out = JsonParser.parseString(
                OllamaProxyServer.ChatProxyHandler.applyDefaultMaxTokens(body)).getAsJsonObject();
        assertEquals(2048, out.get("max_tokens").getAsInt());
        String explicit = "{\"model\":\"test\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        JsonObject kept = JsonParser.parseString(
                OllamaProxyServer.ChatProxyHandler.applyDefaultMaxTokens(explicit)).getAsJsonObject();
        assertEquals(100, kept.get("max_tokens").getAsInt());
    }

    /** 动态内容一律追加到消息尾部，首位的静态前缀不能被动。 */
    private static void verifyTailAppend() {
        JsonObject body = JsonParser.parseString(
                "{\"model\":\"test\",\"messages\":["
                        + "{\"role\":\"system\",\"content\":\"STATIC-PREFIX\"},"
                        + "{\"role\":\"user\",\"content\":\"hello\"}]}").getAsJsonObject();
        OllamaProxyServer.ChatProxyHandler.appendSystemContextAtTail(body, "动态提醒");
        JsonArray messages = body.getAsJsonArray("messages");
        assertEquals(3, messages.size());
        assertEquals("STATIC-PREFIX", messages.get(0).getAsJsonObject().get("content").getAsString());
        assertEquals("system", messages.get(2).getAsJsonObject().get("role").getAsString());
        assertEquals("动态提醒", messages.get(2).getAsJsonObject().get("content").getAsString());
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertEquals(int expected, int actual) {
        if (expected != actual) {
            throw new AssertionError("expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertTrue(boolean condition) {
        if (!condition) throw new AssertionError("condition was false");
    }
}
