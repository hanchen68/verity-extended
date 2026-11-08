/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

public class OnlineParaformerModelConfig {
    private final String encoder;
    private final String decoder;

    private OnlineParaformerModelConfig(Builder builder) {
        this.encoder = builder.encoder;
        this.decoder = builder.decoder;
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

    public static class Builder {
        private String encoder = "";
        private String decoder = "";

        public OnlineParaformerModelConfig build() {
            return new OnlineParaformerModelConfig(this);
        }

        public Builder setEncoder(String string) {
            this.encoder = string;
            return this;
        }

        public Builder setDecoder(String string) {
            this.decoder = string;
            return this;
        }
    }
}

