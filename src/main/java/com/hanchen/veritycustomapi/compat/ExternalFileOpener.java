package com.hanchen.veritycustomapi.compat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * 用系统默认程序打开日志文件或目录。
 *
 * <p>先走 {@code java.awt.Desktop}；它不可用（很多启动器会给游戏加
 * {@code -Djava.awt.headless=true}）时退回系统命令：
 * Windows {@code rundll32 url.dll,FileProtocolHandler} / {@code explorer.exe}，
 * macOS {@code open}，Linux {@code xdg-open}。
 *
 * <p>以前只用 Desktop，headless 环境下会直接报「当前系统不支持从游戏打开文件」，
 * 但系统本身完全有能力打开，只是 JVM 没提供 Desktop。
 */
public final class ExternalFileOpener {
    public record OpenResult(boolean success, Path target, String message) {
    }

    private ExternalFileOpener() {
    }

    public static OpenResult openLatestLog() {
        Path log = ForgePaths.game("logs", "latest.log");
        if (!Files.isRegularFile(log)) {
            return new OpenResult(false, log, "latest.log 尚未生成");
        }
        return open(log, "已打开 latest.log");
    }

    public static OpenResult openLogsDirectory() {
        Path directory = ForgePaths.game("logs");
        try {
            Files.createDirectories(directory);
        } catch (IOException error) {
            return new OpenResult(false, directory, "无法创建日志目录: " + safeMessage(error));
        }
        return open(directory, "已打开日志目录");
    }

    /** 用系统默认程序打开任意文件（导出文件、日志等）。 */
    public static OpenResult openFile(Path target) {
        if (target == null || !Files.isRegularFile(target)) {
            return new OpenResult(false, target, "文件不存在");
        }
        return open(target, "已打开 " + target.getFileName());
    }

    /** 在文件管理器中打开任意目录。 */
    public static OpenResult openDirectory(Path target) {
        if (target == null) {
            return new OpenResult(false, null, "目录不存在");
        }
        try {
            Files.createDirectories(target);
        } catch (IOException error) {
            return new OpenResult(false, target, "无法创建目录: " + safeMessage(error));
        }
        return open(target, "已打开目录");
    }

    private static OpenResult open(Path target, String successMessage) {
        if (RuntimePlatform.isAndroid()) {
            return new OpenResult(false, target, "当前 Android/FCL 环境不支持直接打开桌面文件");
        }
        OpenResult desktop = tryDesktop(target, successMessage);
        if (desktop.success()) return desktop;

        OpenResult nativeResult = tryNative(target, successMessage);
        return nativeResult.success() ? nativeResult : new OpenResult(
                false, target, nativeResult.message().isBlank() ? desktop.message() : nativeResult.message());
    }

    /** 首选：AWT Desktop。headless 或系统未提供打开程序时返回失败，交给系统命令兜底。 */
    private static OpenResult tryDesktop(Path target, String successMessage) {
        try {
            Class<?> desktopClass = Class.forName("java.awt.Desktop", false, ExternalFileOpener.class.getClassLoader());
            Method isDesktopSupported = desktopClass.getMethod("isDesktopSupported");
            if (!Boolean.TRUE.equals(isDesktopSupported.invoke(null))) {
                return new OpenResult(false, target, "当前系统不支持从游戏打开文件");
            }
            Object desktop = desktopClass.getMethod("getDesktop").invoke(null);
            Class<?> actionClass = Class.forName("java.awt.Desktop$Action", false, ExternalFileOpener.class.getClassLoader());
            @SuppressWarnings({"rawtypes", "unchecked"})
            Object openAction = Enum.valueOf((Class<? extends Enum>) actionClass.asSubclass(Enum.class), "OPEN");
            if (!Boolean.TRUE.equals(desktopClass.getMethod("isSupported", actionClass).invoke(desktop, openAction))) {
                return new OpenResult(false, target, "当前系统未提供文件打开程序");
            }
            desktopClass.getMethod("open", java.io.File.class).invoke(desktop, target.toFile());
            return new OpenResult(true, target, successMessage);
        } catch (ReflectiveOperationException | LinkageError | SecurityException error) {
            return new OpenResult(false, target, "打开失败: " + safeMessage(error));
        }
    }

    /** 兜底：直接调系统命令。成功启动就认为已交给系统，不把「命令返回值」当成打开结果。 */
    private static OpenResult tryNative(Path target, String successMessage) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        boolean directory = Files.isDirectory(target);
        List<String> command;
        if (os.contains("win")) {
            command = directory
                    ? List.of("explorer.exe", target.toString())
                    : List.of("rundll32.exe", "url.dll,FileProtocolHandler", target.toString());
        } else if (os.contains("mac")) {
            command = List.of("open", target.toString());
        } else {
            command = List.of("xdg-open", target.toString());
        }
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            // explorer.exe 成功时也常返回 1，不能等退出码；其它命令给 2 秒判断明显失败
            if (!command.get(0).toLowerCase(Locale.ROOT).startsWith("explorer")) {
                if (process.waitFor(2, TimeUnit.SECONDS) && process.exitValue() != 0) {
                    return new OpenResult(false, target, "打开失败：系统命令返回 " + process.exitValue());
                }
            }
            return new OpenResult(true, target, successMessage);
        } catch (IOException error) {
            return new OpenResult(false, target, "打开失败: " + safeMessage(error));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return new OpenResult(false, target, "打开被中断");
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return error.getClass().getSimpleName();
        message = message.replace('\r', ' ').replace('\n', ' ').trim();
        return message.length() > 120 ? message.substring(0, 120) + "..." : message;
    }
}
