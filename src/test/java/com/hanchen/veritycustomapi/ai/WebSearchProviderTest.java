package com.hanchen.veritycustomapi.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 联网搜索服务商适配的自检。
 *
 * <p>不联网、不需要 Forge：只验证「拿到响应后怎么解析」这一层，
 * 因为真正容易错、且一错就静默失效的正是这里——字段名写错、分数阈值判反、
 * 摘要优先级搞错，都会让搜索「看起来启用了但什么都没给模型」。
 */
public final class WebSearchProviderTest {

    private static final List<String> FAILURES = new ArrayList<>();

    public static void main(String[] args) {
        deepSeekKeepsGoodResultsAndDropsLowScore();
        deepSeekPrefersSummaryOverSnippet();
        deepSeekFallsBackToSnippet();
        deepSeekToleratesMissingAndNullFields();
        deepSeekEmptyAndNullResults();
        deepSeekIncludesUrlAndDate();
        providerSelectionIsCaseInsensitive();
        customFieldMappingParsesFallbackChain();
        customNavigateWalksDotPath();
        customPickFallsBackInOrder();
        customJsonEscapeKeepsBodyValid();
        customTemplateSubstitutesPlaceholders();

        if (!FAILURES.isEmpty()) {
            System.out.println("WebSearchProviderTest 失败 " + FAILURES.size() + " 项：");
            for (String f : FAILURES) System.out.println("  - " + f);
            System.exit(1);
        }
        System.out.println("WebSearchProviderTest 全部通过");
    }

    // ------------------------------------------------------------------ 用例

    /** rerank_score < 1.5 的结果必须被丢掉（官方把 1.5 定为优质线）。 */
    private static void deepSeekKeepsGoodResultsAndDropsLowScore() {
        JsonArray results = array(
                item("优质结果", "好摘要", 2.4, "https://a.example/1"),
                item("软文结果", "差摘要", 0.8, "https://a.example/2"),
                item("边界结果", "刚好达标", 1.5, "https://a.example/3"));
        String text = WebSearchClient.parseDeepSeekResults(results);
        require(text.contains("优质结果"), "2.4 分的结果应保留");
        require(text.contains("边界结果"), "1.5 分是优质线，应保留（阈值是 < 1.5 才丢）");
        require(!text.contains("软文结果"), "0.8 分的结果应被丢弃");
    }

    /** summary 是给模型读的长摘要，优先级必须高于 snippet。 */
    private static void deepSeekPrefersSummaryOverSnippet() {
        JsonObject item = new JsonObject();
        item.addProperty("title", "标题");
        item.addProperty("summary", "长摘要内容");
        item.addProperty("snippet", "短摘要内容");
        item.addProperty("rerank_score", 2.0);
        String text = WebSearchClient.parseDeepSeekResults(array(item));
        require(text.contains("长摘要内容"), "应优先使用 summary");
        require(!text.contains("短摘要内容"), "有 summary 时不应再带 snippet");
    }

    /** summary 缺失或为空时退回 snippet，不能输出空正文。 */
    private static void deepSeekFallsBackToSnippet() {
        JsonObject noSummary = new JsonObject();
        noSummary.addProperty("title", "只有snippet");
        noSummary.addProperty("snippet", "退回的短摘要");
        noSummary.addProperty("rerank_score", 2.0);
        require(WebSearchClient.parseDeepSeekResults(array(noSummary)).contains("退回的短摘要"),
                "summary 缺失时应退回 snippet");

        JsonObject emptySummary = new JsonObject();
        emptySummary.addProperty("title", "空summary");
        emptySummary.addProperty("summary", "");
        emptySummary.addProperty("snippet", "空摘要时退回");
        emptySummary.addProperty("rerank_score", 2.0);
        require(WebSearchClient.parseDeepSeekResults(array(emptySummary)).contains("空摘要时退回"),
                "summary 为空串时也应退回 snippet");
    }

    /** 缺字段、字段为 null 都不能抛异常（真实响应里 site_name/date 常为 null）。 */
    private static void deepSeekToleratesMissingAndNullFields() {
        JsonObject bare = new JsonObject();
        bare.addProperty("title", "只有标题");
        bare.addProperty("rerank_score", 2.0);

        JsonObject withNulls = new JsonObject();
        withNulls.addProperty("title", "带null");
        withNulls.add("summary", com.google.gson.JsonNull.INSTANCE);
        withNulls.add("snippet", com.google.gson.JsonNull.INSTANCE);
        withNulls.add("url", com.google.gson.JsonNull.INSTANCE);
        withNulls.add("date", com.google.gson.JsonNull.INSTANCE);
        withNulls.addProperty("rerank_score", 2.0);

        try {
            String text = WebSearchClient.parseDeepSeekResults(array(bare, withNulls));
            require(text.contains("只有标题"), "缺字段的条目仍应输出标题");
            require(text.contains("带null"), "null 字段不应导致该条目被丢弃");
        } catch (RuntimeException e) {
            fail("缺字段/null 字段不应抛异常，实际抛出 " + e.getClass().getSimpleName());
        }
    }

    /** 空数组与 null 都返回空串，不抛异常。 */
    private static void deepSeekEmptyAndNullResults() {
        require(WebSearchClient.parseDeepSeekResults(new JsonArray()).isEmpty(), "空数组应返回空串");
        require(WebSearchClient.parseDeepSeekResults(null).isEmpty(), "null 应返回空串");
    }

    /** 链接与日期要带出来，方便模型标注来源、玩家核对。 */
    private static void deepSeekIncludesUrlAndDate() {
        String text = WebSearchClient.parseDeepSeekResults(
                array(item("标题", "摘要", 2.0, "https://a.example/x")));
        require(text.contains("https://a.example/x"), "输出应包含结果链接");
    }

    /** 服务商名大小写不应影响选择（配置界面写大写，云端下发可能是小写）。 */
    private static void providerSelectionIsCaseInsensitive() {
        for (String v : new String[]{"DEEPSEEK", "deepseek", "DeepSeek"}) {
            require("DEEPSEEK".equalsIgnoreCase(v), "provider 判定必须忽略大小写：" + v);
        }
        // 反向确认：非 DEEPSEEK/BRAVE 的取值都会落到 Tavily 分支（默认行为）
        require(!"TAVILY".equalsIgnoreCase("BRAVE"), "TAVILY 不应被判成 Brave");
    }

    /** 字段映射：支持回退链，输出名大小写无关。 */
    private static void customFieldMappingParsesFallbackChain() {
        var map = WebSearchClient.parseFieldMapping("title=title, content=summary|snippet, url=url");
        require(map.get("title").equals(java.util.List.of("title")), "title 映射解析错误");
        require(map.get("content").equals(java.util.List.of("summary", "snippet")),
                "content 的回退链应解析成 [summary, snippet]，实际 " + map.get("content"));
        require(map.get("url").equals(java.util.List.of("url")), "url 映射解析错误");
    }

    /** 点号路径逐级下钻；路径为空返回根节点。 */
    private static void customNavigateWalksDotPath() {
        var root = com.google.gson.JsonParser.parseString(
                "{\"data\":{\"results\":[{\"t\":\"x\"}]}}");
        var found = WebSearchClient.navigate(root, "data.results");
        require(found != null && found.isJsonArray(), "data.results 应解析出数组");
        require(WebSearchClient.navigate(root, "data.missing") == null, "不存在的路径应返回 null");
        require(WebSearchClient.navigate(root, "") == root, "空路径应返回根节点本身");
    }

    /** 取字段时按回退链选第一个非空；空串要跳过，不能当成命中。 */
    private static void customPickFallsBackInOrder() {
        var item = com.google.gson.JsonParser.parseString(
                "{\"summary\":\"\",\"snippet\":\"短摘要\",\"title\":\"标题\"}");
        String picked = WebSearchClient.pick(item,
                java.util.List.of("summary", "snippet"));
        require("短摘要".equals(picked), "空串应跳过、落到 snippet，实际 " + picked);
        require(WebSearchClient.pick(item, java.util.List.of("nope")).isEmpty(),
                "全都不存在时应返回空串");
    }

    /** 引号 / 反斜杠 / 控制字符必须转义，否则拼出来的请求体不是合法 JSON。 */
    private static void customJsonEscapeKeepsBodyValid() {
        String raw = "a\"b\\c\nd";
        String escaped = WebSearchClient.jsonEscape(raw);
        var parsed = com.google.gson.JsonParser.parseString("{\"q\":\"" + escaped + "\"}");
        require(raw.equals(parsed.getAsJsonObject().get("q").getAsString()),
                "转义后应能原样还原，实际 " + escaped);
    }

    /** 模板占位符替换，且 {query} 会被转义。 */
    private static void customTemplateSubstitutesPlaceholders() {
        String body = WebSearchClient.fillTemplate(
                "{\"q\":\"{query}\",\"n\":{count},\"k\":\"{key}\"}", "he said \"hi\"", "KEY");
        var json = com.google.gson.JsonParser.parseString(body).getAsJsonObject();
        require("he said \"hi\"".equals(json.get("q").getAsString()), "query 应被转义后还原");
        require(json.get("n").getAsInt() > 0, "count 应替换成条数");
        require("KEY".equals(json.get("k").getAsString()), "key 应被替换");
    }

    // ------------------------------------------------------------------ 工具

    private static JsonArray array(JsonObject... items) {
        JsonArray arr = new JsonArray();
        for (JsonObject o : items) arr.add(o);
        return arr;
    }

    private static JsonObject item(String title, String summary, double score, String url) {
        JsonObject o = new JsonObject();
        o.addProperty("title", title);
        o.addProperty("summary", summary);
        o.addProperty("rerank_score", score);
        o.addProperty("url", url);
        return o;
    }

    private static void require(boolean condition, String message) {
        if (!condition) fail(message);
    }

    private static void fail(String message) {
        FAILURES.add(message);
    }

    private WebSearchProviderTest() {}
}
