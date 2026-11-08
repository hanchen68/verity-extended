/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationWhisperConfig;

public class SpokenLanguageIdentificationConfig {
    private final SpokenLanguageIdentificationWhisperConfig whisper;
    private final int numThreads;
    private final boolean debug;
    private final String provider;

    private SpokenLanguageIdentificationConfig(Builder builder) {
        this.whisper = builder.whisper;
        this.numThreads = builder.numThreads;
        this.debug = builder.debug;
        this.provider = builder.provider;
    }

    public static Builder builder() {
        return new Builder();
    }

    public SpokenLanguageIdentificationWhisperConfig getWhisper() {
        return this.whisper;
    }

    public static class Builder {
        private SpokenLanguageIdentificationWhisperConfig whisper = SpokenLanguageIdentificationWhisperConfig.builder().build();
        private int numThreads = 1;
        private boolean debug = true;
        private String provider = "cpu";

        public SpokenLanguageIdentificationConfig build() {
            return new SpokenLanguageIdentificationConfig(this);
        }

        public Builder setWhisper(SpokenLanguageIdentificationWhisperConfig spokenLanguageIdentificationWhisperConfig) {
            this.whisper = spokenLanguageIdentificationWhisperConfig;
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

