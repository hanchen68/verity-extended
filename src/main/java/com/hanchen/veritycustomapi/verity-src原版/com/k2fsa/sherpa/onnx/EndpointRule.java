/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

public class EndpointRule {
    private final boolean mustContainNonSilence;
    private final float minTrailingSilence;
    private final float minUtteranceLength;

    private EndpointRule(Builder builder) {
        this.mustContainNonSilence = builder.mustContainNonSilence;
        this.minTrailingSilence = builder.minTrailingSilence;
        this.minUtteranceLength = builder.minUtteranceLength;
    }

    public static Builder builder() {
        return new Builder();
    }

    public float getMinTrailingSilence() {
        return this.minTrailingSilence;
    }

    public float getMinUtteranceLength() {
        return this.minUtteranceLength;
    }

    public boolean getMustContainNonSilence() {
        return this.mustContainNonSilence;
    }

    public static class Builder {
        private boolean mustContainNonSilence = false;
        private float minTrailingSilence = 0.0f;
        private float minUtteranceLength = 0.0f;

        public EndpointRule build() {
            return new EndpointRule(this);
        }

        public Builder setMustContainNonSilence(boolean bl) {
            this.mustContainNonSilence = bl;
            return this;
        }

        public Builder setMinTrailingSilence(float f) {
            this.minTrailingSilence = f;
            return this;
        }

        public Builder setMinUtteranceLength(float f) {
            this.minUtteranceLength = f;
            return this;
        }
    }
}

