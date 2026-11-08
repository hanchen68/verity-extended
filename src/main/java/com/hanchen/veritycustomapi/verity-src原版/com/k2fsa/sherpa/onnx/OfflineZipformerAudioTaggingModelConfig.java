/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

public class OfflineZipformerAudioTaggingModelConfig {
    private final String model;

    private OfflineZipformerAudioTaggingModelConfig(Builder builder) {
        this.model = builder.model;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getModel() {
        return this.model;
    }

    public static class Builder {
        private String model = "";

        public OfflineZipformerAudioTaggingModelConfig build() {
            return new OfflineZipformerAudioTaggingModelConfig(this);
        }

        public Builder setModel(String string) {
            this.model = string;
            return this;
        }
    }
}

