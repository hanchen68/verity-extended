/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

public class OnlineLMConfig {
    private final String model;
    private final float scale;

    private OnlineLMConfig(Builder builder) {
        this.model = builder.model;
        this.scale = builder.scale;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getModel() {
        return this.model;
    }

    public float getScale() {
        return this.scale;
    }

    public static class Builder {
        private String model = "";
        private float scale = 1.0f;

        public OnlineLMConfig build() {
            return new OnlineLMConfig(this);
        }

        public Builder setModel(String string) {
            this.model = string;
            return this;
        }

        public Builder setScale(float f) {
            this.scale = f;
            return this;
        }
    }
}

