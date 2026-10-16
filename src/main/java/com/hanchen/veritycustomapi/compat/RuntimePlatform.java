package com.hanchen.veritycustomapi.compat;

import com.hanchen.veritycustomapi.OllamaProxyMod;

/**
 * 运行时平台检测。认 Android 启动器（PojavLauncher / FCL / Zalith），
 * 桌面端走 JavaSound、Android 端走 OpenAL。
 */
public final class RuntimePlatform {

    private static final boolean IS_ANDROID = detectAndroid();

    private RuntimePlatform() {
    }

    public static boolean isAndroid() {
        return IS_ANDROID;
    }

    /** @deprecated 请使用 {@link #isAndroid()} */
    @Deprecated
    public static boolean isAndroidRuntime() {
        return IS_ANDROID;
    }

    /**
     * 检测是否支持 Android Media API（AudioTrack/MediaCodec）。
     * 仅在 Android 启动器环境下可能可用。
     */
    public static boolean hasAndroidMediaClasses() {
        return isClassAvailable("android.media.AudioTrack")
                && isClassAvailable("android.media.MediaPlayer");
    }

    /**
     * 检测 Android 麦克风录音 API（AudioRecord）是否可用。
     * 桌面端和部分 Android 启动器可能只有 AudioTrack 播放能力，
     * 但没有 AudioRecord 录音能力，需要精确区分。
     */
    public static boolean hasAndroidAudioRecord() {
        return isClassAvailable("android.media.AudioRecord");
    }

    private static boolean detectAndroid() {
        // 先看系统属性
        String vendor = System.getProperty("java.vendor", "").toLowerCase(java.util.Locale.ROOT);
        String osName = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        String javaRuntime = System.getProperty("java.runtime.name", "").toLowerCase(java.util.Locale.ROOT);
        String userHome = System.getProperty("user.home", "").toLowerCase(java.util.Locale.ROOT);
        String javaLibPath = System.getProperty("java.library.path", "").toLowerCase(java.util.Locale.ROOT);
        String dataDir = System.getenv().getOrDefault("POJAV_NATIVEDIR", "")
                + System.getenv().getOrDefault("FCL_NATIVEDIR", "")
                + System.getenv().getOrDefault("TMPDIR", "");
        dataDir = dataDir.toLowerCase(java.util.Locale.ROOT);

        if (vendor.contains("android") || osName.contains("android")
                || javaRuntime.contains("android")
                || userHome.contains("/storage/emulated/")
                || userHome.contains("/data/data/")
                || javaLibPath.contains("/data/app/")
                || javaLibPath.contains("com.tungsten.fcl")
                || dataDir.contains("pojav")
                || dataDir.contains("fcl")) {
            OllamaProxyMod.LOGGER.info("[Platform] Android detected via system properties");
            return true;
        }

        // 属性认不出来，再挨个看启动器类
        String[] launchers = {
                "net.kdt.pojavlaunch.PojavLoginActivity",
                "com.movtery.pojavlauncher.PojavLauncherActivity",
                "com.movtery.zalithlauncher.ZalithLauncherActivity",
                "com.movtery.fcl.FCLApplication",
                "com.tungsten.fcl.FCLApplication",
                "com.mojang.minecraftpe.MainActivity"
        };
        for (String cls : launchers) {
            if (isClassAvailable(cls)) {
                OllamaProxyMod.LOGGER.info("[Platform] Android detected via launcher: {}", cls);
                return true;
            }
        }
        return false;
    }

    private static boolean isClassAvailable(String className) {
        try {
            Class.forName(className, false, RuntimePlatform.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError | SecurityException ignored) {
            return false;
        }
    }
}
