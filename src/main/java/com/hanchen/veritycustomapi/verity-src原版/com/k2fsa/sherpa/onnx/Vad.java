/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import com.k2fsa.sherpa.onnx.LibraryLoader;
import com.k2fsa.sherpa.onnx.SpeechSegment;
import com.k2fsa.sherpa.onnx.VadModelConfig;

public class Vad {
    private long ptr = 0L;

    public Vad(VadModelConfig vadModelConfig) {
        LibraryLoader.maybeLoad();
        this.ptr = this.newFromFile(vadModelConfig);
        if (this.ptr == 0L) {
            throw new IllegalArgumentException("Invalid VadModelConfig: failed to create native Vad");
        }
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

    public void acceptWaveform(float[] fArray) {
        this.acceptWaveform(this.ptr, fArray);
    }

    public float compute(float[] fArray) {
        return this.compute(this.ptr, fArray);
    }

    public boolean empty() {
        return this.empty(this.ptr);
    }

    public void pop() {
        this.pop(this.ptr);
    }

    public void clear() {
        this.clear(this.ptr);
    }

    public void reset() {
        this.reset(this.ptr);
    }

    public void flush() {
        this.flush(this.ptr);
    }

    public SpeechSegment front() {
        return this.front(this.ptr);
    }

    public boolean isSpeechDetected() {
        return this.isSpeechDetected(this.ptr);
    }

    private native void delete(long var1);

    private native long newFromFile(VadModelConfig var1);

    private native void acceptWaveform(long var1, float[] var3);

    private native float compute(long var1, float[] var3);

    private native boolean empty(long var1);

    private native void pop(long var1);

    private native void clear(long var1);

    private native SpeechSegment front(long var1);

    private native boolean isSpeechDetected(long var1);

    private native void reset(long var1);

    private native void flush(long var1);
}

