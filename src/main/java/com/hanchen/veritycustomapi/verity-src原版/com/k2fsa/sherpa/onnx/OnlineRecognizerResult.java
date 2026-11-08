/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

public class OnlineRecognizerResult {
    private final String text;
    private final String[] tokens;
    private final float[] timestamps;
    private final float[] ysProbs;

    public OnlineRecognizerResult(String string, String[] stringArray, float[] fArray, float[] fArray2) {
        this.text = string;
        this.tokens = stringArray;
        this.timestamps = fArray;
        this.ysProbs = fArray2;
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

    public float[] getYsProbs() {
        return this.ysProbs;
    }
}

