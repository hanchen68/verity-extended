/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import java.util.Arrays;

public class KeywordSpotterResult {
    private final String keyword;
    private final String[] tokens;
    private final float[] timestamps;

    public KeywordSpotterResult(String string, String[] stringArray, float[] fArray) {
        this.keyword = string;
        this.tokens = stringArray;
        this.timestamps = fArray;
    }

    public String getKeyword() {
        return this.keyword;
    }

    public String[] getTokens() {
        return this.tokens;
    }

    public float[] getTimestamps() {
        return this.timestamps;
    }

    public String toString() {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("Keyword: ").append(this.keyword).append("\n");
        stringBuilder.append("Tokens: ").append(Arrays.toString(this.tokens)).append("\n");
        stringBuilder.append("Timestamps: ").append(Arrays.toString(this.timestamps));
        return stringBuilder.toString();
    }
}

