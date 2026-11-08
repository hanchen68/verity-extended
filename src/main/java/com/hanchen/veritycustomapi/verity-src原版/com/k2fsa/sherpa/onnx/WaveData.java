/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import java.util.Arrays;

public class WaveData {
    private final float[] samples;
    private final int sampleRate;

    public WaveData(float[] fArray, int n) {
        this.samples = fArray;
        this.sampleRate = n;
    }

    public float[] getSamples() {
        return this.samples;
    }

    public int getSampleRate() {
        return this.sampleRate;
    }

    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || this.getClass() != object.getClass()) {
            return false;
        }
        WaveData waveData = (WaveData)object;
        return this.sampleRate == waveData.sampleRate && Arrays.equals(this.samples, waveData.samples);
    }

    public int hashCode() {
        int n = Arrays.hashCode(this.samples);
        n = 31 * n + this.sampleRate;
        return n;
    }
}

