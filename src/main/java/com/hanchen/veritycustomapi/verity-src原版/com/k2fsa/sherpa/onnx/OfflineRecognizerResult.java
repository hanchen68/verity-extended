/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

public class OfflineRecognizerResult {
    private final String text;
    private final String[] tokens;
    private final float[] timestamps;
    private final String lang;
    private final String emotion;
    private final String event;
    private final float[] durations;

    public OfflineRecognizerResult(String string, String[] stringArray, float[] fArray, String string2, String string3, String string4, float[] fArray2) {
        this.text = string;
        this.tokens = stringArray;
        this.timestamps = fArray;
        this.lang = string2;
        this.emotion = string3;
        this.event = string4;
        this.durations = fArray2;
    }

    public String getText() {
        return this.text;
    }

    public String[] getTokens() {
        return this.tokens;
    }

    public float[] getTimestamps() {
        return this.timestamps;
    }

    public String getLang() {
        return this.lang;
    }

    public String getEmotion() {
        return this.emotion;
    }

    public String getEvent() {
        return this.event;
    }

    public float[] getDurations() {
        return this.durations;
    }
}

