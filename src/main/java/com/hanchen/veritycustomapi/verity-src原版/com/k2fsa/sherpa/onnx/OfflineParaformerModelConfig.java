/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import com.k2fsa.sherpa.onnx.QnnConfig;

public class OfflineParaformerModelConfig {
    private final String model;
    private final QnnConfig qnnConfig;

    private OfflineParaformerModelConfig(Builder builder) {
        this.model = builder.model;
        this.qnnConfig = builder.qnnConfig;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getModel() {
        return this.model;
    }

    public QnnConfig getQnnConfig() {
        return this.qnnConfig;
    }

    public static class Builder {
        private String model = "";
        private QnnConfig qnnConfig = QnnConfig.builder().build();

        public OfflineParaformerModelConfig build() {
            return new OfflineParaformerModelConfig(this);
        }

        public Builder setModel(String string) {
            this.model = string;
            return this;
        }

        public Builder setQnnConfig(QnnConfig qnnConfig) {
            this.qnnConfig = qnnConfig;
            return this;
        }
    }
}

