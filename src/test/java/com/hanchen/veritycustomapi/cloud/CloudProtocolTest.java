package com.hanchen.veritycustomapi.cloud;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.List;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;

public final class CloudProtocolTest {
    public static void main(String[] args) {
        // 版本号变更的「绊线」：改 build.gradle / FALLBACK_VERSION 时这里会失败，
        // 提醒同步确认云端协议（mod_version 上报）也要一起发布。
        require("4.5.3".equals(CloudConfigFetcher.modVersion()), "cloud request mod version is stale");
        CloudConfig billingConfig = new Gson().fromJson("{\"code\":0,\"billing\":{"
                + "\"protocol_version\":2,\"pre_authorization_required_for_paid_chat\":true,"
                + "\"reserve_url\":\"/api/v1/credits/reserve\"},\"data\":{\"version\":1}}", CloudConfig.class);
        require(billingConfig.billing.protocolVersion == 2
                        && Boolean.TRUE.equals(billingConfig.billing.preAuthorizationRequiredForPaidChat),
                "billing protocol v2 was not parsed");
        require("https://verity.xrstudio.cn/api/v1/credits/reserve".equals(
                        CloudBillingClient.resolveEndpoint("https://verity.xrstudio.cn/api/v1/getconfig",
                                "/api/v1/credits/reserve", "/credits/reserve")),
                "relative billing endpoint was resolved incorrectly");
        requireThrows(() -> CloudBillingClient.resolveEndpoint("https://verity.xrstudio.cn/api/v1/getconfig",
                "https://evil.example/credits/deduct", "/credits/deduct"),
                "cross-origin billing endpoint was accepted");
        require(BillingService.fingerprint("account-a").equals(BillingService.fingerprint("account-a"))
                        && !BillingService.fingerprint("account-a").equals(BillingService.fingerprint("account-b")),
                "billing account fingerprint is unstable");
        requireThrows(() -> BillingService.requireRequestId("short"), "short request id accepted");
        require("request-12345678".equals(BillingService.requireRequestId("request-12345678")),
                "valid request id rejected");
        require(BillingService.pendingDelay(1) == 30000L && BillingService.pendingDelay(10) == 300000L,
                "pending billing retry delay is invalid");
        RouteDto free = route("free", "free:0", 2);
        RouteDto paidHigh = route("paid", "paid:1", 9);
        RouteDto paidLow = route("paid", "paid:0", 1);
        RouteSelector selector = new RouteSelector();
        RoutePolicy policy = RoutePolicy.defaults();

        List<RouteDto> freeRoutes = selector.select("chat", "free", "auto",
                List.of(paidHigh, free, paidLow), policy, null, 0L);
        require(freeRoutes.size() == 1 && "free:0".equals(freeRoutes.get(0).routeId), "free tier leaked");

        List<RouteDto> paidRoutes = selector.select("chat", "paid", "paid:1",
                List.of(free, paidHigh, paidLow), policy, null, 0L);
        require(paidRoutes.size() == 2 && "paid:1".equals(paidRoutes.get(0).routeId), "specified route lost");
        require(paidRoutes.stream().noneMatch(route -> "free".equals(route.tier)), "paid tier leaked");

        RouteHealthRegistry health = new RouteHealthRegistry();
        for (int i = 0; i < policy.threshold(); i++) {
            health.reportFailure("chat", "paid", "paid:1", RouteHealthRegistry.FailureKind.SERVER, 0L, policy);
        }
        require(health.state("chat", "paid", "paid:1", 0L, policy) == RouteHealthRegistry.State.COOLDOWN,
                "route did not cool down");
        int failures = health.failureCount("chat", "paid", "paid:1");
        health.reportFailure("chat", "paid", "paid:1", RouteHealthRegistry.FailureKind.AUTHENTICATION, 0L, policy);
        require(health.failureCount("chat", "paid", "paid:1") == failures, "authentication changed health");
        require(selector.select("stt", "free", "auto", List.of(paidHigh, free), policy, null, 0L).size() == 1,
                "STT tier leaked");
        require(selector.select("tts", "paid", "auto", List.of(free, paidLow), policy, null, 0L).size() == 1,
                "TTS tier leaked");
        RouteDto disabled = route("paid", "paid:disabled", 0);
        disabled.enabled = false;
        RouteDto unspecified = route("paid", "paid:unspecified", 0);
        unspecified.enabled = null;
        require(selector.select("tts", "paid", "auto", List.of(disabled, unspecified), policy, null, 0L).isEmpty(),
                "disabled or unspecified route was selected");
        require(selector.select("tts", "paid", "paid:missing", List.of(paidHigh, paidLow), policy, null, 0L).isEmpty(),
                "missing specified route silently failed over");
        require("paid:0".equals(selector.selectFirst("tts", "paid", "auto",
                List.of(paidHigh, paidLow), policy, null, 0L).routeId),
                "small_first priority order was ignored");
        policy.priorityDirection = "large_first";
        require("paid:1".equals(selector.selectFirst("tts", "paid", "auto",
                List.of(paidLow, paidHigh), policy, null, 0L).routeId),
                "large_first priority order was ignored");
        policy.priorityDirection = "small_first";
        long probeAt = policy.healthIntervalMillis();
        require(selector.select("chat", "paid", "auto", List.of(paidHigh), policy, health, probeAt).size() == 1,
                "cooled route was not exposed for probing");
        require(health.tryAcquire("chat", "paid", "paid:1", probeAt, policy), "probe was not acquired");
        require(!health.tryAcquire("chat", "paid", "paid:1", probeAt, policy), "probe was acquired twice");

        String protocolJson = "{\"chatTier\":\"free\",\"chat\":{\"free\":{\"apiUrl\":\"https://legacy.example/v1\"},"
                + "\"routes\":[{\"tier\":\"free\",\"route_id\":\"free:1\",\"auto_failover\":true,"
                + "\"failover_after_errors\":2,\"health_check_interval_seconds\":30}]}}";
        BuiltinApiConfig parsed = new Gson().fromJson(protocolJson, BuiltinApiConfig.class);
        require(parsed.chat.free != null && parsed.chat.routes.size() == 1, "legacy or routes protocol was not parsed");
        RouteDto parsedRoute = parsed.chat.routes.get(0);
        require(Boolean.TRUE.equals(parsedRoute.autoFailover) && parsedRoute.failoverAfterErrors == 2
                && parsedRoute.healthCheckIntervalSeconds == 30, "snake_case route policy was not parsed");

        RouteDto snakeCaseRoute = new Gson().fromJson(
                "{\"tier\":\"paid\",\"route_id\":\"route_snake\","
                        + "\"api_provider\":\"CUSTOM_OPENAI\","
                        + "\"api_url\":\"https://api.example.test/v1\","
                        + "\"api_key\":\"route-key\",\"api_model\":\"model-x\","
                        + "\"request_format\":\"multipart\",\"auth_type\":\"bearer\","
                        + "\"response_field\":\"text\"}", RouteDto.class);
        require("route_snake".equals(snakeCaseRoute.routeId)
                        && "CUSTOM_OPENAI".equals(snakeCaseRoute.apiProvider)
                        && "https://api.example.test/v1".equals(snakeCaseRoute.apiUrl)
                        && "route-key".equals(snakeCaseRoute.apiKey)
                        && "multipart".equals(snakeCaseRoute.requestFormat)
                        && "bearer".equals(snakeCaseRoute.authType)
                        && "text".equals(snakeCaseRoute.responseField),
                "snake_case route connection fields were not parsed");

        require(UsageMeter.characters("A😀B") == 4, "TTS usage is not UTF-16 length");

        JsonObject usage = new JsonObject();
        usage.addProperty("characters", 4);
        CloudBillingClient.DeductRequest request = new CloudBillingClient.DeductRequest(
                "tts", "paid", "paid:0", "request-1", usage, false, "client-1");
        JsonObject json = new Gson().toJsonTree(request).getAsJsonObject();
        require(json.has("route_id") && json.has("request_id") && json.has("client_id"), "billing fields missing");

        Gson gson = new Gson();
        require(gson.fromJson("{\"is_public\":0}", CloudSkin.class).isPublic == 0, "skin 0 flag failed");
        require(gson.fromJson("{\"is_public\":1}", CloudSkin.class).isPublic == 1, "skin 1 flag failed");
        require(gson.fromJson("{\"is_public\":true}", CloudSkin.class).isPublic == 1, "skin true flag failed");
        require(gson.fromJson("{\"is_public\":false}", CloudSkin.class).isPublic == 0, "skin false flag failed");
        require(gson.fromJson("{\"is_public\":null}", CloudSkin.class).isPublic == 0, "skin null flag failed");

        CloudConfig.MimoVoice voice = gson.fromJson("{\"is_owner\":true,\"is_global\":null}", CloudConfig.MimoVoice.class);
        require(Integer.valueOf(1).equals(voice.isOwner), "voice boolean flag failed");
        require(Integer.valueOf(0).equals(voice.isGlobal), "voice null flag failed");
        CloudConfig.MimoVoice[] staleVoices = gson.fromJson(
                "[{\"id\":\"laomushi\",\"type\":\"clone\"}]", CloudConfig.MimoVoice[].class);
        CloudConfig.MimoVoice[] refreshedVoices = gson.fromJson(
                "[{\"id\":\"laomushi\",\"type\":\"clone\","
                        + "\"sample_url\":\"https://verity.xrstudio.cn/api/v1/mimo/clone-samples/laomushi\"}]",
                CloudConfig.MimoVoice[].class);
        require(CloudConfigFetcher.findCloneSampleUrl(staleVoices, "laomushi") == null,
                "stale clone metadata unexpectedly had a sample URL");
        require("https://verity.xrstudio.cn/api/v1/mimo/clone-samples/laomushi".equals(
                        CloudConfigFetcher.findCloneSampleUrl(refreshedVoices, "laomushi")),
                "refreshed clone sample URL was not recovered");
        require(CloudConfigFetcher.findCloneSampleUrl(refreshedVoices, "missing") == null,
                "sample URL leaked across voice IDs");

        UsageMeter.ChatUsage realUsage = UsageMeter.chat("{}",
                "{\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":7,\"total_tokens\":19}}", "", false);
        require(realUsage != null && realUsage.inputTokens() == 12 && realUsage.outputTokens() == 7
                && realUsage.totalTokens() == 19, "real Chat usage not parsed");
        UsageMeter.ChatUsage inconsistentUsage = UsageMeter.chat("{}",
                "{\"usage\":{\"prompt_tokens\":6457,\"completion_tokens\":10,\"total_tokens\":6468}}", "", false);
        require(inconsistentUsage != null && inconsistentUsage.inputTokens() == 6457
                && inconsistentUsage.outputTokens() == 10 && inconsistentUsage.totalTokens() == 6467,
                "inconsistent upstream Chat total was not normalized");
        UsageMeter.ChatUsage overflowUsage = UsageMeter.chat("{}",
                "{\"usage\":{\"input_tokens\":2147483647,\"output_tokens\":1,\"total_tokens\":1}}", "", false);
        require(overflowUsage != null && overflowUsage.totalTokens() == Integer.MAX_VALUE,
                "Chat total token overflow was not clamped");
        require(UsageMeter.chat("{\"messages\":[{\"content\":\"hello\"}]}", "{}", "world", false) == null,
                "paid Chat usage was estimated");
        require(UsageMeter.chat("{\"messages\":[{\"content\":\"hello\"}]}", "{}", "world", true) != null,
                "free Chat usage estimation missing");

        CloudBillingClient.DeductResult rateLimited = CloudBillingClient.DeductResult.failure(429, "limited", true);
        CloudBillingClient.DeductResult networkFailure = CloudBillingClient.DeductResult.failure(0, "network", true);
        CloudBillingClient.DeductResult unauthorized = CloudBillingClient.DeductResult.failure(401, "unauthorized", false);
        CloudBillingClient.DeductResult insufficient = CloudBillingClient.DeductResult.failure(402, "insufficient", false);
        require(BillingService.shouldRetry(rateLimited, 1), "429 was not retried");
        require(BillingService.shouldRetry(networkFailure, 2), "network failure was not retried");
        require(!BillingService.shouldRetry(rateLimited, 3), "retry limit exceeded");
        require(!BillingService.shouldRetry(unauthorized, 1), "401 was retried");
        require(!BillingService.shouldRetry(insufficient, 1), "402 was retried");
        require(BillingService.retryDelayMillis(1, 429) > BillingService.retryDelayMillis(1, 500),
                "429 backoff is not longer");

        String trackedRequestId = "dedup-test-" + System.nanoTime();
        require(BillingService.beginSubmission(trackedRequestId), "first billing submission rejected");
        require(!BillingService.beginSubmission(trackedRequestId), "in-flight billing submission duplicated");
        BillingService.markSucceeded(trackedRequestId);
        require(!BillingService.beginSubmission(trackedRequestId), "successful billing submission duplicated");

        CloudConfig latest = gson.fromJson("{\"code\":0,\"data\":{\"version\":1,\"mimo\":{"
                + "\"api_url\":\"https://api.xiaomimimo.com/v1/chat/completions\","
                + "\"api_key\":\"runtime-key\",\"model\":\"mimo-v2.5-tts\","
                + "\"route_id\":\"route_real\",\"tier\":\"paid\"}}}", CloudConfig.class);
        require("runtime-key".equals(latest.data.mimo.apiKey) && "route_real".equals(latest.data.mimo.routeId),
                "latest MiMo config was not parsed");

        CloudConfig ttsConfig = gson.fromJson("{\"code\":0,\"data\":{\"builtin_api\":{"
                + "\"enableTtsApis\":true,\"ttsTier\":\"free\",\"ttsRouteId\":\"route_tts\","
                + "\"tts\":{\"routes\":[{\"route_id\":\"route_tts\",\"tier\":\"free\","
                + "\"enabled\":true,\"apiProvider\":\"MIMO_TTS\","
                + "\"apiUrl\":\"https://api.xiaomimimo.com/v1/chat/completions\","
                + "\"apiKey\":\"route-runtime-key\",\"apiModel\":\"mimo-v2.5-tts\"}]}},"
                + "\"mimo\":{\"api_url\":\"https://api.xiaomimimo.com/v1/chat/completions\","
                + "\"api_key\":\"mimo-runtime-key\",\"route_id\":\"route_tts\",\"tier\":\"free\"}}}",
                CloudConfig.class);
        BuiltinTtsConfig parsedTtsRoute = ttsConfig.data.builtin_api.tts.routes.get(0);
        require("route-runtime-key".equals(parsedTtsRoute.apiKey)
                        && "mimo-runtime-key".equals(ttsConfig.data.mimo.apiKey),
                "TTS route apiKey and MiMo api_key were not parsed independently");

        URI endpoint = MimoDirectProtocol.requireEndpoint("https://api.xiaomimimo.com/v1/chat/completions");
        require("/v1/chat/completions".equals(endpoint.getPath()), "MiMo endpoint was changed");
        requireThrows(() -> MimoDirectProtocol.requireEndpoint("http://api.xiaomimimo.com/v1"), "MiMo HTTP accepted");
        requireThrows(() -> MimoDirectProtocol.requireEndpoint("https://api.xiaomimimo.com.evil.test/v1"),
                "MiMo lookalike host accepted");
        byte[] wav = "RIFF0000WAVEdata".getBytes(StandardCharsets.US_ASCII);
        String audioJson = "{\"choices\":[{\"message\":{\"audio\":{\"data\":\""
                + Base64.getEncoder().encodeToString(wav) + "\"}}}]}";
        require(java.util.Arrays.equals(wav, MimoDirectProtocol.parseAudio(200,
                audioJson.getBytes(StandardCharsets.UTF_8))), "MiMo WAV was not parsed");
        require(java.util.Arrays.equals(wav, MimoDirectProtocol.parseAudio(200, "",
                audioJson.getBytes(StandardCharsets.UTF_8))), "MiMo JSON without content type was not parsed");
        require(MimoDirectProtocol.parseAudio(200, "text/html",
                audioJson.getBytes(StandardCharsets.UTF_8)) == null,
                "non-JSON MiMo response accepted");
        require(MimoDirectProtocol.parseAudio(200, "{}".getBytes(StandardCharsets.UTF_8)) == null,
                "invalid MiMo response accepted");
        require("苏打".equals(MimoDirectProtocol.normalizeVoice("Suda")),
                "MiMo Suda voice was not normalized");
        require("audio/wav".equals(MimoDirectProtocol.audioMime(wav)), "WAV clone sample was not detected");
        require("audio/mpeg".equals(MimoDirectProtocol.audioMime(new byte[]{'I', 'D', '3', 4})),
                "MP3 clone sample was not detected");
        require(MimoDirectProtocol.audioMime(new byte[]{1, 2, 3, 4}) == null,
                "invalid clone sample was accepted");

        require("route_real".equals(BillingService.requireRouteId("route_real")), "real route rejected");
        requireThrows(() -> BillingService.requireRouteId("auto"), "auto route accepted for billing");
        requireThrows(() -> BillingService.requireRouteId(" "), "blank route accepted for billing");

        AtomicInteger reloads = new AtomicInteger();
        CloudRuntimeReload.set(reloads::incrementAndGet);
        CloudRuntimeReload.notifyApplied();
        require(reloads.get() == 1, "cloud STT reload callback was not invoked");
        CloudRuntimeReload.clear();
    }

    private static RouteDto route(String tier, String id, int priority) {
        RouteDto route = new RouteDto();
        route.tier = tier;
        route.routeId = id;
        route.priority = priority;
        route.enabled = true;
        return route;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void requireThrows(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(message);
    }
}
