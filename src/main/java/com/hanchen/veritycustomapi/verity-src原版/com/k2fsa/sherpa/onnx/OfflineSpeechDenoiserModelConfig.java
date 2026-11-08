/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserDpdfNetModelConfig;
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig;

public class OfflineSpeechDenoiserModelConfig {
    private final OfflineSpeechDenoiserGtcrnModelConfig gtcrn;
    private final OfflineSpeechDenoiserDpdfNetModelConfig dpdfnet;
    private final int numThreads;
    private final boolean debug;
    private final String provider;

    private OfflineSpeechDenoiserModelConfig(Builder builder) {
        this.gtcrn = builder.gtcrn;
        this.dpdfnet = builder.dpdfnet;
        this.numThreads = builder.numThreads;
        this.debug = builder.debug;
        this.provider = builder.provider;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private OfflineSpeechDenoiserGtcrnModelConfig gtcrn = OfflineSpeechDenoiserGtcrnModelConfig.builder().build();
        private OfflineSpeechDenoiserDpdfNetModelConfig dpdfnet = OfflineSpeechDenoiserDpdfNetModelConfig.builder().build();
        private int numThreads = 1;
        private boolean debug = true;
        private String provider = "cpu";

        public OfflineSpeechDenoiserModelConfig build() {
            return new OfflineSpeechDenoiserModelConfig(this);
        }

        public Builder setGtcrn(OfflineSpeechDenoiserGtcrnModelConfig offlineSpeechDenoiserGtcrnModelConfig) {
            this.gtcrn = offlineSpeechDenoiserGtcrnModelConfig;
            return this;
        }

        public Builder setDpdfnet(OfflineSpeechDenoiserDpdfNetModelConfig offlineSpeechDenoiserDpdfNetModelConfig) {
            this.dpdfnet = offlineSpeechDenoiserDpdfNetModelConfig;
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

