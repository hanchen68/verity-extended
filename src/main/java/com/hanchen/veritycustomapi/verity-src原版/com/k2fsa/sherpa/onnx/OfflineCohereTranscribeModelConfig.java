/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

public class OfflineCohereTranscribeModelConfig {
    private final String encoder;
    private final String decoder;
    private final String language;
    private final boolean usePunct;
    private final boolean useItn;

    private OfflineCohereTranscribeModelConfig(Builder builder) {
        this.encoder = builder.encoder;
        this.decoder = builder.decoder;
        this.language = builder.language;
        this.usePunct = builder.usePunct;
        this.useItn = builder.useItn;
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

    public String getLanguage() {
        return this.language;
    }

    public boolean isUsePunct() {
        return this.usePunct;
    }

    public boolean isUseItn() {
        return this.useItn;
    }

    public static class Builder {
        private String encoder = "";
        private String decoder = "";
        private String language = "";
        private boolean usePunct = true;
        private boolean useItn = true;

        public OfflineCohereTranscribeModelConfig build() {
            return new OfflineCohereTranscribeModelConfig(this);
        }

        public Builder setEncoder(String string) {
            this.encoder = string;
            return this;
        }

        public Builder setDecoder(String string) {
            this.decoder = string;
            return this;
        }

        public Builder setLanguage(String string) {
            this.language = string;
            return this;
        }

        public Builder setUsePunct(boolean bl) {
            this.usePunct = bl;
            return this;
        }

        public Builder setUseItn(boolean bl) {
            this.useItn = bl;
            return this;
        }
    }
}

