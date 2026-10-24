package com.hanchen.veritycustomapi.tts;

import com.hanchen.veritycustomapi.util.Silent;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.NetworkSecurity;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.cloud.BillingService;
import com.hanchen.veritycustomapi.cloud.CloudConfigFetcher;
import com.hanchen.veritycustomapi.cloud.MimoDirectProtocol;
import com.hanchen.veritycustomapi.cloud.RouteHealthRegistry;
import com.hanchen.veritycustomapi.cloud.UsageMeter;
import com.hanchen.veritycustomapi.TtsApiProviderEnum;
import com.hanchen.veritycustomapi.compat.RuntimePlatform;
import com.hanchen.veritycustomapi.compat.ForgePaths;
import com.hanchen.veritycustomapi.compat.VerityEntityTalkAnimator;
import com.hanchen.veritycustomapi.mixin.SoundEngineAccessor;
import com.hanchen.veritycustomapi.mixin.SoundManagerAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundEngineExecutor;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.openal.AL10;
import org.lwjgl.system.MemoryUtil;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.ByteBuffer;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@OnlyIn(Dist.CLIENT)
public class OnlineTtsService {
    private static final Gson GSON = new Gson();
    private static final float MOBILE_TARGET_RMS = 8200.0F;
    private static final float MOBILE_MIN_NORMALIZATION_GAIN = 2.0F;
    private static final float MOBILE_MAX_NORMALIZATION_GAIN = 4.0F;
    private static final float MOBILE_PLAYER_GAIN = 1.0F;
    private static volatile int CURRENT_OPENAL_SOURCE;
    private static volatile int CURRENT_OPENAL_BUFFER;

    private static final String MIMO_DIRECT_TTS_MODEL = "mimo-v2.5-tts";
    private static final String MIMO_DIRECT_CLONE_MODEL = "mimo-v2.5-tts-voiceclone";
    private static final Path MIMO_VOICE_CACHE = ForgePaths.config("verity_extended", "voices");

    /** 跨平台兼容的 HTTP 请求发送（使用 HttpURLConnection，兼容 Android/FCL） */
    private static HttpResponse<byte[]> sendHttpRequest(HttpRequest request) throws Exception {
        URI secureUri = NetworkSecurity.requireSecureUri(request.uri().toString(), "TTS API 地址");
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) secureUri.toURL().openConnection();
        try {
        conn.setRequestMethod(request.method());
        conn.setInstanceFollowRedirects(false);
        long requestTimeoutMillis = request.timeout().orElse(Duration.ofSeconds(30)).toMillis();
        int timeoutMillis = (int) Math.min(Integer.MAX_VALUE, Math.max(1000L, requestTimeoutMillis));
        conn.setConnectTimeout(Math.min(10000, timeoutMillis));
        conn.setReadTimeout(timeoutMillis);
        conn.setDoOutput(true);
        request.headers().map().forEach((k, v) -> v.forEach(val -> conn.setRequestProperty(k, val)));
        byte[] bodyBytes = request.bodyPublisher()
                .map(p -> {
                    try {
                        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                        p.subscribe(new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
                            public void onSubscribe(java.util.concurrent.Flow.Subscription s) { s.request(Long.MAX_VALUE); }
                            public void onNext(java.nio.ByteBuffer buf) {
                                byte[] b = new byte[buf.remaining()]; buf.get(b);
                                try { bos.write(b); } catch (Exception ignored) { Silent.ignore("OnlineTtsService", ignored); }
                            }
                            public void onError(Throwable t) {}
                            public void onComplete() {}
                        });
                        return bos.toByteArray();
                    } catch (Exception e) { return new byte[0]; }
                }).orElse(new byte[0]);
        if (bodyBytes.length > 0) {
            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
            }
        }
        int status = conn.getResponseCode();
        java.io.InputStream responseStream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (responseStream == null && status < 400) responseStream = conn.getInputStream();
        byte[] respBytes;
        if (responseStream == null) {
            respBytes = new byte[0];
        } else {
            try (java.io.InputStream is = responseStream) {
                respBytes = is.readAllBytes();
            }
        }
        return java.net.http.HttpResponse.class.cast(new java.net.http.HttpResponse<byte[]>() {
            public int statusCode() { return status; }
            public byte[] body() { return respBytes; }
            public java.net.http.HttpHeaders headers() { return java.net.http.HttpHeaders.of(java.util.Collections.emptyMap(), (k, v) -> true); }
            public java.net.http.HttpRequest request() { return request; }
            public java.util.Optional<java.net.http.HttpResponse<byte[]>> previousResponse() { return java.util.Optional.empty(); }
            public java.net.URI uri() { return request.uri(); }
            public java.net.http.HttpClient.Version version() { return java.net.http.HttpClient.Version.HTTP_1_1; }
            public java.util.Optional<javax.net.ssl.SSLSession> sslSession() { return java.util.Optional.empty(); }
        });
        } finally {
            conn.disconnect();
        }
    }

    private record QueuedAudio(byte[] data, int entityId, long sessionGeneration) {}

    private static final BlockingQueue<QueuedAudio> PLAY_QUEUE = new LinkedBlockingQueue<>(5);
    private static volatile boolean IS_PLAYING = false;
    private static volatile boolean IS_SHUTDOWN = false;
    private static volatile Thread PLAYER_THREAD = null;
    private static volatile SourceDataLine CURRENT_LINE = null;
    private static volatile Object CURRENT_ANDROID_PLAYER = null;
    private static volatile ExecutorService TASK_EXECUTOR = createTaskExecutor();
    private static volatile long SESSION_GENERATION;

    private static ExecutorService createTaskExecutor() {
        return new ThreadPoolExecutor(
                2,
                2,
                30L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(8),
                runnable -> {
                    Thread thread = new Thread(runnable, "Online-TTS-Worker");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    private static synchronized ExecutorService ensureTaskExecutor() {
        if (TASK_EXECUTOR == null || TASK_EXECUTOR.isShutdown()) {
            TASK_EXECUTOR = createTaskExecutor();
        }
        return TASK_EXECUTOR;
    }

    private static synchronized void ensurePlayerThread() {
        if (PLAYER_THREAD == null || !PLAYER_THREAD.isAlive()) {
            IS_SHUTDOWN = false;
            PLAYER_THREAD = new Thread(OnlineTtsService::playLoop, "TTS-Playback-Thread");
            PLAYER_THREAD.setDaemon(true);
            PLAYER_THREAD.start();
        }
    }

    private static void playLoop() {
        while (!IS_SHUTDOWN) {
            try {
                QueuedAudio audio = PLAY_QUEUE.take();
                if (audio.sessionGeneration() != SESSION_GENERATION) continue;
                IS_PLAYING = true;
                long talkToken = 0;
                try {
                    talkToken = com.hanchen.veritycustomapi.compat.VerityEntityTalkAnimator.begin(audio.entityId());
                    playAudio(audio.data(), audio.entityId(), talkToken);
                } catch (Exception e) {
                    if (RuntimePlatform.isAndroidRuntime()) {
                        OllamaProxyMod.LOGGER.warn("Android/FCL 在线 TTS 合成成功，但系统播放器播放失败: {}", e.getMessage());
                    } else {
                        OllamaProxyMod.LOGGER.error("TTS音频播放失败", e);
                    }
                } finally {
                    IS_PLAYING = false;
                    com.hanchen.veritycustomapi.compat.VerityEntityTalkAnimator.end(audio.entityId(), talkToken);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    public static void stopPlayback() {
        PLAY_QUEUE.clear();
        stopCurrentPlaybackResource();
        Thread playerThread = PLAYER_THREAD;
        if (playerThread != null) {
            playerThread.interrupt();
        }
        IS_PLAYING = false;
        OllamaProxyMod.LOGGER.debug("在线 TTS 播放已停止");
    }

    private static void stopCurrentPlaybackResource() {
        SourceDataLine line = CURRENT_LINE;
        if (line != null) {
            try {
                line.stop();
                line.flush();
                line.close();
            } catch (Throwable ignored) { Silent.ignore("OnlineTtsService", ignored); }
            CURRENT_LINE = null;
        }

        Object player = CURRENT_ANDROID_PLAYER;
        if (player != null) {
            try {
                Class<?> mediaPlayerClass = Class.forName("android.media.MediaPlayer");
                Method stop = mediaPlayerClass.getMethod("stop");
                stop.invoke(player);
            } catch (Throwable ignored) { Silent.ignore("OnlineTtsService", ignored); }
            CURRENT_ANDROID_PLAYER = null;
        }
        stopMinecraftOpenAl();
        VanillaAudioSink.stopAll();
    }

    private static void stopMinecraftOpenAl() {
        if (CURRENT_OPENAL_SOURCE == 0 && CURRENT_OPENAL_BUFFER == 0) return;
        try {
            SoundEngineExecutor executor = ((SoundEngineAccessor) ((SoundManagerAccessor) Minecraft.getInstance().getSoundManager())
                    .verityExtended$getSoundEngine()).verityExtended$getExecutor();
            executor.execute(OnlineTtsService::stopMinecraftOpenAlOnSoundThread);
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.warn("提交 Minecraft OpenAL 停止任务失败: {}", error.getMessage());
        }
    }

    public static void speakAsync(String text) {
        speakAsync(text, -1);
    }

    public static void speakAsync(String text, int entityId) {
        speakAsync(text, entityId, SESSION_GENERATION);
    }

    public static void speakAsync(String text, int entityId, long expectedSession) {
        if (text == null || text.isEmpty()) return;
        if (expectedSession != SESSION_GENERATION) return;

        TtsApiProviderEnum provider = ConfigManager.getTtsApiProvider();
        String apiUrl = ConfigManager.getTtsApiUrl();
        if (apiUrl != null && apiUrl.startsWith("ws")) {
            OllamaProxyMod.LOGGER.warn("{} 使用 WebSocket 协议，当前 HTTP 实现不支持，已跳过。请改用其他 TTS 服务商。", provider.displayName);
            return;
        }

        ensurePlayerThread();

        try {
            ensureTaskExecutor().execute(() -> {
                try {
                    byte[] audioBytes = synthesize(text);
                    if (expectedSession != SESSION_GENERATION) return;
                    if (audioBytes.length > 0) {
                        if (!PLAY_QUEUE.offer(new QueuedAudio(audioBytes, entityId, expectedSession))) {
                            OllamaProxyMod.LOGGER.warn("在线 TTS 播放队列已满，已丢弃本次朗读");
                        }
                    } else {
                        OllamaProxyMod.LOGGER.warn("在线TTS未返回可播放音频，已跳过朗读");
                    }
                } catch (Exception e) {
                    OllamaProxyMod.LOGGER.error("在线TTS合成播放失败，未使用系统朗读兜底", e);
                }
            });
        } catch (RejectedExecutionException e) {
            OllamaProxyMod.LOGGER.warn("在线 TTS 任务队列已满，已丢弃本次朗读");
        }
    }

    public static synchronized void startSession(long generation) {
        SESSION_GENERATION = generation;
        PLAY_QUEUE.clear();
        stopCurrentPlaybackResource();
        ensureTaskExecutor();
        ensurePlayerThread();
    }

    public static byte[] synthesize(String text) throws Exception {
        Exception last = null;
        for (ConfigManager.TtsRoute route : ConfigManager.snapshotTtsRoutes()) {
            if (!ConfigManager.tryAcquireRoute("tts", route.tier(), route.id())) continue;
            if ("paid".equals(route.tier()) && !ConfigManager.canUseCloudTier("paid")) {
                OllamaProxyMod.LOGGER.warn("云端 TTS 额度不足，请前往积分中心充值");
                return new byte[0];
            }
            try {
                byte[] audio = ConfigManager.withTtsRoute(route, () -> synthesizeCurrent(text));
                if (audio.length == 0) {
                    ConfigManager.reportRouteFailure("tts", route.tier(), route.id(), RouteHealthRegistry.FailureKind.UNAVAILABLE);
                    continue;
                }
                ConfigManager.reportRouteSuccess("tts", route.tier(), route.id());
                return audio;
            } catch (Exception error) {
                last = error;
                RouteHealthRegistry.FailureKind kind = failureKind(error);
                ConfigManager.reportRouteFailure("tts", route.tier(), route.id(), kind);
                if (!isFailoverFailure(kind)) throw error;
            }
        }
        if (last != null) throw last;
        return new byte[0];
    }

    private static byte[] synthesizeCurrent(String text) throws Exception {
        TtsApiProviderEnum provider = ConfigManager.getTtsApiProvider();
        String apiUrl = ConfigManager.getTtsApiUrl();
        String apiKey = ConfigManager.getTtsApiKey();
        String billingRequestId = BillingService.requestId();
        ConfigManager.TtsRoute activeRoute = ConfigManager.currentTtsRoute();
        String billingTier = activeRoute != null ? activeRoute.tier() : null;
        if (billingTier != null && !ConfigManager.canUseCloudTier(billingTier)) {
            OllamaProxyMod.LOGGER.warn("云端 TTS 额度不足，请前往积分中心充值");
            return new byte[0];
        }
        if (apiUrl == null || apiUrl.isBlank()) {
            OllamaProxyMod.LOGGER.warn("TTS接口地址未配置，跳过合成");
            return new byte[0];
        }

        if (activeRoute != null && activeRoute.tier() != null) {
            OllamaProxyMod.LOGGER.debug("云端内置 TTS 线路: routeId={}, tier={}", activeRoute.id(), activeRoute.tier());
        } else {
            OllamaProxyMod.LOGGER.info("自定义 TTS 线路: provider={}, endpoint={}",
                    provider.name(), NetworkSecurity.sanitizedEndpoint(apiUrl));
        }
        if (provider == TtsApiProviderEnum.MIMO_TTS && activeRoute != null
                && activeRoute.id() != null && activeRoute.tier() != null
                && apiKey != null && !apiKey.isBlank()) {
            return synthesizeMimoDirect(text, billingRequestId,
                    new ConfigManager.MimoRuntimeConfig(apiUrl, apiKey,
                            ConfigManager.getTtsApiModel(), activeRoute.id(), activeRoute.tier()));
        }

        try {
            URI uri = NetworkSecurity.requireSecureUri(apiUrl, "TTS API 地址");
            if (uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath())) {
                OllamaProxyMod.LOGGER.warn("TTS接口地址路径为空，可能导致404错误。endpoint: {}", NetworkSecurity.sanitizedEndpoint(apiUrl));
            }
        } catch (Exception e) {
            OllamaProxyMod.LOGGER.error("TTS接口地址无效: {}", e.getMessage());
            return new byte[0];
        }

        String cleanText = cleanText(text);
        if (cleanText.isEmpty()) {
            OllamaProxyMod.LOGGER.warn("清洗后文本为空，跳过TTS合成");
            return new byte[0];
        }

        String model = ConfigManager.getTtsApiModel();
        String voice = ConfigManager.getTtsApiVoice();
        if (activeRoute != null && activeRoute.tier() != null) {
            OllamaProxyMod.LOGGER.debug("云端内置 TTS 请求参数: routeId={}, tier={}, textLength={}, format={}",
                    activeRoute.id(), activeRoute.tier(), cleanText.length(), provider.responseFormat);
        } else {
            OllamaProxyMod.LOGGER.info("自定义 TTS 请求参数: provider={}, textLength={}, format={}",
                    provider.name(), cleanText.length(), provider.responseFormat);
        }

        HttpRequest request = buildRequest(cleanText, provider, apiUrl, apiKey);
        if (request == null) {
            return new byte[0];
        }

        HttpResponse<byte[]> response = sendHttpRequest(request);

        int status = response.statusCode();
        if (status != 200) {
            logTtsHttpError(status, response.body(), provider, apiUrl, model, voice, cleanText.length());
            throw new TtsStatusException(status);
        }

        byte[] body = response.body();
        OllamaProxyMod.LOGGER.info("在线TTS合成成功，原始响应大小: {} 字节", body.length);
        
        if ("base64".equals(provider.responseType)) {
            try {
                String responseStr = new String(body, StandardCharsets.UTF_8);
                String audioData = extractBase64AudioData(responseStr);
                if (audioData != null && !audioData.isEmpty()) {
                    body = Base64.getDecoder().decode(audioData);
                    OllamaProxyMod.LOGGER.info("Base64解码成功，音频大小: {} 字节", body.length);
                } else {
                    OllamaProxyMod.LOGGER.warn("无法从响应中提取Base64音频数据");
                    return new byte[0];
                }
            } catch (Exception e) {
                OllamaProxyMod.LOGGER.error("Base64解码失败", e);
                return new byte[0];
            }
        }
        
        if ("url".equals(provider.responseType)) {
            body = downloadAudioFromJson(body);
            if (body == null || body.length == 0) {
                OllamaProxyMod.LOGGER.warn("TTS 响应里没有可下载的音频地址");
                return new byte[0];
            }
            OllamaProxyMod.LOGGER.info("已从响应地址下载音频，大小: {} 字节", body.length);
        }

        if (!isValidAudioData(body, provider.responseFormat)) {
            OllamaProxyMod.LOGGER.warn("TTS响应数据不是有效的音频格式，响应大小: {} 字节", body.length);
            return new byte[0];
        }
        if (billingTier != null && activeRoute != null) {
            BillingService.reportTts(billingRequestId, billingTier, activeRoute.id(),
                    UsageMeter.characters(cleanText), false);
        }
        return body;
    }

    private static RouteHealthRegistry.FailureKind failureKind(Exception error) {
        if (error instanceof TtsStatusException statusError) {
            int status = statusError.status;
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

    private static final class TtsStatusException extends Exception {
        private final int status;

        private TtsStatusException(int status) {
            super("TTS API 调用失败，状态码：" + status);
            this.status = status;
        }
    }

    private static void logTtsHttpError(int status, byte[] responseBody, TtsApiProviderEnum provider,
                                        String apiUrl, String model, String voice, int textLength) {
        ConfigManager.TtsRoute route = ConfigManager.currentTtsRoute();
        if (route != null && route.tier() != null) {
            OllamaProxyMod.LOGGER.error(
                    "云端内置 TTS 请求失败: routeId={}, tier={}, HTTP={}, textLength={}",
                    route.id(), route.tier(), status, textLength);
        } else {
            OllamaProxyMod.LOGGER.error(
                    "自定义 TTS 请求失败: provider={}, HTTP={}, endpoint={}, modelConfigured={}, voiceConfigured={}, textLength={}",
                    provider.name(), status, NetworkSecurity.sanitizedEndpoint(apiUrl),
                    model != null && !model.isBlank(), voice != null && !voice.isBlank(), textLength);
        }
        if (status == 400) {
            OllamaProxyMod.LOGGER.error("TTS错误说明: HTTP 400，通常表示请求参数、模型、音色或请求体格式不被服务端接受");
        } else if (status == 401) {
            OllamaProxyMod.LOGGER.error("TTS错误说明: HTTP 401，API Key 无效、过期或未正确传递");
        } else if (status == 403) {
            OllamaProxyMod.LOGGER.error("TTS错误说明: HTTP 403，账号权限、额度或服务访问权限不足");
        } else if (status == 404) {
            OllamaProxyMod.LOGGER.error("TTS错误说明: HTTP 404，TTS 接口地址或路径不存在");
        } else if (status == 429) {
            OllamaProxyMod.LOGGER.error("TTS错误说明: HTTP 429，请求过于频繁或服务当前负载过高");
        } else if (status >= 500) {
            OllamaProxyMod.LOGGER.error("TTS错误说明: HTTP {}，上游服务暂时异常，可能与服务负载或服务商故障有关", status);
        }
    }

    private static String extractBase64AudioData(String jsonResponse) {
        try {
            JsonObject root = GSON.fromJson(jsonResponse, JsonObject.class);
            if (root != null && root.has("choices")) {
                JsonArray choices = root.getAsJsonArray("choices");
                if (!choices.isEmpty()) {
                    JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
                    if (message != null && message.has("audio")) {
                        JsonObject audio = message.getAsJsonObject("audio");
                        if (audio != null && audio.has("data")) {
                            return audio.get("data").getAsString();
                        }
                    }
                }
            }
            if (root != null && root.has("audio")) {
                JsonObject audio = root.getAsJsonObject("audio");
                if (audio != null && audio.has("data")) {
                    return audio.get("data").getAsString();
                }
            }
        } catch (Exception e) {
            OllamaProxyMod.LOGGER.warn("按 JSON 结构提取 Base64 音频失败，尝试兼容旧格式: {}", e.getMessage());
        }

        int base64Start = jsonResponse.indexOf("data:audio/");
        if (base64Start != -1) {
            int commaIndex = jsonResponse.indexOf(",", base64Start);
            if (commaIndex != -1) {
                int endIndex = jsonResponse.indexOf("\"", commaIndex);
                if (endIndex != -1) {
                    return jsonResponse.substring(commaIndex + 1, endIndex);
                }
                return jsonResponse.substring(commaIndex + 1);
            }
        }
        return null;
    }

    private static boolean isBuiltinMimoEndpoint(String apiUrl) {
        return apiUrl != null && apiUrl.contains("ai.mocwl.top");
    }

    /**
     * 有些服务商（通义千问 / CosyVoice、Moss）不直接回音频字节，而是回一段 JSON、
     * 音频放在某个 url 字段里。这里把地址取出来再下载。
     *
     * 字段名各家不一样（{@code output.audio.url} / {@code data.url} …），所以不写死路径，
     * 而是递归找第一个看起来像 http(s) 地址的字符串；深度限制 6 层，避免在异常结构上打转。
     */
    private static byte[] downloadAudioFromJson(byte[] body) {
        try {
            String responseStr = new String(body, StandardCharsets.UTF_8);
            String url = extractHttpUrl(responseStr);
            if (url == null || url.isBlank()) {
                OllamaProxyMod.LOGGER.warn("未能从 TTS 响应中解析出音频地址");
                return null;
            }
            HttpRequest download = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<byte[]> response = sendHttpRequest(download);
            if (response.statusCode() != 200) {
                OllamaProxyMod.LOGGER.warn("音频地址下载失败，HTTP {}", response.statusCode());
                return null;
            }
            return response.body();
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.warn("下载 TTS 音频失败: {}", error.toString());
            return null;
        }
    }

    /** 递归查找 JSON 里第一个 http(s) 字符串。 */
    private static String extractHttpUrl(String json) {
        if (json == null || json.isBlank() || json.indexOf('{') < 0) {
            return null;
        }
        try {
            StringBuilder found = new StringBuilder();
            collectHttpUrl(JsonParser.parseString(json), found, 0);
            return found.isEmpty() ? null : found.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void collectHttpUrl(JsonElement element, StringBuilder found, int depth) {
        if (depth > 6 || element == null || element.isJsonNull() || !found.isEmpty()) {
            return;
        }
        if (element.isJsonPrimitive()) {
            if (element.getAsJsonPrimitive().isString()) {
                String text = element.getAsString();
                if (text.startsWith("http://") || text.startsWith("https://")) {
                    found.append(text);
                }
            }
            return;
        }
        if (element.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                collectHttpUrl(entry.getValue(), found, depth + 1);
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectHttpUrl(child, found, depth + 1);
            }
        }
    }

    /** MiniMax：文本放在 text 字段，音色与语速音量在 voice_setting 里，音频参数在 audio_setting 里。 */
    private static HttpRequest buildMiniMaxRequest(String text, TtsApiProviderEnum provider, String apiUrl, String apiKey) {
        String voice = requireVoice(provider);
        if (voice == null) return null;
        String model = resolveModel(provider);
        String json = String.format(
                "{\"model\":\"%s\",\"text\":\"%s\",\"voice_setting\":{\"voice_id\":\"%s\",\"speed\":1,\"vol\":1,\"pitch\":0},"
                        + "\"audio_setting\":{\"sample_rate\":32000,\"format\":\"%s\",\"channel\":1}}",
                escapeJson(model), escapeJson(text), escapeJson(voice), provider.responseFormat);
        return jsonPost(apiUrl, json, "Bearer " + apiKey);
    }

    /**
     * Cartesia：鉴权头是 {@code X-API-Key} 而不是 Bearer，且必须带 {@code Cartesia-Version}；
     * 文本字段叫 transcript，音频格式是一个嵌套的 output_format 对象。
     */
    private static HttpRequest buildCartesiaRequest(String text, TtsApiProviderEnum provider, String apiUrl, String apiKey) {
        String voice = requireVoice(provider);
        if (voice == null) return null;
        String model = resolveModel(provider);
        String json = String.format(
                "{\"model_id\":\"%s\",\"transcript\":\"%s\",\"voice\":\"%s\","
                        + "\"output_format\":{\"container\":\"wav\",\"encoding\":\"pcm_s16le\",\"sample_rate\":44100}}",
                escapeJson(model), escapeJson(text), escapeJson(voice));
        if (apiUrl == null || apiUrl.isBlank()) return null;
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Cartesia-Version", "2026-08-14")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("X-API-Key", apiKey);
        }
        return builder.build();
    }

    /**
     * 通义千问 / CosyVoice：文本与音色都在嵌套的 input 对象里。
     * 响应是 JSON、音频是一个下载链接，所以 responseType = url。
     */
    private static HttpRequest buildQwenRequest(String text, TtsApiProviderEnum provider, String apiUrl, String apiKey) {
        String voice = requireVoice(provider);
        if (voice == null) return null;
        if (apiUrl == null || apiUrl.isBlank()) {
            OllamaProxyMod.LOGGER.warn("通义千问 / CosyVoice 需要自行填写 API 地址（不同区域端点不同）");
            return null;
        }
        String model = resolveModel(provider);
        String json = String.format(
                "{\"model\":\"%s\",\"input\":{\"text\":\"%s\",\"voice\":\"%s\",\"format\":\"wav\"}}",
                escapeJson(model), escapeJson(text), escapeJson(voice));
        return jsonPost(apiUrl, json, "Bearer " + apiKey);
    }

    /** Moss（魔音工坊）：文本字段叫 input，音色字段叫 voice_id，取音频用下载链接（delivery_method=url）。 */
    private static HttpRequest buildMossRequest(String text, TtsApiProviderEnum provider, String apiUrl, String apiKey) {
        String voice = requireVoice(provider);
        if (voice == null) return null;
        String model = resolveModel(provider);
        String json = String.format(
                "{\"model\":\"%s\",\"input\":\"%s\",\"voice_id\":\"%s\",\"response_format\":\"wav\",\"delivery_method\":\"url\"}",
                escapeJson(model), escapeJson(text), escapeJson(voice));
        return jsonPost(apiUrl, json, "Bearer " + apiKey);
    }

    /** 音色必填：没填就明确告警并放弃，不要发一个空音色的请求上去。 */
    private static String requireVoice(TtsApiProviderEnum provider) {
        String voice = ConfigManager.getTtsApiVoice();
        if (voice == null || voice.isBlank()) {
            voice = provider.defaultVoice;
        }
        if (voice == null || voice.isBlank()) {
            OllamaProxyMod.LOGGER.warn("{} 还没填音色（{}），请在 TTS 配置的音色一栏填写",
                    provider.displayName, provider.name());
            return null;
        }
        return voice;
    }

    private static String resolveModel(TtsApiProviderEnum provider) {
        String model = ConfigManager.getTtsApiModel();
        return (model == null || model.isBlank()) ? provider.defaultModel : model;
    }

    /** 通用 JSON POST + 单个鉴权头。 */
    private static HttpRequest jsonPost(String apiUrl, String json, String authorization) {
        if (apiUrl == null || apiUrl.isBlank()) {
            return null;
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (authorization != null && !authorization.isBlank()) {
            builder.header("Authorization", authorization);
        }
        return builder.build();
    }

    /**
     * Fish Audio 语音合成。
     *
     * 请求体与 OpenAI 系完全不同：这里是 {@code text + reference_id + format}，
     * 其中 reference_id 是 Fish Audio 的音色模型 id（配置里填在「音色」一栏），
     * 没有 model 字段——所以不能复用 openai 分支。
     *
     * 响应是原始 WAV 字节，和 responseFormat=wav 的其它供应商一样交给通用音频校验即可。
     */
    private static HttpRequest buildFishRequest(String text, TtsApiProviderEnum provider, String apiUrl, String apiKey) {
        String voice = ConfigManager.getTtsApiVoice();
        if (voice == null || voice.isBlank()) {
            voice = provider.defaultVoice;
        }
        if (voice == null || voice.isBlank()) {
            OllamaProxyMod.LOGGER.warn("Fish Audio 还没填 Reference ID（音色模型 id），请在 TTS 配置的音色一栏填写");
            return null;
        }
        String json = String.format(
                "{\"text\":\"%s\",\"reference_id\":\"%s\",\"format\":\"%s\"}",
                escapeJson(text), escapeJson(voice), provider.responseFormat);

        OllamaProxyMod.LOGGER.debug("TTS请求体已构建(Fish Audio): {} 字节", json.length());

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));

        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return builder.build();
    }

    private static HttpRequest buildRequest(String text, TtsApiProviderEnum provider, String apiUrl, String apiKey) {
        String requestFormat = provider.requestFormat;
        String authType = provider.authType;

        if (provider == TtsApiProviderEnum.MIMO_TTS) {
            return buildMimoRequest(text, provider, apiUrl, apiKey, isBuiltinMimoEndpoint(apiUrl));
        }

        try {
            switch (requestFormat) {
                case "openai":
                    return buildOpenAiRequest(text, provider, apiUrl, apiKey);
                case "alibaba":
                    return buildAlibabaRequest(text, provider, apiUrl, apiKey);
                case "tencent":
                    return buildTencentRequest(text, provider, apiUrl, apiKey);
                case "baidu":
                    return buildBaiduRequest(text, provider, apiUrl, apiKey);
                case "iflytek":
                    if (apiUrl != null && apiUrl.startsWith("ws")) {
                        OllamaProxyMod.LOGGER.error("讯飞 TTS 使用 WebSocket 协议，当前 HTTP 实现不支持，请切换为其他 TTS 服务商。");
                        return null;
                    }
                    return buildIflytekRequest(text, provider, apiUrl, apiKey);
                case "siliconflow":
                    return buildSiliconFlowRequest(text, provider, apiUrl, apiKey);
                case "moonshot":
                    return buildMoonshotRequest(text, provider, apiUrl, apiKey);
                case "bytedance":
                    return buildByteDanceRequest(text, provider, apiUrl, apiKey);
                case "qianwan":
                    return buildQianwanRequest(text, provider, apiUrl, apiKey);
                case "mimo":
                    return buildMimoRequest(text, provider, apiUrl, apiKey, isBuiltinMimoEndpoint(apiUrl));
                case "fish":
                    return buildFishRequest(text, provider, apiUrl, apiKey);
                case "minimax":
                    return buildMiniMaxRequest(text, provider, apiUrl, apiKey);
                case "cartesia":
                    return buildCartesiaRequest(text, provider, apiUrl, apiKey);
                case "qwen":
                    return buildQwenRequest(text, provider, apiUrl, apiKey);
                case "moss":
                    return buildMossRequest(text, provider, apiUrl, apiKey);
                default:
                    return buildOpenAiRequest(text, provider, apiUrl, apiKey);
            }
        } catch (Exception e) {
            OllamaProxyMod.LOGGER.error("构建TTS请求失败", e);
            return null;
        }
    }

    private static HttpRequest buildOpenAiRequest(String text, TtsApiProviderEnum provider, String apiUrl, String apiKey) {
        String model = ConfigManager.getTtsApiModel();
        if (model == null || model.isBlank()) {
            model = provider.defaultModel;
        }
        String voice = ConfigManager.getTtsApiVoice();
        if (voice == null || voice.isBlank()) {
            voice = provider.defaultVoice;
        }
        double speed = clamp(ConfigManager.getTtsSpeed(), 0.25, 4.0);
        
        String json = String.format(
                "{\"model\":\"%s\",\"input\":\"%s\",\"voice\":\"%s\",\"speed\":%.2f,\"response_format\":\"%s\"}",
                escapeJson(model), escapeJson(text), escapeJson(voice), speed, provider.responseFormat
        );

        OllamaProxyMod.LOGGER.debug("TTS请求体已构建: {} 字节", json.length());

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));

        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return builder.build();
    }

    private static HttpRequest buildAlibabaRequest(String text, TtsApiProviderEnum provider, String apiUrl, String apiKey) {
        try {
            String voice = ConfigManager.getTtsApiVoice();
            String speed = String.valueOf((int)(ConfigManager.getTtsSpeed() * 100));
            
            String query = String.format(
                    "appkey=%s&text=%s&voice=%s&speech_rate=%s&format=%s",
                    URLEncoder.encode(apiKey, "UTF-8"),
                    URLEncoder.encode(text, "UTF-8"),
                    URLEncoder.encode(voice, "UTF-8"),
                    URLEncoder.encode(speed, "UTF-8"),
                    URLEncoder.encode(provider.responseFormat, "UTF-8")
            );

            String fullUrl = apiUrl + (apiUrl.contains("?") ? "&" : "?") + query;
            
            return HttpRequest.newBuilder()
                    .uri(URI.create(fullUrl))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
        } catch (UnsupportedEncodingException e) {
            OllamaProxyMod.LOGGER.error("阿里云TTS编码失败", e);
            return null;
        }
    }

    private static HttpRequest buildTencentRequest(String text, TtsApiProviderEnum provider, String apiUrl, String apiKey) {
        try {
            String[] keys = apiKey != null ? apiKey.split(":") : new String[]{"", ""};
            String secretId = keys.length > 0 ? keys[0] : "";
            String secretKey = keys.length > 1 ? keys[1] : "";
            
            String voice = ConfigManager.getTtsApiVoice();
            String speed = String.valueOf((int)(ConfigManager.getTtsSpeed() * 100));
            
            String json = String.format(
                    "{\"Action\":\"TextToSpeech\",\"Version\":\"2019-08-23\",\"Text\":\"%s\",\"VoiceType\":\"%s\",\"Speed\":%s,\"Codec\":\"%s\"}",
                    escapeJson(text), escapeJson(voice), speed, provider.responseFormat.toUpperCase()
            );

            return HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("X-TC-Key", secretId)
                    .header("X-TC-Secret", secretKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
        } catch (Exception e) {
            OllamaProxyMod.LOGGER.error("腾讯云TTS请求构建失败", e);
            return null;
        }
    }

    private static HttpRequest buildBaiduRequest(String text, TtsApiProviderEnum provider, String apiUrl, String apiKey) {
        try {
            String[] keys = apiKey != null ? apiKey.split(":") : new String[]{"", ""};
            String apiKeyPart = keys.length > 0 ? keys[0] : "";
            String secretKey = keys.length > 1 ? keys[1] : "";
            
            String voice = ConfigManager.getTtsApiVoice();
            String speed = String.valueOf(ConfigManager.getTtsSpeed());
            
            String query = String.format(
                    "tex=%s&lan=zh&cuid=verity_minecraft&ctp=1&tok=%s&per=%s&spd=%s&pit=5&vol=5&aue=%s",
                    URLEncoder.encode(text, "UTF-8"),
                    URLEncoder.encode(apiKeyPart, "UTF-8"),
                    URLEncoder.encode(voice, "UTF-8"),
                    URLEncoder.encode(speed, "UTF-8"),
                    provider.responseFormat.equals("mp3") ? "3" : "6"
            );

            String fullUrl = apiUrl + (apiUrl.contains("?") ? "&" : "?") + query;
            
            return HttpRequest.newBuilder()
                    .uri(URI.create(fullUrl))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
        } catch (UnsupportedEncodingException e) {
            OllamaProxyMod.LOGGER.error("百度TTS编码失败", e);
            return null;
        }
    }

    private static HttpRequest buildIflytekRequest(String text, TtsApiProviderEnum provider, String apiUrl, String apiKey) {
        try {
            String[] keys = apiKey != null ? apiKey.split(":") : new String[]{"", "", ""};
            String appId = keys.length > 0 ? keys[0] : "";
            String apiKeyPart = keys.length > 1 ? keys[1] : "";
            String secretKey = keys.length > 2 ? keys[2] : "";
            
            String voice = ConfigManager.getTtsApiVoice();
            String speed = String.valueOf((int)(ConfigManager.getTtsSpeed() * 100));
            
            String textBase64 = Base64.getEncoder().encodeToString(text.getBytes("UTF-8"));
            
            String json = String.format(
                    "{\"common\":{\"app_id\":\"%s\"},\"business\":{\"aue\":\"%s\",\"sfl\":1,\"vcn\":\"%s\",\"speed\":%s},\"data\":{\"text\":\"%s\"}}",
                    appId,
                    provider.responseFormat.equals("wav") ? "raw" : "lame",
                    voice,
                    speed,
                    textBase64
            );

            String digest = generateIflytekDigest(json, apiKeyPart, secretKey);
            
            return HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("X-Appid", appId)
                    .header("X-CurTime", String.valueOf(System.currentTimeMillis() / 1000))
                    .header("X-Param", Base64.getEncoder().encodeToString("{\"aue\":\"raw\",\"sfl\":1}".getBytes()))
                    .header("X-CheckSum", digest)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
        } catch (Exception e) {
            OllamaProxyMod.LOGGER.error("讯飞TTS请求构建失败", e);
            return null;
        }
    }

    private static String generateIflytekDigest(String json, String apiKey, String secretKey) {
        try {
            String curTime = String.valueOf(System.currentTimeMillis() / 1000);
            String data = apiKey + curTime + secretKey + json;
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(data.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static HttpRequest buildSiliconFlowRequest(String text, TtsApiProviderEnum provider, String apiUrl, String apiKey) {
        String model = ConfigManager.getTtsApiModel();
        String voice = ConfigManager.getTtsApiVoice();
        double speed = clamp(ConfigManager.getTtsSpeed(), 0.25, 4.0);
        
        String json = String.format(
                "{\"model\":\"%s\",\"input\":\"%s\",\"voice\":\"%s\",\"speed\":%.2f,\"response_format\":\"%s\"}",
                escapeJson(model), escapeJson(text), escapeJson(voice), speed, provider.responseFormat
        );

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));

        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return builder.build();
    }

    private static HttpRequest buildMoonshotRequest(String text, TtsApiProviderEnum provider, String apiUrl, String apiKey) {
        String model = ConfigManager.getTtsApiModel();
        if (model == null || model.isBlank()) {
            model = "moonshot-tts-v1";
        }
        String voice = ConfigManager.getTtsApiVoice();
        if (voice == null || voice.isBlank()) {
            voice = "zh-CN-1";
        }
        double speed = clamp(ConfigManager.getTtsSpeed(), 0.25, 4.0);
        
        String json = String.format(
                "{\"model\":\"%s\",\"input\":\"%s\",\"voice\":\"%s\",\"speed\":%.2f,\"response_format\":\"%s\"}",
                escapeJson(model), escapeJson(text), escapeJson(voice), speed, provider.responseFormat
        );

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));

        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return builder.build();
    }

    private static HttpRequest buildByteDanceRequest(String text, TtsApiProviderEnum provider, String apiUrl, String apiKey) {
        // 火山引擎 V1 TTS API 格式: appid:token 用冒号分隔
        String[] parts = apiKey != null ? apiKey.split(":", 2) : new String[]{"", ""};
        String appId = parts.length > 0 ? parts[0] : "";
        String token = parts.length > 1 ? parts[1] : "";

        String voiceType = ConfigManager.getTtsApiVoice();
        if (voiceType == null || voiceType.isBlank()) voiceType = "zh_male_qingrun";

        String json = String.format(
                "{\"app\":{\"appid\":\"%s\",\"token\":\"\",\"cluster\":\"volcano_tts\"}," +
                "\"user\":{\"uid\":\"verity_mc\"}," +
                "\"audio\":{\"voice_type\":\"%s\",\"encoding\":\"wav\"}," +
                "\"request\":{\"text\":\"%s\",\"text_type\":\"plain\"}}",
                escapeJson(appId), escapeJson(voiceType), escapeJson(text)
        );

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));

        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer;" + token);
        }
        return builder.build();
    }

    private static HttpRequest buildQianwanRequest(String text, TtsApiProviderEnum provider, String apiUrl, String apiKey) {
        String model = ConfigManager.getTtsApiModel();
        String voice = ConfigManager.getTtsApiVoice();
        double speed = clamp(ConfigManager.getTtsSpeed(), 0.25, 4.0);
        
        String json = String.format(
                "{\"text\":\"%s\",\"voice\":\"%s\",\"model\":\"%s\",\"speed\":%.2f,\"format\":\"%s\"}",
                escapeJson(text), escapeJson(voice), escapeJson(model), speed, provider.responseFormat
        );

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));

        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        return builder.build();
    }

    private static HttpRequest buildMimoRequest(String text, TtsApiProviderEnum provider, String apiUrl, String apiKey, boolean useBuiltinTtsApi) {
        String model = ConfigManager.getTtsApiModel();
        if (model == null || model.isBlank()) {
            model = TtsApiProviderEnum.MIMO_TTS.defaultModel;
        }
        
        if (model.contains("voiceclone") || model.contains("voicedesign")) {
            OllamaProxyMod.LOGGER.warn("MiMo模型 {} 是语音克隆/设计模型，可能需要额外参数或无法正常工作。建议使用 mimo-v2.5-tts", model);
        }
        
        String voice = ConfigManager.getTtsApiVoice();
        if (voice == null || voice.isBlank()) {
            voice = TtsApiProviderEnum.MIMO_TTS.defaultVoice;
        }
        
        String mappedVoice = MimoDirectProtocol.normalizeVoice(voice);
        if (!mappedVoice.equals(voice)) {
            OllamaProxyMod.LOGGER.info("MiMo音色名称映射: {} -> {}", voice, mappedVoice);
            voice = mappedVoice;
        }
        
        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        JsonArray messages = new JsonArray();
        JsonObject userMessage = new JsonObject();
        userMessage.addProperty("role", "user");
        userMessage.addProperty("content", "Generate the following text to speech.");
        messages.add(userMessage);
        JsonObject assistantMessage = new JsonObject();
        assistantMessage.addProperty("role", "assistant");
        assistantMessage.addProperty("content", text);
        messages.add(assistantMessage);
        root.add("messages", messages);
        JsonObject audio = new JsonObject();
        audio.addProperty("voice", voice);
        audio.addProperty("format", provider.responseFormat);
        root.add("audio", audio);
        String json = GSON.toJson(root);

        OllamaProxyMod.LOGGER.debug("MiMo TTS请求体已构建: {} 字节", json.length());

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));

        if (apiKey != null && !apiKey.isBlank()) {
            if (useBuiltinTtsApi) {
                builder.header("Authorization", "Bearer " + apiKey);
            } else {
                builder.header("api-key", apiKey);
            }
        }
        return builder.build();
    }
    
    private static String cleanText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        String cleaned = text;
        cleaned = cleaned.replaceAll("\\p{C}", "");
        cleaned = cleaned.replaceAll("[\\uD83C\\uDF00-\\uD83D\\uDDFF\\uD83D\\uDE00-\\uD83D\\uDE4F\\uD83D\\uDE80-\\uD83D\\uDEFF\\uD83C\\uDDE6-\\uD83C\\uDDFF\\uD83C\\uDD80-\\uD83C\\uDD9F\\u2600-\\u26FF\\u2700-\\u27BF\\u2300-\\u23FF\\u2100-\\u21FF\\u2B00-\\u2BFF\\u2A00-\\u2AFF]", "");
        cleaned = cleaned.replaceAll("[^\\u4e00-\\u9fa5a-zA-Z0-9，。！？、；：\\\"'（）【】《》…—,.!?;:()\\[\\]<>\\-\\s]", "");
        cleaned = cleaned.replaceAll("\\s+", " ").trim();

        if (cleaned.length() > 2000) {
            cleaned = cleaned.substring(0, 2000);
        }

        if (!cleaned.equals(text)) {
            OllamaProxyMod.LOGGER.debug("TTS文本清洗完成，原长度: {}, 清洗后长度: {}", text.length(), cleaned.length());
        }

        return cleaned;
    }

    private static void playAudio(byte[] audioBytes, int entityId, long talkToken) throws Exception {
        if (audioBytes == null || audioBytes.length < 10) {
            OllamaProxyMod.LOGGER.warn("音频数据不足，无法播放");
            return;
        }
        OllamaProxyMod.LOGGER.info("准备播放音频，数据长度: {} 字节，前20字节HEX: {}", 
                audioBytes.length, hexDump(audioBytes, 20));

        ByteArrayInputStream bis = new ByteArrayInputStream(audioBytes);
        AudioInputStream ais = null;

        // Android/FCL 环境 javax.sound 不可靠，直接用 createPcmInputStream 手动解析
        if (RuntimePlatform.isAndroidRuntime()) {
            ais = createPcmInputStream(audioBytes);
        } else {
            try {
                ais = AudioSystem.getAudioInputStream(bis);
                OllamaProxyMod.LOGGER.info("AudioSystem成功解析音频格式: {}", ais.getFormat());
            } catch (Exception e) {
                OllamaProxyMod.LOGGER.warn("无法解析音频格式，尝试直接播放PCM数据");
                ais = createPcmInputStream(audioBytes);
            }
        }

        if (ais == null) {
            OllamaProxyMod.LOGGER.error("无法创建音频输入流，播放中止");
            return;
        }

        AudioFormat format = ais.getFormat();
        byte[] audioData = ais.readAllBytes();
        ais.close();

        // 桌面端优先交给游戏原版音频通道：跟随主音量滑块、与其它声音统一混音，
        // 音量走通道增益而不是改样本。
        // 🔴 手机端**保持走下面的 Android 专用路径**（MediaPlayer / playMinecraftOpenAl），
        // 不要改成走 VanillaAudioSink：那条路依赖原版 Channel 的流式播放，
        // 在 FCL / Pojav 上的兼容性没有实测过。手机端本来就能出声，
        // 贸然换路径只会把「音量不跟主音量」换成「可能完全没声音」，不划算。
        if (!RuntimePlatform.isAndroidRuntime()) {
            VerityEntityTalkAnimator.startPcmTimeline(
                    entityId,
                    talkToken,
                    audioData,
                    Math.round(format.getSampleRate()),
                    format.getFrameSize()
            );
            if (VanillaAudioSink.play(format, audioData, 0, audioData.length,
                    (float) ConfigManager.getTtsVolume(), entityId)) {
                return;
            }
        }

        double volume = ConfigManager.getTtsVolume();
        if (volume != 1.0 && format.getSampleSizeInBits() == 16) {
            float volFactor = (float) Math.max(0.0, Math.min(2.0, volume));
            for (int i = 0; i + 1 < audioData.length; i += 2) {
                short sample = (short) ((audioData[i + 1] << 8) | (audioData[i] & 0xFF));
                int amplified = Math.round(sample * volFactor);
                if (amplified > Short.MAX_VALUE) {
                    amplified = Short.MAX_VALUE;
                } else if (amplified < Short.MIN_VALUE) {
                    amplified = Short.MIN_VALUE;
                }
                audioData[i] = (byte) (amplified & 0xFF);
                audioData[i + 1] = (byte) ((amplified >> 8) & 0xFF);
            }
        }

        if (RuntimePlatform.isAndroidRuntime()) {
            normalizeMobilePcm(audioData);
            if (RuntimePlatform.hasAndroidMediaClasses()) {
                playAndroidAudio(toWavBytes(audioData, format), MOBILE_PLAYER_GAIN, audioData, format, entityId, talkToken);
            } else {
                playMinecraftOpenAl(audioData, format, entityId, talkToken);
            }
            return;
        }

        SourceDataLine line = null;
        try {
            line = AudioSystem.getSourceDataLine(format);
            CURRENT_LINE = line;
            line.open(format, 4096);
            line.start();
            VerityEntityTalkAnimator.startPcmTimeline(
                    entityId,
                    talkToken,
                    audioData,
                    Math.round(format.getSampleRate()),
                    format.getFrameSize()
            );

            int offset = 0;
            while (offset < audioData.length && !Thread.currentThread().isInterrupted()) {
                int toWrite = Math.min(4096, audioData.length - offset);
                line.write(audioData, offset, toWrite);
                offset += toWrite;
            }
            if (!Thread.currentThread().isInterrupted()) {
                line.drain();
            }
        } catch (IllegalArgumentException e) {
            if (RuntimePlatform.hasAndroidMediaClasses()) {
                OllamaProxyMod.LOGGER.warn("桌面 Java Sound 不可用，回退 Android MediaPlayer 播放: {}", e.getMessage());
                playAndroidAudio(audioBytes, MOBILE_PLAYER_GAIN, audioData, format, entityId, talkToken);
                return;
            }
            throw e;
        } finally {
            if (line != null) {
                try {
                    line.stop();
                    line.close();
                } catch (Exception ignored) { Silent.ignore("OnlineTtsService", ignored); }
                if (CURRENT_LINE == line) {
                    CURRENT_LINE = null;
                }
            }
        }
    }

    private static void applyPcmGain(byte[] audioData, float gain) {
        if (gain == 1.0F) {
            return;
        }
        for (int i = 0; i + 1 < audioData.length; i += 2) {
            short sample = (short) ((audioData[i + 1] << 8) | (audioData[i] & 0xFF));
            int amplified = Math.round(sample * gain);
            if (amplified > Short.MAX_VALUE) {
                amplified = Short.MAX_VALUE;
            } else if (amplified < Short.MIN_VALUE) {
                amplified = Short.MIN_VALUE;
            }
            audioData[i] = (byte) (amplified & 0xFF);
            audioData[i + 1] = (byte) ((amplified >> 8) & 0xFF);
        }
    }

    private static void normalizeMobilePcm(byte[] audioData) {
        long sumSquares = 0L;
        int peak = 0;
        int samples = 0;
        for (int i = 0; i + 1 < audioData.length; i += 2) {
            int sample = (short) ((audioData[i + 1] << 8) | (audioData[i] & 0xFF));
            int absolute = Math.abs(sample);
            peak = Math.max(peak, absolute);
            sumSquares += (long) sample * sample;
            samples++;
        }
        if (samples == 0 || peak == 0) return;
        double rms = Math.sqrt(sumSquares / (double) samples);
        float rmsGain = (float) (MOBILE_TARGET_RMS / Math.max(1.0, rms));
        float peakGain = 30000.0F / peak;
        float gain = Math.max(MOBILE_MIN_NORMALIZATION_GAIN,
                Math.min(MOBILE_MAX_NORMALIZATION_GAIN, Math.min(rmsGain, peakGain)));
        applyPcmGain(audioData, gain);
        OllamaProxyMod.LOGGER.info("Android/FCL TTS 已应用强制响度增益，增益={}x",
                String.format(java.util.Locale.ROOT, "%.2f", gain));
    }

    private static void playMinecraftOpenAl(
            byte[] audioData,
            AudioFormat format,
            int entityId,
            long talkToken
    ) throws Exception {
        int channels = format.getChannels();
        int bits = format.getSampleSizeInBits();
        if ((channels != 1 && channels != 2) || bits != 16) {
            throw new IllegalArgumentException("FCL OpenAL 仅支持 16-bit 单声道或双声道 PCM");
        }
        SoundEngineExecutor executor = ((SoundEngineAccessor) ((SoundManagerAccessor) Minecraft.getInstance().getSoundManager())
                .verityExtended$getSoundEngine()).verityExtended$getExecutor();
        java.util.concurrent.CompletableFuture<Integer> started = new java.util.concurrent.CompletableFuture<>();
        executor.execute(() -> {
            ByteBuffer pcm = null;
            int buffer = 0;
            int source = 0;
            try {
                stopMinecraftOpenAlOnSoundThread();
                pcm = MemoryUtil.memAlloc(audioData.length);
                pcm.put(audioData).flip();
                buffer = AL10.alGenBuffers();
                source = AL10.alGenSources();
                AL10.alBufferData(buffer, channels == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16,
                        pcm, Math.round(format.getSampleRate()));
                AL10.alSourcei(source, AL10.AL_BUFFER, buffer);
                AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
                AL10.alSource3f(source, AL10.AL_POSITION, 0.0F, 0.0F, 0.0F);
                AL10.alSourcef(source, AL10.AL_ROLLOFF_FACTOR, 0.0F);
                AL10.alSourcef(source, AL10.AL_GAIN, Math.max(0.0F, Math.min(2.0F, MOBILE_PLAYER_GAIN)));
                CURRENT_OPENAL_BUFFER = buffer;
                CURRENT_OPENAL_SOURCE = source;
                AL10.alSourcePlay(source);
                int error = AL10.alGetError();
                int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
                if (error != AL10.AL_NO_ERROR || state != AL10.AL_PLAYING) {
                    throw new IllegalStateException("OpenAL 播放启动失败: error=" + error + ", state=" + state);
                }
                started.complete(source);
            } catch (Throwable error) {
                if (source != 0) AL10.alDeleteSources(source);
                if (buffer != 0) AL10.alDeleteBuffers(buffer);
                if (CURRENT_OPENAL_SOURCE == source) {
                    CURRENT_OPENAL_SOURCE = 0;
                    CURRENT_OPENAL_BUFFER = 0;
                }
                started.completeExceptionally(error);
            } finally {
                if (pcm != null) MemoryUtil.memFree(pcm);
            }
        });
        int startedSource = started.get(5, TimeUnit.SECONDS);
        VerityEntityTalkAnimator.startPcmTimeline(
                entityId,
                talkToken,
                audioData,
                Math.round(format.getSampleRate()),
                format.getFrameSize()
        );
        long durationMillis = Math.max(250L, Math.round(audioData.length * 1000.0 / Math.max(1.0, format.getFrameRate() * format.getFrameSize())) + 500L);
        OllamaProxyMod.LOGGER.info("Android/FCL 使用 Minecraft OpenAL 声音线程播放在线 TTS");
        // 等播放结束，但**必须可被打断**：
        // 原来是一整段 Thread.sleep(最长 120s)，这一句还没念完，下一句就只能干等——
        // 表现为「说话卡住/延迟好几分钟」。改成每 50ms 检查一次会话代数，
        // 玩家发新消息（SESSION_GENERATION 递增）或 stopPlayback 中断时立刻退出。
        long sessionAtStart = SESSION_GENERATION;
        long deadline = System.currentTimeMillis() + Math.min(120000L, durationMillis);
        try {
            while (System.currentTimeMillis() < deadline && SESSION_GENERATION == sessionAtStart) {
                Thread.sleep(50L);
            }
        } catch (InterruptedException interrupted) {
            // 被 stopPlayback 打断是正常路径，恢复中断标记后继续走清理
            Thread.currentThread().interrupt();
        } finally {
            executor.execute(() -> {
                if (CURRENT_OPENAL_SOURCE == startedSource) stopMinecraftOpenAlOnSoundThread();
            });
        }
    }

    private static void stopMinecraftOpenAlOnSoundThread() {
        int source = CURRENT_OPENAL_SOURCE;
        int buffer = CURRENT_OPENAL_BUFFER;
        CURRENT_OPENAL_SOURCE = 0;
        CURRENT_OPENAL_BUFFER = 0;
        if (source != 0) {
            AL10.alSourceStop(source);
            AL10.alDeleteSources(source);
        }
        if (buffer != 0) AL10.alDeleteBuffers(buffer);
    }

    private static byte[] toWavBytes(byte[] pcmData, AudioFormat format) throws IOException {
        return com.hanchen.veritycustomapi.tts.AudioBytes.toWav(pcmData, format);
    }

    private static void writeAscii(ByteArrayOutputStream out, String value) {
        com.hanchen.veritycustomapi.tts.AudioBytes.writeAscii(out, value);
    }

    private static void writeIntLE(ByteArrayOutputStream out, int value) {
        com.hanchen.veritycustomapi.tts.AudioBytes.writeIntLE(out, value);
    }

    private static void writeShortLE(ByteArrayOutputStream out, int value) {
        com.hanchen.veritycustomapi.tts.AudioBytes.writeShortLE(out, value);
    }

    private static void playAndroidAudio(
            byte[] audioBytes,
            float playerGain,
            byte[] pcmData,
            AudioFormat format,
            int entityId,
            long talkToken
    ) throws Exception {
        Path tempFile = createAndroidAudioTempFile(audioBytes);
        OllamaProxyMod.LOGGER.info("Android/FCL 使用系统 MediaPlayer 播放在线 TTS: {}", tempFile);
        try {
            Class<?> mediaPlayerClass = Class.forName("android.media.MediaPlayer");
            Object player = mediaPlayerClass.getConstructor().newInstance();
            CURRENT_ANDROID_PLAYER = player;
            Method prepare = mediaPlayerClass.getMethod("prepare");
            Method start = mediaPlayerClass.getMethod("start");
            Method isPlaying = mediaPlayerClass.getMethod("isPlaying");
            Method release = mediaPlayerClass.getMethod("release");

            try {
                setAndroidMediaPlayerDataSource(mediaPlayerClass, player, tempFile);
                Method setVolume = null;
                try {
                    setVolume = mediaPlayerClass.getMethod("setVolume", float.class, float.class);
                } catch (NoSuchMethodException ignored) {
                }
                if (setVolume != null) {
                    float volume = Math.max(0.0F, Math.min(2.0F, playerGain));
                    setVolume.invoke(player, volume, volume);
                }
                prepare.invoke(player);
                start.invoke(player);
                if (pcmData != null && format != null) {
                    VerityEntityTalkAnimator.startPcmTimeline(
                            entityId,
                            talkToken,
                            pcmData,
                            Math.round(format.getSampleRate()),
                            format.getFrameSize()
                    );
                }
                long startedAt = System.currentTimeMillis();
                while (Boolean.TRUE.equals(isPlaying.invoke(player)) && System.currentTimeMillis() - startedAt < 120000L && !Thread.currentThread().isInterrupted()) {
                    Thread.sleep(100L);
                }
            } finally {
                try {
                    release.invoke(player);
                } catch (Exception ignored) { Silent.ignore("OnlineTtsService", ignored); }
                if (CURRENT_ANDROID_PLAYER == player) {
                    CURRENT_ANDROID_PLAYER = null;
                }
            }
        } finally {
            try {
                Files.deleteIfExists(tempFile);
            } catch (IOException e) {
                OllamaProxyMod.LOGGER.debug("清理 Android TTS 临时文件失败: {}", tempFile, e);
            }
        }
    }

    private static Path createAndroidAudioTempFile(byte[] audioBytes) throws IOException {
        Path tempDir = getAndroidWritableTempDir();
        Files.createDirectories(tempDir);
        Path tempFile = Files.createTempFile(tempDir, "verity-tts-", ".wav");
        Files.write(tempFile, audioBytes);
        return tempFile;
    }

    private static Path getAndroidWritableTempDir() {
        Path gameCache = ForgePaths.game("verity_extended", "cache", "tts");
        try {
            Files.createDirectories(gameCache);
            return gameCache;
        } catch (IOException e) {
            OllamaProxyMod.LOGGER.debug("Android/FCL 游戏目录缓存不可写，回退系统临时目录: {}", gameCache, e);
        }
        return Paths.get(System.getProperty("java.io.tmpdir", "."), "verity-tts");
    }

    private static void setAndroidMediaPlayerDataSource(Class<?> mediaPlayerClass, Object player, Path tempFile) throws Exception {
        File file = tempFile.toFile();
        try (FileInputStream input = new FileInputStream(file)) {
            Method setDataSourceFd = mediaPlayerClass.getMethod("setDataSource", java.io.FileDescriptor.class);
            setDataSourceFd.invoke(player, input.getFD());
            OllamaProxyMod.LOGGER.info("Android/FCL MediaPlayer 已通过 FileDescriptor 读取 TTS 音频");
            return;
        } catch (Throwable t) {
            OllamaProxyMod.LOGGER.warn("Android/FCL MediaPlayer FileDescriptor 播放路径失败，回退文件路径: {}", t.getMessage());
        }
        Method setDataSource = mediaPlayerClass.getMethod("setDataSource", String.class);
        setDataSource.invoke(player, file.getAbsolutePath());
    }

    private static AudioInputStream createPcmInputStream(byte[] rawData) {
        int sampleRate = 24000;
        int bitsPerSample = 16;
        int channels = 1;
        byte[] pcmData = rawData;
        
        if (rawData.length >= 44 && isWavHeader(rawData)) {
            sampleRate = (rawData[27] & 0xFF) << 24 | 
                        (rawData[26] & 0xFF) << 16 | 
                        (rawData[25] & 0xFF) << 8 | 
                        (rawData[24] & 0xFF);
            
            channels = (rawData[23] & 0xFF) << 8 | (rawData[22] & 0xFF);
            
            bitsPerSample = (rawData[35] & 0xFF) << 8 | (rawData[34] & 0xFF);
            
            int dataStart = findWavDataStart(rawData);
            
            if (dataStart < rawData.length) {
                pcmData = new byte[rawData.length - dataStart];
                System.arraycopy(rawData, dataStart, pcmData, 0, pcmData.length);
            }
            
            OllamaProxyMod.LOGGER.info("从WAV头解析参数: 采样率={}, 声道={}, 位深={}", sampleRate, channels, bitsPerSample);
        }
        
        int frameSize = (bitsPerSample / 8) * channels;
        AudioFormat format = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate,
                bitsPerSample,
                channels,
                frameSize,
                sampleRate,
                false
        );
        
        return new AudioInputStream(new ByteArrayInputStream(pcmData), format, pcmData.length / frameSize);
    }

    private static int findWavDataStart(byte[] rawData) {
        return com.hanchen.veritycustomapi.tts.AudioBytes.findWavDataStart(rawData);
    }

    private static boolean isWavHeader(byte[] data) {
        return com.hanchen.veritycustomapi.tts.AudioBytes.isWavHeader(data);
    }
    
    /** 已迁到 {@link com.hanchen.veritycustomapi.tts.AudioValidator#isValid}。 */
    private static boolean isValidAudioData(byte[] data, String format) {
        return com.hanchen.veritycustomapi.tts.AudioValidator.isValid(data, format);
    }
    
    private static boolean isMp3Header(byte[] data) {
        return com.hanchen.veritycustomapi.tts.AudioBytes.isMp3Header(data);
    }
    
    private static String hexDump(byte[] data, int length) {
        return com.hanchen.veritycustomapi.tts.AudioBytes.hexDump(data, length);
    }

    private static double clamp(double value, double min, double max) {
        return com.hanchen.veritycustomapi.tts.AudioBytes.clamp(value, min, max);
    }

    private static String escapeJson(String text) {
        return com.hanchen.veritycustomapi.tts.AudioBytes.escapeJson(text);
    }

    public static void shutdown() {
        SESSION_GENERATION++;
        IS_SHUTDOWN = true;
        PLAY_QUEUE.clear();
        stopCurrentPlaybackResource();
        Thread playerThread = PLAYER_THREAD;
        PLAYER_THREAD = null;
        if (playerThread != null) {
            playerThread.interrupt();
        }
        ExecutorService executor = TASK_EXECUTOR;
        TASK_EXECUTOR = null;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private static byte[] synthesizeMimoDirect(String text, String billingRequestId,
                                               ConfigManager.MimoRuntimeConfig config) {
        String cleanText = cleanText(text);
        if (cleanText.isEmpty()) return new byte[0];
        String voiceId = ConfigManager.getTtsApiVoice();
        if (voiceId == null || voiceId.isBlank()) {
            voiceId = "苏打";
        }
        voiceId = resolveMimoVoiceId(voiceId);
        voiceId = MimoDirectProtocol.normalizeVoice(voiceId);
        boolean isClone = ConfigManager.isMimoCloneVoice(voiceId) || ConfigManager.isSavedMimoVoiceClone(voiceId);
        int requiredCredits = isClone
                ? Math.max(ConfigManager.getMinDeductCost(), ConfigManager.getTtsCost(true))
                : ConfigManager.getTtsCost(false);
        if (requiredCredits > 0 && !ConfigManager.isCreditsSufficient(requiredCredits)) {
            OllamaProxyMod.LOGGER.warn("云端 TTS 请求已拒绝: 额度不足，clone={}", isClone);
            return new byte[0];
        }
        OllamaProxyMod.LOGGER.info("MiMo TTS 线路诊断: routeId={}, tier={}, model={}, voice={}, clone={}, keyLength={}",
                config.routeId(), config.tier(), config.model(), voiceId, isClone,
                config.apiKey() == null ? 0 : config.apiKey().length());
        byte[] audio = isClone
                ? synthesizeMimoCloneDirect(cleanText, voiceId, config.apiUrl(), config.apiKey())
                : synthesizeMimoBuiltinDirect(cleanText, voiceId, config.apiUrl(), config.apiKey(), config.model());
        if (audio == null || audio.length == 0) return new byte[0];
        BillingService.reportTts(billingRequestId, config.tier(), config.routeId(),
                UsageMeter.characters(cleanText), isClone);
        return audio;
    }

    /**
     * 下载并缓存克隆音色样本，返回本地缓存路径；已缓存就直接用。
     */
    private static Path ensureCloneVoiceSample(String voiceId) {
        try {
            Files.createDirectories(MIMO_VOICE_CACHE);
            Path cachedFile = MIMO_VOICE_CACHE.resolve(Integer.toHexString(voiceId.hashCode()) + ".wav");
            if (Files.exists(cachedFile) && Files.size(cachedFile) > 0) {
                byte[] cachedBytes = Files.readAllBytes(cachedFile);
                if (MimoDirectProtocol.audioMime(cachedBytes) != null) return cachedFile;
                Files.deleteIfExists(cachedFile);
            }

            com.hanchen.veritycustomapi.cloud.CloudConfig.MimoVoice[] voices = ConfigManager.getMimoVoices();
            String sampleUrl = CloudConfigFetcher.findCloneSampleUrl(voices, voiceId);
            if (sampleUrl == null) {
                OllamaProxyMod.LOGGER.info("克隆音色样本元数据缺失，重新拉取音色列表: voice={}", voiceId);
                voices = new CloudConfigFetcher().fetchMimoVoices(
                        ConfigManager.getCloudApiUrl(), ConfigManager.getMimoVoicesUrl(),
                        ConfigManager.getCloudApiKey(), ConfigManager.getCloudTimeoutSeconds());
                ConfigManager.setMimoVoices(voices);
                sampleUrl = CloudConfigFetcher.findCloneSampleUrl(voices, voiceId);
            }
            if (sampleUrl == null) {
                boolean voiceFound = false;
                for (com.hanchen.veritycustomapi.cloud.CloudConfig.MimoVoice voice : voices) {
                    if (voice != null && voiceId.equals(voice.id)) {
                        voiceFound = true;
                        break;
                    }
                }
                OllamaProxyMod.LOGGER.warn("云端音色列表未提供克隆样本 URL: voice={}, voiceFound={}", voiceId, voiceFound);
                return null;
            }

            URI cloudUri = NetworkSecurity.requireSecureUri(ConfigManager.getCloudApiUrl(), "云端配置地址");
            URI sampleUri = cloudUri.resolve(sampleUrl);
            if (!sameOrigin(cloudUri, sampleUri)) {
                OllamaProxyMod.LOGGER.warn("拒绝下载非云端同源的克隆音色样本: {}", NetworkSecurity.sanitizedEndpoint(sampleUri.toString()));
                return null;
            }
            OllamaProxyMod.LOGGER.info("下载克隆音色样本: endpoint={}", NetworkSecurity.sanitizedEndpoint(sampleUri.toString()));
            String apiKey = ConfigManager.getCloudApiKey();
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(sampleUri)
                    .timeout(Duration.ofSeconds(30))
                    .GET();
            if (apiKey != null && !apiKey.isBlank()) {
                builder.header("Authorization", "Bearer " + apiKey.trim());
            }
            HttpResponse<byte[]> response = sendHttpRequest(builder.build());
            if (response.statusCode() != 200) {
                OllamaProxyMod.LOGGER.warn("克隆音色样本下载失败: HTTP {}", response.statusCode());
                return null;
            }
            byte[] sampleBytes = response.body();
            String sampleMime = MimoDirectProtocol.audioMime(sampleBytes);
            if (sampleMime == null) {
                OllamaProxyMod.LOGGER.warn("克隆音色样本下载响应不是有效 WAV/MP3: voice={}, size={}",
                        voiceId, sampleBytes == null ? 0 : sampleBytes.length);
                return null;
            }
            Files.write(cachedFile, sampleBytes);
            OllamaProxyMod.LOGGER.info("克隆音色样本已缓存: {}, mime={}, 大小={} 字节",
                    cachedFile, sampleMime, sampleBytes.length);
            return cachedFile;
        } catch (Throwable t) {
            OllamaProxyMod.LOGGER.warn("克隆音色样本缓存失败: {}", t.getMessage());
            return null;
        }
    }

    private static boolean sameOrigin(URI first, URI second) {
        int firstPort = first.getPort() >= 0 ? first.getPort() : 443;
        int secondPort = second.getPort() >= 0 ? second.getPort() : 443;
        return "https".equalsIgnoreCase(second.getScheme())
                && first.getScheme().equalsIgnoreCase(second.getScheme())
                && first.getHost().equalsIgnoreCase(second.getHost())
                && firstPort == secondPort;
    }

    /**
     * 直连 MiMo API 合成内置音色 TTS，返回 WAV 字节数组。
     */
    private static byte[] synthesizeMimoBuiltinDirect(String text, String voiceId, String baseUrl, String apiKey,
                                                      String model) {
        try {
            JsonObject root = buildMimoTtsBody(text, voiceId,
                    model == null || model.isBlank() ? MIMO_DIRECT_TTS_MODEL : model, false);
            String json = GSON.toJson(root);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(MimoDirectProtocol.requireEndpoint(baseUrl))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("api-key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            return parseMimoAudioResponse(sendHttpRequest(request));
        } catch (Throwable t) {
            OllamaProxyMod.LOGGER.warn("MiMo 内置直连 TTS 异常: {}", t.getMessage());
            return null;
        }
    }

    /**
     * 直连 MiMo API 合成克隆音色 TTS，返回 WAV 字节数组。
     */
    private static byte[] synthesizeMimoCloneDirect(String text, String voiceId, String baseUrl, String apiKey) {
        try {
            // 有缓存直接用，没有就现下
            Path samplePath = ensureCloneVoiceSample(voiceId);
            if (samplePath == null) {
                OllamaProxyMod.LOGGER.warn("MiMo 克隆音色 TTS：无法获取样本文件，voice={}", voiceId);
                return null;
            }

            // 样本转成 Data URL 塞进请求体
            byte[] sampleBytes = Files.readAllBytes(samplePath);
            String mime = MimoDirectProtocol.audioMime(sampleBytes);
            if (mime == null) {
                OllamaProxyMod.LOGGER.warn("MiMo 克隆音色样本格式无效，voice={}", voiceId);
                return null;
            }
            String dataUrl = "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(sampleBytes);

            JsonObject root = buildMimoTtsBody(text, dataUrl, MIMO_DIRECT_CLONE_MODEL, true);
            String json = GSON.toJson(root);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(MimoDirectProtocol.requireEndpoint(baseUrl))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .header("api-key", apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            return parseMimoAudioResponse(sendHttpRequest(request));
        } catch (Throwable t) {
            OllamaProxyMod.LOGGER.warn("MiMo 克隆直连 TTS 异常: {}", t.getMessage());
            return null;
        }
    }

    /**
     * 构建 MiMo TTS 请求体。
     * @param isClone 克隆模式：仅 assistant 消息，无 user 消息
     */
    private static JsonObject buildMimoTtsBody(String text, String voiceValue, String model, boolean isClone) {
        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        JsonArray messages = new JsonArray();
        if (!isClone) {
            JsonObject userMsg = new JsonObject();
            userMsg.addProperty("role", "user");
            userMsg.addProperty("content", "");
            messages.add(userMsg);
        }
        JsonObject asstMsg = new JsonObject();
        asstMsg.addProperty("role", "assistant");
        asstMsg.addProperty("content", text);
        messages.add(asstMsg);
        root.add("messages", messages);
        JsonObject audio = new JsonObject();
        audio.addProperty("voice", voiceValue);
        audio.addProperty("format", "wav");
        root.add("audio", audio);
        return root;
    }

    /**
     * 解析 MiMo API 响应，提取 base64 编码的音频数据。
     */
    private static byte[] parseMimoAudioResponse(HttpResponse<byte[]> response) {
        try {
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            byte[] audio = MimoDirectProtocol.parseAudio(response.statusCode(), contentType, response.body());
            if (audio != null) OllamaProxyMod.LOGGER.info("MiMo 直连 TTS 成功，音频大小: {} 字节", audio.length);
            else OllamaProxyMod.LOGGER.warn("MiMo 直连 TTS 响应无有效 WAV 音频: HTTP {}, contentType={}, error={}",
                    response.statusCode(), safeContentType(contentType), safeMimoError(response.body()));
            return audio;
        } catch (Throwable t) {
            OllamaProxyMod.LOGGER.warn("MiMo 直连 TTS 响应解析异常: {}", t.getMessage());
            return null;
        }
    }

    private static String safeContentType(String value) {
        if (value == null || value.isBlank()) return "missing";
        int separator = value.indexOf(';');
        return separator >= 0 ? value.substring(0, separator).trim() : value.trim();
    }

    private static String safeMimoError(byte[] body) {
        if (body == null || body.length == 0) return "empty";
        try {
            JsonObject root = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject error = root.has("error") && root.get("error").isJsonObject()
                    ? root.getAsJsonObject("error") : root;
            String code = safeJsonString(error, "code");
            String type = safeJsonString(error, "type");
            String message = safeJsonString(error, "message")
                    .replaceAll("(?i)(api[_-]?key|authorization|token)[=: ]+[^,;\\s]+", "$1=****");
            if (message.length() > 160) message = message.substring(0, 160);
            return "code=" + code + ",type=" + type + ",message=" + message;
        } catch (RuntimeException ignored) {
            return "unparseable";
        }
    }

    private static String safeJsonString(JsonObject object, String name) {
        return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : "";
    }

    private static String resolveMimoVoiceId(String selected) {
        for (com.hanchen.veritycustomapi.cloud.CloudConfig.MimoVoice voice : ConfigManager.getMimoVoices()) {
            if (voice != null && voice.id != null && !voice.id.isBlank()
                    && (voice.id.equalsIgnoreCase(selected) || selected.equals(voice.name))) {
                return voice.id;
            }
        }
        return selected;
    }

}
