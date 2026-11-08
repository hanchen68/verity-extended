/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

public class SpeechSegment {
    private final int start;
    private final float[] samples;

    public SpeechSegment(int n, float[] fArray) {
        this.start = n;
        this.samples = fArray;
    }

    public int getStart() {
        return this.start;
    }

    public float[] getSamples() {
        return this.samples;
    }
}

