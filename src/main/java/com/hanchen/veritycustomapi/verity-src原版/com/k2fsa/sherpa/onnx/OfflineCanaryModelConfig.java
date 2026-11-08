/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

public class OfflineCanaryModelConfig {
    private final String encoder;
    private final String decoder;
    private final String srcLang;
    private final String tgtLang;
    private final boolean usePnc;

    private OfflineCanaryModelConfig(Builder builder) {
        this.encoder = builder.encoder;
        this.decoder = builder.decoder;
        this.srcLang = builder.srcLang;
        this.tgtLang = builder.tgtLang;
        this.usePnc = builder.usePnc;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getEncoder() {
        return this.encoder;
    }

    public String getDecoder() {
        return this.decoder;
    }

    public String getSrcLang() {
        return this.srcLang;
    }

    public String getTgtLang() {
        return this.tgtLang;
    }

    public boolean isUsePnc() {
        return this.usePnc;
    }

    public static class Builder {
        private String encoder = "";
        private String decoder = "";
        private String srcLang = "en";
        private String tgtLang = "en";
        private boolean usePnc = true;

        public OfflineCanaryModelConfig build() {
            return new OfflineCanaryModelConfig(this);
        }

        public Builder setEncoder(String string) {
            this.encoder = string;
            return this;
        }

        public Builder setDecoder(String string) {
            this.decoder = string;
            return this;
        }

        public Builder setSrcLang(String string) {
            this.srcLang = string;
            return this;
        }

        public Builder setTgtLang(String string) {
            this.tgtLang = string;
            return this;
        }

        public Builder setUsePnc(boolean bl) {
            this.usePnc = bl;
            return this;
        }
    }
}

