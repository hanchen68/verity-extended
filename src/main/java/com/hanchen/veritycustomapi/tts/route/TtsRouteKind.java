package com.hanchen.veritycustomapi.tts.route;

import java.util.Locale;

/**
 * 语音朗读的三条路线，三选一。
 *
 * {@link #NATIVE} 表示「交给原版自己念」，扩展不接管；{@link #LOCAL} 与 {@link #ENDPOINT}
 * 分别走本地引擎和网络端点。每个路线背后挂哪个引擎由 {@link TtsEngines} 决定，
 * 所以以后加引擎只需要注册一个新的 {@link TtsEngine}，不用动这条链路上的调用方。
 */
public enum TtsRouteKind {
    /** 原版朗读：扩展完全不接管。 */
    NATIVE("原版朗读", "native"),
    /** 本地引擎：不需要网络的合成（离线模型、系统引擎等）。 */
    LOCAL("本地引擎", "local"),
    /** 网络端点：走 HTTP 接口的合成。 */
    ENDPOINT("网络端点", "endpoint");

    private final String displayName;
    private final String configValue;

    TtsRouteKind(String displayName, String configValue) {
        this.displayName = displayName;
        this.configValue = configValue;
    }

    public String displayName() {
        return displayName;
    }

    /** 写进配置文件 / 从配置文件读出的稳定值。 */
    public String configValue() {
        return configValue;
    }

    @Override
    public String toString() {
        return displayName;
    }

    /**
     * 宽松解析：认自己的三个值，也认历史上出现过的写法。
     * 认不出来返回 {@code null}，由调用方决定默认值（而不是在这里偷偷兜底）。
     */
    public static TtsRouteKind parse(String raw) {
        if (raw == null) return null;
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "native":
            case "vanilla":
            case "original":
            case "原版":
                return NATIVE;
            case "local":
            case "offline":
            case "本地":
                return LOCAL;
            case "endpoint":
            case "online":
            case "remote":
            case "api":
            case "在线":
                return ENDPOINT;
            default:
                return null;
        }
    }
}
