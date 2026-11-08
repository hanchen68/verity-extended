/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

public class SpeakerEmbeddingExtractorConfig {
    private final String model;
    private final int numThreads;
    private final boolean debug;
    private final String provider;

    private SpeakerEmbeddingExtractorConfig(Builder builder) {
        this.model = builder.model;
        this.numThreads = builder.numThreads;
        this.debug = builder.debug;
        this.provider = builder.provider;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String model = "";
        private int numThreads = 1;
        private boolean debug = true;
        private String provider = "cpu";

        public SpeakerEmbeddingExtractorConfig build() {
            return new SpeakerEmbeddingExtractorConfig(this);
        }

        public Builder setModel(String string) {
            this.model = string;
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

