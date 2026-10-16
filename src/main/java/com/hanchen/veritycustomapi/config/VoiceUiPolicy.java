package com.hanchen.veritycustomapi.config;

public final class VoiceUiPolicy {
    private VoiceUiPolicy() {
    }

    public static boolean offlineEntriesEnabled(boolean android) {
        return !android;
    }

    public static String recognitionMode(boolean android, String configured) {
        return android ? "api" : configured;
    }

    public static String ttsMode(boolean android, String configured) {
        return android ? "online" : configured;
    }
}
