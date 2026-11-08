/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig;

public class OfflineSpeechDenoiserConfig {
    private final OfflineSpeechDenoiserModelConfig model;

    private OfflineSpeechDenoiserConfig(Builder builder) {
        this.model = builder.model;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private OfflineSpeechDenoiserModelConfig model = OfflineSpeechDenoiserModelConfig.builder().build();

        public OfflineSpeechDenoiserConfig build() {
            return new OfflineSpeechDenoiserConfig(this);
        }

        public Builder setModel(OfflineSpeechDenoiserModelConfig offlineSpeechDenoiserModelConfig) {
            this.model = offlineSpeechDenoiserModelConfig;
            return this;
        }
    }
}

