/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import com.k2fsa.sherpa.onnx.LibraryLoader;

public class OfflineStream {
    private long ptr = 0L;

    public OfflineStream() {
        LibraryLoader.maybeLoad();
        this.ptr = 0L;
    }

    public OfflineStream(long l) {
        this.ptr = l;
    }

    public long getPtr() {
        return this.ptr;
    }

    public void setPtr(long l) {
        this.ptr = l;
    }

    public void acceptWaveform(float[] fArray, int n) {
        this.acceptWaveform(this.ptr, fArray, n);
    }

    public void setOption(String string, String string2) {
        this.setOption(this.ptr, string, string2);
    }

    public String getOption(String string) {
        return this.getOption(this.ptr, string);
    }

    public boolean hasOption(String string) {
        return this.hasOption(this.ptr, string);
    }

    public void release() {
        if (this.ptr == 0L) {
            return;
        }
        this.delete(this.ptr);
        this.ptr = 0L;
    }

    protected void finalize() throws Throwable {
        this.release();
        super.finalize();
    }

    private native void acceptWaveform(long var1, float[] var3, int var4);

    private native void setOption(long var1, String var3, String var4);

    private native String getOption(long var1, String var3);

    private native boolean hasOption(long var1, String var3);

    private native void delete(long var1);
}

