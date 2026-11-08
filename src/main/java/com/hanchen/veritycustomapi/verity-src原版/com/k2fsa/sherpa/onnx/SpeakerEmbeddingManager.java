/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import com.k2fsa.sherpa.onnx.LibraryLoader;

public class SpeakerEmbeddingManager {
    private long ptr = 0L;

    public SpeakerEmbeddingManager(int n) {
        LibraryLoader.maybeLoad();
        this.ptr = this.create(n);
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

    public boolean add(String string, float[] fArray) {
        return this.add(this.ptr, string, fArray);
    }

    public boolean add(String string, float[][] fArray) {
        return this.addList(this.ptr, string, fArray);
    }

    public boolean remove(String string) {
        return this.remove(this.ptr, string);
    }

    public String search(float[] fArray, float f) {
        return this.search(this.ptr, fArray, f);
    }

    public boolean verify(String string, float[] fArray, float f) {
        return this.verify(this.ptr, string, fArray, f);
    }

    public boolean contains(String string) {
        return this.contains(this.ptr, string);
    }

    public int getNumSpeakers() {
        return this.numSpeakers(this.ptr);
    }

    public String[] getAllSpeakerNames() {
        return this.allSpeakerNames(this.ptr);
    }

    private native long create(int var1);

    private native void delete(long var1);

    private native boolean add(long var1, String var3, float[] var4);

    private native boolean addList(long var1, String var3, float[][] var4);

    private native boolean remove(long var1, String var3);

    private native String search(long var1, float[] var3, float var4);

    private native boolean verify(long var1, String var3, float[] var4, float var5);

    private native boolean contains(long var1, String var3);

    private native int numSpeakers(long var1);

    private native String[] allSpeakerNames(long var1);
}

