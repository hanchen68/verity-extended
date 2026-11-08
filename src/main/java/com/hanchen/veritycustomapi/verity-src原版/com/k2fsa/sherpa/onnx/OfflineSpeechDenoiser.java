/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import com.k2fsa.sherpa.onnx.DenoisedAudio;
import com.k2fsa.sherpa.onnx.LibraryLoader;
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserConfig;

public class OfflineSpeechDenoiser {
    private long ptr = 0L;

    public OfflineSpeechDenoiser(OfflineSpeechDenoiserConfig offlineSpeechDenoiserConfig) {
        LibraryLoader.maybeLoad();
        this.ptr = this.newFromFile(offlineSpeechDenoiserConfig);
        if (this.ptr == 0L) {
            throw new IllegalArgumentException("Invalid OfflineSpeechDenoiserConfig: failed to create native OfflineSpeechDenoiser");
        }
    }

    public int getSampleRate() {
        return this.getSampleRate(this.ptr);
    }

    public DenoisedAudio run(float[] fArray, int n) {
        return this.run(this.ptr, fArray, n);
    }

    protected void finalize() throws Throwable {
        this.release();
    }

    public void release() {
        if (this.ptr == 0L) {
            return;
        }
        this.delete(this.ptr);
        this.ptr = 0L;
    }

    private native void delete(long var1);

    private native int getSampleRate(long var1);

    private native DenoisedAudio run(long var1, float[] var3, int var4);

    private native long newFromFile(OfflineSpeechDenoiserConfig var1);
}

