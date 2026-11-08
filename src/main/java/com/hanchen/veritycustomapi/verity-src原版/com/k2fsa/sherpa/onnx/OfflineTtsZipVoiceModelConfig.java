/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

public class OfflineTtsZipVoiceModelConfig {
    private final String tokens;
    private final String encoder;
    private final String decoder;
    private final String vocoder;
    private final String dataDir;
    private final String lexicon;
    private final float featScale;
    private final float tShift;
    private final float targetRms;
    private final float guidanceScale;

    private OfflineTtsZipVoiceModelConfig(Builder builder) {
        this.tokens = builder.tokens;
        this.encoder = builder.encoder;
        this.decoder = builder.decoder;
        this.vocoder = builder.vocoder;
        this.dataDir = builder.dataDir;
        this.lexicon = builder.lexicon;
        this.featScale = builder.featScale;
        this.tShift = builder.tShift;
        this.targetRms = builder.targetRms;
        this.guidanceScale = builder.guidanceScale;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getTokens() {
        return this.tokens;
    }

    public String getEncoder() {
        return this.encoder;
    }

    public String getDecoder() {
        return this.decoder;
    }

    public String getVocoder() {
        return this.vocoder;
    }

    public String getDataDir() {
        return this.dataDir;
    }

    public String getLexicon() {
        return this.lexicon;
    }

    public float getFeatScale() {
        return this.featScale;
    }

    public float getTShift() {
        return this.tShift;
    }

    public float getTargetRms() {
        return this.targetRms;
    }

    public float getGuidanceScale() {
        return this.guidanceScale;
    }

    public static class Builder {
        private String tokens = "";
        private String encoder = "";
        private String decoder = "";
        private String vocoder = "";
        private String dataDir = "";
        private String lexicon = "";
        private float featScale = 0.1f;
        private float tShift = 0.5f;
        private float targetRms = 0.1f;
        private float guidanceScale = 1.0f;

        public OfflineTtsZipVoiceModelConfig build() {
            return new OfflineTtsZipVoiceModelConfig(this);
        }

        public Builder setTokens(String string) {
            this.tokens = string;
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

        public Builder setVocoder(String string) {
            this.vocoder = string;
            return this;
        }

        public Builder setDataDir(String string) {
            this.dataDir = string;
            return this;
        }

        public Builder setLexicon(String string) {
            this.lexicon = string;
            return this;
        }

        public Builder setFeatScale(float f) {
            this.featScale = f;
            return this;
        }

        public Builder setTShift(float f) {
            this.tShift = f;
            return this;
        }

        public Builder setTargetRms(float f) {
            this.targetRms = f;
            return this;
        }

        public Builder setGuidanceScale(float f) {
            this.guidanceScale = f;
            return this;
        }
    }
}

