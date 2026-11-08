/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

public class OfflineTtsVitsModelConfig {
    private final String model;
    private final String lexicon;
    private final String tokens;
    private final String dataDir;
    private final String dictDir;
    private final float noiseScale;
    private final float noiseScaleW;
    private final float lengthScale;

    private OfflineTtsVitsModelConfig(Builder builder) {
        this.model = builder.model;
        this.lexicon = builder.lexicon;
        this.tokens = builder.tokens;
        this.dataDir = builder.dataDir;
        this.dictDir = builder.dictDir;
        this.noiseScale = builder.noiseScale;
        this.noiseScaleW = builder.noiseScaleW;
        this.lengthScale = builder.lengthScale;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getModel() {
        return this.model;
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

    public float getNoiseScaleW() {
        return this.noiseScaleW;
    }

    public static class Builder {
        private String model = "";
        private String lexicon = "";
        private String tokens = "";
        private String dataDir = "";
        private String dictDir = "";
        private float noiseScale = 0.667f;
        private float noiseScaleW = 0.8f;
        private float lengthScale = 1.0f;

        public OfflineTtsVitsModelConfig build() {
            return new OfflineTtsVitsModelConfig(this);
        }

        public Builder setModel(String string) {
            this.model = string;
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

        public Builder setNoiseScaleW(float f) {
            this.noiseScaleW = f;
            return this;
        }

        public Builder setLengthScale(float f) {
            this.lengthScale = f;
            return this;
        }
    }
}

