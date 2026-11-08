/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

public class OfflineDiacritizationModelConfig {
    private final String cattEncoder;
    private final String cattDecoder;
    private final int numThreads;
    private final boolean debug;
    private final String provider;

    private OfflineDiacritizationModelConfig(Builder builder) {
        this.cattEncoder = builder.cattEncoder;
        this.cattDecoder = builder.cattDecoder;
        this.numThreads = builder.numThreads;
        this.debug = builder.debug;
        this.provider = builder.provider;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getCattEncoder() {
        return this.cattEncoder;
    }

    public String getCattDecoder() {
        return this.cattDecoder;
    }

    public static class Builder {
        private String cattEncoder = "";
        private String cattDecoder = "";
        private int numThreads = 1;
        private boolean debug = true;
        private String provider = "cpu";

        public OfflineDiacritizationModelConfig build() {
            return new OfflineDiacritizationModelConfig(this);
        }

        public Builder setCattEncoder(String string) {
            this.cattEncoder = string;
            return this;
        }

        public Builder setCattDecoder(String string) {
            this.cattDecoder = string;
            return this;
        }

        public Builder setNumThreads(int n) {
            this.numThreads = n;
            return this;
        }

        public Builder setDebug(boolean bl) {
            this.debug = bl;
            return this;
        }

        public Builder setProvider(String string) {
            this.provider = string;
            return this;
        }
    }
}

