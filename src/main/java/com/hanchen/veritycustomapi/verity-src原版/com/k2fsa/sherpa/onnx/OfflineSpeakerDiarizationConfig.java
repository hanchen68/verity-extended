/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import com.k2fsa.sherpa.onnx.FastClusteringConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;

public class OfflineSpeakerDiarizationConfig {
    private final OfflineSpeakerSegmentationModelConfig segmentation;
    private final SpeakerEmbeddingExtractorConfig embedding;
    private final FastClusteringConfig clustering;
    private final float minDurationOn;
    private final float minDurationOff;

    private OfflineSpeakerDiarizationConfig(Builder builder) {
        this.segmentation = builder.segmentation;
        this.embedding = builder.embedding;
        this.clustering = builder.clustering;
        this.minDurationOff = builder.minDurationOff;
        this.minDurationOn = builder.minDurationOn;
    }

    public static Builder builder() {
        return new Builder();
    }

    public OfflineSpeakerSegmentationModelConfig getSegmentation() {
        return this.segmentation;
    }

    public SpeakerEmbeddingExtractorConfig getEmbedding() {
        return this.embedding;
    }

    public FastClusteringConfig getClustering() {
        return this.clustering;
    }

    public float getMinDurationOff() {
        return this.minDurationOff;
    }

    public float getMinDurationOn() {
        return this.minDurationOn;
    }

    public static class Builder {
        private OfflineSpeakerSegmentationModelConfig segmentation = OfflineSpeakerSegmentationModelConfig.builder().build();
        private SpeakerEmbeddingExtractorConfig embedding = SpeakerEmbeddingExtractorConfig.builder().build();
        private FastClusteringConfig clustering = FastClusteringConfig.builder().build();
        private float minDurationOn = 0.2f;
        private float minDurationOff = 0.5f;

        public OfflineSpeakerDiarizationConfig build() {
            return new OfflineSpeakerDiarizationConfig(this);
        }

        public Builder setSegmentation(OfflineSpeakerSegmentationModelConfig offlineSpeakerSegmentationModelConfig) {
            this.segmentation = offlineSpeakerSegmentationModelConfig;
            return this;
        }

        public Builder setEmbedding(SpeakerEmbeddingExtractorConfig speakerEmbeddingExtractorConfig) {
            this.embedding = speakerEmbeddingExtractorConfig;
            return this;
        }

        public Builder setClustering(FastClusteringConfig fastClusteringConfig) {
            this.clustering = fastClusteringConfig;
            return this;
        }

        public Builder setMinDurationOff(float f) {
            this.minDurationOff = f;
            return this;
        }

        public Builder setMinDurationOn(float f) {
            this.minDurationOn = f;
            return this;
        }
    }
}

