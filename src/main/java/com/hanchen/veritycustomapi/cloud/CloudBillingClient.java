package com.hanchen.veritycustomapi.cloud;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hanchen.veritycustomapi.NetworkSecurity;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.time.Duration;

public final class CloudBillingClient {
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public DeductResult deduct(String apiUrl, String apiKey, DeductRequest payload, int timeoutSeconds) {
        return deduct(apiUrl, null, apiKey, payload, timeoutSeconds);
    }

    public DeductResult deduct(String apiUrl, String configuredEndpoint, String apiKey,
                               DeductRequest payload, int timeoutSeconds) {
        if (apiUrl == null || apiUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
            return DeductResult.failure(0, "云端 API 未配置", false);
        }
        try {
            String endpoint = resolveEndpoint(apiUrl, configuredEndpoint, "/credits/deduct");
            int timeout = Math.max(3, timeoutSeconds);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(NetworkSecurity.requireSecureUri(endpoint, "额度接口地址"))
                    .timeout(Duration.ofSeconds(timeout))
                    .header("Authorization", "Bearer " + apiKey.trim())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            JsonObject root = null;
            try {
                root = JsonParser.parseString(response.body()).getAsJsonObject();
            } catch (RuntimeException ignored) {
            }
            int responseCode = root != null && root.has("code") ? root.get("code").getAsInt() : 0;
            if (status != 200 || responseCode != 0) {
                int effectiveStatus = responseCode != 0 ? responseCode : status;
                String message = root != null && root.has("message")
                        ? root.get("message").getAsString() : statusMessage(effectiveStatus);
                return DeductResult.failure(effectiveStatus, message,
                        effectiveStatus == 429 || effectiveStatus >= 500);
            }
            if (root == null) return DeductResult.failure(status, "扣费响应不是有效 JSON", retryableStatus(status));
            if (!root.has("data") || !root.get("data").isJsonObject()) {
                return DeductResult.failure(status, "扣费响应缺少 data", false);
            }
            JsonObject data = root.getAsJsonObject("data");
            int cost = data.has("cost") ? data.get("cost").getAsInt() : 0;
            Double remaining = CloudNumber.nullable(data, "credits_remaining");
            Double total = CloudNumber.nullable(data, "credits_total");
            JsonObject billing = data.has("billing") && data.get("billing").isJsonObject()
                    ? data.getAsJsonObject("billing") : null;
            return DeductResult.success(cost, remaining, total, billing);
        } catch (Throwable error) {
            return DeductResult.failure(0, error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(), true);
        }
    }

    public ReserveResult reserve(String apiUrl, String endpoint, String apiKey, ReserveRequest payload,
                                 int timeoutSeconds) {
        JsonResult response = post(apiUrl, endpoint, "/credits/reserve", apiKey, payload, timeoutSeconds);
        if (!response.success()) {
            return ReserveResult.failure(response.status(), response.error(), response.retryable());
        }
        JsonObject data = response.data();
        String reservationId = string(data, "reservation_id");
        if (reservationId == null || reservationId.isBlank()) {
            return ReserveResult.failure(200, "预授权响应缺少 reservation_id", false);
        }
        return ReserveResult.success(reservationId, integer(data, "reserved_cost"),
                CloudNumber.nullable(data, "credits_remaining"), CloudNumber.nullable(data, "credits_total"));
    }

    public OperationResult settle(String apiUrl, String endpoint, String apiKey, SettlementRequest payload,
                                  int timeoutSeconds) {
        return operation(post(apiUrl, endpoint, "/credits/settle", apiKey, payload, timeoutSeconds));
    }

    public OperationResult release(String apiUrl, String endpoint, String apiKey, ReleaseRequest payload,
                                   int timeoutSeconds) {
        return operation(post(apiUrl, endpoint, "/credits/release", apiKey, payload, timeoutSeconds));
    }

    private JsonResult post(String apiUrl, String endpoint, String fallback, String apiKey, Object payload,
                            int timeoutSeconds) {
        if (apiUrl == null || apiUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
            return JsonResult.failure(0, "云端 API 未配置", false);
        }
        try {
            String target = resolveEndpoint(apiUrl, endpoint, fallback);
            int timeout = Math.max(3, timeoutSeconds);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(NetworkSecurity.requireSecureUri(target, "额度接口地址"))
                    .timeout(Duration.ofSeconds(timeout))
                    .header("Authorization", "Bearer " + apiKey.trim())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload)))
                    .build();
            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            JsonObject root;
            try {
                root = JsonParser.parseString(response.body()).getAsJsonObject();
            } catch (RuntimeException error) {
                return JsonResult.failure(response.statusCode(), "计费响应不是有效 JSON",
                        retryableStatus(response.statusCode()));
            }
            int code = root.has("code") ? root.get("code").getAsInt() : response.statusCode();
            if (response.statusCode() != 200 || code != 0) {
                int status = code != 0 ? code : response.statusCode();
                String message = root.has("message") ? root.get("message").getAsString() : statusMessage(status);
                return JsonResult.failure(status, message, status == 429 || status >= 500);
            }
            if (!root.has("data") || !root.get("data").isJsonObject()) {
                return JsonResult.failure(200, "计费响应缺少 data", false);
            }
            return JsonResult.success(root.getAsJsonObject("data"));
        } catch (Throwable error) {
            return JsonResult.failure(0,
                    error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(), true);
        }
    }

    private static OperationResult operation(JsonResult response) {
        if (!response.success()) {
            return OperationResult.failure(response.status(), response.error(), response.retryable());
        }
        JsonObject data = response.data();
        return OperationResult.success(CloudNumber.nullable(data, "credits_remaining"),
                CloudNumber.nullable(data, "credits_total"));
    }

    private static String resolveEndpoint(String baseUrl, String suffix) {
        String value = baseUrl.trim();
        int index = value.lastIndexOf("/getconfig");
        if (index >= 0) return value.substring(0, index) + suffix;
        return value.endsWith("/") ? value + suffix.substring(1) : value + suffix;
    }

    static String resolveEndpoint(String baseUrl, String endpoint, String fallback) {
        URI base = URI.create(baseUrl.trim());
        URI resolved;
        if (endpoint == null || endpoint.isBlank()) {
            resolved = URI.create(resolveEndpoint(baseUrl, fallback));
        } else {
            URI value = URI.create(endpoint.trim());
            resolved = value.isAbsolute() ? value
                    : URI.create(base.getScheme() + "://" + base.getAuthority() + "/").resolve(value);
        }
        if (!sameOrigin(base, resolved)) {
            throw new IllegalArgumentException("计费接口必须与云端 API 同源");
        }
        return resolved.toString();
    }

    private static boolean sameOrigin(URI first, URI second) {
        return first.getScheme() != null && first.getHost() != null
                && first.getScheme().equalsIgnoreCase(second.getScheme())
                && first.getHost().equalsIgnoreCase(second.getHost())
                && effectivePort(first) == effectivePort(second);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static boolean retryableStatus(int status) {
        return status == 0 || status == 429 || status >= 500;
    }

    private static String string(JsonObject object, String name) {
        return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : null;
    }

    private static int integer(JsonObject object, String name) {
        return object.has(name) && !object.get(name).isJsonNull() ? Math.max(0, object.get(name).getAsInt()) : 0;
    }

    private static String statusMessage(int status) {
        return switch (status) {
            case 400 -> "用量上报参数错误";
            case 401 -> "云端 API Key 无效，请重新同步配置";
            case 402 -> "额度不足，请前往云端积分中心充值";
            case 429 -> "扣费请求过于频繁";
            default -> status >= 500 ? "云端计费服务暂时不可用" : "HTTP " + status;
        };
    }

    public record DeductRequest(String service, String tier,
                                @com.google.gson.annotations.SerializedName("route_id") String routeId,
                                @com.google.gson.annotations.SerializedName("request_id") String requestId,
                                JsonObject usage,
                                @com.google.gson.annotations.SerializedName("is_clone") Boolean isClone,
                                @com.google.gson.annotations.SerializedName("client_id") String clientId) {
    }

    public record ReserveRequest(String service, String tier,
                                 @com.google.gson.annotations.SerializedName("route_id") String routeId,
                                 @com.google.gson.annotations.SerializedName("request_id") String requestId,
                                 @com.google.gson.annotations.SerializedName("estimated_cost") int estimatedCost,
                                 @com.google.gson.annotations.SerializedName("client_id") String clientId) {
    }

    public record SettlementRequest(
            @com.google.gson.annotations.SerializedName("reservation_id") String reservationId,
            @com.google.gson.annotations.SerializedName("request_id") String requestId,
            String service, String tier,
            @com.google.gson.annotations.SerializedName("route_id") String routeId,
            JsonObject usage) {
    }

    public record ReleaseRequest(
            @com.google.gson.annotations.SerializedName("reservation_id") String reservationId,
            @com.google.gson.annotations.SerializedName("request_id") String requestId,
            String service, String tier,
            @com.google.gson.annotations.SerializedName("route_id") String routeId,
            String reason) {
    }

    public record ReserveResult(boolean success, int status, String reservationId, int reservedCost,
                                Double creditsRemaining, Double creditsTotal, String error, boolean retryable) {
        static ReserveResult success(String reservationId, int reservedCost, Double remaining, Double total) {
            return new ReserveResult(true, 200, reservationId, reservedCost, remaining, total, null, false);
        }

        static ReserveResult failure(int status, String error, boolean retryable) {
            return new ReserveResult(false, status, null, 0, null, null, error, retryable);
        }
    }

    public record OperationResult(boolean success, int status, Double creditsRemaining,
                                  Double creditsTotal, String error, boolean retryable) {
        static OperationResult success(Double remaining, Double total) {
            return new OperationResult(true, 200, remaining, total, null, false);
        }

        static OperationResult failure(int status, String error, boolean retryable) {
            return new OperationResult(false, status, null, null, error, retryable);
        }
    }

    private record JsonResult(boolean success, int status, JsonObject data, String error, boolean retryable) {
        static JsonResult success(JsonObject data) {
            return new JsonResult(true, 200, data, null, false);
        }

        static JsonResult failure(int status, String error, boolean retryable) {
            return new JsonResult(false, status, null, error, retryable);
        }
    }

    public record DeductResult(boolean success, int status, int cost, Double creditsRemaining,
                               Double creditsTotal, JsonObject billing, String error, boolean retryable) {
        static DeductResult success(int cost, Double remaining, Double total, JsonObject billing) {
            return new DeductResult(true, 200, cost, remaining, total, billing, null, false);
        }

        static DeductResult failure(int status, String error, boolean retryable) {
            return new DeductResult(false, status, 0, null, null, null, error, retryable);
        }
    }
}
