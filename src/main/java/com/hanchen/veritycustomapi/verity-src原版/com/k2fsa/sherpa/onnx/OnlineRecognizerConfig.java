/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import com.k2fsa.sherpa.onnx.EndpointConfig;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.HomophoneReplacerConfig;
import com.k2fsa.sherpa.onnx.OnlineCtcFstDecoderConfig;
import com.k2fsa.sherpa.onnx.OnlineLMConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;

public class OnlineRecognizerConfig {
    private final FeatureConfig featConfig;
    private final OnlineModelConfig modelConfig;
    private final OnlineLMConfig lmConfig;
    private final OnlineCtcFstDecoderConfig ctcFstDecoderConfig;
    private final EndpointConfig endpointConfig;
    private final HomophoneReplacerConfig hr;
    private final boolean enableEndpoint;
    private final String decodingMethod;
    private final int maxActivePaths;
    private final String hotwordsFile;
    private final float hotwordsScore;
    private final String ruleFsts;
    private final String ruleFars;
    private final float blankPenalty;

    private OnlineRecognizerConfig(Builder builder) {
        this.featConfig = builder.featConfig;
        this.modelConfig = builder.modelConfig;
        this.lmConfig = builder.lmConfig;
        this.ctcFstDecoderConfig = builder.ctcFstDecoderConfig;
        this.endpointConfig = builder.endpointConfig;
        this.hr = builder.hr;
        this.enableEndpoint = builder.enableEndpoint;
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

    public OnlineModelConfig getModelConfig() {
        return this.modelConfig;
    }

    public static class Builder {
        private FeatureConfig featConfig = FeatureConfig.builder().build();
        private OnlineModelConfig modelConfig = OnlineModelConfig.builder().build();
        private OnlineLMConfig lmConfig = OnlineLMConfig.builder().build();
        private OnlineCtcFstDecoderConfig ctcFstDecoderConfig = OnlineCtcFstDecoderConfig.builder().build();
        private EndpointConfig endpointConfig = EndpointConfig.builder().build();
        private HomophoneReplacerConfig hr = HomophoneReplacerConfig.builder().build();
        private boolean enableEndpoint = true;
        private String decodingMethod = "greedy_search";
        private int maxActivePaths = 4;
        private String hotwordsFile = "";
        private float hotwordsScore = 1.5f;
        private String ruleFsts = "";
        private String ruleFars = "";
        private float blankPenalty = 0.0f;

        public OnlineRecognizerConfig build() {
            return new OnlineRecognizerConfig(this);
        }

        public Builder setFeatureConfig(FeatureConfig featureConfig) {
            this.featConfig = featureConfig;
            return this;
        }

        public Builder setOnlineModelConfig(OnlineModelConfig onlineModelConfig) {
            this.modelConfig = onlineModelConfig;
            return this;
        }

        public Builder setOnlineLMConfig(OnlineLMConfig onlineLMConfig) {
            this.lmConfig = onlineLMConfig;
            return this;
        }

        public Builder setCtcFstDecoderConfig(OnlineCtcFstDecoderConfig onlineCtcFstDecoderConfig) {
            this.ctcFstDecoderConfig = onlineCtcFstDecoderConfig;
            return this;
        }

        public Builder setEndpointConfig(EndpointConfig endpointConfig) {
            this.endpointConfig = endpointConfig;
            return this;
        }

        public Builder setHr(HomophoneReplacerConfig homophoneReplacerConfig) {
            this.hr = homophoneReplacerConfig;
            return this;
        }

        public Builder setEnableEndpoint(boolean bl) {
            this.enableEndpoint = bl;
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

