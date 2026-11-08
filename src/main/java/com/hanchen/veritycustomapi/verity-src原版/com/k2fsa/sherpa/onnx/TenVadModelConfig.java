/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

public class TenVadModelConfig {
    private final String model;
    private final float threshold;
    private final float minSilenceDuration;
    private final float minSpeechDuration;
    private final int windowSize;
    private final float maxSpeechDuration;

    private TenVadModelConfig(Builder builder) {
        this.model = builder.model;
        this.threshold = builder.threshold;
        this.minSilenceDuration = builder.minSilenceDuration;
        this.minSpeechDuration = builder.minSpeechDuration;
        this.windowSize = builder.windowSize;
        this.maxSpeechDuration = builder.maxSpeechDuration;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getModel() {
        return this.model;
    }

    public float getThreshold() {
        return this.threshold;
    }

    public float getMinSilenceDuration() {
        return this.minSilenceDuration;
    }

    public float getMinSpeechDuration() {
        return this.minSpeechDuration;
    }

    public int getWindowSize() {
        return this.windowSize;
    }

    public float getMaxSpeechDuration() {
        return this.maxSpeechDuration;
    }

    public static class Builder {
        private String model = "";
        private float threshold = 0.5f;
        private float minSilenceDuration = 0.25f;
        private float minSpeechDuration = 0.25f;
        private int windowSize = 256;
        private float maxSpeechDuration = 5.0f;

        public TenVadModelConfig build() {
            return new TenVadModelConfig(this);
        }

        public Builder setModel(String string) {
            this.model = string;
            return this;
        }

        public Builder setThreshold(float f) {
            this.threshold = f;
            return this;
        }

        public Builder setMinSilenceDuration(float f) {
            this.minSilenceDuration = f;
            return this;
        }

        public Builder setMinSpeechDuration(float f) {
            this.minSpeechDuration = f;
            return this;
        }

        public Builder setWindowSize(int n) {
            this.windowSize = n;
            return this;
        }

        public Builder setMaxSpeechDuration(float f) {
            this.maxSpeechDuration = f;
            return this;
        }
    }
}

