/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import com.k2fsa.sherpa.onnx.LibraryLoader;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult;
import com.k2fsa.sherpa.onnx.OnlineStream;

public class OnlineRecognizer {
    private long ptr = 0L;

    public OnlineRecognizer(OnlineRecognizerConfig onlineRecognizerConfig) {
        LibraryLoader.maybeLoad();
        this.ptr = this.newFromFile(onlineRecognizerConfig);
        if (this.ptr == 0L) {
            throw new IllegalArgumentException("Invalid OnlineRecognizerConfig: failed to create native OnlineRecognizer");
        }
    }

    public void decode(OnlineStream onlineStream) {
        this.decode(this.ptr, onlineStream.getPtr());
    }

    public void decode(OnlineStream[] onlineStreamArray) {
        if (onlineStreamArray == null || onlineStreamArray.length == 0) {
            throw new IllegalArgumentException("Stream array must be non-empty");
        }
        long[] lArray = new long[onlineStreamArray.length];
        for (int i = 0; i < onlineStreamArray.length; ++i) {
            lArray[i] = onlineStreamArray[i].getPtr();
        }
        this.decodeStreams(this.ptr, lArray);
    }

    public boolean isReady(OnlineStream onlineStream) {
        return this.isReady(this.ptr, onlineStream.getPtr());
    }

    public boolean isEndpoint(OnlineStream onlineStream) {
        return this.isEndpoint(this.ptr, onlineStream.getPtr());
    }

    public void reset(OnlineStream onlineStream) {
        this.reset(this.ptr, onlineStream.getPtr());
    }

    public OnlineStream createStream() {
        long l = this.createStream(this.ptr, "");
        return new OnlineStream(l);
    }

    protected void finalize() throws Throwable {
        this.release();
    }

    protected void close() {
        if (this.ptr == 0L) {
            return;
        }
        this.delete(this.ptr);
        this.ptr = 0L;
    }

    public void release() {
        this.close();
    }

    public OnlineRecognizerResult getResult(OnlineStream onlineStream) {
        return this.getResult(this.ptr, onlineStream.getPtr());
    }

    private native void delete(long var1);

    private native long newFromFile(OnlineRecognizerConfig var1);

    private native long createStream(long var1, String var3);

    private native void reset(long var1, long var3);

    private native void decode(long var1, long var3);

    private native void decodeStreams(long var1, long[] var3);

    private native boolean isEndpoint(long var1, long var3);

    private native boolean isReady(long var1, long var3);

    private native OnlineRecognizerResult getResult(long var1, long var3);
}

