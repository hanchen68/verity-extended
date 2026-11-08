/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsMatchaModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsZipVoiceModelConfig;

public class OfflineTtsModelConfig {
    private final OfflineTtsVitsModelConfig vits;
    private final OfflineTtsMatchaModelConfig matcha;
    private final OfflineTtsKokoroModelConfig kokoro;
    private final OfflineTtsZipVoiceModelConfig zipvoice;
    private final OfflineTtsKittenModelConfig kitten;
    private final OfflineTtsPocketModelConfig pocket;
    private final OfflineTtsSupertonicModelConfig supertonic;
    private final int numThreads;
    private final boolean debug;
    private final String provider;

    private OfflineTtsModelConfig(Builder builder) {
        this.vits = builder.vits;
        this.matcha = builder.matcha;
        this.kokoro = builder.kokoro;
        this.zipvoice = builder.zipvoice;
        this.kitten = builder.kitten;
        this.pocket = builder.pocket;
        this.supertonic = builder.supertonic;
        this.numThreads = builder.numThreads;
        this.debug = builder.debug;
        this.provider = builder.provider;
    }

    public static Builder builder() {
        return new Builder();
    }

    public OfflineTtsVitsModelConfig getVits() {
        return this.vits;
    }

    public OfflineTtsMatchaModelConfig getMatcha() {
        return this.matcha;
    }

    public OfflineTtsKokoroModelConfig getKokoro() {
        return this.kokoro;
    }

    public OfflineTtsZipVoiceModelConfig getZipvoice() {
        return this.zipvoice;
    }

    public OfflineTtsKittenModelConfig getKitten() {
        return this.kitten;
    }

    public OfflineTtsPocketModelConfig getPocket() {
        return this.pocket;
    }

    public OfflineTtsSupertonicModelConfig getSupertonic() {
        return this.supertonic;
    }

    public static class Builder {
        private OfflineTtsVitsModelConfig vits = OfflineTtsVitsModelConfig.builder().build();
        private OfflineTtsMatchaModelConfig matcha = OfflineTtsMatchaModelConfig.builder().build();
        private OfflineTtsKokoroModelConfig kokoro = OfflineTtsKokoroModelConfig.builder().build();
        private OfflineTtsZipVoiceModelConfig zipvoice = OfflineTtsZipVoiceModelConfig.builder().build();
        private OfflineTtsKittenModelConfig kitten = OfflineTtsKittenModelConfig.builder().build();
        private OfflineTtsPocketModelConfig pocket = OfflineTtsPocketModelConfig.builder().build();
        private OfflineTtsSupertonicModelConfig supertonic = OfflineTtsSupertonicModelConfig.builder().build();
        private int numThreads = 1;
        private boolean debug = true;
        private String provider = "cpu";

        public OfflineTtsModelConfig build() {
            return new OfflineTtsModelConfig(this);
        }

        public Builder setVits(OfflineTtsVitsModelConfig offlineTtsVitsModelConfig) {
            this.vits = offlineTtsVitsModelConfig;
            return this;
        }

        public Builder setMatcha(OfflineTtsMatchaModelConfig offlineTtsMatchaModelConfig) {
            this.matcha = offlineTtsMatchaModelConfig;
            return this;
        }

        public Builder setKokoro(OfflineTtsKokoroModelConfig offlineTtsKokoroModelConfig) {
            this.kokoro = offlineTtsKokoroModelConfig;
            return this;
        }

        public Builder setZipvoice(OfflineTtsZipVoiceModelConfig offlineTtsZipVoiceModelConfig) {
            this.zipvoice = offlineTtsZipVoiceModelConfig;
            return this;
        }

        public Builder setKitten(OfflineTtsKittenModelConfig offlineTtsKittenModelConfig) {
            this.kitten = offlineTtsKittenModelConfig;
            return this;
        }

        public Builder setPocket(OfflineTtsPocketModelConfig offlineTtsPocketModelConfig) {
            this.pocket = offlineTtsPocketModelConfig;
            return this;
        }

        public Builder setSupertonic(OfflineTtsSupertonicModelConfig offlineTtsSupertonicModelConfig) {
            this.supertonic = offlineTtsSupertonicModelConfig;
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

