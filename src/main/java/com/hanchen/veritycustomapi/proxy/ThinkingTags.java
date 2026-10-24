package com.hanchen.veritycustomapi.proxy;

/**
 * 去掉模型回复里的「思考标签」。
 *
 * <p>抽自 {@code OllamaProxyServer.ChatProxyHandler}。看着只有几行正则，但它出现在
 * **三条独立路径**上（响应抽取、SSE 聚合、reasoning 兜底）。之前散在处理器里时，
 * 新加一条路径忘了调用，就会把整段 {@code <think>…</think>} 当成台词念出来。
 * 集中一处后，任何新路径只要调用 {@link #strip} 就不会漏。
 */
public final class ThinkingTags {

    private ThinkingTags() {
    }

    /**
     * 去掉 {@code <think>} / {@code <thinking>} / {@code <reasoning>} 包裹的内容。
     *
     * <p>用 {@code (?s)} 让 {@code .} 跨行匹配：思考块经常是多行的，
     * 不加这个标志只能删掉单行内的标签。
     */
    public static String strip(String value) {
        if (value == null || value.isBlank()) return "";
        return value.replaceAll("(?s)<think>.*?</think>", "")
                .replaceAll("(?s)<thinking>.*?</thinking>", "")
                .replaceAll("(?s)<reasoning>.*?</reasoning>", "")
                .trim();
    }
}
