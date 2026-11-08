/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import com.k2fsa.sherpa.onnx.AudioEvent;
import com.k2fsa.sherpa.onnx.AudioTaggingConfig;
import com.k2fsa.sherpa.onnx.LibraryLoader;
import com.k2fsa.sherpa.onnx.OfflineStream;

public class AudioTagging {
    private long ptr = 0L;

    public AudioTagging(AudioTaggingConfig audioTaggingConfig) {
        LibraryLoader.maybeLoad();
        this.ptr = this.newFromFile(audioTaggingConfig);
        if (this.ptr == 0L) {
            throw new IllegalArgumentException("Invalid AudioTaggingConfig: failed to create native AudioTagging");
        }
    }

    public OfflineStream createStream() {
        long l = this.createStream(this.ptr);
        return new OfflineStream(l);
    }

    public AudioEvent[] compute(OfflineStream offlineStream) {
        return this.compute(offlineStream, -1);
    }

    public AudioEvent[] compute(OfflineStream offlineStream, int n) {
        return this.compute(this.ptr, offlineStream.getPtr(), n);
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

    private native long newFromFile(AudioTaggingConfig var1);

    private native long createStream(long var1);

    private native AudioEvent[] compute(long var1, long var3, int var5);
}

