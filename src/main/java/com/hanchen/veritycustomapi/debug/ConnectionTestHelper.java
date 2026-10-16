package com.hanchen.veritycustomapi.debug;

import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.NetworkSecurity;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.stt.VoskRuntimeSupport;

import java.io.File;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class ConnectionTestHelper {
    public enum State { WAITING, RUNNING, PASSED, FAILED, SKIPPED }

    public record TestResult(String id, String name, State state, String mode, String endpoint, String detail) {
    }

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "Verity-Config-Health");
        thread.setDaemon(true);
        return thread;
    });
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private ConnectionTestHelper() {
    }

    public static List<TestResult> initialResults() {
        return List.of(
                new TestResult("chat", "对话服务", State.WAITING, "等待检查", "", ""),
                new TestResult("stt", "语音输入", State.WAITING, "等待检查", "", ""),
                new TestResult("tts", "语音输出", State.WAITING, "等待检查", "", "")
        );
    }

    public static CompletableFuture<Void> testAll(Consumer<TestResult> update) {
        return CompletableFuture.allOf(
                run("chat", "对话服务", update, ConnectionTestHelper::testChat),
                run("stt", "语音输入", update, ConnectionTestHelper::testStt),
                run("tts", "语音输出", update, ConnectionTestHelper::testTts)
        );
    }

    public static void testAll() {
        testAll(result -> OllamaProxyMod.LOGGER.info("[ConnTest] {}: {}", result.name(), result.state()));
    }

    private static CompletableFuture<Void> run(String id, String name, Consumer<TestResult> update,
                                                java.util.concurrent.Callable<TestResult> test) {
        update.accept(new TestResult(id, name, State.RUNNING, "检查中", "", "正在检查设置和服务连接"));
        return CompletableFuture.supplyAsync(() -> {
            try {
                return test.call();
            } catch (Throwable error) {
                return new TestResult(id, name, State.FAILED, "检查失败", "", safeError(error));
            }
        }, EXECUTOR).thenAccept(update);
    }

    private static TestResult testChat() {
        ConfigManager.ApiHealthTarget target = ConfigManager.getChatHealthTarget();
        if (isCloudSource(target)) {
            return cloudResult("chat", "对话服务", target);
        }
        String url = target.apiUrl();
        String model = target.model();
        if (blank(url)) return failed("chat", "对话服务", "在线服务", "", "服务地址未填写");
        if (blank(model)) return failed("chat", "对话服务", "在线服务", url, "模型名称未填写");
        return probe("chat", "对话服务", target.source() + " · " + model, url, target.apiKey(), target.authType());
    }

    private static TestResult testStt() {
        if (!ConfigManager.isEnableVoiceInput()) {
            return skipped("stt", "语音输入", "未启用", "语音输入未启用");
        }
        if (ConfigManager.getRecognitionMode() == ConfigManager.RecognitionMode.LOCAL) {
            String issue = VoskRuntimeSupport.getUnavailableReason();
            boolean modelReady = VoskRuntimeSupport.hasUsableModel(Paths.get(ConfigManager.getVoskModelPath()));
            if (issue != null) return failed("stt", "语音输入", "本地语音识别", "", issue);
            if (!modelReady) return failed("stt", "语音输入", "本地语音识别", "", "本地语音识别模型未安装，或位置无效");
            return passed("stt", "语音输入", "本地语音识别", "", "本地语音识别已准备就绪");
        }
        ConfigManager.ApiHealthTarget target = ConfigManager.getSttHealthTarget();
        if (isCloudSource(target)) {
            return cloudResult("stt", "语音输入", target);
        }
        String url = target.apiUrl();
        String model = target.model();
        if (blank(url)) return failed("stt", "语音输入", "在线服务", "", "服务地址未填写");
        if (blank(model)) return failed("stt", "语音输入", "在线服务", url, "模型名称未填写");
        return probe("stt", "语音输入", target.source() + " · " + model, url, target.apiKey(), target.authType());
    }

    private static TestResult testTts() {
        if (!ConfigManager.isEnableTts()) {
            return skipped("tts", "语音输出", "未启用", "语音输出未启用");
        }
        if (ConfigManager.getTtsMode() == ConfigManager.TtsMode.LOCAL) {
            File model = new File(ConfigManager.getActualTtsModelPath());
            if (!model.isFile()) return failed("tts", "语音输出", "本地语音合成", "", "本地语音合成模型文件不存在");
            try {
                Class<?> type = Class.forName("ai.onnxruntime.OrtEnvironment");
                type.getMethod("getEnvironment").invoke(null);
                return passed("tts", "语音输出", "本地语音合成", "", "本地语音合成已准备就绪");
            } catch (Throwable error) {
                return failed("tts", "语音输出", "本地语音合成", "", "本地语音合成组件不可用：" + safeError(error));
            }
        }
        ConfigManager.ApiHealthTarget target = ConfigManager.getTtsHealthTarget();
        if (isCloudSource(target)) {
            return cloudResult("tts", "语音输出", target);
        }
        String url = target.apiUrl();
        String model = target.model();
        if (blank(url)) return failed("tts", "语音输出", "在线服务", "", "服务地址未填写");
        if (blank(model)) return failed("tts", "语音输出", "在线服务", url, "模型名称未填写");
        return probe("tts", "语音输出", target.source() + " · " + model, url, target.apiKey(), target.authType());
    }

    private static TestResult probe(String id, String name, String mode, String endpoint, String key, String authType) {
        URI actual = NetworkSecurity.requireSecureUri(endpoint, name + "服务地址");
        if (blank(key) && !NetworkSecurity.isLoopbackHost(actual.getHost())) {
            return failed(id, name, mode, endpoint, "访问密钥未填写");
        }
        if ("chat".equals(id)) actual = chatUri(actual);
        URI models = modelsUri(actual);
        try {
            HttpResponse<Void> response = CLIENT.send(request(models, key, authType, false), HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return passed(id, name, mode, endpoint, "服务连接正常，访问权限有效");
            }
            if (status == 401 || status == 403 || status == 429 || status >= 500) {
                return httpFailure(id, name, mode, endpoint, status);
            }
            HttpResponse<Void> fallback = CLIENT.send(request(actual, key, authType, true), HttpResponse.BodyHandlers.discarding());
            int fallbackStatus = fallback.statusCode();
            if (fallbackStatus == 401 || fallbackStatus == 403) return failed(id, name, mode, endpoint, "访问密钥无效，或没有使用权限");
            if (fallbackStatus == 404) return failed(id, name, mode, endpoint, "服务地址无效，请检查地址是否正确");
            if (fallbackStatus == 429) return failed(id, name, mode, endpoint, "请求过于频繁或额度不足，请稍后重试");
            if (fallbackStatus >= 500) return failed(id, name, mode, endpoint, "服务暂时不可用，请稍后重试");
            if (fallbackStatus >= 300 && fallbackStatus < 400) {
                return failed(id, name, mode, endpoint, "服务返回重定向，请填写重定向后的最终地址");
            }
            if (isReachableProbeStatus(fallbackStatus)) {
                return passed(id, name, mode, endpoint, "服务可达且地址有效；未发送真实内容，不会产生用量");
            }
            return failed(id, name, mode, endpoint, "服务拒绝连通性探针，HTTP " + fallbackStatus);
        } catch (Exception error) {
            return failed(id, name, mode, endpoint, safeError(error));
        }
    }

    private static TestResult httpFailure(String id, String name, String mode, String endpoint, int status) {
        if (status == 401 || status == 403) return failed(id, name, mode, endpoint, "访问密钥无效，或没有使用权限");
        if (status == 404) return failed(id, name, mode, endpoint, "服务地址无效，请检查地址是否正确");
        if (status == 429) return failed(id, name, mode, endpoint, "请求过于频繁或额度不足，请稍后重试");
        if (status >= 500) return failed(id, name, mode, endpoint, "服务暂时不可用，请稍后重试");
        return failed(id, name, mode, endpoint, "服务没有接受这次检查");
    }

    private static HttpRequest request(URI uri, String key, String authType, boolean options) {
        String auth = authType == null ? "bearer" : authType.toLowerCase(Locale.ROOT);
        if (!blank(key) && auth.contains("param")) {
            uri = appendQuery(uri, "api_key", URLEncoder.encode(key.trim(), StandardCharsets.UTF_8));
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .header("User-Agent", "VerityExtended/3.2-ConfigCheck");
        if (!blank(key)) {
            if (auth.contains("param")) {
            } else if (auth.contains("api-key") || auth.contains("api_key")) builder.header("api-key", key.trim());
            else if (auth.contains("custom") && key.contains(":")) {
                String[] parts = key.split(":", 2);
                builder.header(parts[0].trim(), parts[1].trim());
            } else builder.header("Authorization", "Bearer " + key.trim());
        }
        return options ? builder.method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build() : builder.GET().build();
    }

    private static URI modelsUri(URI endpoint) {
        String path = endpoint.getPath() == null ? "" : endpoint.getPath();
        path = path.replaceFirst("(?i)/(chat/completions|audio/transcriptions|audio/speech)/?$", "");
        if (!path.endsWith("/")) path += "/";
        path += "models";
        return rebuildUri(endpoint, path, endpoint.getRawQuery());
    }

    private static URI chatUri(URI endpoint) {
        String path = endpoint.getPath() == null ? "" : endpoint.getPath();
        if (!path.toLowerCase(Locale.ROOT).endsWith("/chat/completions")) {
            if (!path.endsWith("/")) path += "/";
            path += "chat/completions";
        }
        return rebuildUri(endpoint, path, endpoint.getRawQuery());
    }

    static boolean isReachableProbeStatus(int status) {
        return status >= 200 && status < 300
                || status == 400
                || status == 405
                || status == 406
                || status == 409
                || status == 415
                || status == 422;
    }

    static URI appendQuery(URI uri, String name, String value) {
        String query = uri.getRawQuery();
        String appended = (query == null || query.isBlank() ? "" : query + "&") + name + "=" + value;
        return rebuildUri(uri, uri.getRawPath(), appended);
    }

    static URI rebuildUri(URI source, String path, String query) {
        String suffix = query == null || query.isBlank() ? "" : "?" + query;
        return URI.create(source.getScheme() + "://" + source.getRawAuthority() + path + suffix);
    }

    private static String safeError(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        if (cause instanceof HttpTimeoutException) return "连接超时，请检查网络、代理或服务状态";
        if (cause instanceof UnknownHostException) return "无法找到服务地址，请检查地址是否正确或网络是否正常";
        if (cause instanceof ConnectException) return "无法连接服务，请检查地址、端口或服务是否已启动";
        if (cause instanceof javax.net.ssl.SSLException) return "安全证书验证失败，请检查服务地址和证书设置";
        if (cause instanceof IllegalArgumentException) return trim(cause.getMessage());
        return "连接服务失败：" + trim(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
    }

    private static String trim(String value) {
        String clean = value == null ? "未知错误" : value.replaceAll("(?i)(api[_-]?key|authorization|token)=[^&\\s]+", "$1=****")
                .replaceAll("[\\r\\n\\t]+", " ").trim();
        return clean.length() > 220 ? clean.substring(0, 220) + "..." : clean;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isCloudSource(ConfigManager.ApiHealthTarget target) {
        return target.source() != null && target.source().startsWith("云端");
    }

    private static TestResult cloudResult(String id, String name, ConfigManager.ApiHealthTarget target) {
        boolean builtin = "云端内置".equals(target.source());
        if (!ConfigManager.isCloudEnabled()) {
            return failed(id, name, target.source(), "", "云端服务未启用");
        }
        if (blank(ConfigManager.getCloudApiKey())) {
            return failed(id, name, target.source(), "", "云端服务尚未授权，请先完成账号授权");
        }
        if (blank(target.apiUrl()) || blank(target.model())) {
            return failed(id, name, target.source(), builtin ? "" : target.apiUrl(),
                    "云端线路尚未同步完成，或线路连接信息不完整");
        }
        TestResult result = probe(id, name, target.source() + " · " + target.model(), target.apiUrl(),
                target.apiKey(), target.authType());
        return builtin
                ? new TestResult(result.id(), result.name(), result.state(), target.source(), "", result.detail())
                : result;
    }

    private static TestResult passed(String id, String name, String mode, String endpoint, String detail) {
        return new TestResult(id, name, State.PASSED, mode, displayEndpoint(endpoint), detail);
    }

    private static TestResult failed(String id, String name, String mode, String endpoint, String detail) {
        return new TestResult(id, name, State.FAILED, mode, displayEndpoint(endpoint), detail);
    }

    private static TestResult skipped(String id, String name, String mode, String detail) {
        return new TestResult(id, name, State.SKIPPED, mode, "", detail);
    }

    private static String displayEndpoint(String endpoint) {
        return blank(endpoint) ? "" : NetworkSecurity.sanitizedEndpoint(endpoint);
    }
}
