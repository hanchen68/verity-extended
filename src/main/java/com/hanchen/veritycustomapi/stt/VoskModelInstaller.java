package com.hanchen.veritycustomapi.stt;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.compat.RuntimePlatform;
import com.hanchen.veritycustomapi.compat.ForgePaths;
import com.hanchen.veritycustomapi.config.ModConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

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
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class VoskModelInstaller {
    public static final String MODEL_URL = "https://dl.xrstudio.cn/vosk-model-small-cn.zip";
    public static final String DEFAULT_MODEL_PATH = "./config/vosk-model-small-cn";

    private static final AtomicBoolean INSTALLING = new AtomicBoolean(false);
    private static volatile String lastStatus = "未安装";

    private VoskModelInstaller() {
    }

    public static boolean isInstalling() {
        return INSTALLING.get();
    }

    public static String getLastStatus() {
        return lastStatus;
    }

    public static String getUiStatus() {
        return VoskRuntimeSupport.getOfflineStatus(ModConfig.VOSK_MODEL_PATH.get()) + "；安装器状态：" + lastStatus;
    }

    public static void installAsync(Runnable onSuccess) {
        if (RuntimePlatform.isAndroidRuntime()) {
            updateStatus("当前为 Android/FCL 环境，离线语音模型不可用，请使用在线 API 模式", true);
            OllamaProxyMod.LOGGER.warn("Android/FCL 环境不支持 Vosk 本地库，模型安装已跳过");
            return;
        }
        if (!INSTALLING.compareAndSet(false, true)) {
            updateStatus("模型正在安装中，请等待当前任务完成", true);
            OllamaProxyMod.LOGGER.warn("Vosk 模型安装任务已在运行");
            return;
        }

        updateStatus("开始下载离线中文识别模型，文件约 44MB", true);
        CompletableFuture.runAsync(() -> {
            try {
                Path installedPath = install();
                String runtimeIssue = VoskRuntimeSupport.getUnavailableReason();
                if (runtimeIssue == null) {
                    updateStatus("安装完成，已切换到本地离线识别：" + installedPath, true);
                } else {
                    updateStatus("模型已安装到 " + installedPath + "，但" + runtimeIssue, true);
                }
                OllamaProxyMod.LOGGER.info("Vosk 离线语音识别模型安装完成: {}", installedPath);
                if (onSuccess != null) {
                    onSuccess.run();
                }
            } catch (Exception e) {
                updateStatus("安装失败：" + e.getMessage(), true);
                OllamaProxyMod.LOGGER.error("Vosk 离线语音识别模型安装失败", e);
            } finally {
                INSTALLING.set(false);
            }
        });
    }

    private static Path install() throws Exception {
        Path cacheDir = ForgePaths.config("verity-model-cache");
        Path zipPath = cacheDir.resolve("vosk-model-small-cn.zip");
        Path targetDir = ForgePaths.config("vosk-model-small-cn");
        Path tempDir = ForgePaths.config("vosk-model-small-cn.tmp");

        Files.createDirectories(cacheDir);
        download(zipPath);

        deleteDirectory(tempDir);
        Files.createDirectories(tempDir);
        unzip(zipPath, tempDir);

        Path modelRoot = findModelRoot(tempDir);
        deleteDirectory(targetDir);
        Files.createDirectories(targetDir.getParent());
        Files.move(modelRoot, targetDir, StandardCopyOption.REPLACE_EXISTING);
        deleteDirectory(tempDir);

        String installedPath = targetDir.toString();
        ModConfig.VOSK_MODEL_PATH.set(installedPath);
        if (VoskRuntimeSupport.isRuntimeAvailable()) {
            ModConfig.RECOGNITION_MODE.set("local");
        }
        ConfigManager.saveCommonConfig();
        return targetDir;
    }

    private static void download(Path zipPath) throws Exception {
        updateStatus("正在下载模型，请稍候...", true);
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(MODEL_URL))
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();
        HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(zipPath));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("下载失败，HTTP " + response.statusCode());
        }
        if (!Files.exists(zipPath) || Files.size(zipPath) == 0) {
            throw new IOException("下载文件为空");
        }
    }

    private static void unzip(Path zipPath, Path targetDir) throws IOException {
        updateStatus("正在解压模型...", true);
        try (InputStream input = Files.newInputStream(zipPath);
             ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path output = targetDir.resolve(entry.getName()).normalize();
                if (!output.startsWith(targetDir)) {
                    throw new IOException("模型压缩包包含非法路径: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                } else {
                    Files.createDirectories(output.getParent());
                    Files.copy(zip, output, StandardCopyOption.REPLACE_EXISTING);
                }
                zip.closeEntry();
            }
        }
    }

    private static Path findModelRoot(Path tempDir) throws IOException {
        try (var stream = Files.walk(tempDir, 4)) {
            return stream
                    .filter(Files::isDirectory)
                    .filter(path -> Files.exists(path.resolve("am").resolve("final.mdl"))
                            || Files.exists(path.resolve("conf").resolve("model.conf")))
                    .findFirst()
                    .orElseThrow(() -> new IOException("压缩包内未找到可用的 Vosk 模型目录"));
        }
    }

    private static void updateStatus(String status, boolean notifyPlayer) {
        lastStatus = status;
        OllamaProxyMod.LOGGER.info("Vosk 模型安装状态: {}", status);
        if (!notifyPlayer) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null) {
            minecraft.execute(() -> {
                if (minecraft.player != null) {
                    minecraft.player.displayClientMessage(Component.literal("[Verity] " + status), false);
                }
            });
        }
    }

    private static void deleteDirectory(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw e;
        }
    }
}
