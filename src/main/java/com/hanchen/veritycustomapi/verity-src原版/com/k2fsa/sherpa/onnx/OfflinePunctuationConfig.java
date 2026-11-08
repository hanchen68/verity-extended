/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig;

public class OfflinePunctuationConfig {
    private final OfflinePunctuationModelConfig model;

    private OfflinePunctuationConfig(Builder builder) {
        this.model = builder.model;
    }

    public static Builder builder() {
        return new Builder();
    }

    public OfflinePunctuationModelConfig getModel() {
        return this.model;
    }

    public static class Builder {
        private OfflinePunctuationModelConfig model = OfflinePunctuationModelConfig.builder().build();

        public OfflinePunctuationConfig build() {
            return new OfflinePunctuationConfig(this);
        }

        public Builder setModel(OfflinePunctuationModelConfig offlinePunctuationModelConfig) {
            this.model = offlinePunctuationModelConfig;
            return this;
        }
    }
}

