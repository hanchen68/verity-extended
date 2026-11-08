/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig;

public class OnlineSpeechDenoiserConfig {
    private final OfflineSpeechDenoiserModelConfig model;

    private OnlineSpeechDenoiserConfig(Builder builder) {
        this.model = builder.model;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private OfflineSpeechDenoiserModelConfig model = OfflineSpeechDenoiserModelConfig.builder().build();

        public OnlineSpeechDenoiserConfig build() {
            return new OnlineSpeechDenoiserConfig(this);
        }

        public Builder setModel(OfflineSpeechDenoiserModelConfig offlineSpeechDenoiserModelConfig) {
            this.model = offlineSpeechDenoiserModelConfig;
            return this;
        }
    }
}

