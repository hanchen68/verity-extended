package com.hanchen.veritycustomapi.ai;

import com.hanchen.veritycustomapi.util.Silent;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.google.gson.JsonObject;
import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.NetworkSecurity;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 联网搜索。三种服务商，全部走 POST：
 * <ul>
 *   <li>Tavily —— 云端聚合搜索，返回 content</li>
 *   <li>Brave Search —— 返回 description</li>
 *   <li>DeepSeek Search —— 自研搜索引擎，返回 summary/content，另带 Fetch 可抓单页正文</li>
 *   <li>CUSTOM —— 玩家自配的任意 JSON 搜索接口（地址/请求体/结果路径/字段映射都可配）</li>
 * </ul>
 *
 * <p>对外只暴露「标题 + 正文」这种与具体服务商无关的纯文本，代理层不需要知道搜索源是谁。
 *
 * <p>三条服务商的失败语义一致：任何异常都退化成空字符串，绝不把异常抛给对话链路——
 * 搜索只是「锦上添花」，搜不到就当没搜过，不能因此让整轮回复失败。
 */
public final class WebSearchClient {
    private static final Gson GSON = new Gson();
    /** 单次响应最多读这么多，防止某个服务商返回巨大页面把内存吃满。 */
    private static final int MAX_RESPONSE_BYTES = 128 * 1024;
    /** 交给模型的正文总预算，超出就丢弃后续结果。 */
    private static final int MAX_TEXT_CHARS = 12000;

    private static final String TAVILY_URL = "https://api.tavily.com/search";
    private static final String BRAVE_URL = "https://api.search.brave.com/res/v1/web/search";
    private static final String DEEPSEEK_SEARCH_URL = "https://api.deepseek.com/v1/tools/search";
    private static final String DEEPSEEK_FETCH_URL = "https://api.deepseek.com/v1/tools/fetch";

    /** DeepSeek 建议条数 5～10 较稳；计费按请求次数，与条数无关，所以不用为了省钱压低。 */
    private static final int DEEPSEEK_COUNT = 6;
    /** 官方定义的优质线：rerank_score >= 1.5。 */
    private static final double DEEPSEEK_GOOD_SCORE = 1.5D;
    /**
     * 保底线：实测同一查询的分数受语言和主题影响很大
     * （「北京今天天气」最高只有 0.72），严格按 1.5 过滤会让整轮搜索变成 0 字符。
     * 一条优质结果都没有时，宁可给模型前几条低分资料，也不要让它空手回答「查不到」。
     */
    private static final double DEEPSEEK_FALLBACK_SCORE = 0.3D;
    private static final int DEEPSEEK_FALLBACK_RESULTS = 3;
    /** 自定义接口的默认条数。模板里的 {count} 会替换成这个值。 */
    private static final int CUSTOM_COUNT = 6;

    private WebSearchClient() {}

    /** 供对话链路调用：返回拼好的纯文本，失败或未启用时返回空串。 */
    public static String search(String query) {
        return search(query, null);
    }

    /**
     * @param originalQuestion 触发本次搜索的用户原话（DeepSeek 用它理解意图，其它服务商忽略）
     */
    public static String search(String query, String originalQuestion) {
        if (!ConfigManager.isWebSearchEnabled() || query == null || query.isBlank()) {
            log("跳过：联网搜索未开启，或搜索词为空");
            return "";
        }
        String provider = ConfigManager.getWebSearchProvider();
        boolean custom = "CUSTOM".equalsIgnoreCase(provider);
        String key = ConfigManager.getWebSearchApiKey();
        // 自定义接口可能不需要鉴权（本机服务、内网等），所以只有内置三家才强制要 Key
        if (!custom && (key == null || key.isBlank())) {
            log("跳过：服务商 " + provider + " 需要访问密钥，但 webSearchApiKey 为空");
            return "";
        }
        if (custom && (ConfigManager.getWebSearchCustomUrl() == null
                || ConfigManager.getWebSearchCustomUrl().isBlank())) {
            log("跳过：选了自定义 API 但 webSearchCustomUrl 为空");
            return "";
        }
        long startedAt = System.currentTimeMillis();
        try {
            String text;
            if ("BRAVE".equalsIgnoreCase(provider)) text = brave(query, key);
            else if ("DEEPSEEK".equalsIgnoreCase(provider)) text = deepseek(query, originalQuestion, key);
            else if (custom) text = custom(query, key);
            else text = tavily(query, key);
            log("服务商 " + provider + " 返回 " + (text == null ? 0 : text.length())
                    + " 字符，耗时 " + (System.currentTimeMillis() - startedAt) + "ms");
            return text;
        } catch (Exception error) {
            // 以前这里 catch 后直接返回空串，出错完全看不见——搜索「不生效」时无从排查。
            log("失败：" + error.getClass().getSimpleName()
                    + (error.getMessage() == null ? "" : " — " + error.getMessage()));
            return "";
        }
    }

    /** 搜索链路的诊断日志。只在排查「开了搜索却不生效」时需要，所以统一走这里。 */
    private static void log(String message) {
        try {
            com.hanchen.veritycustomapi.OllamaProxyMod.LOGGER.info("[联网搜索] {}", message);
        } catch (Throwable ignored) { Silent.ignore("WebSearchClient", ignored); }
    }

    // ---------------------------------------------------------------- Tavily

    private static String tavily(String query, String key) throws Exception {
        HttpURLConnection conn = open(TAVILY_URL);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        JsonObject body = new JsonObject();
        body.addProperty("api_key", key);
        body.addProperty("query", query);
        body.addProperty("max_results", 5);
        try (var out = conn.getOutputStream()) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        return parseTavily(read(conn));
    }

    private static String parseTavily(String value) {
        JsonArray results = GSON.fromJson(value, JsonObject.class).getAsJsonArray("results");
        StringBuilder out = new StringBuilder();
        if (results != null) for (int i = 0; i < Math.min(5, results.size()); i++) {
            JsonObject item = results.get(i).getAsJsonObject();
            append(out, str(item, "title"), str(item, "content"));
        }
        return out.toString();
    }

    // ----------------------------------------------------------- Brave Search

    private static String brave(String query, String key) throws Exception {
        String url = BRAVE_URL + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        HttpURLConnection conn = open(url);
        conn.setRequestProperty("X-Subscription-Token", key);
        conn.setRequestProperty("Accept", "application/json");
        return parseBrave(read(conn));
    }

    private static String parseBrave(String value) {
        JsonObject root = GSON.fromJson(value, JsonObject.class);
        JsonObject web = root.getAsJsonObject("web");
        JsonArray results = web == null ? null : web.getAsJsonArray("results");
        StringBuilder out = new StringBuilder();
        if (results != null) for (int i = 0; i < Math.min(5, results.size()); i++) {
            JsonObject item = results.get(i).getAsJsonObject();
            append(out, str(item, "title"), str(item, "description"));
        }
        return out.toString();
    }

    // -------------------------------------------------------- DeepSeek Search

    /**
     * DeepSeek Search + 可选 Fetch。
     *
     * <p>Search 按 {@code rerank_score} 已经排好序，这里只做两件事：
     * <ol>
     *   <li>丢掉 {@code rerank_score < 1.5} 的「非优质」结果——官方把 1.5 定为优质线，
     *       低分结果多是软文或无关页，喂给模型只会干扰；</li>
     *   <li>正文优先用 {@code summary}（600～800 字，专为模型阅读设计），
     *       只有它为空时才退回 {@code snippet}（100～200 字，是给前端展示的）。</li>
     * </ol>
     *
     * <p>{@code need_content=true} 会带回整页正文，但通常远超上下文预算，
     * 所以**不开**——需要正文时由 {@link #fetch(String)} 单独抓指定页面。
     */
    private static String deepseek(String query, String originalQuestion, String key) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("query", query);
        body.addProperty("count", DEEPSEEK_COUNT);
        body.addProperty("need_content", false);
        if (originalQuestion != null && !originalQuestion.isBlank()) {
            // 官方要求 1～128 字符，超长直接截断，避免 400
            String q = originalQuestion.length() > 128
                    ? originalQuestion.substring(0, 128) : originalQuestion;
            body.addProperty("ori_question", q);
        }

        JsonArray results = postJson(DEEPSEEK_SEARCH_URL, key, body).getAsJsonArray("results");
        String text = parseDeepSeekResults(results);
        if (text.isBlank() && results != null && !results.isEmpty()) {
            // 排障关键：有结果但输出为空，说明分数或摘要字段出了问题，
            // 不能只在调用方看到「返回 0 字符」而无从下手。
            log("DeepSeek 返回 " + results.size() + " 条，但摘要全为空或相关性过低（已丢弃）");
        }
        return text;
    }

    /**
     * 把 DeepSeek 的 {@code results} 数组拼成给模型的纯文本。
     *
     * <p>包级可见是为了让自检能直接喂 JSON 验证，不必真的发网络请求。
     */
    static String parseDeepSeekResults(JsonArray results) {
        if (results == null) return "";
        StringBuilder strict = new StringBuilder();
        List<JsonObject> lowScore = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            if (!results.get(i).isJsonObject()) continue;
            JsonObject item = results.get(i).getAsJsonObject();
            double score = deepSeekScore(item);
            if (score >= DEEPSEEK_GOOD_SCORE) {
                appendDeepSeekItem(strict, item);
            } else if (score >= DEEPSEEK_FALLBACK_SCORE && lowScore.size() < DEEPSEEK_FALLBACK_RESULTS) {
                lowScore.add(item);
            }
        }
        if (strict.length() > 0) return strict.toString();

        // 一条优质结果都没有：按分数取前几条保底，避免整轮搜索 0 字符。
        // 分数低的资料未必没用（天气/新闻类查询普遍偏低），最终取舍交给模型。
        lowScore.sort(java.util.Comparator.comparingDouble(WebSearchClient::deepSeekScore).reversed());
        StringBuilder out = new StringBuilder();
        for (JsonObject item : lowScore) appendDeepSeekItem(out, item);
        return out.toString();
    }

    private static double deepSeekScore(JsonObject item) {
        if (!item.has("rerank_score") || item.get("rerank_score").isJsonNull()) {
            // 没给分数不能当成 0 分丢掉，否则字段一变整轮搜索就静默为空
            return Double.MAX_VALUE;
        }
        try {
            return item.get("rerank_score").getAsDouble();
        } catch (RuntimeException ignored) {
            return Double.MAX_VALUE;
        }
    }

    private static void appendDeepSeekItem(StringBuilder out, JsonObject item) {
        String title = str(item, "title");
        // summary 是专为模型阅读写的长摘要（600~800 字）；snippet 是给前端展示的短摘要
        String text = str(item, "summary");
        if (text.isBlank()) text = str(item, "snippet");
        // 带上链接与日期：模型引用来源时更可信，也方便玩家自己核对
        String url = str(item, "url");
        String date = str(item, "date");
        String meta = date;
        if (!url.isBlank()) {
            meta = meta.isBlank() ? url : meta + " " + url;
        }
        append(out, title, text + (meta.isBlank() ? "" : "\n" + meta));
    }

    /**
     * 抓取单个网页，返回 Markdown 正文。
     *
     * <p>用途：搜索结果只有摘要时，模型可以指定某个 URL 再要全文。
     * 失败（403 被安全策略拒绝、404 抓不到正文、超时）一律返回空串。
     */
    public static String fetch(String url) {
        if (!ConfigManager.isWebSearchEnabled() || url == null || url.isBlank()) return "";
        String key = ConfigManager.getWebSearchApiKey();
        if (key == null || key.isBlank()) return "";
        try {
            JsonObject body = new JsonObject();
            body.addProperty("url", url.trim());
            JsonObject root = postJson(DEEPSEEK_FETCH_URL, key, body);
            String content = str(root, "content");
            return content.length() > MAX_TEXT_CHARS ? content.substring(0, MAX_TEXT_CHARS) : content;
        } catch (Exception ignored) {
            return "";
        }
    }

    // ------------------------------------------------------------------ 公共

    /**
     * 自定义搜索接口。
     *
     * <p>设计目标是「任何返回 JSON 的搜索 API 都能接」，所以三个环节都做成可配置：
     * <ol>
     *   <li><b>请求体</b>：模板字符串，支持 {@code {query}} / {@code {count}} / {@code {key}} 占位符。
     *       占位符替换后会做 JSON 转义——搜索词里带引号也不会把请求体搞坏。</li>
     *   <li><b>结果位置</b>：点号路径（{@code results} / {@code data.results} / {@code web.pages}），
     *       留空表示响应根节点本身就是数组。</li>
     *   <li><b>字段映射</b>：{@code 输出名=字段名}，用 {@code |} 指定回退顺序。
     *       不同服务商把摘要叫 {@code snippet} / {@code summary} / {@code content}，
     *       有了回退链就不必为每家写一份解析代码。</li>
     * </ol>
     *
     * <p>鉴权走请求头模板（如 {@code Authorization: Bearer {key}}），也允许完全不带 Key。
     */
    private static String custom(String query, String key) throws Exception {
        String url = ConfigManager.getWebSearchCustomUrl();
        if (url == null || url.isBlank()) return "";

        String keyValue = key == null ? "" : key;
        String body = fillTemplate(ConfigManager.getWebSearchCustomBody(), query, keyValue);
        if (body.isBlank()) body = "{}";

        HttpURLConnection conn = open(url.trim());
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        applyHeaders(conn, ConfigManager.getWebSearchCustomHeaders(), keyValue);
        conn.setDoOutput(true);
        try (var out = conn.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }

        String text = read(conn);
        if (text.isBlank()) return "";
        JsonElement root = JsonParser.parseString(text);
        JsonElement results = navigate(root, ConfigManager.getWebSearchCustomResults());
        if (results == null || !results.isJsonArray()) return "";

        Map<String, List<String>> fields = parseFieldMapping(ConfigManager.getWebSearchCustomFields());
        StringBuilder out = new StringBuilder();
        JsonArray array = results.getAsJsonArray();
        for (int i = 0; i < Math.min(CUSTOM_COUNT, array.size()); i++) {
            JsonElement item = array.get(i);
            String title = pick(item, fields.get("title"));
            String content = pick(item, fields.get("content"));
            String link = pick(item, fields.get("url"));
            if (content.isBlank()) content = link;
            append(out, title, content);
        }
        return out.toString();
    }

    /** 替换 {@code {query}} / {@code {count}} / {@code {key}}，并做 JSON 转义。 */
    static String fillTemplate(String template, String query, String key) {
        if (template == null) return "";
        return template
                .replace("{query}", jsonEscape(query))
                .replace("{count}", String.valueOf(CUSTOM_COUNT))
                .replace("{key}", jsonEscape(key));
    }

    /** 按「每行一个 名称: 值」写入请求头；空行与不含冒号的行忽略。 */
    private static void applyHeaders(HttpURLConnection conn, String headers, String key) {
        if (headers == null || headers.isBlank()) return;
        for (String line : headers.split("\\R")) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim().replace("{key}", key);
            if (!name.isEmpty()) conn.setRequestProperty(name, value);
        }
    }

    /**
     * 按点号路径取子节点；路径为空时返回根本身。
     *
     * <p>只处理对象逐级下钻——够覆盖 {@code data.results} / {@code web.pages} 这类，
     * 不支持数组下标（搜索 API 没有这种结构）。
     */
    static JsonElement navigate(JsonElement root, String path) {
        if (root == null) return null;
        if (path == null || path.isBlank()) return root;
        JsonElement current = root;
        for (String segment : path.split("\\.")) {
            if (segment.isBlank()) continue;
            if (!current.isJsonObject()) return null;
            JsonObject object = current.getAsJsonObject();
            if (!object.has(segment)) return null;
            current = object.get(segment);
        }
        return current;
    }

    /** 解析 {@code title=title,content=summary|snippet,url=url} 这种映射。 */
    static Map<String, List<String>> parseFieldMapping(String mapping) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (mapping == null || mapping.isBlank()) return result;
        for (String entry : mapping.split(",")) {
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            String output = entry.substring(0, eq).trim().toLowerCase();
            List<String> sources = new ArrayList<>();
            for (String source : entry.substring(eq + 1).split("\\|")) {
                if (!source.isBlank()) sources.add(source.trim());
            }
            if (!output.isEmpty() && !sources.isEmpty()) result.put(output, sources);
        }
        return result;
    }

    /** 按回退链取第一个非空字符串。 */
    static String pick(JsonElement item, List<String> paths) {
        if (item == null || paths == null) return "";
        for (String path : paths) {
            JsonElement value = navigate(item, path);
            if (value == null || value.isJsonNull()) continue;
            if (value.isJsonPrimitive()) {
                String text = value.getAsString();
                if (!text.isBlank()) return text;
            }
        }
        return "";
    }

    /**
     * 最小 JSON 字符串转义。
     *
     * <p>只转义必须转的字符：搜索词里出现引号或反斜杠时，不转义会直接拼出非法 JSON，
     * 服务端回 400，而玩家只会看到「搜不到」。
     */
    static String jsonEscape(String value) {
        if (value == null) return "";
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    /** POST JSON 并解析响应；非 200 一律当失败（不把错误体内容喂给模型）。 */
    private static JsonObject postJson(String url, String key, JsonObject body) throws Exception {
        HttpURLConnection conn = open(url);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + key);
        conn.setDoOutput(true);
        try (var out = conn.getOutputStream()) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        String text = read(conn);
        if (text.isBlank()) return new JsonObject();
        JsonObject root = GSON.fromJson(text, JsonObject.class);
        return root == null ? new JsonObject() : root;
    }

    private static HttpURLConnection open(String value) throws Exception {
        URI uri = NetworkSecurity.requireSecureUri(value, "搜索 API 地址");
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(12000);
        return conn;
    }

    private static String read(HttpURLConnection conn) throws Exception {
        try {
            int status = conn.getResponseCode();
            InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (stream == null || status >= 400) {
                String detail = "";
                if (stream != null) {
                    try (stream) {
                        byte[] data = stream.readNBytes(400);
                        // 错误体可能很长且换行，压成一行再塞进日志
                        detail = new String(data, StandardCharsets.UTF_8)
                                .replace('\n', ' ').replace('\r', ' ');
                    } catch (Exception ignored) { Silent.ignore("WebSearchClient", ignored); }
                }
                log("HTTP " + status + (detail.isBlank() ? "" : "：" + detail));
                return "";
            }
            try (stream) {
                byte[] data = stream.readNBytes(MAX_RESPONSE_BYTES);
                return new String(data, StandardCharsets.UTF_8);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static String str(JsonObject obj, String name) {
        if (obj == null || !obj.has(name) || obj.get(name).isJsonNull()) return "";
        try {
            return obj.get(name).getAsString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static void append(StringBuilder out, String title, String content) {
        if (title.isBlank() && content.isBlank()) return;
        if (out.length() > MAX_TEXT_CHARS) return;
        out.append(title).append("\n").append(content).append("\n\n");
    }
}
