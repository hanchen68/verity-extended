/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

public class HomophoneReplacerConfig {
    private final String dictDir;
    private final String lexicon;
    private final String ruleFsts;

    private HomophoneReplacerConfig(Builder builder) {
        this.dictDir = builder.dictDir;
        this.lexicon = builder.lexicon;
        this.ruleFsts = builder.ruleFsts;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getDictDir() {
        return this.dictDir;
    }

    public String getLexicon() {
        return this.lexicon;
    }

    public String getRuleFsts() {
        return this.ruleFsts;
    }

    public static class Builder {
        private String dictDir = "";
        private String lexicon = "";
        private String ruleFsts = "";

        public HomophoneReplacerConfig build() {
            return new HomophoneReplacerConfig(this);
        }

        public Builder setDictDir(String string) {
            this.dictDir = string;
            return this;
        }

        public Builder setLexicon(String string) {
            this.lexicon = string;
            return this;
        }

        public Builder setRuleFsts(String string) {
            this.ruleFsts = string;
            return this;
        }
    }
}

