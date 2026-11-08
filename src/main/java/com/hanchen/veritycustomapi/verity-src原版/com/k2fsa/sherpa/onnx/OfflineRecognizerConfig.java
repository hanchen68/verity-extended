/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.HomophoneReplacerConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;

public class OfflineRecognizerConfig {
    private final FeatureConfig featConfig;
    private final OfflineModelConfig modelConfig;
    private final HomophoneReplacerConfig hr;
    private final String decodingMethod;
    private final int maxActivePaths;
    private final String hotwordsFile;
    private final float hotwordsScore;
    private final String ruleFsts;
    private final String ruleFars;
    private final float blankPenalty;

    private OfflineRecognizerConfig(Builder builder) {
        this.featConfig = builder.featConfig;
        this.modelConfig = builder.modelConfig;
        this.hr = builder.hr;
        this.decodingMethod = builder.decodingMethod;
        this.maxActivePaths = builder.maxActivePaths;
        this.hotwordsFile = builder.hotwordsFile;
        this.hotwordsScore = builder.hotwordsScore;
        this.ruleFsts = builder.ruleFsts;
        this.ruleFars = builder.ruleFars;
        this.blankPenalty = builder.blankPenalty;
    }

    public static Builder builder() {
        return new Builder();
    }

    public OfflineModelConfig getModelConfig() {
        return this.modelConfig;
    }

    public static class Builder {
        private FeatureConfig featConfig = FeatureConfig.builder().build();
        private OfflineModelConfig modelConfig = OfflineModelConfig.builder().build();
        private HomophoneReplacerConfig hr = HomophoneReplacerConfig.builder().build();
        private String decodingMethod = "greedy_search";
        private int maxActivePaths = 4;
        private String hotwordsFile = "";
        private float hotwordsScore = 1.5f;
        private String ruleFsts = "";
        private String ruleFars = "";
        private float blankPenalty = 0.0f;

        public OfflineRecognizerConfig build() {
            return new OfflineRecognizerConfig(this);
        }

        public Builder setFeatureConfig(FeatureConfig featureConfig) {
            this.featConfig = featureConfig;
            return this;
        }

        public Builder setOfflineModelConfig(OfflineModelConfig offlineModelConfig) {
            this.modelConfig = offlineModelConfig;
            return this;
        }

        public Builder setHr(HomophoneReplacerConfig homophoneReplacerConfig) {
            this.hr = homophoneReplacerConfig;
            return this;
        }

        public Builder setDecodingMethod(String string) {
            this.decodingMethod = string;
            return this;
        }

        public Builder setMaxActivePaths(int n) {
            this.maxActivePaths = n;
            return this;
        }

        public Builder setHotwordsFile(String string) {
            this.hotwordsFile = string;
            return this;
        }

        public Builder setHotwordsScore(float f) {
            this.hotwordsScore = f;
            return this;
        }

        public Builder setRuleFsts(String string) {
            this.ruleFsts = string;
            return this;
        }

        public Builder setRuleFars(String string) {
            this.ruleFars = string;
            return this;
        }

        public Builder setBlankPenalty(float f) {
            this.blankPenalty = f;
            return this;
        }
    }
}

