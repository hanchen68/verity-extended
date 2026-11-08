/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

public class OfflineFunAsrNanoModelConfig {
    private final String encoderAdaptor;
    private final String llm;
    private final String embedding;
    private final String tokenizer;
    private final String systemPrompt;
    private final String userPrompt;
    private final int maxNewTokens;
    private final float temperature;
    private final float topP;
    private final int seed;
    private final String language;
    private final boolean itn;
    private final String hotwords;

    private OfflineFunAsrNanoModelConfig(Builder builder) {
        this.encoderAdaptor = builder.encoderAdaptor;
        this.llm = builder.llm;
        this.embedding = builder.embedding;
        this.tokenizer = builder.tokenizer;
        this.systemPrompt = builder.systemPrompt;
        this.userPrompt = builder.userPrompt;
        this.maxNewTokens = builder.maxNewTokens;
        this.temperature = builder.temperature;
        this.topP = builder.topP;
        this.seed = builder.seed;
        this.language = builder.language;
        this.itn = builder.itn;
        this.hotwords = builder.hotwords;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getEncoderAdaptor() {
        return this.encoderAdaptor;
    }

    public String getLLM() {
        return this.llm;
    }

    public String getEmbedding() {
        return this.embedding;
    }

    public String getTokenizer() {
        return this.tokenizer;
    }

    public String getSystemPrompt() {
        return this.systemPrompt;
    }

    public String getUserPrompt() {
        return this.userPrompt;
    }

    public String getLanguage() {
        return this.language;
    }

    public boolean getItn() {
        return this.itn;
    }

    public String getHotwords() {
        return this.hotwords;
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
        private String encoderAdaptor = "";
        private String llm = "";
        private String embedding = "";
        private String tokenizer = "";
        private String systemPrompt = "You are a helpful assistant.";
        private String userPrompt = "\u8bed\u97f3\u8f6c\u5199\uff1a";
        private int maxNewTokens = 512;
        private float temperature = 1.0E-6f;
        private float topP = 0.8f;
        private int seed = 42;
        private String language = "";
        private boolean itn = true;
        private String hotwords = "";

        public OfflineFunAsrNanoModelConfig build() {
            return new OfflineFunAsrNanoModelConfig(this);
        }

        public Builder setEncoderAdaptor(String string) {
            this.encoderAdaptor = string;
            return this;
        }

        public Builder setLLM(String string) {
            this.llm = string;
            return this;
        }

        public Builder setEmbedding(String string) {
            this.embedding = string;
            return this;
        }

        public Builder setTokenizer(String string) {
            this.tokenizer = string;
            return this;
        }

        public Builder setSystemPrompt(String string) {
            this.systemPrompt = string;
            return this;
        }

        public Builder setUserPrompt(String string) {
            this.userPrompt = string;
            return this;
        }

        public Builder setLanguage(String string) {
            this.language = string;
            return this;
        }

        public Builder setItn(boolean bl) {
            this.itn = bl;
            return this;
        }

        public Builder setHotwords(String string) {
            this.hotwords = string;
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

