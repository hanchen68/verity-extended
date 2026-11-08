/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.TenVadModelConfig;

public class VadModelConfig {
    private final SileroVadModelConfig sileroVadModelConfig;
    private final TenVadModelConfig tenVadModelConfig;
    private final int sampleRate;
    private final int numThreads;
    private final boolean debug;
    private final String provider;

    private VadModelConfig(Builder builder) {
        this.sileroVadModelConfig = builder.sileroVadModelConfig;
        this.tenVadModelConfig = builder.tenVadModelConfig;
        this.sampleRate = builder.sampleRate;
        this.numThreads = builder.numThreads;
        this.debug = builder.debug;
        this.provider = builder.provider;
    }

    public static Builder builder() {
        return new Builder();
    }

    public SileroVadModelConfig getSileroVadModelConfig() {
        return this.sileroVadModelConfig;
    }

    public TenVadModelConfig getTenVadModelConfig() {
        return this.tenVadModelConfig;
    }

    public int getSampleRate() {
        return this.sampleRate;
    }

    public int getNumThreads() {
        return this.numThreads;
    }

    public String getProvider() {
        return this.provider;
    }

    public boolean getDebug() {
        return this.debug;
    }

    public static class Builder {
        private SileroVadModelConfig sileroVadModelConfig = new SileroVadModelConfig.Builder().build();
        private TenVadModelConfig tenVadModelConfig = new TenVadModelConfig.Builder().build();
        private int sampleRate = 16000;
        private int numThreads = 1;
        private boolean debug = true;
        private String provider = "cpu";

        public VadModelConfig build() {
            return new VadModelConfig(this);
        }

        public Builder setSileroVadModelConfig(SileroVadModelConfig sileroVadModelConfig) {
            this.sileroVadModelConfig = sileroVadModelConfig;
            return this;
        }

        public Builder setTenVadModelConfig(TenVadModelConfig tenVadModelConfig) {
            this.tenVadModelConfig = tenVadModelConfig;
            return this;
        }

        public Builder setSampleRate(int n) {
            this.sampleRate = n;
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

