/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import java.util.Map;

public class GenerationConfig {
    private float silenceScale = 0.2f;
    private float speed = 1.0f;
    private int sid = 0;
    private float[] referenceAudio = null;
    private int referenceSampleRate = 0;
    private String referenceText = null;
    private int numSteps = 5;
    private Map<String, String> extra = null;

    public float getSilenceScale() {
        return this.silenceScale;
    }

    public float getSpeed() {
        return this.speed;
    }

    public int getSid() {
        return this.sid;
    }

    public float[] getReferenceAudio() {
        return this.referenceAudio;
    }

    public int getReferenceSampleRate() {
        return this.referenceSampleRate;
    }

    public String getReferenceText() {
        return this.referenceText;
    }

    public int getNumSteps() {
        return this.numSteps;
    }

    public Map<String, String> getExtra() {
        return this.extra;
    }

    public void setSilenceScale(float f) {
        this.silenceScale = f;
    }

    public void setSpeed(float f) {
        this.speed = f;
    }

    public void setSid(int n) {
        this.sid = n;
    }

    public void setReferenceAudio(float[] fArray) {
        this.referenceAudio = fArray;
    }

    public void setReferenceSampleRate(int n) {
        this.referenceSampleRate = n;
    }

    public void setReferenceText(String string) {
        this.referenceText = string;
    }

    public void setNumSteps(int n) {
        this.numSteps = n;
    }

    public void setExtra(Map<String, String> map) {
        this.extra = map;
    }

    public String toString() {
        return "GenerationConfig{silenceScale=" + this.silenceScale + ", speed=" + this.speed + ", sid=" + this.sid + ", referenceAudioLength=" + (this.referenceAudio != null ? this.referenceAudio.length : 0) + ", referenceSampleRate=" + this.referenceSampleRate + ", referenceText='" + this.referenceText + '\'' + ", numSteps=" + this.numSteps + ", extra=" + this.extra + '}';
    }
}

