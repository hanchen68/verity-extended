/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

public class OfflineTtsPocketModelConfig {
    private final String lmFlow;
    private final String lmMain;
    private final String encoder;
    private final String decoder;
    private final String textConditioner;
    private final String vocabJson;
    private final String tokenScoresJson;
    private final int voiceEmbeddingCacheCapacity;

    private OfflineTtsPocketModelConfig(Builder builder) {
        this.lmFlow = builder.lmFlow;
        this.lmMain = builder.lmMain;
        this.encoder = builder.encoder;
        this.decoder = builder.decoder;
        this.textConditioner = builder.textConditioner;
        this.vocabJson = builder.vocabJson;
        this.tokenScoresJson = builder.tokenScoresJson;
        this.voiceEmbeddingCacheCapacity = builder.voiceEmbeddingCacheCapacity;
    }

    public String getLmFlow() {
        return this.lmFlow;
    }

    public String getLmMain() {
        return this.lmMain;
    }

    public String getEncoder() {
        return this.encoder;
    }

    public String getDecoder() {
        return this.decoder;
    }

    public String getTextConditioner() {
        return this.textConditioner;
    }

    public String getVocabJson() {
        return this.vocabJson;
    }

    public String getTokenScoresJson() {
        return this.tokenScoresJson;
    }

    public int getVoiceEmbeddingCacheCapacity() {
        return this.voiceEmbeddingCacheCapacity;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String lmFlow = "";
        private String lmMain = "";
        private String encoder = "";
        private String decoder = "";
        private String textConditioner = "";
        private String vocabJson = "";
        private String tokenScoresJson = "";
        private int voiceEmbeddingCacheCapacity = 50;

        public OfflineTtsPocketModelConfig build() {
            return new OfflineTtsPocketModelConfig(this);
        }

        public Builder setLmFlow(String string) {
            this.lmFlow = string;
            return this;
        }

        public Builder setLmMain(String string) {
            this.lmMain = string;
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

        public Builder setTextConditioner(String string) {
            this.textConditioner = string;
            return this;
        }

        public Builder setVocabJson(String string) {
            this.vocabJson = string;
            return this;
        }

        public Builder setTokenScoresJson(String string) {
            this.tokenScoresJson = string;
            return this;
        }

        public Builder setVoiceEmbeddingCacheCapacity(int n) {
            this.voiceEmbeddingCacheCapacity = n;
            return this;
        }
    }
}

