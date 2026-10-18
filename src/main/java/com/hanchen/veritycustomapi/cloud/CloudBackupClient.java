package com.hanchen.veritycustomapi.cloud;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.NetworkSecurity;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 对话/日记云端备份客户端。
 *
 * <p>协议（云端按此实现）：
 * <pre>
 * POST /api/v1/user/data/backup         上传完整导出 JSON
 * GET  /api/v1/user/data/backup/latest  取最近一次备份
 * </pre>
 * 均使用 {@code Authorization: Bearer <api-key>}，返回 {@code code/message/data} 信封。
 */
public final class CloudBackupClient {
    public static final String UPLOAD_PATH = "user/data/backup";
    public static final String LATEST_PATH = "user/data/backup/latest";
    private static final Gson GSON = new Gson();

    private final URI apiRoot;
    private final String apiKey;
    private final HttpClient client;
    private final Duration uploadTimeout;

    public CloudBackupClient() {
        String key = ConfigManager.getCloudApiKey();
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("请先完成云端配对");
        }
        try {
            URI configured = NetworkSecurity.requireSecureUri(ConfigManager.getCloudApiUrl(), "云端备份地址");
            this.apiRoot = new URI(configured.getScheme(), configured.getAuthority(), "/api/v1/", null, null);
        } catch (Exception error) {
            throw new IllegalArgumentException("云端地址无效: " + error.getMessage());
        }
        this.apiKey = key.trim();
        int seconds = Math.max(5, ConfigManager.getCloudTimeoutSeconds());
        this.uploadTimeout = Duration.ofSeconds(Math.max(60, seconds * 2L));
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(seconds))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public record UploadResult(boolean success, long updatedAt, String message) {
    }

    public record DownloadResult(boolean success, String json, long updatedAt, String message) {
    }

    public UploadResult upload(String json) {
        if (json == null || json.isBlank()) {
            return new UploadResult(false, 0L, "没有可上传的数据");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(resolve(UPLOAD_PATH))
                    .timeout(uploadTimeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status == 204) {
                return new UploadResult(true, 0L, "已上传到云端");
            }
            if (status < 200 || status >= 300) {
                return new UploadResult(false, 0L, statusMessage(status));
            }
            JsonObject root = parse(response.body());
            if (root == null) {
                return new UploadResult(false, 0L, "云端返回格式错误");
            }
            if (root.has("code") && root.get("code").getAsInt() != 0) {
                return new UploadResult(false, 0L, message(root, "上传失败"));
            }
            JsonObject data = object(root, "data");
            long updatedAt = number(data, "updated_at");
            return new UploadResult(true, updatedAt, "已上传到云端");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return new UploadResult(false, 0L, "上传已取消");
        } catch (Exception error) {
            return new UploadResult(false, 0L, "上传失败: " + safe(error));
        }
    }

    public DownloadResult downloadLatest() {
        try {
            HttpRequest request = HttpRequest.newBuilder(resolve(LATEST_PATH))
                    .timeout(uploadTimeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status == 404) {
                return new DownloadResult(false, null, 0L, "云端还没有备份");
            }
            if (status < 200 || status >= 300) {
                return new DownloadResult(false, null, 0L, statusMessage(status));
            }
            JsonObject root = parse(response.body());
            if (root == null) {
                return new DownloadResult(false, null, 0L, "云端返回格式错误");
            }
            if (root.has("code") && root.get("code").getAsInt() != 0) {
                return new DownloadResult(false, null, 0L, message(root, "读取备份失败"));
            }
            JsonObject data = object(root, "data");
            String json = extractBackup(data);
            if (json == null || json.isBlank()) {
                return new DownloadResult(false, null, 0L, "云端备份为空");
            }
            return new DownloadResult(true, json, number(data, "updated_at"), "已读取云端备份");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return new DownloadResult(false, null, 0L, "读取已取消");
        } catch (Exception error) {
            return new DownloadResult(false, null, 0L, "读取备份失败: " + safe(error));
        }
    }

    /** 兼容几种常见返回：data.backup 对象、data.json 字符串、data 本身是导出对象。 */
    private static String extractBackup(JsonObject data) {
        if (data == null) return null;
        JsonElement backup = data.get("backup");
        if (backup != null && backup.isJsonObject()) return GSON.toJson(backup);
        JsonElement json = data.get("json");
        if (json != null && json.isJsonPrimitive()) return json.getAsString();
        if (data.has("format")) return GSON.toJson(data);
        JsonElement text = data.get("text");
        if (text != null && text.isJsonPrimitive()) return text.getAsString();
        return null;
    }

    private URI resolve(String path) {
        return apiRoot.resolve(path.startsWith("/") ? path.substring(1) : path);
    }

    private static JsonObject parse(String body) {
        try {
            JsonElement element = JsonParser.parseString(body);
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JsonObject object(JsonObject root, String key) {
        return root != null && root.has(key) && root.get(key).isJsonObject()
                ? root.getAsJsonObject(key) : new JsonObject();
    }

    private static String message(JsonObject root, String fallback) {
        if (root != null && root.has("message") && !root.get("message").isJsonNull()) {
            String value = root.get("message").getAsString();
            if (!value.isBlank()) return value;
        }
        return fallback;
    }

    private static long number(JsonObject root, String key) {
        try {
            return root != null && root.has(key) && !root.get(key).isJsonNull()
                    ? root.get(key).getAsLong() : 0L;
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private static String statusMessage(int status) {
        return switch (status) {
            case 401 -> "API Key 无效，请重新同步云端配置";
            case 403 -> "当前账号无权备份";
            case 404 -> "云端还没有备份";
            case 413 -> "备份文件过大";
            case 429 -> "上传过于频繁，请稍后重试";
            default -> status >= 500 ? "云端暂不可用" : "云端请求失败: HTTP " + status;
        };
    }

    private static String safe(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        return message == null || message.isBlank() ? "网络异常" : message;
    }
}
