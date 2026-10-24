package com.hanchen.veritycustomapi.cloud;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hanchen.veritycustomapi.NetworkSecurity;
import com.hanchen.veritycustomapi.OllamaProxyMod;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class CloudConfigFetcher {
    private static final Gson GSON = new Gson();
    private final String clientId = CloudClientIdentity.get();
    private long backoffMillis = 0L;
    private int backoffAttempt = 0;

    static String modVersion() {
        return OllamaProxyMod.version();
    }

    public FetchResult fetch(String apiUrl, String apiKey, int timeoutSeconds) {
        if (apiUrl == null || apiUrl.isBlank()) {
            return FetchResult.failure("云端配置地址为空", false);
        }
        if (apiKey == null || apiKey.isBlank()) {
            return FetchResult.failure("云端配置密钥为空", false);
        }
        int timeout = Math.max(1, timeoutSeconds);
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(timeout))
                    .build();
            JsonObject body = new JsonObject();
            body.addProperty("mod_version", modVersion());
            body.addProperty("client_id", clientId);
            body.addProperty("billing_protocol_version", 2);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(NetworkSecurity.requireSecureUri(apiUrl, "云端配置地址"))
                    .timeout(Duration.ofSeconds(timeout))
                    .header("Authorization", "Bearer " + apiKey.trim())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status != 200) {
                increaseBackoff(status == 429 || status >= 500);
                return FetchResult.failure("HTTP " + status, status == 429 || status >= 500, status == 401);
            }

            CloudConfig config = GSON.fromJson(JsonParser.parseString(response.body()), CloudConfig.class);
            if (config == null) {
                increaseBackoff(false);
                return FetchResult.failure("响应为空", false);
            }
            if (!config.isSuccess()) {
                increaseBackoff(false);
                return FetchResult.failure("code=" + config.code + ", message=" + safeMessage(config.message),
                        false, config.code == 401);
            }
            if (config.data.version == null || config.data.version < 0) {
                increaseBackoff(false);
                return FetchResult.failure("响应缺少有效版本号", false);
            }
            if (config.data.billing == null && config.billing != null) {
                config.data.billing = config.billing;
            }
            backoffMillis = 0L;
            backoffAttempt = 0;
            return FetchResult.success(config);
        } catch (Throwable t) {
            increaseBackoff(true);
            return FetchResult.failure(rootMessage(t), true);
        }
    }

    public long getBackoffMillis() {
        return backoffMillis;
    }

    /** 由云端配置地址推导其他 API 端点：含 /getconfig 时替换其后缀，否则直接拼接 suffix。 */
    private static String resolveEndpoint(String baseUrl, String suffix) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("云端配置地址为空");
        }
        baseUrl = baseUrl.trim();
        int idx = baseUrl.lastIndexOf("/getconfig");
        if (idx >= 0) {
            return baseUrl.substring(0, idx) + suffix;
        }
        if (baseUrl.endsWith("/")) {
            return baseUrl + suffix.replaceFirst("^/", "");
        }
        return baseUrl + suffix;
    }

    /**
     * 旧版直接扣费上报，已无调用方，扣费统一走 {@link BillingService}。
     * @deprecated 改用 {@link BillingService#reportChat} / {@code reportStt} / {@code reportTts}
     */
    @Deprecated
    public DeductResult deductCredits(String apiUrl, String apiKey, int cost, int timeoutSeconds) {
        if (apiUrl == null || apiUrl.isBlank()) {
            return null;
        }
        try {
            String deductUrl = resolveEndpoint(apiUrl, "/credits/deduct");
            int timeout = Math.max(3, timeoutSeconds);

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(timeout))
                    .build();

            JsonObject body = new JsonObject();
            body.addProperty("cost", cost);
            body.addProperty("client_id", this.clientId);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(NetworkSecurity.requireSecureUri(deductUrl, "额度接口地址"))
                    .timeout(Duration.ofSeconds(timeout))
                    .header("Authorization", "Bearer " + (apiKey != null ? apiKey.trim() : ""))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                OllamaProxyMod.LOGGER.warn("额度扣费上报失败: HTTP {}", response.statusCode());
                return null;
            }

            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!json.has("data")) return null;

            JsonObject data = json.getAsJsonObject("data");
            Double remaining = CloudNumber.nullable(data, "credits_remaining");
            Double total = CloudNumber.nullable(data, "credits_total");
            return new DeductResult(remaining, total);
        } catch (Throwable t) {
            OllamaProxyMod.LOGGER.debug("额度扣费上报异常（不作为错误处理）: {}", rootMessage(t));
            return null;
        }
    }

    /**
     * 用配对码兑换 API 密钥：POST /api/v1/exchange/exchange { code, client_id } -> { data: { api_key } }
     */
    public ExchangeResult exchangeCode(String apiUrl, String code, int timeoutSeconds) {
        if (apiUrl == null || apiUrl.isBlank()) {
            OllamaProxyMod.LOGGER.warn("云端配置地址为空");
            return ExchangeResult.failure(0, "云端配置地址为空");
        }
        if (code == null || code.isBlank()) {
            OllamaProxyMod.LOGGER.warn("配对码为空");
            return ExchangeResult.failure(0, "配对码为空");
        }
        try {
            // 由 getconfig 地址推导 exchange 地址
            String exchangeUrl = resolveEndpoint(apiUrl, "/exchange/exchange");
            int timeout = Math.max(3, timeoutSeconds);

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(timeout))
                    .build();

            JsonObject body = new JsonObject();
            body.addProperty("code", code.trim().toUpperCase());
            body.addProperty("client_id", this.clientId);
            body.addProperty("mod_version", modVersion());

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(NetworkSecurity.requireSecureUri(exchangeUrl, "配对接口地址"))
                    .timeout(Duration.ofSeconds(timeout))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                // 服务端在 400/404 时返回的仍然是业务信封
                // {"code":400,"message":"配对码格式无效","data":null}。
                // 丢掉 body 只报「配对失败（HTTP 400）」等于让玩家对着一个状态码猜，
                // 所以优先把服务端那句话原样给出去，抠不到才退回按状态码写的兜底文案。
                String serverMessage = extractServerMessage(response.body());
                OllamaProxyMod.LOGGER.warn("配对码兑换失败: HTTP {}，服务端返回: {}",
                        response.statusCode(), serverMessage == null ? "(无 message)" : serverMessage);
                return ExchangeResult.failure(response.statusCode(),
                        serverMessage != null ? serverMessage : exchangeStatusMessage(response.statusCode()));
            }

            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            int codeVal = json.has("code") ? json.get("code").getAsInt() : -1;
            if (codeVal != 0 || !json.has("data")) {
                String msg = json.has("message") ? json.get("message").getAsString() : "未知错误";
                String safe = safeMessage(msg);
                OllamaProxyMod.LOGGER.warn("配对码兑换失败: {}", safe);
                return ExchangeResult.failure(response.statusCode(), safe);
            }

            JsonObject data = json.getAsJsonObject("data");
            if (data.has("api_key")) {
                String key = data.get("api_key").getAsString();
                String configId = data.has("config_id") ? data.get("config_id").getAsString() : "";
                return ExchangeResult.success(key, configId);
            }
            return ExchangeResult.failure(response.statusCode(), "配对响应缺少密钥");
        } catch (Throwable t) {
            OllamaProxyMod.LOGGER.warn("配对码兑换请求异常: {}", rootMessage(t));
            return ExchangeResult.failure(0, "网络异常，请稍后重试");
        }
    }

    /** 包内可见：{@code CloudProtocolTest} 直接验证这条兜底文案。 */
    static String exchangeStatusMessage(int status) {
        return switch (status) {
            case 400 -> "配对码格式无效（配对码是 6 位字母数字）";
            case 404 -> "配对码不存在、已使用或已过期";
            case 429 -> "操作过于频繁，请稍后再试";
            default -> status >= 500 ? "云端服务暂时不可用" : "配对失败（HTTP " + status + "）";
        };
    }

    /**
     * 从错误响应体里抠出可读的 {@code message}。
     *
     * <p>只认业务信封里的那几种常见字段；网关返回 HTML 错误页、纯文本、空 body 都退化成
     * {@code null}，由调用方按状态码给兜底文案——**绝不能因为解析失败把异常抛出去**，
     * 那样玩家看到的会是「网络异常」，比 HTTP 状态码更没有信息量。
     *
     * <p>包内可见：{@code CloudProtocolTest} 直接验证这条解析。
     */
    static String extractServerMessage(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            for (String key : new String[]{"message", "msg", "error", "detail"}) {
                if (json.has(key) && json.get(key).isJsonPrimitive()) {
                    String text = json.get(key).getAsString();
                    if (text != null && !text.isBlank()) return safeMessage(text);
                }
            }
        } catch (Throwable ignored) {
            // 非 JSON 响应，交给状态码兜底
        }
        return null;
    }

    public record ExchangeResult(boolean success, String apiKey, String configId, int status, String error) {
        static ExchangeResult success(String apiKey, String configId) {
            return new ExchangeResult(true, apiKey, configId, 200, null);
        }

        static ExchangeResult failure(int status, String error) {
            return new ExchangeResult(false, null, null, status, error);
        }
    }

    /**
     * 拉取云端皮肤列表：GET /api/v1/skins（带 Authorization）-> { data: { skins: [...] } }
     */
    public CloudSkin[] fetchSkinList(String apiUrl, String apiKey, int timeoutSeconds) {
        if (apiUrl == null || apiUrl.isBlank()) {
            return new CloudSkin[0];
        }
        try {
            String skinListUrl = resolveEndpoint(apiUrl, "/skins");
            int timeout = Math.max(5, timeoutSeconds);

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(timeout))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(NetworkSecurity.requireSecureUri(skinListUrl, "皮肤列表地址"))
                    .timeout(Duration.ofSeconds(timeout))
                    .header("Authorization", "Bearer " + (apiKey != null ? apiKey.trim() : ""))
                    .header("Content-Type", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new RuntimeException("HTTP " + response.statusCode());
            }

            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!json.has("data")) return new CloudSkin[0];

            JsonObject data = json.getAsJsonObject("data");
            if (!data.has("skins")) return new CloudSkin[0];

            return GSON.fromJson(data.getAsJsonArray("skins"), CloudSkin[].class);
        } catch (Throwable t) {
            OllamaProxyMod.LOGGER.warn("云端皮肤列表拉取异常: {}", rootMessage(t));
            return new CloudSkin[0];
        }
    }

    /** 下载皮肤 ZIP 到 targetPath（如 config/verity/skins/xxx.zip），成功返回 true。 */
    public boolean downloadSkinZip(String downloadUrl, String apiKey, java.nio.file.Path targetPath, int timeoutSeconds) {
        if (downloadUrl == null || downloadUrl.isBlank()) {
            return false;
        }
        try {
            int timeout = Math.max(30, timeoutSeconds);
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(NetworkSecurity.requireSecureUri(downloadUrl, "皮肤下载地址"))
                    .timeout(Duration.ofSeconds(timeout))
                    .GET();
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey.trim());
            }
            HttpRequest request = builder.build();

            HttpResponse<java.nio.file.Path> response = client.send(
                    request, HttpResponse.BodyHandlers.ofFile(targetPath));

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                OllamaProxyMod.LOGGER.info("云端皮肤下载成功: {} -> {}",
                        NetworkSecurity.sanitizedEndpoint(downloadUrl), targetPath);
                return true;
            }
            OllamaProxyMod.LOGGER.warn("云端皮肤下载失败: HTTP {}", response.statusCode());
            return false;
        } catch (Throwable t) {
            OllamaProxyMod.LOGGER.warn("云端皮肤下载异常: {}", rootMessage(t));
            return false;
        }
    }

    /**
     * 解析音色列表地址：mimo.voices_url 是站点绝对路径，要拼到同一 origin 上；
     * 绝对 URL 必须同源，否则回退默认路径。
     */
    private static String resolveVoicesEndpoint(String baseUrl, String voicesUrl) {
        if (voicesUrl == null || voicesUrl.isBlank()) {
            return resolveEndpoint(baseUrl, "/mimo/voices");
        }
        String value = voicesUrl.trim();
        if (value.startsWith("http://") || value.startsWith("https://")) {
            URI candidate = URI.create(value);
            URI base = URI.create(baseUrl.trim());
            return sameOrigin(base, candidate) ? candidate.toString() : resolveEndpoint(baseUrl, "/mimo/voices");
        }
        URI base = URI.create(baseUrl.trim());
        String origin = base.getScheme() + "://" + base.getAuthority();
        return value.startsWith("/") ? origin + value : resolveEndpoint(baseUrl, "/" + value);
    }

    private static boolean sameOrigin(URI first, URI second) {
        return first.getScheme() != null && first.getHost() != null
                && second.getScheme() != null && second.getHost() != null
                && first.getScheme().equalsIgnoreCase(second.getScheme())
                && first.getHost().equalsIgnoreCase(second.getHost());
    }

    private void increaseBackoff(boolean retryable) {
        if (!retryable) {
            backoffMillis = 0L;
            backoffAttempt = 0;
            return;
        }
        backoffAttempt = Math.min(backoffAttempt + 1, BACKOFF_SCHEDULE.length);
        backoffMillis = BACKOFF_SCHEDULE[backoffAttempt - 1];
    }

    private static final long[] BACKOFF_SCHEDULE = {5_000L, 15_000L, 60_000L, 300_000L};

    private static String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "unknown";
        }
        String sanitized = message;
        try {
            String apiKey = CloudCredentialStore.getInstance().apiKey();
            if (apiKey != null && !apiKey.isBlank()) {
                sanitized = sanitized.replace(apiKey, "[REDACTED]");
            }
        } catch (Throwable ignored) {
            // 🔴 凭据存储要读游戏目录，测试环境/未初始化时会抛异常。
            // 这里**不能**让异常冒到外层——外层的 catch 会把整条消息吞成 null，
            // 结果是「脱敏失败 = 什么都不显示」。退化成纯正则脱敏即可。
        }
        return scrubSecrets(sanitized);
    }

    /** 纯正则脱敏：不依赖任何外部状态，任何环境下都能跑。 */
    private static String scrubSecrets(String text) {
        return text.replaceAll("sk_[A-Za-z0-9_-]+", "sk_****")
                .replaceAll("sk-[A-Za-z0-9_-]+", "sk-****");
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : safeMessage(message);
    }

    // ==================== MiMo 音色克隆 ====================

    /**
     * 从云端拉取 MiMo 可用音色列表（内置 + 克隆）
     * GET /api/v1/mimo/voices -> { code, data: { voices: [{id, name, type}] } }
     */
    public CloudConfig.MimoVoice[] fetchMimoVoices(String apiUrl, String apiKey, int timeoutSeconds) {
        return fetchMimoVoices(apiUrl, null, apiKey, timeoutSeconds);
    }

    /**
     * @param voicesUrl 云端下发的相对/绝对路径（文档 §3 的 {@code mimo.voices_url}），为空时回退到 {@code /mimo/voices}
     */
    public CloudConfig.MimoVoice[] fetchMimoVoices(String apiUrl, String voicesUrl, String apiKey,
                                                   int timeoutSeconds) {
        if (apiUrl == null || apiUrl.isBlank()) {
            return new CloudConfig.MimoVoice[0];
        }
        try {
            String url = resolveVoicesEndpoint(apiUrl, voicesUrl);
            int timeout = Math.max(5, timeoutSeconds);

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(timeout))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(NetworkSecurity.requireSecureUri(url, "音色列表地址"))
                    .timeout(Duration.ofSeconds(timeout))
                    .header("Authorization", "Bearer " + (apiKey != null ? apiKey.trim() : ""))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                OllamaProxyMod.LOGGER.warn("MiMo 音色列表拉取失败: HTTP {}", response.statusCode());
                return new CloudConfig.MimoVoice[0];
            }

            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            if (!json.has("data")) return new CloudConfig.MimoVoice[0];
            JsonObject data = json.getAsJsonObject("data");
            if (!data.has("voices")) return new CloudConfig.MimoVoice[0];

            return GSON.fromJson(data.getAsJsonArray("voices"), CloudConfig.MimoVoice[].class);
        } catch (Throwable t) {
            OllamaProxyMod.LOGGER.warn("MiMo 音色列表拉取异常: {}", rootMessage(t));
            return new CloudConfig.MimoVoice[0];
        }
    }

    public static String findCloneSampleUrl(CloudConfig.MimoVoice[] voices, String voiceId) {
        if (voices == null || voiceId == null || voiceId.isBlank()) return null;
        for (CloudConfig.MimoVoice voice : voices) {
            if (voice != null && voiceId.equals(voice.id) && "clone".equals(voice.type)
                    && voice.sampleUrl != null && !voice.sampleUrl.isBlank()) {
                return voice.sampleUrl.trim();
            }
        }
        return null;
    }

    public static class FetchResult {
        public final boolean success;
        public final boolean retryable;
        /** 文档 §7.1：HTTP 401 或响应 code=401 表示配置密钥失效，应提示用户但不覆盖旧缓存。 */
        public final boolean authFailure;
        public final CloudConfig config;
        public final String error;

        private FetchResult(boolean success, boolean retryable, boolean authFailure,
                            CloudConfig config, String error) {
            this.success = success;
            this.retryable = retryable;
            this.authFailure = authFailure;
            this.config = config;
            this.error = error;
        }

        public static FetchResult success(CloudConfig config) {
            return new FetchResult(true, false, false, config, null);
        }

        public static FetchResult failure(String error, boolean retryable) {
            return failure(error, retryable, false);
        }

        public static FetchResult failure(String error, boolean retryable, boolean authFailure) {
            OllamaProxyMod.LOGGER.warn("云端配置拉取失败: {}", safeMessage(error));
            return new FetchResult(false, retryable, authFailure, null, safeMessage(error));
        }
    }

    public static class DeductResult {
        public final Double creditsRemaining;
        public final Double creditsTotal;

        DeductResult(Double creditsRemaining, Double creditsTotal) {
            this.creditsRemaining = creditsRemaining;
            this.creditsTotal = creditsTotal;
        }
    }
}
