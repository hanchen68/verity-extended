/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

public class OfflineMoonshineModelConfig {
    private final String preprocessor;
    private final String encoder;
    private final String uncachedDecoder;
    private final String cachedDecoder;
    private final String mergedDecoder;

    private OfflineMoonshineModelConfig(Builder builder) {
        this.preprocessor = builder.preprocessor;
        this.encoder = builder.encoder;
        this.uncachedDecoder = builder.uncachedDecoder;
        this.cachedDecoder = builder.cachedDecoder;
        this.mergedDecoder = builder.mergedDecoder;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getPreprocessor() {
        return this.preprocessor;
    }

    public String getEncoder() {
        return this.encoder;
    }

    public String getUncachedDecoder() {
        return this.uncachedDecoder;
    }

    public String getCachedDecoder() {
        return this.cachedDecoder;
    }

    public String getMergedDecoder() {
        return this.mergedDecoder;
    }

    public static class Builder {
        private String preprocessor = "";
        private String encoder = "";
        private String uncachedDecoder = "";
        private String cachedDecoder = "";
        private String mergedDecoder = "";

        public OfflineMoonshineModelConfig build() {
            return new OfflineMoonshineModelConfig(this);
        }

        public Builder setPreprocessor(String string) {
            this.preprocessor = string;
            return this;
        }

        public Builder setEncoder(String string) {
            this.encoder = string;
            return this;
        }

        public Builder setUncachedDecoder(String string) {
            this.uncachedDecoder = string;
            return this;
        }

        public Builder setCachedDecoder(String string) {
            this.cachedDecoder = string;
            return this;
        }

        public Builder setMergedDecoder(String string) {
            this.mergedDecoder = string;
            return this;
        }
    }
}

