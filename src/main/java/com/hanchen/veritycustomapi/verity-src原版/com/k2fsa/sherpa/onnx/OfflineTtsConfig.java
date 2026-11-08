/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;

public class OfflineTtsConfig {
    private final OfflineTtsModelConfig model;
    private final String ruleFsts;
    private final String ruleFars;
    private final int maxNumSentences;
    private final float silenceScale;

    private OfflineTtsConfig(Builder builder) {
        this.model = builder.model;
        this.ruleFsts = builder.ruleFsts;
        this.ruleFars = builder.ruleFars;
        this.maxNumSentences = builder.maxNumSentences;
        this.silenceScale = builder.silenceScale;
    }

    public static Builder builder() {
        return new Builder();
    }

    public OfflineTtsModelConfig getModel() {
        return this.model;
    }

    public String getRuleFsts() {
        return this.ruleFsts;
    }

    public String getRuleFars() {
        return this.ruleFars;
    }

    public int getMaxNumSentences() {
        return this.maxNumSentences;
    }

    public float getSilenceScale() {
        return this.silenceScale;
    }

    public static class Builder {
        private OfflineTtsModelConfig model = OfflineTtsModelConfig.builder().build();
        private String ruleFsts = "";
        private String ruleFars = "";
        private int maxNumSentences = 1;
        private float silenceScale = 0.2f;

        public OfflineTtsConfig build() {
            return new OfflineTtsConfig(this);
        }

        public Builder setModel(OfflineTtsModelConfig offlineTtsModelConfig) {
            this.model = offlineTtsModelConfig;
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

        public Builder setMaxNumSentences(int n) {
            this.maxNumSentences = n;
            return this;
        }

        public Builder setSilenceScale(float f) {
            this.silenceScale = f;
            return this;
        }
    }
}

