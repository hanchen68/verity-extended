/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

public class OfflineQwen3AsrModelConfig {
    private final String convFrontend;
    private final String encoder;
    private final String decoder;
    private final String tokenizer;
    private final String hotwords;
    private final int maxTotalLen;
    private final int maxNewTokens;
    private final float temperature;
    private final float topP;
    private final int seed;

    private OfflineQwen3AsrModelConfig(Builder builder) {
        this.convFrontend = builder.convFrontend;
        this.encoder = builder.encoder;
        this.decoder = builder.decoder;
        this.tokenizer = builder.tokenizer;
        this.hotwords = builder.hotwords;
        this.maxTotalLen = builder.maxTotalLen;
        this.maxNewTokens = builder.maxNewTokens;
        this.temperature = builder.temperature;
        this.topP = builder.topP;
        this.seed = builder.seed;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getConvFrontend() {
        return this.convFrontend;
    }

    public String getEncoder() {
        return this.encoder;
    }

    public String getDecoder() {
        return this.decoder;
    }

    public String getTokenizer() {
        return this.tokenizer;
    }

    public String getHotwords() {
        return this.hotwords;
    }

    public int getMaxTotalLen() {
        return this.maxTotalLen;
    }

    public int getMaxNewTokens() {
        return this.maxNewTokens;
    }

    public float getTemperature() {
        return this.temperature;
    }

    public float getTopP() {
        return this.topP;
    }

    public int getSeed() {
        return this.seed;
    }

    public static class Builder {
        private String convFrontend = "";
        private String encoder = "";
        private String decoder = "";
        private String tokenizer = "";
        private String hotwords = "";
        private int maxTotalLen = 512;
        private int maxNewTokens = 128;
        private float temperature = 1.0E-6f;
        private float topP = 0.8f;
        private int seed = 42;

        public OfflineQwen3AsrModelConfig build() {
            return new OfflineQwen3AsrModelConfig(this);
        }

        public Builder setConvFrontend(String string) {
            this.convFrontend = string;
            return this;
        }

        public Builder setEncoder(String string) {
            this.encoder = string;
            return this;
        }

        public Builder setDecoder(String string) {
            this.decoder = string;
            return this;
        }

        public Builder setTokenizer(String string) {
            this.tokenizer = string;
            return this;
        }

        public Builder setHotwords(String string) {
            this.hotwords = string;
            return this;
        }

        public Builder setMaxTotalLen(int n) {
            this.maxTotalLen = n;
            return this;
        }

        public Builder setMaxNewTokens(int n) {
            this.maxNewTokens = n;
            return this;
        }

        public Builder setTemperature(float f) {
            this.temperature = f;
            return this;
        }

        public Builder setTopP(float f) {
            this.topP = f;
            return this;
        }

        public Builder setSeed(int n) {
            this.seed = n;
            return this;
        }
    }
}

