/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import com.k2fsa.sherpa.onnx.LibraryLoader;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;

public class SpeakerEmbeddingExtractor {
    private long ptr = 0L;

    public SpeakerEmbeddingExtractor(SpeakerEmbeddingExtractorConfig speakerEmbeddingExtractorConfig) {
        LibraryLoader.maybeLoad();
        this.ptr = this.newFromFile(speakerEmbeddingExtractorConfig);
        if (this.ptr == 0L) {
            throw new IllegalArgumentException("Invalid SpeakerEmbeddingExtractorConfig: failed to create native SpeakerEmbeddingExtractor");
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

    public OnlineStream createStream() {
        long l = this.createStream(this.ptr);
        return new OnlineStream(l);
    }

    public boolean isReady(OnlineStream onlineStream) {
        return this.isReady(this.ptr, onlineStream.getPtr());
    }

    public float[] compute(OnlineStream onlineStream) {
        return this.compute(this.ptr, onlineStream.getPtr());
    }

    public int getDim() {
        return this.dim(this.ptr);
    }

    private native void delete(long var1);

    private native long newFromFile(SpeakerEmbeddingExtractorConfig var1);

    private native long createStream(long var1);

    private native boolean isReady(long var1, long var3);

    private native float[] compute(long var1, long var3);

    private native int dim(long var1);
}

