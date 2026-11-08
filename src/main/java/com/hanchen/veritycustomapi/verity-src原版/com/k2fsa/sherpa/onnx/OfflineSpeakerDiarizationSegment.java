/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

public class OfflineSpeakerDiarizationSegment {
    private final float start;
    private final float end;
    private final int speaker;

    public OfflineSpeakerDiarizationSegment(float f, float f2, int n) {
        this.start = f;
        this.end = f2;
        this.speaker = n;
    }

    public float getStart() {
        return this.start;
    }

    public float getEnd() {
        return this.end;
    }

    public int getSpeaker() {
        return this.speaker;
    }
}

