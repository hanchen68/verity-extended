/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import com.k2fsa.sherpa.onnx.LibraryLoader;
import com.k2fsa.sherpa.onnx.OnlinePunctuationConfig;

public class OnlinePunctuation {
    private long ptr = 0L;

    public OnlinePunctuation(OnlinePunctuationConfig onlinePunctuationConfig) {
        LibraryLoader.maybeLoad();
        this.ptr = this.newFromFile(onlinePunctuationConfig);
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

    private native long newFromFile(OnlinePunctuationConfig var1);

    private native String addPunctuation(long var1, String var3);
}

