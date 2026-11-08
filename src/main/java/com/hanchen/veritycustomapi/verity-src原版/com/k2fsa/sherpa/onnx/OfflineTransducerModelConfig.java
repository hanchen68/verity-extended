/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import com.k2fsa.sherpa.onnx.QnnConfig;

public class OfflineTransducerModelConfig {
    private final String encoder;
    private final String decoder;
    private final String joiner;
    private final QnnConfig qnnConfig;

    private OfflineTransducerModelConfig(Builder builder) {
        this.encoder = builder.encoder;
        this.decoder = builder.decoder;
        this.joiner = builder.joiner;
        this.qnnConfig = builder.qnnConfig;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getEncoder() {
        return this.encoder;
    }

    public String getDecoder() {
        return this.decoder;
    }

    public String getJoiner() {
        return this.joiner;
    }

    public QnnConfig getQnnConfig() {
        return this.qnnConfig;
    }

    public static class Builder {
        private String encoder = "";
        private String decoder = "";
        private String joiner = "";
        private QnnConfig qnnConfig = QnnConfig.builder().build();

        public OfflineTransducerModelConfig build() {
            return new OfflineTransducerModelConfig(this);
        }

        public Builder setEncoder(String string) {
            this.encoder = string;
            return this;
        }

        public Builder setDecoder(String string) {
            this.decoder = string;
            return this;
        }

        public Builder setJoiner(String string) {
            this.joiner = string;
            return this;
        }

        public Builder setQnnConfig(QnnConfig qnnConfig) {
            this.qnnConfig = qnnConfig;
            return this;
        }
    }
}

