/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

public class OfflineTtsSupertonicModelConfig {
    private final String durationPredictor;
    private final String textEncoder;
    private final String vectorEstimator;
    private final String vocoder;
    private final String ttsJson;
    private final String unicodeIndexer;
    private final String voiceStyle;

    private OfflineTtsSupertonicModelConfig(Builder builder) {
        this.durationPredictor = builder.durationPredictor;
        this.textEncoder = builder.textEncoder;
        this.vectorEstimator = builder.vectorEstimator;
        this.vocoder = builder.vocoder;
        this.ttsJson = builder.ttsJson;
        this.unicodeIndexer = builder.unicodeIndexer;
        this.voiceStyle = builder.voiceStyle;
    }

    public String getDurationPredictor() {
        return this.durationPredictor;
    }

    public String getTextEncoder() {
        return this.textEncoder;
    }

    public String getVectorEstimator() {
        return this.vectorEstimator;
    }

    public String getVocoder() {
        return this.vocoder;
    }

    public String getTtsJson() {
        return this.ttsJson;
    }

    public String getUnicodeIndexer() {
        return this.unicodeIndexer;
    }

    public String getVoiceStyle() {
        return this.voiceStyle;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String durationPredictor = "";
        private String textEncoder = "";
        private String vectorEstimator = "";
        private String vocoder = "";
        private String ttsJson = "";
        private String unicodeIndexer = "";
        private String voiceStyle = "";

        public OfflineTtsSupertonicModelConfig build() {
            return new OfflineTtsSupertonicModelConfig(this);
        }

        public Builder setDurationPredictor(String string) {
            this.durationPredictor = string;
            return this;
        }

        public Builder setTextEncoder(String string) {
            this.textEncoder = string;
            return this;
        }

        public Builder setVectorEstimator(String string) {
            this.vectorEstimator = string;
            return this;
        }

        public Builder setVocoder(String string) {
            this.vocoder = string;
            return this;
        }

        public Builder setTtsJson(String string) {
            this.ttsJson = string;
            return this;
        }

        public Builder setUnicodeIndexer(String string) {
            this.unicodeIndexer = string;
            return this;
        }

        public Builder setVoiceStyle(String string) {
            this.voiceStyle = string;
            return this;
        }
    }
}

