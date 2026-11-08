/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import com.k2fsa.sherpa.onnx.OnlinePunctuationModelConfig;

public class OnlinePunctuationConfig {
    private final OnlinePunctuationModelConfig model;

    private OnlinePunctuationConfig(Builder builder) {
        this.model = builder.model;
    }

    public static Builder builder() {
        return new Builder();
    }

    public OnlinePunctuationModelConfig getModel() {
        return this.model;
    }

    public static class Builder {
        private OnlinePunctuationModelConfig model = OnlinePunctuationModelConfig.builder().build();

        public OnlinePunctuationConfig build() {
            return new OnlinePunctuationConfig(this);
        }

        public Builder setModel(OnlinePunctuationModelConfig onlinePunctuationModelConfig) {
            this.model = onlinePunctuationModelConfig;
            return this;
        }
    }
}

