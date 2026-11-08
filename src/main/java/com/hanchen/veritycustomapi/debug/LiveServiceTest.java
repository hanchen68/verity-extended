package com.hanchen.veritycustomapi.debug;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.memory.ChatCompletionClient;
import com.hanchen.veritycustomapi.TtsApiProviderEnum;
import com.hanchen.veritycustomapi.tts.AudioPlayer;
import com.hanchen.veritycustomapi.tts.AudioValidator;
import com.hanchen.veritycustomapi.tts.OfflineTtsService;
import com.hanchen.veritycustomapi.tts.OnlineTtsService;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * User-initiated live tests. These reuse the production call paths (including quota reservation and
 * settlement) instead of probing a second, unbilled route.
 *
 * <p>Nothing here runs on its own: every request is started by an explicit click after the player
 * confirms that it may consume quota. Tests never invent a "usable" result from an HTTP status.
 */
public final class LiveServiceTest {
    public static final String DISCLAIMER = "真实测试会发送一次很小的请求，可能消耗少量额度。";
    private static final Gson GSON = new Gson();
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "Verity-Live-Test");
        thread.setDaemon(true);
        return thread;
    });
    private static final String CHAT_PROMPT = "这是连接测试。请只回复四个字：连接正常。";

    private LiveServiceTest() {
    }

    /** Runs one live check on the network in a background thread. */
    public static CompletableFuture<ConnectionTestHelper.TestResult> runChat(Consumer<String> progress) {
        return run("chat", "对话服务", progress, LiveServiceTest::chat);
    }

    public static CompletableFuture<ConnectionTestHelper.TestResult> runTts(Consumer<String> progress) {
        return run("tts", "语音输出", progress, LiveServiceTest::tts);
    }

    /**
     * Records a few seconds from the real microphone and runs it through the production recognizer.
     * The player has to actually speak, so an empty result is reported as "no speech detected"
     * rather than as a service failure.
     */
    public static CompletableFuture<ConnectionTestHelper.TestResult> runStt(
            Consumer<String> progress, int seconds) {
        progress.accept("请对着麦克风说话，正在录音…");
        return CompletableFuture.supplyAsync(() -> {
            com.hanchen.veritycustomapi.stt.VoiceRecognitionService service = null;
            long started = System.nanoTime();
            try {
                service = new com.hanchen.veritycustomapi.stt.VoiceRecognitionService();
                if (!service.isReady()) {
                    String reason = service.getUnavailableReason();
                    return failed("stt", "语音输入", reason == null ? "语音识别模块未就绪" : reason);
                }
                if (!service.startListening()) {
                    return failed("stt", "语音输入", "无法打开麦克风，请检查系统录音权限与输入设备");
                }
                progress.accept("正在录音，请说一句话…");
                try {
                    Thread.sleep(Math.max(1, seconds) * 1000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                String text = service.stopListening();
                if (text == null || text.isBlank()) {
                    return new ConnectionTestHelper.TestResult("stt", "语音输入",
                            ConnectionTestHelper.State.WARNING, "真实录音", "",
                            "录音已完成，但没有识别到文字。可能是没说话，或麦克风没有拾音；"
                                    + "请确认系统输入设备后再说一次。");
                }
                return new ConnectionTestHelper.TestResult("stt", "语音输入", ConnectionTestHelper.State.PASSED,
                        "真实录音", "", "已识别：" + com.hanchen.veritycustomapi.memory.ChatCompletionClient
                        .sanitizeOutput(text, 120) + "，用时 " + elapsed(started));
            } catch (Throwable error) {
                try {
                    if (service != null && service.isRecording()) service.stopListening();
                } catch (Throwable ignored) {
                    // best effort: the test already failed
                }
                return failed("stt", "语音输入", describe(error));
            }
        }, EXECUTOR);
    }

    private static CompletableFuture<ConnectionTestHelper.TestResult> run(
            String id, String name, Consumer<String> progress,
            java.util.concurrent.Callable<ConnectionTestHelper.TestResult> test) {
        progress.accept("正在发送真实测试请求…");
        return CompletableFuture.supplyAsync(() -> {
            try {
                return test.call();
            } catch (Throwable error) {
                return new ConnectionTestHelper.TestResult(id, name, ConnectionTestHelper.State.FAILED,
                        "真实测试", "", describe(error));
            }
        }, EXECUTOR);
    }

    /** Sends one minimal completion through the normal route and billing path. */
    static ConnectionTestHelper.TestResult chat() {
        List<ConfigManager.ChatRoute> routes = ConfigManager.snapshotChatRoutes();
        if (routes.isEmpty()) return failed("chat", "对话服务", "没有可用的对话线路");
        ConfigManager.ChatRoute route = routes.get(0);
        if (route.model() == null || route.model().isBlank()) {
            return failed("chat", "对话服务", "模型名称未填写");
        }
        String requestBody;
        String responseBody;
        long started = System.nanoTime();
        try {
            requestBody = GSON.toJson(minimalRequest(route.model()));
            // 计费由云端负责：这里只发一次最小请求判断「能不能用」，不做预授权/结算/上报，
            // 也不再用本地余额拦测（否则云端可用时本地仍会拒绝测试）。
            responseBody = ChatCompletionClient.complete(route, requestBody, 30000);
        } catch (Exception error) {
            return failed("chat", "对话服务", describe(error));
        }
        String text = ChatCompletionClient.sanitizeOutput(ChatCompletionClient.extractText(responseBody), 200);
        if (text.isBlank()) {
            return failed("chat", "对话服务", "服务返回了结果，但没有可读的回复内容");
        }
        return new ConnectionTestHelper.TestResult("chat", "对话服务", ConnectionTestHelper.State.PASSED,
                source(route), "", "模型已成功回复，用时 " + elapsed(started) + "；回答内容：" + text);
    }

    /** Synthesizes a short sentence and plays it once through the normal TTS path. */
    static ConnectionTestHelper.TestResult tts() {
        long started = System.nanoTime();
        if (ConfigManager.getTtsMode() == ConfigManager.TtsMode.LOCAL) {
            if (!OfflineTtsService.isInitialized()) {
                return failed("tts", "语音输出", "离线朗读尚未初始化，请稍后再试");
            }
            float[] pcm = OfflineTtsService.synthesize("连接测试");
            if (pcm.length == 0) return failed("tts", "语音输出", "离线合成没有产生音频");
            AudioPlayer.playAsync(pcm);
            return new ConnectionTestHelper.TestResult("tts", "语音输出", ConnectionTestHelper.State.WARNING,
                    "本机离线朗读", "", "已合成并播放 " + pcm.length + " 个采样，用时 " + elapsed(started)
                    + "；是否听到声音需要你确认");
        }
        byte[] audio;
        try {
            audio = OnlineTtsService.synthesize("连接测试");
        } catch (Exception error) {
            return failed("tts", "语音输出", describe(error));
        }
        if (audio == null || audio.length == 0) return failed("tts", "语音输出", "服务没有返回音频");
        TtsApiProviderEnum provider = ConfigManager.getTtsApiProvider();
        String format = provider == null ? null : provider.responseFormat;
        if (!AudioValidator.isValid(audio, format)) {
            return failed("tts", "语音输出", "返回内容不是有效音频，可能被服务端换成了错误页面");
        }
        // Reuse the bytes we already paid for; speakAsync would synthesize a second time.
        boolean queued = OnlineTtsService.playSynthesized(audio, "连接测试");
        if (!queued) return failed("tts", "语音输出", "音频已生成，但播放队列已满，未能播放");
        return new ConnectionTestHelper.TestResult("tts", "语音输出", ConnectionTestHelper.State.WARNING,
                "在线朗读", "", "已生成 " + audio.length + " 字节音频，用时 " + elapsed(started)
                + "；已交给游戏播放，是否听到声音需要你确认");
    }

    static JsonObject minimalRequest(String model) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", 24);
        body.addProperty("stream", false);
        com.google.gson.JsonArray messages = new com.google.gson.JsonArray();
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", CHAT_PROMPT);
        messages.add(message);
        body.add("messages", messages);
        return body;
    }

    private static ConnectionTestHelper.TestResult failed(String id, String name, String detail) {
        return new ConnectionTestHelper.TestResult(id, name, ConnectionTestHelper.State.FAILED,
                "真实测试", "", detail);
    }

    private static String describe(Throwable error) {
        Throwable cause = error;
        for (int depth = 0; depth < 12 && cause.getCause() != null && cause.getCause() != cause; depth++) {
            cause = cause.getCause();
        }
        if (cause instanceof java.net.http.HttpTimeoutException) return "真实请求超时，服务没有在限定时间内回复";
        if (cause instanceof java.net.UnknownHostException) return "找不到服务地址，请检查网络或服务地址";
        if (cause instanceof java.net.ConnectException) return "连接不上服务，请检查地址、端口或服务是否可用";
        if (cause instanceof javax.net.ssl.SSLException) return "安全证书校验失败，请检查服务地址与证书";
        return "真实请求未成功；为避免泄露凭据，不显示服务端原始错误。请检查设置后重试。";
    }

    private static String source(ConfigManager.ChatRoute route) {
        return switch (route.source()) {
            case "builtin" -> "平台内置对话";
            case "cloud" -> "云端自定义对话";
            default -> "本机自定义对话";
        };
    }

    private static String elapsed(long started) {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L) + " 毫秒";
    }

    public static Set<String> supported() {
        return Set.of("chat", "stt", "tts");
    }
}
