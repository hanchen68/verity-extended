package com.hanchen.veritycustomapi.stt;

import com.hanchen.veritycustomapi.compat.RuntimePlatform;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class VoskRuntimeSupport {
    private VoskRuntimeSupport() {
    }

    public static boolean isRuntimeAvailable() {
        return getUnavailableReason() == null;
    }

    public static String getUnavailableReason() {
        if (RuntimePlatform.isAndroidRuntime()) {
            return "当前是 Android/FCL 环境，离线 Vosk 不可用";
        }

        String platformIssue = getPlatformIssue();
        if (platformIssue != null) {
            return platformIssue;
        }

        try {
            Class.forName("org.vosk.Model");
            return null;
        } catch (ClassNotFoundException e) {
            return "当前版本未包含 Vosk 运行库，请改用在线识别或使用带离线识别依赖的版本";
        } catch (UnsatisfiedLinkError e) {
            return "Vosk 本地库加载失败，请检查本机依赖环境";
        } catch (Throwable t) {
            String message = t.getMessage();
            if (message == null || message.isBlank()) {
                return "Vosk 运行库初始化失败";
            }
            return "Vosk 运行库初始化失败: " + message;
        }
    }

    /**
     * 检查当前平台有没有随包提供的 Vosk 本地库。
     * Class.forName 只加载 Java 类、不触发 JNA 本地库加载，架构不匹配时会误判可用；
     * 包里只有 x86-64 库，Apple Silicon 与 ARM Linux 要如实判为不可用。
     *
     * @return 不可用原因；平台没问题时返回 null
     */
    private static String getPlatformIssue() {
        String os = String.valueOf(System.getProperty("os.name")).toLowerCase(java.util.Locale.ROOT);
        String arch = String.valueOf(System.getProperty("os.arch")).toLowerCase(java.util.Locale.ROOT);

        boolean x86_64 = arch.contains("x86_64") || arch.contains("amd64") || arch.contains("x64");
        if (!x86_64) {
            return "当前 CPU 架构（" + arch + "）没有随包提供的 Vosk 本地库，离线识别不可用";
        }

        String directory;
        String library;
        if (os.contains("win")) {
            directory = "win32-x86-64";
            library = "libvosk.dll";
        } else if (os.contains("mac") || os.contains("darwin")) {
            directory = "darwin";
            library = "libvosk.dylib";
        } else {
            directory = "linux-x86-64";
            library = "libvosk.so";
        }

        String resource = directory + "/" + library;
        ClassLoader loader = VoskRuntimeSupport.class.getClassLoader();
        if (loader == null || loader.getResource(resource) == null) {
            return "当前平台缺少 Vosk 本地库（" + resource + "），离线识别不可用";
        }
        return null;
    }

    public static boolean hasUsableModel(Path modelPath) {
        if (modelPath == null || !Files.isDirectory(modelPath)) {
            return false;
        }

        if (Files.exists(modelPath.resolve("am").resolve("final.mdl"))
                || Files.exists(modelPath.resolve("conf").resolve("model.conf"))) {
            return true;
        }

        try (var stream = Files.walk(modelPath, 4)) {
            return stream
                    .filter(Files::isDirectory)
                    .anyMatch(path -> Files.exists(path.resolve("am").resolve("final.mdl"))
                            || Files.exists(path.resolve("conf").resolve("model.conf")));
        } catch (IOException e) {
            return false;
        }
    }

    public static String getOfflineStatus(String modelPathText) {
        Path modelPath = modelPathText == null || modelPathText.isBlank() ? null : Paths.get(modelPathText).normalize();
        boolean modelReady = hasUsableModel(modelPath);
        String runtimeIssue = getUnavailableReason();

        if (modelReady && runtimeIssue == null) {
            return "离线识别可用";
        }
        if (modelReady) {
            return "模型已安装，但" + runtimeIssue;
        }
        if (runtimeIssue != null) {
            return "模型未就位，且" + runtimeIssue;
        }
        return "模型未就位，请先安装离线中文识别模型";
    }
}
