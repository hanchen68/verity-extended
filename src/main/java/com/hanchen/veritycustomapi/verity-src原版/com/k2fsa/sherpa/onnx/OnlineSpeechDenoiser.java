/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import com.k2fsa.sherpa.onnx.DenoisedAudio;
import com.k2fsa.sherpa.onnx.LibraryLoader;
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiserConfig;

public class OnlineSpeechDenoiser {
    private long ptr = 0L;

    public OnlineSpeechDenoiser(OnlineSpeechDenoiserConfig onlineSpeechDenoiserConfig) {
        LibraryLoader.maybeLoad();
        this.ptr = this.newFromFile(onlineSpeechDenoiserConfig);
        if (this.ptr == 0L) {
            throw new IllegalArgumentException("Invalid OnlineSpeechDenoiserConfig: failed to create native OnlineSpeechDenoiser");
        }
    }

    public int getSampleRate() {
        return this.getSampleRate(this.ptr);
    }

    public int getFrameShiftInSamples() {
        return this.getFrameShiftInSamples(this.ptr);
    }

    public DenoisedAudio run(float[] fArray, int n) {
        return this.run(this.ptr, fArray, n);
    }

    public DenoisedAudio flush() {
        return this.flush(this.ptr);
    }

    public void reset() {
        this.reset(this.ptr);
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

    private native int getFrameShiftInSamples(long var1);

    private native DenoisedAudio run(long var1, float[] var3, int var4);

    private native DenoisedAudio flush(long var1);

    private native void reset(long var1);

    private native long newFromFile(OnlineSpeechDenoiserConfig var1);
}

