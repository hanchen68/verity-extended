/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

public class FeatureConfig {
    private final int sampleRate;
    private final int featureDim;
    private final float dither;

    private FeatureConfig(Builder builder) {
        this.sampleRate = builder.sampleRate;
        this.featureDim = builder.featureDim;
        this.dither = builder.dither;
    }

    public static Builder builder() {
        return new Builder();
    }

    public int getSampleRate() {
        return this.sampleRate;
    }

    public int getFeatureDim() {
        return this.featureDim;
    }

    public float getDither() {
        return this.dither;
    }

    public static class Builder {
        private int sampleRate = 16000;
        private int featureDim = 80;
        private float dither = 0.0f;

        public FeatureConfig build() {
            return new FeatureConfig(this);
        }

        public Builder setSampleRate(int n) {
            this.sampleRate = n;
            return this;
        }

        public Builder setFeatureDim(int n) {
            this.featureDim = n;
            return this;
        }

        public Builder setDither(float f) {
            this.dither = f;
            return this;
        }
    }
}

