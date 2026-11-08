package com.hanchen.veritycustomapi.debug;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;

public final class ConnectionTestHelper {
    public enum State { WAITING, RUNNING, PASSED, WARNING, FAILED, SKIPPED, CANCELLED }

    public record TestResult(String id, String name, State state, String mode, String endpoint, String detail) {
    }

    private static final List<String> IDS = List.of("chat", "stt", "tts");
    private static final Object RUN_LOCK = new Object();
    private static Round activeRound;
    private static final ThreadLocal<Round> RUN_CONTEXT = new ThreadLocal<>();
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(3, runnable -> {
        Thread thread = new Thread(runnable, "Verity-Config-Health");
        thread.setDaemon(true);
        return thread;
    });
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private record Check(String id, String name, boolean enabled, boolean local, String modelPath,
                         ConfigManager.ApiHealthTarget target, boolean cloudEnabled, boolean cloudAuthorized) {
    }

    record ProbeOutcome(State state, String detail) {
    }

    private static final class Round {
        private final Consumer<TestResult> update;
        private final List<Future<?>> children = new ArrayList<>();
        private final List<CompletableFuture<?>> requests = new ArrayList<>();
        private int remaining;
        private final CompletableFuture<Void> result = new CompletableFuture<>() {
            @Override
            public boolean cancel(boolean mayInterruptIfRunning) {
                synchronized (RUN_LOCK) {
                    if (isDone()) return false;
                    stop();
                    return super.cancel(mayInterruptIfRunning);
                }
            }
        };

        private Round(Consumer<TestResult> update, int remaining) {
            this.update = update;
            this.remaining = remaining;
        }

        private boolean current() {
            return activeRound == this && !result.isDone();
        }

        private void stop() {
            if (activeRound == this) activeRound = null;
            for (CompletableFuture<?> request : List.copyOf(requests)) request.cancel(true);
            for (Future<?> child : children) child.cancel(true);
        }

        private void emit(TestResult value) {
            synchronized (RUN_LOCK) {
                if (!current()) return;
                try {
                    update.accept(value);
                } catch (RuntimeException error) {
                    stop();
                    result.completeExceptionally(new IllegalStateException("检查结果接收失败"));
                }
            }
        }

        private void finish() {
            synchronized (RUN_LOCK) {
                if (--remaining == 0 && current()) {
                    activeRound = null;
                    result.complete(null);
                }
            }
        }
    }

    private ConnectionTestHelper() {
    }

    public static List<TestResult> initialResults() {
        return IDS.stream().map(id -> new TestResult(id, name(id), State.WAITING, "等待检查", "", "")).toList();
    }

    public static CompletableFuture<Void> testAll(Consumer<TestResult> update) {
        return testSelected(Set.copyOf(IDS), update);
    }

    public static CompletableFuture<Void> testSelected(Set<String> ids, Consumer<TestResult> update) {
        return startRound(ids, update, id -> {
            Check snapshot = snapshot(id);
            return () -> test(snapshot);
        });
    }

    public static void testAll() {
        testAll(result -> OllamaProxyMod.LOGGER.info("[ConnTest] {}: {}", result.name(), result.state()));
    }

    // 同一入口也用于纯本地测试，测试工厂不会读取配置或访问网络。
    static CompletableFuture<Void> startRound(Set<String> ids, Consumer<TestResult> update,
                                               Function<String, Callable<TestResult>> factory) {
        Objects.requireNonNull(ids, "检测项目不能为空");
        Objects.requireNonNull(update, "结果接收器不能为空");
        Set<String> selected = Set.copyOf(ids);
        if (!IDS.containsAll(selected)) throw new IllegalArgumentException("检测项目无效");
        if (selected.isEmpty()) return CompletableFuture.completedFuture(null);
        synchronized (RUN_LOCK) {
            if (activeRound != null) {
                return CompletableFuture.failedFuture(new IllegalStateException("已有连接检查正在进行，请先取消或等待完成"));
            }
            Round round = new Round(update, selected.size());
            activeRound = round;
            for (String id : IDS) {
                if (!selected.contains(id)) continue;
                Callable<TestResult> check;
                try {
                    check = factory.apply(id);
                } catch (Exception | LinkageError error) {
                    check = () -> failed(id, name(id), "设置检查", "", safeError(error));
                }
                Callable<TestResult> captured = check;
                round.children.add(new FutureTask<Void>(() -> {
                    long started = System.nanoTime();
                    try {
                        synchronized (RUN_LOCK) {
                            if (!round.current()) return null;
                        }
                        RUN_CONTEXT.set(round);
                        TestResult value = captured.call();
                        round.emit(withElapsed(value, started));
                    } catch (InterruptedException | CancellationException error) {
                        Thread.currentThread().interrupt();
                    } catch (Exception | LinkageError error) {
                        round.emit(withElapsed(failed(id, name(id), "检查失败", "", safeError(error)), started));
                    } finally {
                        RUN_CONTEXT.remove();
                        round.finish();
                    }
                    return null;
                }));
            }
            for (String id : IDS) {
                if (selected.contains(id)) {
                    round.emit(new TestResult(id, name(id), State.RUNNING, "检查中", "", "正在检查设置和基础连接，不发送真实模型请求"));
                }
            }
            if (round.current()) {
                for (Future<?> child : round.children) EXECUTOR.execute((Runnable) child);
            }
            return round.result;
        }
    }

    private static Check snapshot(String id) {
        String name = name(id);
        boolean enabled = switch (id) {
            case "stt" -> ConfigManager.isEnableVoiceInput();
            case "tts" -> ConfigManager.isEnableTts();
            default -> true;
        };
        if (!enabled) return new Check(id, name, false, false, "", null, false, false);
        boolean local = "stt".equals(id) && ConfigManager.getRecognitionMode() == ConfigManager.RecognitionMode.LOCAL
                || "tts".equals(id) && ConfigManager.getTtsMode() == ConfigManager.TtsMode.LOCAL;
        if (local) {
            String path = "stt".equals(id) ? ConfigManager.getVoskModelPath() : ConfigManager.getActualTtsModelPath();
            return new Check(id, name, true, true, path, null, false, false);
        }
        ConfigManager.ApiHealthTarget target = switch (id) {
            case "stt" -> ConfigManager.getSttHealthTarget();
            case "tts" -> ConfigManager.getTtsHealthTarget();
            default -> ConfigManager.getChatHealthTarget();
        };
        boolean cloud = isCloudSource(target);
        return new Check(id, name, true, false, "", target,
                !cloud || ConfigManager.isCloudEnabled(), !cloud || !blank(ConfigManager.getCloudApiKey()));
    }

    private static TestResult test(Check check) throws Exception {
        String id = check.id();
        String name = check.name();
        if (!check.enabled()) return skipped(id, name, "未启用", name + "未启用");
        if (check.local()) return testLocal(check);
        ConfigManager.ApiHealthTarget target = check.target();
        boolean builtin = "云端内置".equals(target.source());
        String mode = builtin ? target.source() : target.source() + " · " + target.model();
        String endpoint = builtin ? "" : target.apiUrl();
        if (isCloudSource(target)) {
            if (!check.cloudEnabled()) return failed(id, name, target.source(), "", "云端服务未启用");
            if (!check.cloudAuthorized()) return failed(id, name, target.source(), "", "云端服务尚未授权，请先完成账号授权");
            if (blank(target.apiUrl()) || blank(target.model())) {
                return failed(id, name, target.source(), endpoint, "云端线路尚未同步完成，或线路连接信息不完整");
            }
        }
        if (blank(target.apiUrl())) return failed(id, name, mode, "", "服务地址未填写");
        if (blank(target.model())) return failed(id, name, mode, endpoint, "模型名称未填写");
        return probe(id, name, mode, endpoint, target);
    }

    private static TestResult testLocal(Check check) throws Exception {
        String id = check.id();
        String name = check.name();
        if ("stt".equals(id)) {
            if (VoskRuntimeSupport.getUnavailableReason() != null) {
                return failed(id, name, "本地语音识别", "", "本地语音识别组件不可用，请检查组件安装和运行环境");
            }
            if (!VoskRuntimeSupport.hasUsableModel(Paths.get(check.modelPath()))) {
                return failed(id, name, "本地语音识别", "", "本地语音识别模型未安装，或位置无效");
            }
            return passed(id, name, "本地语音识别", "", "本地模型文件与组件已就绪；未测试麦克风、录音或实际识别");
        }
        if (!new File(check.modelPath()).isFile()) {
            return failed(id, name, "本地语音合成", "", "本地语音合成模型文件不存在");
        }
        try {
            Class<?> type = Class.forName("ai.onnxruntime.OrtEnvironment");
            type.getMethod("getEnvironment").invoke(null);
            return passed(id, name, "本地语音合成", "", "本地模型文件与组件已就绪；未测试语音合成、扬声器或播放");
        } catch (ReflectiveOperationException | LinkageError error) {
            return failed(id, name, "本地语音合成", "", "本地语音合成组件不可用，请检查组件安装和运行环境");
        }
    }

    private static TestResult probe(String id, String name, String mode, String endpoint,
                                    ConfigManager.ApiHealthTarget target) throws Exception {
        URI actual = NetworkSecurity.requireSecureUri(target.apiUrl(), name + "服务地址");
        if (blank(target.apiKey()) && !NetworkSecurity.isLoopbackHost(actual.getHost())) {
            return failed(id, name, mode, endpoint, "访问密钥未填写");
        }
        Round round;
        synchronized (RUN_LOCK) {
            round = RUN_CONTEXT.get();
            if (round == null || !round.current() || Thread.currentThread().isInterrupted()) throw new InterruptedException();
        }
        URI models = modelsUri(actual);
        String prefix = "";
        if (models != null) {
            HttpResponse<String> response = send(round, request(models, target.apiKey(), target.authType(), false),
                    info -> limitedBody(131072));
            ProbeOutcome outcome = modelsOutcome(response.statusCode(), response.body());
            if (response.statusCode() == 200 && isModelsPayload(response.body())) {
                return outcome(id, name, mode, endpoint, outcome);
            }
            prefix = outcome.detail() + "；";
        }
        HttpResponse<Void> response = send(round, request(actual, "", "", true), HttpResponse.BodyHandlers.discarding());
        ProbeOutcome outcome = optionsOutcome(response.statusCode());
        return outcome(id, name, mode, endpoint, new ProbeOutcome(outcome.state(), prefix + outcome.detail()));
    }

    private static <T> HttpResponse<T> send(Round round, HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws Exception {
        CompletableFuture<HttpResponse<T>> pending;
        synchronized (RUN_LOCK) {
            if (!round.current() || Thread.currentThread().isInterrupted()) throw new InterruptedException();
            pending = CLIENT.sendAsync(request, handler);
            round.requests.add(pending);
        }
        try {
            return pending.get(12, TimeUnit.SECONDS);
        } catch (TimeoutException error) {
            pending.cancel(true);
            throw new HttpTimeoutException("基础连接检查超时");
        } catch (InterruptedException error) {
            pending.cancel(true);
            throw error;
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Exception exception) throw exception;
            throw new IllegalStateException("连接检查失败");
        } finally {
            synchronized (RUN_LOCK) {
                round.requests.remove(pending);
            }
        }
    }

    /** Enforce the response cap while streaming, rather than after allocating an arbitrary body. */
    static HttpResponse.BodySubscriber<String> limitedBody(int maxBytes) {
        return new HttpResponse.BodySubscriber<>() {
            private final CompletableFuture<String> body = new CompletableFuture<>();
            private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            private java.util.concurrent.Flow.Subscription subscription;
            @Override public java.util.concurrent.CompletionStage<String> getBody() { return body; }
            @Override public void onSubscribe(java.util.concurrent.Flow.Subscription next) {
                if (subscription != null) { next.cancel(); return; }
                subscription = next; next.request(1);
            }
            @Override public void onNext(List<java.nio.ByteBuffer> chunks) {
                if (body.isDone()) return;
                for (java.nio.ByteBuffer chunk : chunks) {
                    if (chunk.remaining() > maxBytes - bytes.size()) {
                        subscription.cancel();
                        body.completeExceptionally(new java.io.IOException("检测响应过大")); return;
                    }
                    byte[] data = new byte[chunk.remaining()]; chunk.get(data); bytes.write(data, 0, data.length);
                }
                subscription.request(1);
            }
            @Override public void onError(Throwable error) { body.completeExceptionally(error); }
            @Override public void onComplete() { body.complete(bytes.toString(StandardCharsets.UTF_8)); }
        };
    }

    static ProbeOutcome modelsOutcome(int status, String body) {
        if (status == 200 && isModelsPayload(body)) {
            return new ProbeOutcome(State.WARNING, "连接结果：已取得服务的模型列表。\n功能测试：未执行，暂不能确认对话、识别或朗读是否可用。");
        }
        if (status == 401 || status == 403) {
            return new ProbeOutcome(State.WARNING, "模型列表接口拒绝访问（HTTP " + status + "），不能据此判断当前接口权限");
        }
        if (status == 429) {
            return new ProbeOutcome(State.WARNING, "模型列表接口受到频率或额度限制（HTTP 429），尚不能判断当前接口状态");
        }
        return new ProbeOutcome(State.WARNING, status == 200
                ? "模型列表接口未返回预期的 JSON 对象和 data 数组"
                : "模型列表接口未完成基础检查（HTTP " + status + "）");
    }

    static ProbeOutcome optionsOutcome(int status) {
        if (isReachableProbeStatus(status)) {
            return new ProbeOutcome(State.WARNING, "服务响应无认证预检（HTTP " + status + "），仅说明基础可达；未验证密钥、模型推理或语音功能");
        }
        if (status == 401 || status == 403) {
            return new ProbeOutcome(State.WARNING, "无认证预检被拒绝（HTTP " + status + "）；未提供凭据，不能据此判断密钥或当前接口使用权限");
        }
        if (status == 404) return new ProbeOutcome(State.WARNING, "服务未提供此预检方式（HTTP 404）。请核对地址；仅凭预检不能判断实际服务是否可用。");
        if (status == 429) return new ProbeOutcome(State.FAILED, "服务受到频率或额度限制（HTTP 429），请稍后重试；不代表密钥失效");
        if (status >= 500) return new ProbeOutcome(State.FAILED, "服务暂时不可用（HTTP " + status + "），请稍后重试");
        if (status >= 300 && status < 400) {
            return new ProbeOutcome(State.FAILED, "服务返回重定向，请填写最终服务地址；检查不会自动跟随重定向");
        }
        return new ProbeOutcome(State.WARNING, "服务未接受无认证预检（HTTP " + status + "）；未验证实际功能");
    }

    static boolean isModelsPayload(String body) {
        if (body == null || body.length() > 131072) return false;
        try {
            JsonElement parsed = JsonParser.parseString(body);
            return parsed.isJsonObject() && parsed.getAsJsonObject().has("data")
                    && parsed.getAsJsonObject().get("data").isJsonArray();
        } catch (RuntimeException error) {
            return false;
        }
    }

    static HttpRequest request(URI uri, String key, String authType, boolean options) {
        String auth = authType == null ? "bearer" : authType.toLowerCase(Locale.ROOT);
        // 预检完全不携带认证，包括地址原有的查询参数及 userinfo。
        uri = rebuildUri(uri, uri.getRawPath(), options ? null : uri.getRawQuery());
        if (!options && !blank(key) && auth.contains("param")) {
            uri = appendQuery(uri, "api_key", URLEncoder.encode(key.trim(), StandardCharsets.UTF_8));
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .header("User-Agent", "VerityExtended/ConfigCheck");
        if (!options && !blank(key) && !auth.contains("param")) {
            if (auth.contains("api-key") || auth.contains("api_key")) builder.header("api-key", key.trim());
            else if (auth.contains("custom") && key.contains(":")) {
                String[] parts = key.split(":", 2);
                builder.header(parts[0].trim(), parts[1].trim());
            } else builder.header("Authorization", "Bearer " + key.trim());
        }
        return options ? builder.method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build() : builder.GET().build();
    }

    static URI modelsUri(URI endpoint) {
        String path = endpoint.getRawPath() == null ? "" : endpoint.getRawPath();
        if (path.matches("(?i).*/models/?$")) return rebuildUri(endpoint, path, endpoint.getRawQuery());
        if (path.matches("(?i).*/(chat/completions|audio/transcriptions|audio/speech)/?$")) {
            path = path.replaceFirst("(?i)/(chat/completions|audio/transcriptions|audio/speech)/?$", "/models");
        } else {
            return null;
        }
        return rebuildUri(endpoint, path, endpoint.getRawQuery());
    }

    static boolean isReachableProbeStatus(int status) {
        return status >= 200 && status < 300 || status == 400 || status == 405 || status == 406
                || status == 409 || status == 415 || status == 422;
    }

    static URI appendQuery(URI uri, String name, String value) {
        String query = uri.getRawQuery();
        String appended = (query == null || query.isBlank() ? "" : query + "&") + name + "=" + value;
        return rebuildUri(uri, uri.getRawPath(), appended);
    }

    static URI rebuildUri(URI source, String path, String query) {
        String suffix = query == null || query.isBlank() ? "" : "?" + query;
        String host = source.getHost();
        if (host.contains(":")) host = host.startsWith("[") ? host : "[" + host + "]";
        String port = source.getPort() >= 0 ? ":" + source.getPort() : "";
        return URI.create(source.getScheme() + "://" + host + port + (path == null ? "" : path) + suffix);
    }

    static String safeError(Throwable error) {
        Throwable cause = error;
        for (int depth = 0; depth < 16 && cause.getCause() != null && cause.getCause() != cause; depth++) {
            cause = cause.getCause();
        }
        if (cause instanceof HttpTimeoutException) return "连接超时，请检查网络、代理或服务状态";
        if (cause instanceof UnknownHostException) return "无法找到服务地址，请检查地址或网络";
        if (cause instanceof ConnectException) return "无法连接服务，请检查地址、端口或服务是否已启动";
        if (cause instanceof javax.net.ssl.SSLException) return "安全证书验证失败，请检查服务地址和证书设置";
        if (cause instanceof InterruptedException || cause instanceof CancellationException) return "检查已取消";
        if (cause instanceof IllegalArgumentException) return "服务地址或请求设置无效；仅允许 HTTPS 或现有安全规则支持的回环 HTTP 地址";
        return "检查未完成，请检查服务配置、网络及本地组件；详细异常不会显示以保护凭据";
    }

    private static TestResult withElapsed(TestResult value, long started) {
        long elapsed = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
        return new TestResult(value.id(), value.name(), value.state(), value.mode(), value.endpoint(),
                value.detail() + " · 耗时 " + elapsed + " 毫秒");
    }

    private static String name(String id) {
        return switch (id) {
            case "chat" -> "对话服务";
            case "stt" -> "语音输入";
            case "tts" -> "语音输出";
            default -> throw new IllegalArgumentException("检测项目无效");
        };
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean isCloudSource(ConfigManager.ApiHealthTarget target) {
        return target.source() != null && target.source().startsWith("云端");
    }

    private static TestResult outcome(String id, String name, String mode, String endpoint, ProbeOutcome outcome) {
        return new TestResult(id, name, outcome.state(), mode, displayEndpoint(endpoint), outcome.detail());
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

    static String displayEndpoint(String endpoint) {
        if (blank(endpoint)) return "";
        try {
            URI uri = URI.create(endpoint.trim());
            if (uri.getScheme() == null || uri.getHost() == null) return "地址格式无效";
            return rebuildUri(uri, "", null).toString();
        } catch (RuntimeException error) {
            return "地址格式无效";
        }
    }
}
