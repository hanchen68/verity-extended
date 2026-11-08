/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

public class OfflineWhisperModelConfig {
    private final String encoder;
    private final String decoder;
    private final String language;
    private final String task;
    private final int tailPaddings;
    private final boolean enableTokenTimestamps;
    private final boolean enableSegmentTimestamps;

    private OfflineWhisperModelConfig(Builder builder) {
        this.encoder = builder.encoder;
        this.decoder = builder.decoder;
        this.language = builder.language;
        this.task = builder.task;
        this.tailPaddings = builder.tailPaddings;
        this.enableTokenTimestamps = builder.enableTokenTimestamps;
        this.enableSegmentTimestamps = builder.enableSegmentTimestamps;
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

    public String getLanguage() {
        return this.language;
    }

    public String getTask() {
        return this.task;
    }

    public int getTailPaddings() {
        return this.tailPaddings;
    }

    public boolean getEnableTokenTimestamps() {
        return this.enableTokenTimestamps;
    }

    public boolean getEnableSegmentTimestamps() {
        return this.enableSegmentTimestamps;
    }

    public static class Builder {
        private String encoder = "";
        private String decoder = "";
        private String language = "en";
        private String task = "transcribe";
        private int tailPaddings = 1000;
        private boolean enableTokenTimestamps = false;
        private boolean enableSegmentTimestamps = false;

        public OfflineWhisperModelConfig build() {
            return new OfflineWhisperModelConfig(this);
        }

        public Builder setEncoder(String string) {
            this.encoder = string;
            return this;
        }

        public Builder setDecoder(String string) {
            this.decoder = string;
            return this;
        }

        public Builder setLanguage(String string) {
            this.language = string;
            return this;
        }

        public Builder setTask(String string) {
            this.task = string;
            return this;
        }

        public Builder setTailPaddings(int n) {
            this.tailPaddings = n;
            return this;
        }

        public Builder setEnableTokenTimestamps(boolean bl) {
            this.enableTokenTimestamps = bl;
            return this;
        }

        public Builder setEnableSegmentTimestamps(boolean bl) {
            this.enableSegmentTimestamps = bl;
            return this;
        }
    }
}

