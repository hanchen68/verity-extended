/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

public class OnlineCtcFstDecoderConfig {
    private final String graph;
    private final int maxActive;

    private OnlineCtcFstDecoderConfig(Builder builder) {
        this.graph = builder.graph;
        this.maxActive = builder.maxActive;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getGraph() {
        return this.graph;
    }

    public float getMaxActive() {
        return this.maxActive;
    }

    public static class Builder {
        private String graph = "";
        private int maxActive = 3000;

        public OnlineCtcFstDecoderConfig build() {
            return new OnlineCtcFstDecoderConfig(this);
        }

        public Builder setGraph(String string) {
            this.graph = string;
            return this;
        }

        public Builder setMaxActive(int n) {
            this.maxActive = n;
            return this;
        }
    }
}

