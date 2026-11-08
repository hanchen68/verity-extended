/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

public class SpokenLanguageIdentificationWhisperConfig {
    private final String encoder;
    private final String decoder;
    private final int tailPaddings;

    private SpokenLanguageIdentificationWhisperConfig(Builder builder) {
        this.encoder = builder.encoder;
        this.decoder = builder.decoder;
        this.tailPaddings = builder.tailPaddings;
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

    public int getTailPaddings() {
        return this.tailPaddings;
    }

    public static class Builder {
        private String encoder = "";
        private String decoder = "";
        private int tailPaddings = 1000;

        public SpokenLanguageIdentificationWhisperConfig build() {
            return new SpokenLanguageIdentificationWhisperConfig(this);
        }

        public Builder setEncoder(String string) {
            this.encoder = string;
            return this;
        }

        public Builder setDecoder(String string) {
            this.decoder = string;
            return this;
        }

        public Builder setTailPaddings(int n) {
            this.tailPaddings = n;
            return this;
        }
    }
}

