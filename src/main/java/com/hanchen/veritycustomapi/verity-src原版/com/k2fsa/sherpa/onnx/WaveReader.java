/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import com.k2fsa.sherpa.onnx.LibraryLoader;
import com.k2fsa.sherpa.onnx.WaveData;

public class WaveReader {
    private WaveData data;

    public WaveReader(String string) {
        LibraryLoader.maybeLoad();
        this.data = this.readWaveFromFile(string);
    }

    public int getSampleRate() {
        return this.data.getSampleRate();
    }

    public float[] getSamples() {
        return this.data.getSamples();
    }

    private native WaveData readWaveFromFile(String var1);
}

