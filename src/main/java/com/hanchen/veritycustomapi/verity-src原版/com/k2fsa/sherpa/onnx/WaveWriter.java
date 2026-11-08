/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

public class WaveWriter {
    public static boolean write(String string, float[] fArray, int n) {
        WaveWriter waveWriter = new WaveWriter();
        return waveWriter.writeWaveToFile(string, fArray, n);
    }

    private native boolean writeWaveToFile(String var1, float[] var2, int var3);
}

