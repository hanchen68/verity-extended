/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import com.k2fsa.sherpa.onnx.LibraryLoader;
import com.k2fsa.sherpa.onnx.OfflineDiacritizationConfig;

public class OfflineDiacritization {
    private long ptr = 0L;

    public OfflineDiacritization(OfflineDiacritizationConfig offlineDiacritizationConfig) {
        LibraryLoader.maybeLoad();
        this.ptr = this.newFromFile(offlineDiacritizationConfig);
        if (this.ptr == 0L) {
            throw new IllegalArgumentException("Invalid OfflineDiacritizationConfig: failed to create native OfflineDiacritization");
        }
    }

    public String addDiacritics(String string) {
        return this.addDiacritics(this.ptr, string);
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

    private native long newFromFile(OfflineDiacritizationConfig var1);

    private native String addDiacritics(long var1, String var3);
}

