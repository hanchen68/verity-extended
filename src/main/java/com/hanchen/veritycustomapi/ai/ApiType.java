package com.hanchen.veritycustomapi.ai;

public enum ApiType {
    CHAT("chat"),
    TTS("tts"),
    STT("stt");

    private final String value;

    ApiType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}

