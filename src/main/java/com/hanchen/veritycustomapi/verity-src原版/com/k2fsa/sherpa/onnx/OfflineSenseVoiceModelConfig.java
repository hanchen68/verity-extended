/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import com.k2fsa.sherpa.onnx.QnnConfig;

public class OfflineSenseVoiceModelConfig {
    private final String model;
    private final String language;
    private final boolean useInverseTextNormalization;
    private final QnnConfig qnnConfig;

    private OfflineSenseVoiceModelConfig(Builder builder) {
        this.model = builder.model;
        this.language = builder.language;
        this.useInverseTextNormalization = builder.useInverseTextNormalization;
        this.qnnConfig = builder.qnnConfig;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getModel() {
        return this.model;
    }

    public String getLanguage() {
        return this.language;
    }

    public boolean getUseInverseTextNormalization() {
        return this.useInverseTextNormalization;
    }

    public QnnConfig getQnnConfig() {
        return this.qnnConfig;
    }

    public static class Builder {
        private String model = "";
        private String language = "";
        private boolean useInverseTextNormalization = true;
        private QnnConfig qnnConfig = QnnConfig.builder().build();

        public OfflineSenseVoiceModelConfig build() {
            return new OfflineSenseVoiceModelConfig(this);
        }

        public Builder setModel(String string) {
            this.model = string;
            return this;
        }

        public Builder setLanguage(String string) {
            this.language = string;
            return this;
        }

        public Builder setInverseTextNormalization(boolean bl) {
            this.useInverseTextNormalization = bl;
            return this;
        }

        public Builder setQnnConfig(QnnConfig qnnConfig) {
            this.qnnConfig = qnnConfig;
            return this;
        }
    }
}

