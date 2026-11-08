/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import com.k2fsa.sherpa.onnx.LibraryLoader;
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationCallback;
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationSegment;

public class OfflineSpeakerDiarization {
    private long ptr = 0L;

    public OfflineSpeakerDiarization(OfflineSpeakerDiarizationConfig offlineSpeakerDiarizationConfig) {
        LibraryLoader.maybeLoad();
        this.ptr = this.newFromFile(offlineSpeakerDiarizationConfig);
        if (this.ptr == 0L) {
            throw new IllegalArgumentException("Invalid OfflineSpeakerDiarizationConfig: failed to create native OfflineSpeakerDiarization");
        }
    }

    public int getSampleRate() {
        return this.getSampleRate(this.ptr);
    }

    public void setConfig(OfflineSpeakerDiarizationConfig offlineSpeakerDiarizationConfig) {
        this.setConfig(this.ptr, offlineSpeakerDiarizationConfig);
    }

    public OfflineSpeakerDiarizationSegment[] process(float[] fArray) {
        return this.process(this.ptr, fArray);
    }

    public OfflineSpeakerDiarizationSegment[] processWithCallback(float[] fArray, OfflineSpeakerDiarizationCallback offlineSpeakerDiarizationCallback) {
        return this.processWithCallback(this.ptr, fArray, offlineSpeakerDiarizationCallback, 0L);
    }

    public OfflineSpeakerDiarizationSegment[] processWithCallback(float[] fArray, OfflineSpeakerDiarizationCallback offlineSpeakerDiarizationCallback, long l) {
        return this.processWithCallback(this.ptr, fArray, offlineSpeakerDiarizationCallback, l);
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

    private native int getSampleRate(long var1);

    private native void delete(long var1);

    private native long newFromFile(OfflineSpeakerDiarizationConfig var1);

    private native void setConfig(long var1, OfflineSpeakerDiarizationConfig var3);

    private native OfflineSpeakerDiarizationSegment[] process(long var1, float[] var3);

    private native OfflineSpeakerDiarizationSegment[] processWithCallback(long var1, float[] var3, OfflineSpeakerDiarizationCallback var4, long var5);
}

