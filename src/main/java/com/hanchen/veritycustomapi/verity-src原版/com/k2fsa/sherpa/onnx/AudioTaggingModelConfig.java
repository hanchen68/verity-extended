/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import com.k2fsa.sherpa.onnx.OfflineZipformerAudioTaggingModelConfig;

public class AudioTaggingModelConfig {
    private final OfflineZipformerAudioTaggingModelConfig zipformer;
    private final String ced;
    private final int numThreads;
    private final boolean debug;
    private final String provider;

    private AudioTaggingModelConfig(Builder builder) {
        this.zipformer = builder.zipformer;
        this.ced = builder.ced;
        this.numThreads = builder.numThreads;
        this.debug = builder.debug;
        this.provider = builder.provider;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private OfflineZipformerAudioTaggingModelConfig zipformer = OfflineZipformerAudioTaggingModelConfig.builder().build();
        private String ced = "";
        private int numThreads = 1;
        private boolean debug = true;
        private String provider = "cpu";

        public AudioTaggingModelConfig build() {
            return new AudioTaggingModelConfig(this);
        }

        public Builder setZipformer(OfflineZipformerAudioTaggingModelConfig offlineZipformerAudioTaggingModelConfig) {
            this.zipformer = offlineZipformerAudioTaggingModelConfig;
            return this;
        }

        public Builder setCED(String string) {
            this.ced = string;
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

