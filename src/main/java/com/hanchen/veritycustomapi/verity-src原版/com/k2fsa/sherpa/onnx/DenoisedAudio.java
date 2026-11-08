/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import com.k2fsa.sherpa.onnx.LibraryLoader;

public class DenoisedAudio {
    private final float[] samples;
    private final int sampleRate;

    public DenoisedAudio(float[] fArray, int n) {
        LibraryLoader.maybeLoad();
        this.samples = fArray;
        this.sampleRate = n;
    }

    public int getSampleRate() {
        return this.sampleRate;
    }

    public float[] getSamples() {
        return this.samples;
    }

    public boolean save(String string) {
        return this.saveImpl(string, this.samples, this.sampleRate);
    }

    private native boolean saveImpl(String var1, float[] var2, int var3);
}

