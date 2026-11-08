/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.GenerationConfig;
import com.k2fsa.sherpa.onnx.LibraryLoader;
import com.k2fsa.sherpa.onnx.OfflineTtsCallback;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import java.util.function.Consumer;

public class OfflineTts {
    private long ptr = 0L;

    public OfflineTts(OfflineTtsConfig offlineTtsConfig) {
        LibraryLoader.maybeLoad();
        this.ptr = this.newFromFile(offlineTtsConfig);
        if (this.ptr == 0L) {
            throw new IllegalArgumentException("Invalid OfflineTtsConfig: failed to create native OfflineTts");
        }
    }

    public int getSampleRate() {
        return this.getSampleRate(this.ptr);
    }

    public int getNumSpeakers() {
        return this.getNumSpeakers(this.ptr);
    }

    public GeneratedAudio generate(String string) {
        return this.generate(string, 0, 1.0f);
    }

    public GeneratedAudio generate(String string, int n) {
        return this.generate(string, n, 1.0f);
    }

    public GeneratedAudio generate(String string, int n, float f) {
        return this.generateImpl(this.ptr, string, n, f);
    }

    public GeneratedAudio generateWithCallback(String string, OfflineTtsCallback offlineTtsCallback) {
        return this.generateWithCallback(string, 0, 1.0f, offlineTtsCallback);
    }

    public GeneratedAudio generateWithCallback(String string, Consumer<float[]> consumer) {
        return this.generateWithCallback(string, 0, 1.0f, consumer);
    }

    public GeneratedAudio generateWithCallback(String string, int n, OfflineTtsCallback offlineTtsCallback) {
        return this.generateWithCallback(string, n, 1.0f, offlineTtsCallback);
    }

    public GeneratedAudio generateWithCallback(String string, int n, Consumer<float[]> consumer) {
        return this.generateWithCallback(string, n, 1.0f, consumer);
    }

    public GeneratedAudio generateWithCallback(String string, int n, float f, OfflineTtsCallback offlineTtsCallback) {
        return this.generateWithCallbackImpl(this.ptr, string, n, f, offlineTtsCallback);
    }

    public GeneratedAudio generateWithCallback(String string, int n, float f, Consumer<float[]> consumer) {
        OfflineTtsCallback offlineTtsCallback = fArray -> {
            consumer.accept(fArray);
            return 1;
        };
        return this.generateWithCallback(string, n, f, offlineTtsCallback);
    }

    public GeneratedAudio generateWithConfigAndCallback(String string, GenerationConfig generationConfig, OfflineTtsCallback offlineTtsCallback) {
        return this.generateWithConfigImpl(this.ptr, string, generationConfig, offlineTtsCallback);
    }

    public GeneratedAudio generateWithConfigAndCallback(String string, GenerationConfig generationConfig, Consumer<float[]> consumer) {
        OfflineTtsCallback offlineTtsCallback = fArray -> {
            consumer.accept(fArray);
            return 1;
        };
        return this.generateWithConfigAndCallback(string, generationConfig, offlineTtsCallback);
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

    private native int getNumSpeakers(long var1);

    private native GeneratedAudio generateImpl(long var1, String var3, int var4, float var5);

    private native GeneratedAudio generateWithCallbackImpl(long var1, String var3, int var4, float var5, OfflineTtsCallback var6);

    private native GeneratedAudio generateWithConfigImpl(long var1, String var3, GenerationConfig var4, OfflineTtsCallback var5);

    private native long newFromFile(OfflineTtsConfig var1);
}

