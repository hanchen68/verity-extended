package com.hanchen.veritycustomapi.proxy;

import com.hanchen.veritycustomapi.NetworkSecurity;
import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 本地代理的 HTTP 管道：鉴权、CORS、限长读体、响应写出。
 *
 * <p>抽自 {@code OllamaProxyServer}。这些方法共同点：**只碰 {@link HttpExchange}，
 * 不碰任何业务状态**。它们决定「谁能进来、能读多少、怎么出去」，
 * 和「请求内容怎么改写」是两件事，混在一个类里时，
 * 排查 401 / CORS / 413 要在业务代码里翻半天。
 *
 * <p>🔴 鉴权用 {@link MessageDigest#isEqual} 而不是 {@code String.equals}：
 * 后者提前返回会泄漏「前几个字符是否匹配」，本地端口虽然只监听 loopback，
 * 但这属于不需要额外成本的正确性，没理由不做。
 */
public final class HttpPipeline {

    private HttpPipeline() {
    }

    /**
     * 请求体超过上限时抛出。
     *
     * <p>带 {@code statusCode} 是因为调用方要原样回给客户端（一般是 413），
     * 而不是统一按 500 处理——那样客户端会以为是自己写错了协议。
     */
    public static final class PayloadTooLargeException extends IOException {
        public final int statusCode;

        public PayloadTooLargeException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }
    }

    /** 校验 {@code Authorization: Bearer <token>}，常量时间比较。 */
    public static boolean isAuthorized(HttpExchange exchange, String expectedToken) {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return false;
        }
        byte[] supplied = authorization.substring(7).getBytes(StandardCharsets.UTF_8);
        byte[] expected = expectedToken.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(supplied, expected);
    }

    /**
     * 处理 CORS 预检与来源校验。
     *
     * <p>**只允许 loopback 来源**，且要求 scheme 是 http/https、无 userinfo、
     * 无 query/fragment。这是防止任意网页（含恶意站点）通过浏览器
     * 直接调用本地代理读玩家的对话内容——本地端口默认不设防，
     * 唯一的门槛就是这里。
     *
     * @return {@code true} 表示可以继续处理；{@code false} 表示来源不合法，
     *         调用方应回一个不带 CORS 头的响应
     */
    public static boolean applyCors(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null || origin.isBlank()) {
            // 非浏览器客户端（模组自身）不带 Origin，放行
            return true;
        }
        try {
            URI uri = URI.create(origin);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && NetworkSecurity.isLoopbackHost(host)
                    && uri.getRawUserInfo() == null
                    && uri.getRawPath() != null && uri.getRawPath().isEmpty()
                    && uri.getRawQuery() == null && uri.getRawFragment() == null) {
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
                exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST, OPTIONS");
                exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Authorization, Content-Type");
                exchange.getResponseHeaders().set("Access-Control-Max-Age", "600");
                exchange.getResponseHeaders().set("Vary", "Origin");
                return true;
            }
        } catch (RuntimeException ignored) {
            // Origin 不是合法 URI，按不合法处理
        }
        return false;
    }

    /** 读满整个输入流，超过 {@code limit} 抛 {@link PayloadTooLargeException}。 */
    public static byte[] readLimited(InputStream input, int limit, String name, int statusCode)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > limit) {
                throw new PayloadTooLargeException(name + "超过大小限制", statusCode);
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    /** 空响应（无 body）。 */
    public static void sendEmpty(HttpExchange exchange, int statusCode) throws IOException {
        exchange.sendResponseHeaders(statusCode, -1);
    }

    /** 纯文本响应。 */
    public static void sendText(HttpExchange exchange, int statusCode, String message) throws IOException {
        byte[] body = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    /** JSON 响应。 */
    public static void sendJson(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(statusCode, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }
}
