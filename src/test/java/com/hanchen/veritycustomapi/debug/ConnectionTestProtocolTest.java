package com.hanchen.veritycustomapi.debug;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

public final class ConnectionTestProtocolTest {
    private ConnectionTestProtocolTest() {
    }

    public static void main(String[] args) throws Exception {
        testProbeStatuses();
        testPayloadValidation();
        testBoundedBody();
        testUriAndAuthentication();
        testSafeErrors();
        testCancellationIsolation();
        testSelectedAndCallbackFailure();
        System.out.println("连接检测协议检查通过");
    }

    private static void testProbeStatuses() {
        for (int status : new int[]{200, 201, 204, 299, 400, 405, 406, 409, 415, 422}) {
            assertReachable(status, true);
            assertEquals(ConnectionTestHelper.State.WARNING, ConnectionTestHelper.optionsOutcome(status).state());
        }
        for (int status : new int[]{301, 401, 403, 404, 429, 500}) assertReachable(status, false);
        for (int status : new int[]{200, 204, 301, 400, 401, 403, 404, 405, 429, 500}) {
            assertNotPassed(ConnectionTestHelper.modelsOutcome(status, "{\"data\":[]}"));
            assertNotPassed(ConnectionTestHelper.optionsOutcome(status));
        }
        assertEquals(ConnectionTestHelper.State.WARNING, ConnectionTestHelper.modelsOutcome(200, "{\"data\":[]}").state());
        assertEquals(ConnectionTestHelper.State.WARNING, ConnectionTestHelper.optionsOutcome(401).state());
        assertContains(ConnectionTestHelper.optionsOutcome(401).detail(), "不能据此判断密钥");
        assertContains(ConnectionTestHelper.modelsOutcome(401, "").detail(), "不能据此判断当前接口权限");
        assertEquals(ConnectionTestHelper.State.FAILED, ConnectionTestHelper.optionsOutcome(429).state());
        assertContains(ConnectionTestHelper.optionsOutcome(429).detail(), "频率或额度限制");
        assertContains(ConnectionTestHelper.optionsOutcome(429).detail(), "不代表密钥失效");
        assertEquals(ConnectionTestHelper.State.WARNING, ConnectionTestHelper.modelsOutcome(429, "").state());
        assertContains(ConnectionTestHelper.optionsOutcome(302).detail(), "不会自动跟随重定向");
    }

    private static void testPayloadValidation() {
        assertEquals(true, ConnectionTestHelper.isModelsPayload("{\"object\":\"list\",\"data\":[]}"));
        assertEquals(true, ConnectionTestHelper.isModelsPayload("{\"data\":[{\"id\":\"example\"}]}"));
        for (String body : new String[]{"", "<html>ok</html>", "[]", "null", "{\"data\":{}}", "{\"data\":null}", "{}", "{bad}"}) {
            assertEquals(false, ConnectionTestHelper.isModelsPayload(body));
            assertNotPassed(ConnectionTestHelper.modelsOutcome(200, body));
        }
        assertEquals(false, ConnectionTestHelper.isModelsPayload(null));
        assertEquals(false, ConnectionTestHelper.isModelsPayload(" ".repeat(131073)));
    }

    private static void testBoundedBody() throws Exception {
        var subscriber = ConnectionTestHelper.limitedBody(4);
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        subscriber.onSubscribe(new java.util.concurrent.Flow.Subscription() {
            public void request(long n) { }
            public void cancel() { cancelled.set(true); }
        });
        subscriber.onNext(List.of(java.nio.ByteBuffer.wrap(new byte[]{1,2,3})));
        subscriber.onNext(List.of(java.nio.ByteBuffer.wrap(new byte[]{4,5})));
        assertEquals(true, cancelled.get());
        try { subscriber.getBody().toCompletableFuture().get(1,TimeUnit.SECONDS); throw new AssertionError("超限响应必须失败"); }
        catch(ExecutionException expected) { assertEquals(true, expected.getCause() instanceof java.io.IOException); }
        var normal = ConnectionTestHelper.limitedBody(4);
        normal.onSubscribe(new java.util.concurrent.Flow.Subscription() { public void request(long n) {} public void cancel() {} });
        normal.onNext(List.of(java.nio.ByteBuffer.wrap("test".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        normal.onComplete();
        assertEquals("test", normal.getBody().toCompletableFuture().get(1,TimeUnit.SECONDS));
        assertEquals(ConnectionTestHelper.State.WARNING, ConnectionTestHelper.optionsOutcome(404).state());
    }

    private static void testUriAndAuthentication() {
        URI source = URI.create("https://example.com/v1/audio/speech?api-version=2026-01-01");
        URI rebuilt = ConnectionTestHelper.rebuildUri(source, "/v1/models", source.getRawQuery());
        assertEquals("https://example.com/v1/models?api-version=2026-01-01", rebuilt.toString());
        URI appended = ConnectionTestHelper.appendQuery(source, "api_key", "a%2Bb");
        assertEquals("https://example.com/v1/audio/speech?api-version=2026-01-01&api_key=a%2Bb", appended.toString());
        assertEquals("http://127.0.0.1:8080/v1/models?api_key=test", ConnectionTestHelper.appendQuery(
                URI.create("http://127.0.0.1:8080/v1/models"), "api_key", "test").toString());
        assertEquals("https://example.com/v1/models?api-version=2026-01-01", ConnectionTestHelper.modelsUri(source).toString());
        assertEquals("https://example.com/v1/models", ConnectionTestHelper.modelsUri(
                URI.create("https://example.com/v1/chat/completions")).toString());
        assertEquals(null, ConnectionTestHelper.modelsUri(URI.create("https://example.com/custom/tts")));
        assertEquals(null, ConnectionTestHelper.modelsUri(URI.create("https://example.com/api/generate")));
        assertEquals(null, ConnectionTestHelper.modelsUri(URI.create("https://example.com/v1")));

        URI secret = URI.create("https://user:password@example.com:8443/private/path/key-secret?token=secret#fragment");
        assertEquals("https://example.com:8443", ConnectionTestHelper.displayEndpoint(secret.toString()));
        assertEquals("http://[::1]:8080", ConnectionTestHelper.displayEndpoint("http://[::1]:8080/private?token=secret"));
        assertEquals("地址格式无效", ConnectionTestHelper.displayEndpoint("secret invalid endpoint"));
        for (String auth : new String[]{"bearer", "api-key", "param", "custom"}) {
            HttpRequest request = ConnectionTestHelper.request(secret, "X-Secret: secret", auth, true);
            assertEquals("OPTIONS", request.method());
            assertEquals("https://example.com:8443/private/path/key-secret", request.uri().toString());
            assertEquals(Set.of("User-Agent"), request.headers().map().keySet());
        }
        HttpRequest query = ConnectionTestHelper.request(source, "a+b", "param", false);
        assertEquals("GET", query.method());
        assertContains(query.uri().toString(), "api_key=a%2Bb");
        assertEquals("Bearer secret", ConnectionTestHelper.request(source, "secret", "bearer", false)
                .headers().firstValue("Authorization").orElseThrow());
    }

    private static void testSafeErrors() {
        String secret = "https://user:password@example.com/private/key-secret?api_key=secret&token=secret";
        for (Throwable error : List.of(new IllegalArgumentException(secret), new RuntimeException(secret),
                new RuntimeException(new java.net.ConnectException(secret)),
                new RuntimeException(new java.net.UnknownHostException(secret)),
                new java.net.http.HttpTimeoutException(secret))) {
            String detail = ConnectionTestHelper.safeError(error);
            for (String forbidden : List.of("password", "example.com", "key-secret", "api_key", "token=", "https://")) {
                if (detail.contains(forbidden)) throw new AssertionError("错误文案泄漏敏感信息");
            }
        }
        assertContains(ConnectionTestHelper.safeError(new InterruptedException(secret)), "取消");
    }

    private static void testCancellationIsolation() throws Exception {
        List<ConnectionTestHelper.TestResult> oldUpdates = new CopyOnWriteArrayList<>();
        List<ConnectionTestHelper.TestResult> newUpdates = new CopyOnWriteArrayList<>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(1);
        CompletableFuture<Void> old = ConnectionTestHelper.startRound(Set.of("chat"), oldUpdates::add, id -> () -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException error) {
                interrupted.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("旧任务未被释放");
            } finally {
                exited.countDown();
            }
            return result(id);
        });
        try {
            await(entered, "子任务未启动");
            CompletableFuture<Void> busy = ConnectionTestHelper.startRound(Set.of("tts"), newUpdates::add,
                    id -> { throw new AssertionError("并发轮次不应读取配置或启动任务"); });
            assertFailed(busy);
            assertEquals(true, old.cancel(true));
            assertEquals(true, old.isCancelled());
            await(interrupted, "取消未传递到真实子任务");
            int oldCount = oldUpdates.size();
            CompletableFuture<Void> next = ConnectionTestHelper.startRound(Set.of("tts"), newUpdates::add, id -> () -> result(id));
            next.get(5, TimeUnit.SECONDS);
            release.countDown();
            await(exited, "旧任务未结束");
            assertEquals(oldCount, oldUpdates.size());
            assertEquals(1, oldCount);
            assertEquals(2, newUpdates.size());
            assertEquals("tts", newUpdates.get(1).id());
            assertEquals(ConnectionTestHelper.State.WARNING, newUpdates.get(1).state());
            assertContains(newUpdates.get(1).detail(), "耗时");
        } finally {
            release.countDown();
            old.cancel(true);
        }
    }

    private static void testSelectedAndCallbackFailure() throws Exception {
        List<ConnectionTestHelper.TestResult> updates = new CopyOnWriteArrayList<>();
        ConnectionTestHelper.startRound(Set.of("stt"), updates::add, id -> () -> result(id)).get(5, TimeUnit.SECONDS);
        assertEquals(2, updates.size());
        for (ConnectionTestHelper.TestResult value : updates) assertEquals("stt", value.id());
        CompletableFuture<Void> broken = ConnectionTestHelper.startRound(Set.of("chat"), value -> {
            throw new IllegalStateException("不应透出此消息");
        }, id -> () -> result(id));
        assertFailed(broken);
        ConnectionTestHelper.startRound(Set.of("tts"), value -> { }, id -> () -> result(id)).get(5, TimeUnit.SECONDS);
        ConnectionTestHelper.startRound(Set.of(), value -> { throw new AssertionError("空选择不应回调"); },
                id -> { throw new AssertionError("空选择不应创建任务"); }).get(5, TimeUnit.SECONDS);
    }

    private static ConnectionTestHelper.TestResult result(String id) {
        return new ConnectionTestHelper.TestResult(id, id, ConnectionTestHelper.State.WARNING, "测试", "", "未验证实际功能");
    }

    private static void assertFailed(CompletableFuture<Void> future) throws Exception {
        try {
            future.get(5, TimeUnit.SECONDS);
            throw new AssertionError("预期异常完成");
        } catch (ExecutionException expected) {
            if (!(expected.getCause() instanceof IllegalStateException)) throw new AssertionError("异常分类不正确", expected);
        }
    }

    private static void await(CountDownLatch latch, String message) throws InterruptedException {
        if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError(message);
    }

    private static void assertNotPassed(ConnectionTestHelper.ProbeOutcome outcome) {
        if (outcome.state() == ConnectionTestHelper.State.PASSED) throw new AssertionError("弱探针不能证明实际功能通过");
    }

    private static void assertReachable(int status, boolean expected) {
        assertEquals(expected, ConnectionTestHelper.isReachableProbeStatus(status));
    }

    private static void assertContains(String actual, String expected) {
        if (!actual.contains(expected)) throw new AssertionError("文案缺少预期说明：" + expected);
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("预期=" + expected + "，实际=" + actual);
        }
    }
}
