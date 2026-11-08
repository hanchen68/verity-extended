/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig;

public class OfflineSpeakerSegmentationModelConfig {
    private final OfflineSpeakerSegmentationPyannoteModelConfig pyannote;
    private final int numThreads;
    private final boolean debug;
    private final String provider;

    private OfflineSpeakerSegmentationModelConfig(Builder builder) {
        this.pyannote = builder.pyannote;
        this.numThreads = builder.numThreads;
        this.debug = builder.debug;
        this.provider = builder.provider;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private OfflineSpeakerSegmentationPyannoteModelConfig pyannote = OfflineSpeakerSegmentationPyannoteModelConfig.builder().build();
        private int numThreads = 1;
        private boolean debug = true;
        private String provider = "cpu";

        public OfflineSpeakerSegmentationModelConfig build() {
            return new OfflineSpeakerSegmentationModelConfig(this);
        }

        public Builder setPyannote(OfflineSpeakerSegmentationPyannoteModelConfig offlineSpeakerSegmentationPyannoteModelConfig) {
            this.pyannote = offlineSpeakerSegmentationPyannoteModelConfig;
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

