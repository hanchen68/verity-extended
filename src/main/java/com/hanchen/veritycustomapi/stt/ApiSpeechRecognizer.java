package com.hanchen.veritycustomapi.stt;

import com.hanchen.veritycustomapi.NetworkSecurity;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.cloud.BillingService;
import com.hanchen.veritycustomapi.cloud.RouteHealthRegistry;
import com.hanchen.veritycustomapi.cloud.UsageMeter;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class ApiSpeechRecognizer implements SpeechRecognizer {

    private static final Gson GSON = new Gson();

    private final String apiUrl;
    private final String apiKey;
    private final String modelName;
    private final RequestFormat requestFormat;
    private final AuthType authType;
    private final String responseField;
    private final Map<String, String> customHeaders;
    private final String routeId;
    private final String routeTier;

    public enum RequestFormat {
        MULTIPART_FORM_DATA,
        JSON,
        URL_ENCODED
    }

    public enum AuthType {
        BEARER,
        API_KEY_HEADER,
        API_KEY_PARAM,
        CUSTOM_HEADER
    }

    public ApiSpeechRecognizer(String apiUrl, String apiKey, String modelName) {
        this(apiUrl, apiKey, modelName, RequestFormat.MULTIPART_FORM_DATA, AuthType.BEARER, "text", null);
    }

    public ApiSpeechRecognizer(String apiUrl, String apiKey, String modelName, RequestFormat requestFormat,
                               AuthType authType, String responseField, Map<String, String> customHeaders) {
        this(apiUrl, apiKey, modelName, requestFormat, authType, responseField, customHeaders, null, null);
    }

    private ApiSpeechRecognizer(String apiUrl, String apiKey, String modelName, RequestFormat requestFormat,
                                AuthType authType, String responseField, Map<String, String> customHeaders,
                                String routeId, String routeTier) {
        this.apiUrl = apiUrl;
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.requestFormat = requestFormat != null ? requestFormat : RequestFormat.MULTIPART_FORM_DATA;
        this.authType = authType != null ? authType : AuthType.BEARER;
        this.responseField = responseField != null && !responseField.isBlank() ? responseField : "text";
        this.customHeaders = customHeaders != null ? customHeaders : new HashMap<>();
        this.routeId = routeId;
        this.routeTier = routeTier;
    }

    @Override
    public String recognize(byte[] pcmData) throws Exception {
        java.util.List<ConfigManager.SttRoute> routes = ConfigManager.snapshotSttRoutes();
        Exception last = null;
        ConfigManager.SttRoute lastRoute = null;
        for (ConfigManager.SttRoute route : routes) {
            if (!ConfigManager.tryAcquireRoute("stt", route.tier(), route.id())) continue;
            lastRoute = route;
            double audioSeconds = UsageMeter.audioSeconds(pcmData, 16000, 16, 1);
            int requiredCredits = "paid".equalsIgnoreCase(route.tier())
                    ? ConfigManager.getSttCost(audioSeconds) : 0;
            if (requiredCredits > 0 && !ConfigManager.isCreditsSufficient(requiredCredits)) {
                throw new Exception("云端 STT 额度不足，请前往积分中心充值。");
            }
            ApiSpeechRecognizer delegate = fromRoute(route);
            try {
                String result = delegate.recognizeSingle(pcmData);
                ConfigManager.reportRouteSuccess("stt", route.tier(), route.id());
                if (route.tier() != null && result != null && !result.isBlank()) {
                    BillingService.reportStt(BillingService.requestId(), route.tier(), route.id(), audioSeconds);
                }
                return result;
            } catch (Exception error) {
                last = error;
                RouteHealthRegistry.FailureKind kind = failureKind(error);
                ConfigManager.reportRouteFailure("stt", route.tier(), route.id(), kind);
                if (!isFailoverFailure(kind)) throw sanitizeBuiltinFailure(route, error);
            }
        }
        if (last != null) {
            throw sanitizeBuiltinFailure(lastRoute, last);
        }
        throw new Exception("所有 STT 线路均处于冷却或不可用状态");
    }

    private String recognizeSingle(byte[] pcmData) throws Exception {
        if (apiUrl != null && apiUrl.startsWith("ws")) {
            throw new Exception("WebSocket 协议接口（如讯飞）暂不支持，请切换为 HTTP 协议的 STT 服务商。");
        }
        logPcmStats(pcmData);
        String tier = ConfigManager.getBuiltinSttTierForEndpoint(apiUrl);
        if ("paid".equalsIgnoreCase(tier)
                && !ConfigManager.isCreditsSufficient(ConfigManager.getSttCost(UsageMeter.audioSeconds(pcmData, 16000, 16, 1)))) {
            throw new Exception("云端 STT 额度不足，请前往积分中心充值。");
        }
        String result;
        if (isMiMoEndpoint(apiUrl)) {
            result = recognizeMimoAsr(pcmData);
        } else {
            result = switch (requestFormat) {
                case JSON -> recognizeJson(pcmData);
                case URL_ENCODED -> recognizeUrlEncoded(pcmData);
                case MULTIPART_FORM_DATA -> recognizeMultipart(pcmData);
            };
        }
        return result;
    }

    private static ApiSpeechRecognizer fromRoute(ConfigManager.SttRoute route) {
        String format = route.requestFormat() == null ? "" : route.requestFormat().toLowerCase();
        String auth = route.authType() == null ? "" : route.authType().toLowerCase();
        RequestFormat requestFormat = format.contains("json") ? RequestFormat.JSON
                : format.contains("url") ? RequestFormat.URL_ENCODED : RequestFormat.MULTIPART_FORM_DATA;
        AuthType authType = auth.contains("api_key_header") ? AuthType.API_KEY_HEADER
                : auth.contains("api_key_param") ? AuthType.API_KEY_PARAM
                : auth.contains("custom") ? AuthType.CUSTOM_HEADER : AuthType.BEARER;
        return new ApiSpeechRecognizer(route.apiUrl(), route.apiKey(), route.model(), requestFormat, authType,
                route.responseField(), null, route.id(), route.tier());
    }

    private static Exception sanitizeBuiltinFailure(ConfigManager.SttRoute route, Exception error) {
        if (route == null || route.tier() == null) return error;
        String type = error instanceof HttpStatusException statusError
                ? "HTTP " + statusError.statusCode : error.getClass().getSimpleName();
        return new Exception("云端内置 STT 线路请求失败: routeId=" + route.id()
                + ", tier=" + route.tier() + ", type=" + type);
    }

    private static RouteHealthRegistry.FailureKind failureKind(Exception error) {
        if (error instanceof HttpStatusException statusError) {
            int status = statusError.statusCode;
            if (status == 401 || status == 403) return RouteHealthRegistry.FailureKind.AUTHENTICATION;
            if (status == 402) return RouteHealthRegistry.FailureKind.BILLING;
            if (status == 408 || status == 504) return RouteHealthRegistry.FailureKind.TIMEOUT;
            if (status == 429) return RouteHealthRegistry.FailureKind.UNAVAILABLE;
            return status >= 500 ? RouteHealthRegistry.FailureKind.SERVER : RouteHealthRegistry.FailureKind.PARAMETER;
        }
        if (error instanceof java.net.SocketTimeoutException) return RouteHealthRegistry.FailureKind.TIMEOUT;
        if (error instanceof IOException) return RouteHealthRegistry.FailureKind.NETWORK;
        return RouteHealthRegistry.FailureKind.PARAMETER;
    }

    private static boolean isFailoverFailure(RouteHealthRegistry.FailureKind kind) {
        return kind == RouteHealthRegistry.FailureKind.NETWORK || kind == RouteHealthRegistry.FailureKind.TIMEOUT
                || kind == RouteHealthRegistry.FailureKind.SERVER || kind == RouteHealthRegistry.FailureKind.UNAVAILABLE;
    }

    /** 检测是否为小米 MiMo 端点（专用 ASR 格式） */
    private static boolean isMiMoEndpoint(String url) {
        return url != null && url.contains("xiaomimimo.com");
    }

    /** MiMo ASR 识别：使用 /chat/completions + input_audio 格式 */
    private String recognizeMimoAsr(byte[] pcmData) throws Exception {
        String model = modelName != null && !modelName.isBlank() ? modelName : "mimo-v2.5-asr";
        logRequestInfo("MiMo ASR (input_audio)", model, pcmData.length);

        byte[] wavData = buildWavFile(pcmData, 16000, 16, 1);
        String base64Audio = Base64.getEncoder().encodeToString(wavData);
        String dataUrl = "data:audio/wav;base64," + base64Audio;

        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        JsonArray messages = new JsonArray();
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        JsonArray contentParts = new JsonArray();
        JsonObject audioPart = new JsonObject();
        audioPart.addProperty("type", "input_audio");
        JsonObject inputAudio = new JsonObject();
        inputAudio.addProperty("data", dataUrl);
        audioPart.add("input_audio", inputAudio);
        contentParts.add(audioPart);
        userMsg.add("content", contentParts);
        messages.add(userMsg);
        root.add("messages", messages);
        JsonObject asrOptions = new JsonObject();
        asrOptions.addProperty("language", "zh");
        root.add("asr_options", asrOptions);

        String jsonBody = GSON.toJson(root);
        String endpointUrl = apiUrl.endsWith("/") ? apiUrl + "chat/completions" : apiUrl + "/chat/completions";
        URL url = NetworkSecurity.requireSecureUri(endpointUrl, "STT API 地址").toURL();

        HttpURLConnection conn = createConnection(url);
        conn.setRequestProperty("Content-Type", "application/json");
        if (apiKey != null && !apiKey.isBlank()) {
            conn.setRequestProperty("api-key", apiKey);
        }

        conn.setDoOutput(true);
        conn.setDoInput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);

        try {
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            return getResponseAndParse(conn);
        } catch (Exception e) {
            conn.disconnect();
            throw e;
        }
    }

    private String recognizeMultipart(byte[] pcmData) throws Exception {
        byte[] wavData = buildWavFile(pcmData, 16000, 16, 1);
        String model = modelName != null && !modelName.isBlank() ? modelName : "whisper-1";

        logRequestInfo("multipart/form-data", model, wavData.length);
        logWavInfo(wavData, pcmData.length, 16000, 16, 1);

        try {
            return sendMultipartWithRetry(wavData, model);
        } catch (HttpStatusException error) {
            if (!shouldFallbackSiliconFlowModel(model, error.statusCode)) {
                throw error;
            }
            String fallbackModel = "FunAudioLLM/SenseVoiceSmall";
            if (isBuiltinRoute()) {
                OllamaProxyMod.LOGGER.warn("云端内置 STT 线路模型回退: routeId={}, tier={}, status={}",
                        routeId, routeTier, error.statusCode);
            } else {
                OllamaProxyMod.LOGGER.warn("SiliconFlow 模型 {} 返回 HTTP {}，使用同一音频回退到 {}",
                        model, error.statusCode, fallbackModel);
            }
            logRequestInfo("multipart/form-data fallback", fallbackModel, wavData.length);
            return sendMultipartWithRetry(wavData, fallbackModel);
        }
    }

    private String sendMultipartWithRetry(byte[] wavData, String model) throws Exception {
        for (int attempt = 1; ; attempt++) {
            try {
                return sendMultipart(wavData, model);
            } catch (HttpStatusException error) {
                if (!isRetryableStatus(error.statusCode) || attempt >= 3) throw error;
                long delay = attempt * 750L;
                OllamaProxyMod.LOGGER.warn("语音识别服务返回 HTTP {}，{}ms 后进行第 {} 次重试",
                        error.statusCode, delay, attempt + 1);
                Thread.sleep(delay);
            }
        }
    }

    private static boolean isRetryableStatus(int statusCode) {
        return statusCode == 429 || statusCode == 500 || statusCode == 502
                || statusCode == 503 || statusCode == 504;
    }

    private String sendMultipart(byte[] wavData, String model) throws Exception {
        String boundary = "----VerityCustomAPIBoundary" + System.nanoTime();

        URL url = buildRequestUrl();
        HttpURLConnection conn = createConnection(url);
        
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        applyAuthHeaders(conn);
        
        conn.setDoOutput(true);
        conn.setDoInput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);

        try {
            try (OutputStream os = conn.getOutputStream()) {
                os.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                os.write("Content-Disposition: form-data; name=\"model\"\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                os.write((model + "\r\n").getBytes(StandardCharsets.UTF_8));
                os.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                os.write("Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n".getBytes(StandardCharsets.UTF_8));
                os.write("Content-Type: audio/wav\r\n\r\n".getBytes(StandardCharsets.UTF_8));
                os.write(wavData);
                os.write("\r\n".getBytes(StandardCharsets.UTF_8));
                os.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            return getResponseAndParse(conn);
        } catch (Exception e) {
            conn.disconnect();
            throw e;
        }
    }

    private boolean shouldFallbackSiliconFlowModel(String model, int statusCode) {
        return apiUrl != null
                && apiUrl.contains("api.siliconflow.cn")
                && "TeleAI/TeleSpeechASR".equals(model)
                && (statusCode == 500 || statusCode == 503 || statusCode == 504);
    }

    private String recognizeJson(byte[] pcmData) throws Exception {
        String model = modelName != null && !modelName.isBlank() ? modelName : "whisper-1";
        String base64Audio = java.util.Base64.getEncoder().encodeToString(pcmData);

        logRequestInfo("JSON", model, pcmData.length);

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);
        requestBody.addProperty("audio", base64Audio);
        requestBody.addProperty("format", "pcm");
        requestBody.addProperty("sample_rate", 16000);

        String jsonBody = GSON.toJson(requestBody);

        URL url = buildRequestUrl();
        HttpURLConnection conn = createConnection(url);
        
        conn.setRequestProperty("Content-Type", "application/json");
        applyAuthHeaders(conn);
        
        conn.setDoOutput(true);
        conn.setDoInput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);

        try {
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            return getResponseAndParse(conn);
        } catch (Exception e) {
            conn.disconnect();
            throw e;
        }
    }

    private String recognizeUrlEncoded(byte[] pcmData) throws Exception {
        String model = modelName != null && !modelName.isBlank() ? modelName : "whisper-1";
        String base64Audio = java.util.Base64.getEncoder().encodeToString(pcmData);

        logRequestInfo("URL_ENCODED", model, pcmData.length);

        StringBuilder sb = new StringBuilder();
        sb.append("model=").append(URLEncoder.encode(model, "UTF-8"));
        sb.append("&audio=").append(URLEncoder.encode(base64Audio, "UTF-8"));
        sb.append("&format=pcm");
        sb.append("&sample_rate=16000");

        URL url = buildRequestUrl();
        HttpURLConnection conn = createConnection(url);
        
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        applyAuthHeaders(conn);
        
        conn.setDoOutput(true);
        conn.setDoInput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);

        try {
            try (OutputStream os = conn.getOutputStream()) {
                os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            return getResponseAndParse(conn);
        } catch (Exception e) {
            conn.disconnect();
            throw e;
        }
    }

    private URL buildRequestUrl() throws IOException {
        String requestUrl = apiUrl;
        if (authType == AuthType.API_KEY_PARAM && apiKey != null && !apiKey.isBlank()) {
            requestUrl = apiUrl + (apiUrl.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(apiKey, "UTF-8");
        }
        return NetworkSecurity.requireSecureUri(requestUrl, "STT API 地址").toURL();
    }

    private HttpURLConnection createConnection(URL url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("User-Agent", "VerityCustomAPI/1.0");
        
        for (Map.Entry<String, String> entry : customHeaders.entrySet()) {
            conn.setRequestProperty(entry.getKey(), entry.getValue());
        }
        
        return conn;
    }

    private void applyAuthHeaders(HttpURLConnection conn) {
        if (apiKey == null || apiKey.isBlank()) {
            OllamaProxyMod.LOGGER.warn("API密钥未配置");
            return;
        }

        switch (authType) {
            case BEARER:
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                break;
            case API_KEY_HEADER:
                conn.setRequestProperty("X-API-Key", apiKey);
                break;
            case CUSTOM_HEADER:
                String[] parts = apiKey.split(":", 2);
                if (parts.length == 2) {
                    conn.setRequestProperty(parts[0], parts[1]);
                } else {
                    conn.setRequestProperty("Authorization", apiKey);
                }
                break;
            case API_KEY_PARAM:
                // API key 已在 buildRequestUrl() 中加入 URL。
                break;
        }
        if (!isBuiltinRoute()) {
            OllamaProxyMod.LOGGER.debug("认证方式: {}, API Key: ****", authType);
        }
    }

    private boolean isBuiltinRoute() {
        return routeId != null && routeTier != null;
    }

    private void logRequestInfo(String format, String model, int dataSize) {
        if (isBuiltinRoute()) {
            OllamaProxyMod.LOGGER.debug("云端内置 STT 线路请求: routeId={}, tier={}", routeId, routeTier);
            return;
        }
        OllamaProxyMod.LOGGER.info("===== 语音识别请求 =====");
        OllamaProxyMod.LOGGER.info("端点: {}", NetworkSecurity.sanitizedEndpoint(apiUrl));
        OllamaProxyMod.LOGGER.info("格式: {}", format);
        OllamaProxyMod.LOGGER.info("模型: {}", model);
        OllamaProxyMod.LOGGER.info("音频大小: {} bytes", dataSize);
        OllamaProxyMod.LOGGER.info("认证方式: {}", authType);
        OllamaProxyMod.LOGGER.info("响应字段: {}", responseField);
        OllamaProxyMod.LOGGER.info("========================");
    }

    private String getResponseAndParse(HttpURLConnection conn) throws Exception {
        try {
            int responseCode = conn.getResponseCode();
            InputStream responseStream = responseCode >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String responseBody;
            if (responseStream == null) {
                responseBody = "";
            } else {
                try (InputStream is = responseStream) {
                    responseBody = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
            if (responseCode != 200) {
                logErrorResponse(conn, responseCode, responseBody);
                throw new HttpStatusException(responseCode);
            }
            if (isBuiltinRoute()) {
                OllamaProxyMod.LOGGER.debug("云端内置 STT 线路成功: routeId={}, tier={}, status={}",
                        routeId, routeTier, responseCode);
            } else {
                OllamaProxyMod.LOGGER.info("语音识别API调用成功，响应长度: {} 字符", responseBody.length());
            }
            return parseTextFromJson(responseBody, responseField);
        } finally {
            conn.disconnect();
        }
    }

    private void logErrorResponse(HttpURLConnection conn, int responseCode, String responseBody) {
        if (isBuiltinRoute()) {
            OllamaProxyMod.LOGGER.error("云端内置 STT 线路失败: routeId={}, tier={}, status={}",
                    routeId, routeTier, responseCode);
            return;
        }
        OllamaProxyMod.LOGGER.error("===== 语音识别API调用失败 =====");
        OllamaProxyMod.LOGGER.error("响应摘要: {}", summarize(responseBody, 500));
        OllamaProxyMod.LOGGER.error("状态码: {}", responseCode);
        OllamaProxyMod.LOGGER.error("API端点: {}", NetworkSecurity.sanitizedEndpoint(apiUrl));
        OllamaProxyMod.LOGGER.error("SiliconFlow Trace ID: {}", headerOrMissing(conn, "x-siliconcloud-trace-id"));
        OllamaProxyMod.LOGGER.error("Request ID: {}", headerOrMissing(conn, "x-request-id"));
        
        if (responseCode == 401) {
            OllamaProxyMod.LOGGER.error("===== 401认证失败常见原因 =====");
            OllamaProxyMod.LOGGER.error("1. API密钥错误或已过期");
            OllamaProxyMod.LOGGER.error("2. API密钥与服务商不匹配");
            OllamaProxyMod.LOGGER.error("3. 认证方式不正确（当前使用: {}）", authType);
            OllamaProxyMod.LOGGER.error("4. 服务商需要实名认证后才能使用");
            OllamaProxyMod.LOGGER.error("==============================");
        } else if (responseCode == 400) {
            OllamaProxyMod.LOGGER.error("===== 400参数错误 =====");
            OllamaProxyMod.LOGGER.error("1. 模型名称不正确");
            OllamaProxyMod.LOGGER.error("2. 请求格式不匹配（当前使用: {}）", requestFormat);
            OllamaProxyMod.LOGGER.error("3. 音频格式或采样率不正确");
            OllamaProxyMod.LOGGER.error("======================");
        }
    }

    private static String headerOrMissing(HttpURLConnection conn, String name) {
        String value = conn.getHeaderField(name);
        return value == null || value.isBlank() ? "<missing>" : value;
    }

    @Override
    public boolean isReady() {
        return apiUrl != null && !apiUrl.isBlank() && apiKey != null && !apiKey.isBlank();
    }

    @Override
    public void shutdown() {
    }

    private static void logPcmStats(byte[] pcmData) {
        if (pcmData == null || pcmData.length < 2) {
            OllamaProxyMod.LOGGER.info("[DEBUG online-stt-empty-input] PCM统计: empty");
            return;
        }
        long absSum = 0L;
        int peak = 0;
        int samples = pcmData.length / 2;
        for (int i = 0; i + 1 < pcmData.length; i += 2) {
            int sample = (short) ((pcmData[i] & 0xFF) | (pcmData[i + 1] << 8));
            int abs = Math.abs(sample);
            absSum += abs;
            if (abs > peak) {
                peak = abs;
            }
        }
        double avgAbs = samples == 0 ? 0.0 : absSum / (double) samples;
        OllamaProxyMod.LOGGER.info("[DEBUG online-stt-empty-input] PCM统计: bytes={}, samples={}, avgAbs={}, peak={}", pcmData.length, samples, String.format("%.2f", avgAbs), peak);
    }

    private static void logWavInfo(byte[] wavData, int pcmLength, int sampleRate, int bitsPerSample, int channels) {
        int riffSize = readLittleEndianInt(wavData, 4);
        int dataSize = readLittleEndianInt(wavData, 40);
        OllamaProxyMod.LOGGER.info("[DEBUG online-stt-wav] WAV统计: bytes={}, riffSize={}, dataSize={}, pcmBytes={}, sampleRate={}, bits={}, channels={}",
                wavData.length, riffSize, dataSize, pcmLength, sampleRate, bitsPerSample, channels);
    }

    private static int readLittleEndianInt(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    private static final class HttpStatusException extends RuntimeException {
        private final int statusCode;

        private HttpStatusException(int statusCode) {
            super("语音识别API调用失败，状态码：" + statusCode);
            this.statusCode = statusCode;
        }
    }

    private static String summarize(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return "<empty>";
        }
        String clean = text.replace('\n', ' ').replace('\r', ' ').trim();
        return clean.length() > maxLength ? clean.substring(0, maxLength) + "..." : clean;
    }

    private byte[] buildWavFile(byte[] pcmData, int sampleRate, int bitsPerSample, int channels) {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int totalLen = pcmData.length + 36;
        byte[] header = new byte[44];

        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        header[4] = (byte) (totalLen & 0xff);
        header[5] = (byte) ((totalLen >> 8) & 0xff);
        header[6] = (byte) ((totalLen >> 16) & 0xff);
        header[7] = (byte) ((totalLen >> 24) & 0xff);

        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';

        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0;
        header[20] = 1; header[21] = 0;
        header[22] = (byte) channels; header[23] = 0;

        header[24] = (byte) (sampleRate & 0xff);
        header[25] = (byte) ((sampleRate >> 8) & 0xff);
        header[26] = (byte) ((sampleRate >> 16) & 0xff);
        header[27] = (byte) ((sampleRate >> 24) & 0xff);

        header[28] = (byte) (byteRate & 0xff);
        header[29] = (byte) ((byteRate >> 8) & 0xff);
        header[30] = (byte) ((byteRate >> 16) & 0xff);
        header[31] = (byte) ((byteRate >> 24) & 0xff);

        header[32] = (byte) (channels * bitsPerSample / 8); header[33] = 0;
        header[34] = (byte) bitsPerSample; header[35] = 0;

        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        header[40] = (byte) (pcmData.length & 0xff);
        header[41] = (byte) ((pcmData.length >> 8) & 0xff);
        header[42] = (byte) ((pcmData.length >> 16) & 0xff);
        header[43] = (byte) ((pcmData.length >> 24) & 0xff);

        byte[] wavFile = new byte[44 + pcmData.length];
        System.arraycopy(header, 0, wavFile, 0, 44);
        System.arraycopy(pcmData, 0, wavFile, 44, pcmData.length);
        return wavFile;
    }

    private String parseTextFromJson(String rawJson, String fieldName) {
        try {
            JsonObject json = GSON.fromJson(rawJson, JsonObject.class);

            // OpenAI Chat 格式: choices[0].message.content（MiMo ASR 等返回）
            if (json.has("choices")) {
                JsonArray choices = json.getAsJsonArray("choices");
                if (!choices.isEmpty()) {
                    JsonObject choice = choices.get(0).getAsJsonObject();
                    if (choice.has("message")) {
                        JsonObject message = choice.getAsJsonObject("message");
                        if (message.has("content")) {
                            return message.get("content").getAsString().trim();
                        }
                    }
                }
            }
            
            if (json.has(fieldName)) {
                return json.get(fieldName).getAsString().trim();
            }
            
            if (json.has("transcription")) {
                return json.get("transcription").getAsString().trim();
            }
            
            if (json.has("text")) {
                return json.get("text").getAsString().trim();
            }
            
            if (json.has("data")) {
                JsonObject data = json.getAsJsonObject("data");
                if (data.has(fieldName)) return data.get(fieldName).getAsString().trim();
                if (data.has("text")) return data.get("text").getAsString().trim();
                if (data.has("transcription")) return data.get("transcription").getAsString().trim();
                
                if (data.has("result")) {
                    JsonObject result = data.getAsJsonObject("result");
                    if (result.has(fieldName)) return result.get(fieldName).getAsString().trim();
                    if (result.has("text")) return result.get("text").getAsString().trim();
                }
                
                if (data.has("results")) {
                    JsonArray results = data.getAsJsonArray("results");
                    if (!results.isEmpty()) {
                        JsonObject first = results.get(0).getAsJsonObject();
                        if (first.has(fieldName)) return first.get(fieldName).getAsString().trim();
                        if (first.has("text")) return first.get("text").getAsString().trim();
                    }
                }
            }
            
            if (json.has("result")) {
                String result = json.get("result").getAsString().trim();
                if (!result.isEmpty()) return result;
            }
            
            if (json.has("results")) {
                JsonArray results = json.getAsJsonArray("results");
                if (!results.isEmpty()) {
                    JsonObject first = results.get(0).getAsJsonObject();
                    if (first.has(fieldName)) return first.get(fieldName).getAsString().trim();
                    if (first.has("text")) return first.get("text").getAsString().trim();
                    if (first.has("transcription")) return first.get("transcription").getAsString().trim();
                }
            }
            
            if (json.has("ret")) {
                int ret = json.get("ret").getAsInt();
                if (ret == 0 && json.has("data")) {
                    JsonObject data = json.getAsJsonObject("data");
                    if (data.has("result")) return data.get("result").getAsString().trim();
                }
            }
            
            if (json.has("success") && json.get("success").getAsBoolean()) {
                if (json.has("result")) return json.get("result").getAsString().trim();
                if (json.has("message")) return json.get("message").getAsString().trim();
            }
            
        } catch (Exception e) {
            OllamaProxyMod.LOGGER.warn("解析语音识别响应失败，尝试简单匹配", e);
        }

        String[] keys = {"\"" + fieldName + "\"", "\"text\"", "\"transcription\"", "\"result\""};
        for (String key : keys) {
            String[] patterns = {"\"" + key + "\":\"", "\"" + key + "\" : \""};
            for (String pattern : patterns) {
                int startIdx = rawJson.indexOf(pattern);
                if (startIdx != -1) {
                    startIdx += pattern.length();
                    int endIdx = rawJson.indexOf("\"", startIdx);
                    if (endIdx != -1) {
                        return rawJson.substring(startIdx, endIdx).trim();
                    }
                }
            }
        }

        return "";
    }
}
