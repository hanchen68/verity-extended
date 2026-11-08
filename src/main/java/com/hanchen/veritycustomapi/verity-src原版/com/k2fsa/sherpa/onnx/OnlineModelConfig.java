/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import com.k2fsa.sherpa.onnx.OnlineNeMoCtcModelConfig;
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig;
import com.k2fsa.sherpa.onnx.OnlineToneCtcModelConfig;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig;

public class OnlineModelConfig {
    private final OnlineTransducerModelConfig transducer;
    private final OnlineParaformerModelConfig paraformer;
    private final OnlineZipformer2CtcModelConfig zipformer2Ctc;
    private final OnlineNeMoCtcModelConfig neMoCtc;
    private final OnlineToneCtcModelConfig toneCtc;
    private final String tokens;
    private final int numThreads;
    private final boolean debug;
    private final String provider;
    private final String modelType;
    private final String modelingUnit;
    private final String bpeVocab;

    private OnlineModelConfig(Builder builder) {
        this.transducer = builder.transducer;
        this.paraformer = builder.paraformer;
        this.zipformer2Ctc = builder.zipformer2Ctc;
        this.neMoCtc = builder.neMoCtc;
        this.toneCtc = builder.toneCtc;
        this.tokens = builder.tokens;
        this.numThreads = builder.numThreads;
        this.debug = builder.debug;
        this.provider = builder.provider;
        this.modelType = builder.modelType;
        this.modelingUnit = builder.modelingUnit;
        this.bpeVocab = builder.bpeVocab;
    }

    public static Builder builder() {
        return new Builder();
    }

    public OnlineParaformerModelConfig getParaformer() {
        return this.paraformer;
    }

    public OnlineTransducerModelConfig getTransducer() {
        return this.transducer;
    }

    public OnlineZipformer2CtcModelConfig getZipformer2Ctc() {
        return this.zipformer2Ctc;
    }

    public OnlineNeMoCtcModelConfig getNeMoCtc() {
        return this.neMoCtc;
    }

    public OnlineToneCtcModelConfig getToneCtc() {
        return this.toneCtc;
    }

    public String getTokens() {
        return this.tokens;
    }

    public int getNumThreads() {
        return this.numThreads;
    }

    public boolean getDebug() {
        return this.debug;
    }

    public String getProvider() {
        return this.provider;
    }

    public String getModelType() {
        return this.modelType;
    }

    public String getModelingUnit() {
        return this.modelingUnit;
    }

    public String getBpeVocab() {
        return this.bpeVocab;
    }

    public static class Builder {
        private OnlineParaformerModelConfig paraformer = OnlineParaformerModelConfig.builder().build();
        private OnlineTransducerModelConfig transducer = OnlineTransducerModelConfig.builder().build();
        private OnlineZipformer2CtcModelConfig zipformer2Ctc = OnlineZipformer2CtcModelConfig.builder().build();
        private OnlineNeMoCtcModelConfig neMoCtc = OnlineNeMoCtcModelConfig.builder().build();
        private OnlineToneCtcModelConfig toneCtc = OnlineToneCtcModelConfig.builder().build();
        private String tokens = "";
        private int numThreads = 1;
        private boolean debug = true;
        private String provider = "cpu";
        private String modelType = "";
        private String modelingUnit = "cjkchar";
        private String bpeVocab = "";

        public OnlineModelConfig build() {
            return new OnlineModelConfig(this);
        }

        public Builder setTransducer(OnlineTransducerModelConfig onlineTransducerModelConfig) {
            this.transducer = onlineTransducerModelConfig;
            return this;
        }

        public Builder setParaformer(OnlineParaformerModelConfig onlineParaformerModelConfig) {
            this.paraformer = onlineParaformerModelConfig;
            return this;
        }

        public Builder setZipformer2Ctc(OnlineZipformer2CtcModelConfig onlineZipformer2CtcModelConfig) {
            this.zipformer2Ctc = onlineZipformer2CtcModelConfig;
            return this;
        }

        public Builder setNeMoCtc(OnlineNeMoCtcModelConfig onlineNeMoCtcModelConfig) {
            this.neMoCtc = onlineNeMoCtcModelConfig;
            return this;
        }

        public Builder setToneCtc(OnlineToneCtcModelConfig onlineToneCtcModelConfig) {
            this.toneCtc = onlineToneCtcModelConfig;
            return this;
        }

        public Builder setTokens(String string) {
            this.tokens = string;
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

        public Builder setModelType(String string) {
            this.modelType = string;
            return this;
        }

        public Builder setModelingUnit(String string) {
            this.modelingUnit = string;
            return this;
        }

        public Builder setBpeVocab(String string) {
            this.bpeVocab = string;
            return this;
        }
    }
}

