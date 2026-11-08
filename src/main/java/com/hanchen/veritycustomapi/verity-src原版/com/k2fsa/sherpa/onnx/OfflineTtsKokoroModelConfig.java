/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

public class OfflineTtsKokoroModelConfig {
    private final String model;
    private final String voices;
    private final String tokens;
    private final String lexicon;
    private final String lang;
    private final String dataDir;
    private final String dictDir;
    private final float lengthScale;

    private OfflineTtsKokoroModelConfig(Builder builder) {
        this.model = builder.model;
        this.voices = builder.voices;
        this.tokens = builder.tokens;
        this.lexicon = builder.lexicon;
        this.lang = builder.lang;
        this.dataDir = builder.dataDir;
        this.dictDir = builder.dictDir;
        this.lengthScale = builder.lengthScale;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getModel() {
        return this.model;
    }

    public String getVoices() {
        return this.voices;
    }

    public String getTokens() {
        return this.tokens;
    }

    public String getDataDir() {
        return this.dataDir;
    }

    public float getLengthScale() {
        return this.lengthScale;
    }

    public static class Builder {
        private String model = "";
        private String voices = "";
        private String tokens = "";
        private String lexicon = "";
        private String lang = "";
        private String dataDir = "";
        private String dictDir = "";
        private float lengthScale = 1.0f;

        public OfflineTtsKokoroModelConfig build() {
            return new OfflineTtsKokoroModelConfig(this);
        }

        public Builder setModel(String string) {
            this.model = string;
            return this;
        }

        public Builder setVoices(String string) {
            this.voices = string;
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

        public Builder setLang(String string) {
            this.lang = string;
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

        public Builder setLengthScale(float f) {
            this.lengthScale = f;
            return this;
        }
    }
}

