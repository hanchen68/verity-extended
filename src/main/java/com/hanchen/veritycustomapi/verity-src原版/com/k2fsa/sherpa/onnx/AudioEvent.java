/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

public class AudioEvent {
    private String name = "";
    private int index = 0;
    private float prob = 0.0f;

    public AudioEvent(String string, int n, float f) {
        this.name = string;
        this.index = n;
        this.prob = f;
    }

    public String getName() {
        return this.name;
    }

    public int getIndex() {
        return this.index;
    }

    public float getProb() {
        return this.prob;
    }

    public String toString() {
        return String.format("AudioEven(name=%s, index=%d, prob=%.3f)\n", this.name, this.index, Float.valueOf(this.prob));
    }
}

