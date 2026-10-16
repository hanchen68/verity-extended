package com.hanchen.veritycustomapi.resource;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Gson;
import com.hanchen.veritycustomapi.NetworkSecurity;
import com.hanchen.veritycustomapi.cloud.CloudConfig;
import com.hanchen.veritycustomapi.cloud.CloudMmdProject;
import com.hanchen.veritycustomapi.cloud.CloudSkin;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Arrays;

public final class CloudResourceClient {
    private static final Gson GSON = new Gson();
    private final URI apiRoot;
    private final String apiKey;
    private final HttpClient client;
    private final Duration timeout;
    private final Duration downloadTimeout;

    public CloudResourceClient(String apiUrl, String apiKey, int timeoutSeconds) throws ResourceException {
        if (apiKey == null || apiKey.isBlank()) throw new ResourceException(401, "请先完成云端配对");
        try {
            URI configured = NetworkSecurity.requireSecureUri(apiUrl, "云端资源地址");
            this.apiRoot = new URI(configured.getScheme(), configured.getAuthority(), "/api/v1/", null, null);
        } catch (Exception error) {
            throw new ResourceException("云端资源地址无效", error);
        }
        this.apiKey = apiKey.trim();
        int requestTimeoutSeconds = Math.max(5, timeoutSeconds);
        this.timeout = Duration.ofSeconds(requestTimeoutSeconds);
        this.downloadTimeout = Duration.ofSeconds(Math.max(300, requestTimeoutSeconds * 5L));
        this.client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public CloudSkin[] fetchSkins() throws ResourceException, InterruptedException {
        JsonObject data = fetchJson(resolve("skins"), "皮肤列表");
        JsonArray array = data.has("skins") && data.get("skins").isJsonArray() ? data.getAsJsonArray("skins") : new JsonArray();
        CloudSkin[] values = GSON.fromJson(array, CloudSkin[].class);
        return Arrays.stream(values == null ? new CloudSkin[0] : values)
                .filter(value -> value != null && validId(value.name)).toArray(CloudSkin[]::new);
    }

    public CloudConfig.MimoVoice[] fetchVoices() throws ResourceException, InterruptedException {
        JsonObject data = fetchJson(resolve("mimo/voices"), "音色列表");
        JsonArray array = data.has("voices") && data.get("voices").isJsonArray() ? data.getAsJsonArray("voices") : new JsonArray();
        CloudConfig.MimoVoice[] values = GSON.fromJson(array, CloudConfig.MimoVoice[].class);
        return Arrays.stream(values == null ? new CloudConfig.MimoVoice[0] : values)
                .filter(value -> value != null && validId(value.id)).toArray(CloudConfig.MimoVoice[]::new);
    }

    public CloudMmdProject[] fetchMmdProjects() throws ResourceException, InterruptedException {
        JsonObject data = fetchJson(resolve("mmd/projects"), "MMD 项目列表");
        JsonArray array = data.has("projects") && data.get("projects").isJsonArray()
                ? data.getAsJsonArray("projects") : new JsonArray();
        CloudMmdProject[] values = GSON.fromJson(array, CloudMmdProject[].class);
        return Arrays.stream(values == null ? new CloudMmdProject[0] : values)
                .filter(value -> value != null && validId(value.id)
                        && (value.type == null || value.type.isBlank() || "mmd".equalsIgnoreCase(value.type)))
                .toArray(CloudMmdProject[]::new);
    }

    public Path download(String suppliedUrl, String fallbackPath, Path destination, long maxBytes)
            throws ResourceException, InterruptedException {
        URI uri = suppliedUrl == null || suppliedUrl.isBlank() ? resolve(fallbackPath) : resolveDownloadUrl(suppliedUrl);
        Path parent = destination.toAbsolutePath().normalize().getParent();
        Path temp = destination.resolveSibling(destination.getFileName() + ".download");
        try {
            Files.createDirectories(parent);
            Files.deleteIfExists(temp);
            HttpResponse<InputStream> response = client.send(
                    request(uri).timeout(downloadTimeout).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                response.body().close();
                throw statusError(response.statusCode());
            }
            long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (declared > maxBytes) {
                response.body().close();
                throw new ResourceException(413, "资源文件过大");
            }
            long total = 0L;
            try (InputStream input = response.body(); var output = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[16384];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    total += read;
                    if (total > maxBytes) throw new ResourceException(413, "资源文件过大");
                    output.write(buffer, 0, read);
                }
            }
            if (declared >= 0 && total != declared) {
                throw new IOException("下载内容长度不完整: " + total + "/" + declared);
            }
            Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
            return destination;
        } catch (IOException error) {
            throw new ResourceException("资源下载失败，请重试", error);
        } finally {
            try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
        }
    }

    private JsonObject fetchJson(URI uri, String subject) throws ResourceException, InterruptedException {
        try {
            HttpResponse<String> response = client.send(request(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw statusError(response.statusCode());
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            if (root.has("code") && root.get("code").getAsInt() != 0) {
                throw new ResourceException(0, subject + "返回失败");
            }
            return root.has("data") && root.get("data").isJsonObject() ? root.getAsJsonObject("data") : new JsonObject();
        } catch (IOException error) {
            throw new ResourceException(subject + "请求失败", error);
        } catch (RuntimeException error) {
            throw new ResourceException(subject + "响应格式错误", error);
        }
    }

    private HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(timeout).header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json, application/zip, audio/wav, audio/mpeg");
    }

    private URI resolve(String path) {
        return apiRoot.resolve(path.startsWith("/") ? path.substring(1) : path);
    }

    private URI resolveDownloadUrl(String supplied) throws ResourceException {
        try {
            URI value = URI.create(supplied.trim());
            return value.isAbsolute() ? secureSameOrigin(supplied)
                    : supplied.trim().startsWith("/") ? apiRoot.resolve(value) : resolve(supplied);
        } catch (IllegalArgumentException error) {
            throw new ResourceException("资源地址无效", error);
        }
    }

    private URI secureSameOrigin(String supplied) throws ResourceException {
        try {
            URI uri = NetworkSecurity.requireSecureUri(supplied, "受保护资源地址");
            if (!apiRoot.getScheme().equalsIgnoreCase(uri.getScheme())
                    || !apiRoot.getHost().equalsIgnoreCase(uri.getHost())
                    || effectivePort(apiRoot) != effectivePort(uri)) {
                throw new ResourceException(403, "云端返回了不受信任的资源地址");
            }
            return uri;
        } catch (ResourceException error) {
            throw error;
        } catch (Exception error) {
            throw new ResourceException("资源地址无效", error);
        }
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static boolean validId(String value) {
        return value != null && value.matches("[\\p{L}\\p{N}._-]{1,96}") && !value.equals(".") && !value.equals("..");
    }

    private static ResourceException statusError(int status) {
        return switch (status) {
            case 401 -> new ResourceException(status, "API Key 无效，请重新同步云端配置");
            case 403 -> new ResourceException(status, "无权执行当前操作");
            case 404 -> new ResourceException(status, "资源不存在或当前账号不可见");
            case 413 -> new ResourceException(status, "资源文件过大");
            case 429 -> new ResourceException(status, "请求过于频繁，请稍后重试");
            default -> status >= 500 ? new ResourceException(status, "云端暂不可用")
                    : new ResourceException(status, "云端请求失败: HTTP " + status);
        };
    }
}
