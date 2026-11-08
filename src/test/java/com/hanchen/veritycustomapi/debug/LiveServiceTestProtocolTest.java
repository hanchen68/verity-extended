package com.hanchen.veritycustomapi.debug;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Pure checks for the live-test request shape and its failure semantics.
 *
 * <p>Why these matter: a live test is the only place we spend the player's quota on their behalf.
 * The request has to stay tiny (so a test never turns into a real conversation), and a missing or
 * unusable reply must never be reported as success — that is exactly the "green but broken"
 * behaviour the previous basic-only check was criticized for.
 */
public final class LiveServiceTestProtocolTest {
    private LiveServiceTestProtocolTest() {
    }

    public static void main(String[] args) {
        minimalRequestIsTiny();
        noReplyIsNotSuccess();
        disclaimerIsHonest();
        System.out.println("真实测试协议检查通过");
    }

    private static void minimalRequestIsTiny() {
        JsonObject body = LiveServiceTest.minimalRequest("test-model");
        assertTrue(body.has("model"), "必须带上模型名");
        assertTrue(body.get("max_tokens").getAsInt() <= 32, "测试请求必须是极小的输出上限");
        assertTrue(body.get("max_tokens").getAsInt() >= 1, "输出上限不能为 0");
        assertTrue(!body.has("tools") && !body.has("functions"), "测试不应携带工具定义");
        assertTrue(!body.has("stream") || !body.get("stream").getAsBoolean(), "测试不应使用流式响应");
        JsonArray messages = body.getAsJsonArray("messages");
        assertTrue(messages != null && messages.size() == 1, "测试只应发送一条消息");
        String content = messages.get(0).getAsJsonObject().get("content").getAsString();
        assertTrue(content.length() < 120, "测试提示词必须很短");
        assertTrue(!body.toString().toLowerCase().contains("api_key"), "请求体不得夹带密钥字段");
    }

    private static void noReplyIsNotSuccess() {
        // An empty or whitespace reply is a failed live test, not a pass.
        assertTrue(com.hanchen.veritycustomapi.memory.ChatCompletionClient
                .extractText("{\"choices\":[]}").isBlank(), "空选择不能当成回答");
        assertTrue(com.hanchen.veritycustomapi.memory.ChatCompletionClient
                .extractText("<html>gateway</html>").isBlank(), "HTML 错误页不能当成回答");
        assertTrue(!com.hanchen.veritycustomapi.memory.ChatCompletionClient
                .extractText("{\"choices\":[{\"message\":{\"content\":\"连接正常\"}}]}").isBlank(), "正常回答应能取出");
        // Audio must pass a header check before we claim audio was produced.
        assertEquals(false, com.hanchen.veritycustomapi.tts.AudioValidator.isValid(new byte[0], "mp3"));
        assertEquals(false, com.hanchen.veritycustomapi.tts.AudioValidator
                .isValid("{\"error\":\"quota\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8), "mp3"));
    }

    private static void disclaimerIsHonest() {
        assertTrue(LiveServiceTest.DISCLAIMER.contains("消耗"), "确认文案必须说明可能消耗额度");
        assertTrue(LiveServiceTest.supported().containsAll(java.util.Set.of("chat", "stt", "tts")),
                "三项服务都应支持真实测试");
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("expected=" + expected + ", actual=" + actual);
        }
    }
}
