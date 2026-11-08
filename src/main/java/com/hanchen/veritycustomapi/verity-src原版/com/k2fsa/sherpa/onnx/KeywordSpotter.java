/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotterResult;
import com.k2fsa.sherpa.onnx.LibraryLoader;
import com.k2fsa.sherpa.onnx.OnlineStream;

public class KeywordSpotter {
    private long ptr = 0L;

    public KeywordSpotter(KeywordSpotterConfig keywordSpotterConfig) {
        LibraryLoader.maybeLoad();
        this.ptr = this.newFromFile(keywordSpotterConfig);
        if (this.ptr == 0L) {
            throw new IllegalArgumentException("Invalid KeywordSpotterConfig: failed to create native KeywordSpotter");
        }
    }

    public OnlineStream createStream(String string) {
        long l = this.createStream(this.ptr, string);
        return new OnlineStream(l);
    }

    public OnlineStream createStream() {
        long l = this.createStream(this.ptr, "");
        return new OnlineStream(l);
    }

    public void decode(OnlineStream onlineStream) {
        this.decode(this.ptr, onlineStream.getPtr());
    }

    public void reset(OnlineStream onlineStream) {
        this.reset(this.ptr, onlineStream.getPtr());
    }

    public boolean isReady(OnlineStream onlineStream) {
        return this.isReady(this.ptr, onlineStream.getPtr());
    }

    public KeywordSpotterResult getResult(OnlineStream onlineStream) {
        return this.getResult(this.ptr, onlineStream.getPtr());
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

    private native long newFromFile(KeywordSpotterConfig var1);

    private native void delete(long var1);

    private native long createStream(long var1, String var3);

    private native void decode(long var1, long var3);

    private native void reset(long var1, long var3);

    private native boolean isReady(long var1, long var3);

    private native KeywordSpotterResult getResult(long var1, long var3);
}

