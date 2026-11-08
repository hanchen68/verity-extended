/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import com.k2fsa.sherpa.onnx.OfflineCanaryModelConfig;
import com.k2fsa.sherpa.onnx.OfflineCohereTranscribeModelConfig;
import com.k2fsa.sherpa.onnx.OfflineDolphinModelConfig;
import com.k2fsa.sherpa.onnx.OfflineFireRedAsrCtcModelConfig;
import com.k2fsa.sherpa.onnx.OfflineFireRedAsrModelConfig;
import com.k2fsa.sherpa.onnx.OfflineFunAsrNanoModelConfig;
import com.k2fsa.sherpa.onnx.OfflineMedAsrCtcModelConfig;
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig;
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig;
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig;
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.OfflineWenetCtcModelConfig;
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig;
import com.k2fsa.sherpa.onnx.OfflineZipformerCtcModelConfig;

public class OfflineModelConfig {
    private final OfflineTransducerModelConfig transducer;
    private final OfflineParaformerModelConfig paraformer;
    private final OfflineWhisperModelConfig whisper;
    private final OfflineFireRedAsrModelConfig fireRedAsr;
    private final OfflineMoonshineModelConfig moonshine;
    private final OfflineNemoEncDecCtcModelConfig nemo;
    private final OfflineSenseVoiceModelConfig senseVoice;
    private final OfflineDolphinModelConfig dolphin;
    private final OfflineZipformerCtcModelConfig zipformerCtc;
    private final OfflineWenetCtcModelConfig wenetCtc;
    private final OfflineOmnilingualAsrCtcModelConfig omnilingual;
    private final OfflineMedAsrCtcModelConfig medasr;
    private final OfflineFireRedAsrCtcModelConfig fireRedAsrCtc;
    private final OfflineFunAsrNanoModelConfig funasrNano;
    private final OfflineQwen3AsrModelConfig qwen3Asr;
    private final OfflineCanaryModelConfig canary;
    private final OfflineCohereTranscribeModelConfig cohereTranscribe;
    private final String teleSpeech;
    private final String tokens;
    private final int numThreads;
    private final boolean debug;
    private final String provider;
    private final String modelType;
    private final String modelingUnit;
    private final String bpeVocab;

    private OfflineModelConfig(Builder builder) {
        this.transducer = builder.transducer;
        this.paraformer = builder.paraformer;
        this.whisper = builder.whisper;
        this.fireRedAsr = builder.fireRedAsr;
        this.moonshine = builder.moonshine;
        this.nemo = builder.nemo;
        this.zipformerCtc = builder.zipformerCtc;
        this.canary = builder.canary;
        this.wenetCtc = builder.wenetCtc;
        this.omnilingual = builder.omnilingual;
        this.medasr = builder.medasr;
        this.fireRedAsrCtc = builder.fireRedAsrCtc;
        this.funasrNano = builder.funasrNano;
        this.qwen3Asr = builder.qwen3Asr;
        this.senseVoice = builder.senseVoice;
        this.dolphin = builder.dolphin;
        this.cohereTranscribe = builder.cohereTranscribe;
        this.teleSpeech = builder.teleSpeech;
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

    public OfflineParaformerModelConfig getParaformer() {
        return this.paraformer;
    }

    public OfflineTransducerModelConfig getTransducer() {
        return this.transducer;
    }

    public OfflineWhisperModelConfig getWhisper() {
        return this.whisper;
    }

    public OfflineMoonshineModelConfig getMoonshine() {
        return this.moonshine;
    }

    public OfflineSenseVoiceModelConfig getSenseVoice() {
        return this.senseVoice;
    }

    public OfflineDolphinModelConfig getDolphin() {
        return this.dolphin;
    }

    public OfflineNemoEncDecCtcModelConfig getNemo() {
        return this.nemo;
    }

    public OfflineZipformerCtcModelConfig getZipformerCtc() {
        return this.zipformerCtc;
    }

    public OfflineWenetCtcModelConfig getWenetCtc() {
        return this.wenetCtc;
    }

    public OfflineOmnilingualAsrCtcModelConfig getOmnilingual() {
        return this.omnilingual;
    }

    public OfflineMedAsrCtcModelConfig getMedAsr() {
        return this.medasr;
    }

    public OfflineFireRedAsrCtcModelConfig getFireRedAsrCtc() {
        return this.fireRedAsrCtc;
    }

    public OfflineFireRedAsrModelConfig getFireRedAsr() {
        return this.fireRedAsr;
    }

    public OfflineFunAsrNanoModelConfig getFunAsrNano() {
        return this.funasrNano;
    }

    public OfflineQwen3AsrModelConfig getQwen3Asr() {
        return this.qwen3Asr;
    }

    public OfflineCanaryModelConfig getCanary() {
        return this.canary;
    }

    public OfflineCohereTranscribeModelConfig getCohereTranscribe() {
        return this.cohereTranscribe;
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

    public String getTeleSpeech() {
        return this.teleSpeech;
    }

    public static class Builder {
        private OfflineParaformerModelConfig paraformer = OfflineParaformerModelConfig.builder().build();
        private OfflineTransducerModelConfig transducer = OfflineTransducerModelConfig.builder().build();
        private OfflineWhisperModelConfig whisper = OfflineWhisperModelConfig.builder().build();
        private OfflineFireRedAsrModelConfig fireRedAsr = OfflineFireRedAsrModelConfig.builder().build();
        private OfflineMoonshineModelConfig moonshine = OfflineMoonshineModelConfig.builder().build();
        private OfflineNemoEncDecCtcModelConfig nemo = OfflineNemoEncDecCtcModelConfig.builder().build();
        private OfflineSenseVoiceModelConfig senseVoice = OfflineSenseVoiceModelConfig.builder().build();
        private OfflineDolphinModelConfig dolphin = OfflineDolphinModelConfig.builder().build();
        private OfflineZipformerCtcModelConfig zipformerCtc = OfflineZipformerCtcModelConfig.builder().build();
        private OfflineWenetCtcModelConfig wenetCtc = OfflineWenetCtcModelConfig.builder().build();
        private OfflineOmnilingualAsrCtcModelConfig omnilingual = OfflineOmnilingualAsrCtcModelConfig.builder().build();
        private OfflineMedAsrCtcModelConfig medasr = OfflineMedAsrCtcModelConfig.builder().build();
        private OfflineFireRedAsrCtcModelConfig fireRedAsrCtc = OfflineFireRedAsrCtcModelConfig.builder().build();
        private OfflineFunAsrNanoModelConfig funasrNano = OfflineFunAsrNanoModelConfig.builder().build();
        private OfflineQwen3AsrModelConfig qwen3Asr = OfflineQwen3AsrModelConfig.builder().build();
        private OfflineCanaryModelConfig canary = OfflineCanaryModelConfig.builder().build();
        private OfflineCohereTranscribeModelConfig cohereTranscribe = OfflineCohereTranscribeModelConfig.builder().build();
        private String teleSpeech = "";
        private String tokens = "";
        private int numThreads = 1;
        private boolean debug = true;
        private String provider = "cpu";
        private String modelType = "";
        private String modelingUnit = "cjkchar";
        private String bpeVocab = "";

        public OfflineModelConfig build() {
            return new OfflineModelConfig(this);
        }

        public Builder setTransducer(OfflineTransducerModelConfig offlineTransducerModelConfig) {
            this.transducer = offlineTransducerModelConfig;
            return this;
        }

        public Builder setDolphin(OfflineDolphinModelConfig offlineDolphinModelConfig) {
            this.dolphin = offlineDolphinModelConfig;
            return this;
        }

        public Builder setParaformer(OfflineParaformerModelConfig offlineParaformerModelConfig) {
            this.paraformer = offlineParaformerModelConfig;
            return this;
        }

        public Builder setNemo(OfflineNemoEncDecCtcModelConfig offlineNemoEncDecCtcModelConfig) {
            this.nemo = offlineNemoEncDecCtcModelConfig;
            return this;
        }

        public Builder setZipformerCtc(OfflineZipformerCtcModelConfig offlineZipformerCtcModelConfig) {
            this.zipformerCtc = offlineZipformerCtcModelConfig;
            return this;
        }

        public Builder setWenetCtc(OfflineWenetCtcModelConfig offlineWenetCtcModelConfig) {
            this.wenetCtc = offlineWenetCtcModelConfig;
            return this;
        }

        public Builder setOmnilingual(OfflineOmnilingualAsrCtcModelConfig offlineOmnilingualAsrCtcModelConfig) {
            this.omnilingual = offlineOmnilingualAsrCtcModelConfig;
            return this;
        }

        public Builder setMedAsr(OfflineMedAsrCtcModelConfig offlineMedAsrCtcModelConfig) {
            this.medasr = offlineMedAsrCtcModelConfig;
            return this;
        }

        public Builder setFireRedAsrCtc(OfflineFireRedAsrCtcModelConfig offlineFireRedAsrCtcModelConfig) {
            this.fireRedAsrCtc = offlineFireRedAsrCtcModelConfig;
            return this;
        }

        public Builder setFunAsrNano(OfflineFunAsrNanoModelConfig offlineFunAsrNanoModelConfig) {
            this.funasrNano = offlineFunAsrNanoModelConfig;
            return this;
        }

        public Builder setQwen3Asr(OfflineQwen3AsrModelConfig offlineQwen3AsrModelConfig) {
            this.qwen3Asr = offlineQwen3AsrModelConfig;
            return this;
        }

        public Builder setCanary(OfflineCanaryModelConfig offlineCanaryModelConfig) {
            this.canary = offlineCanaryModelConfig;
            return this;
        }

        public Builder setCohereTranscribe(OfflineCohereTranscribeModelConfig offlineCohereTranscribeModelConfig) {
            this.cohereTranscribe = offlineCohereTranscribeModelConfig;
            return this;
        }

        public Builder setTeleSpeech(String string) {
            this.teleSpeech = string;
            return this;
        }

        public Builder setWhisper(OfflineWhisperModelConfig offlineWhisperModelConfig) {
            this.whisper = offlineWhisperModelConfig;
            return this;
        }

        public Builder setFireRedAsr(OfflineFireRedAsrModelConfig offlineFireRedAsrModelConfig) {
            this.fireRedAsr = offlineFireRedAsrModelConfig;
            return this;
        }

        public Builder setSenseVoice(OfflineSenseVoiceModelConfig offlineSenseVoiceModelConfig) {
            this.senseVoice = offlineSenseVoiceModelConfig;
            return this;
        }

        public Builder setMoonshine(OfflineMoonshineModelConfig offlineMoonshineModelConfig) {
            this.moonshine = offlineMoonshineModelConfig;
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

