/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;

public class KeywordSpotterConfig {
    private final FeatureConfig featConfig;
    private final OnlineModelConfig modelConfig;
    private final int maxActivePaths;
    private final String keywordsFile;
    private final float keywordsScore;
    private final float keywordsThreshold;
    private final int numTrailingBlanks;

    private KeywordSpotterConfig(Builder builder) {
        this.featConfig = builder.featConfig;
        this.modelConfig = builder.modelConfig;
        this.maxActivePaths = builder.maxActivePaths;
        this.keywordsFile = builder.keywordsFile;
        this.keywordsScore = builder.keywordsScore;
        this.keywordsThreshold = builder.keywordsThreshold;
        this.numTrailingBlanks = builder.numTrailingBlanks;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private FeatureConfig featConfig = FeatureConfig.builder().build();
        private OnlineModelConfig modelConfig = OnlineModelConfig.builder().build();
        private int maxActivePaths = 4;
        private String keywordsFile = "keywords.txt";
        private float keywordsScore = 1.5f;
        private float keywordsThreshold = 0.25f;
        private int numTrailingBlanks = 2;

        public KeywordSpotterConfig build() {
            return new KeywordSpotterConfig(this);
        }

        public Builder setFeatureConfig(FeatureConfig featureConfig) {
            this.featConfig = featureConfig;
            return this;
        }

        public Builder setOnlineModelConfig(OnlineModelConfig onlineModelConfig) {
            this.modelConfig = onlineModelConfig;
            return this;
        }

        public Builder setMaxActivePaths(int n) {
            this.maxActivePaths = n;
            return this;
        }

        public Builder setKeywordsFile(String string) {
            this.keywordsFile = string;
            return this;
        }

        public Builder setKeywordsScore(float f) {
            this.keywordsScore = f;
            return this;
        }

        public Builder setKeywordsThreshold(float f) {
            this.keywordsThreshold = f;
            return this;
        }

        public Builder setNumTrailingBlanks(int n) {
            this.numTrailingBlanks = n;
            return this;
        }
    }
}

