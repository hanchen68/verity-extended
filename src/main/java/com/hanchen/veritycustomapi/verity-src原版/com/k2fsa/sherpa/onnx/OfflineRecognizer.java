/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import com.k2fsa.sherpa.onnx.LibraryLoader;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineStream;

public class OfflineRecognizer {
    private long ptr = 0L;
    private final OfflineRecognizerConfig config;

    public OfflineRecognizer(OfflineRecognizerConfig offlineRecognizerConfig) {
        LibraryLoader.maybeLoad();
        this.ptr = this.newFromFile(offlineRecognizerConfig);
        if (this.ptr == 0L) {
            throw new IllegalArgumentException("Invalid OfflineRecognizerConfig: failed to create native OfflineRecognizer");
        }
        this.config = offlineRecognizerConfig;
    }

    public void setConfig(OfflineRecognizerConfig offlineRecognizerConfig) {
        this.setConfig(this.ptr, offlineRecognizerConfig);
    }

    public OfflineRecognizerConfig getConfig() {
        return this.config;
    }

    public void decode(OfflineStream offlineStream) {
        this.decode(this.ptr, offlineStream.getPtr());
    }

    public void decode(OfflineStream[] offlineStreamArray) {
        long[] lArray = new long[offlineStreamArray.length];
        for (int i = 0; i < offlineStreamArray.length; ++i) {
            lArray[i] = offlineStreamArray[i].getPtr();
        }
        this.decodeStreams(this.ptr, lArray);
    }

    public OfflineStream createStream() {
        long l = this.createStream(this.ptr);
        return new OfflineStream(l);
    }

    public OfflineStream createStream(String string) {
        long l = this.createStreamWithHotwords(this.ptr, string);
        return new OfflineStream(l);
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

    public OfflineRecognizerResult getResult(OfflineStream offlineStream) {
        return this.getResult(offlineStream.getPtr());
    }

    private native void delete(long var1);

    private native long newFromFile(OfflineRecognizerConfig var1);

    private native long createStream(long var1);

    private native long createStreamWithHotwords(long var1, String var3);

    private native void decode(long var1, long var3);

    private native void setConfig(long var1, OfflineRecognizerConfig var3);

    private native void decodeStreams(long var1, long[] var3);

    private native OfflineRecognizerResult getResult(long var1);
}

