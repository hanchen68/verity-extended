package com.hanchen.veritycustomapi.compat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

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

    private static OpenResult open(Path target, String successMessage) {
        if (RuntimePlatform.isAndroid()) {
            return new OpenResult(false, target, "当前 Android/FCL 环境不支持直接打开桌面文件");
        }
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

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return error.getClass().getSimpleName();
        message = message.replace('\r', ' ').replace('\n', ' ').trim();
        return message.length() > 120 ? message.substring(0, 120) + "..." : message;
    }
}
