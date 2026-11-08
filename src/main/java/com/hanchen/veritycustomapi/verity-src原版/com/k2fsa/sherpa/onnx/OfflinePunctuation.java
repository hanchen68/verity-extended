/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import com.k2fsa.sherpa.onnx.LibraryLoader;
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig;

public class OfflinePunctuation {
    private long ptr = 0L;

    public OfflinePunctuation(OfflinePunctuationConfig offlinePunctuationConfig) {
        LibraryLoader.maybeLoad();
        this.ptr = this.newFromFile(offlinePunctuationConfig);
        if (this.ptr == 0L) {
            throw new IllegalArgumentException("Invalid OfflinePunctuationConfig: failed to create native OfflinePunctuation");
        }
    }

    public String addPunctuation(String string) {
        return this.addPunctuation(this.ptr, string);
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

    private native long newFromFile(OfflinePunctuationConfig var1);

    private native String addPunctuation(long var1, String var3);
}

