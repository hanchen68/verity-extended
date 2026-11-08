/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

public class OfflineTtsMatchaModelConfig {
    private final String acousticModel;
    private final String vocoder;
    private final String lexicon;
    private final String tokens;
    private final String dataDir;
    private final String dictDir;
    private final float noiseScale;
    private final float lengthScale;

    private OfflineTtsMatchaModelConfig(Builder builder) {
        this.acousticModel = builder.acousticModel;
        this.vocoder = builder.vocoder;
        this.lexicon = builder.lexicon;
        this.tokens = builder.tokens;
        this.dataDir = builder.dataDir;
        this.dictDir = builder.dictDir;
        this.noiseScale = builder.noiseScale;
        this.lengthScale = builder.lengthScale;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getAcousticModel() {
        return this.acousticModel;
    }

    public String getVocoder() {
        return this.vocoder;
    }

    public String getLexicon() {
        return this.lexicon;
    }

    public String getTokens() {
        return this.tokens;
    }

    public String getDataDir() {
        return this.dataDir;
    }

    public String getDictDir() {
        return this.dictDir;
    }

    public float getLengthScale() {
        return this.lengthScale;
    }

    public float getNoiseScale() {
        return this.noiseScale;
    }

    public static class Builder {
        private String acousticModel = "";
        private String vocoder = "";
        private String lexicon = "";
        private String tokens = "";
        private String dataDir = "";
        private String dictDir = "";
        private float noiseScale = 1.0f;
        private float lengthScale = 1.0f;

        public OfflineTtsMatchaModelConfig build() {
            return new OfflineTtsMatchaModelConfig(this);
        }

        public Builder setAcousticModel(String string) {
            this.acousticModel = string;
            return this;
        }

        public Builder setVocoder(String string) {
            this.vocoder = string;
            return this;
        }

        public Builder setTokens(String string) {
            this.tokens = string;
            return this;
        }

        public Builder setLexicon(String string) {
            this.lexicon = string;
            return this;
        }

        public Builder setDataDir(String string) {
            this.dataDir = string;
            return this;
        }

        public Builder setDictDir(String string) {
            this.dictDir = string;
            return this;
        }

        public Builder setNoiseScale(float f) {
            this.noiseScale = f;
            return this;
        }

        public Builder setLengthScale(float f) {
            this.lengthScale = f;
            return this;
        }
    }
}

