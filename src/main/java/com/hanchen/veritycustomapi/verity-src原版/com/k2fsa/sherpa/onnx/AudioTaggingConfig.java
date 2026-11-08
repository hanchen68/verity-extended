/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import com.k2fsa.sherpa.onnx.AudioTaggingModelConfig;

public class AudioTaggingConfig {
    private final AudioTaggingModelConfig model;
    private final String labels;
    private final int topK;

    private AudioTaggingConfig(Builder builder) {
        this.model = builder.model;
        this.labels = builder.labels;
        this.topK = builder.topK;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private AudioTaggingModelConfig model = AudioTaggingModelConfig.builder().build();
        private String labels = "";
        private int topK = 5;

        public AudioTaggingConfig build() {
            return new AudioTaggingConfig(this);
        }

        public Builder setModel(AudioTaggingModelConfig audioTaggingModelConfig) {
            this.model = audioTaggingModelConfig;
            return this;
        }

        public Builder setLabels(String string) {
            this.labels = string;
            return this;
        }

        public Builder setTopK(int n) {
            this.topK = n;
            return this;
        }
    }
}

