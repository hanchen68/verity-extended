/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

public class QnnConfig {
    private final String backendLib;
    private final String contextBinary;
    private final String systemLib;

    private QnnConfig(Builder builder) {
        this.backendLib = builder.backendLib;
        this.contextBinary = builder.contextBinary;
        this.systemLib = builder.systemLib;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getBackendLib() {
        return this.backendLib;
    }

    public String getContextBinary() {
        return this.contextBinary;
    }

    public String getSystemLib() {
        return this.systemLib;
    }

    public static class Builder {
        private String backendLib = "";
        private String contextBinary = "";
        private String systemLib = "";

        public QnnConfig build() {
            return new QnnConfig(this);
        }

        public Builder setBackendLib(String string) {
            this.backendLib = string;
            return this;
        }

        public Builder setContextBinary(String string) {
            this.contextBinary = string;
            return this;
        }

        public Builder setSystemLib(String string) {
            this.systemLib = string;
            return this;
        }
    }
}

