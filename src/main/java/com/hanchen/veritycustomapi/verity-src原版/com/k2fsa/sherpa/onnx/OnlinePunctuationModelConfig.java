/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

public class OnlinePunctuationModelConfig {
    private final String cnnBilstm;
    private final String bpeVocab;
    private final int numThreads;
    private final boolean debug;
    private final String provider;

    private OnlinePunctuationModelConfig(Builder builder) {
        this.cnnBilstm = builder.cnnBilstm;
        this.bpeVocab = builder.bpeVocab;
        this.numThreads = builder.numThreads;
        this.debug = builder.debug;
        this.provider = builder.provider;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getCnnBilstm() {
        return this.cnnBilstm;
    }

    public String getBpeVocab() {
        return this.bpeVocab;
    }

    public static class Builder {
        private String cnnBilstm = "";
        private String bpeVocab = "";
        private int numThreads = 1;
        private boolean debug = true;
        private String provider = "cpu";

        public OnlinePunctuationModelConfig build() {
            return new OnlinePunctuationModelConfig(this);
        }

        public Builder setCnnBilstm(String string) {
            this.cnnBilstm = string;
            return this;
        }

        public Builder setBpeVocab(String string) {
            this.bpeVocab = string;
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

