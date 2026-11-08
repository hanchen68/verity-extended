/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import com.k2fsa.sherpa.onnx.LibraryUtils;

public class LibraryLoader {
    private static volatile boolean autoLoadEnabled = true;
    private static volatile boolean isLoaded = false;

    static synchronized void loadLibrary() {
        if (!isLoaded) {
            LibraryUtils.load();
            isLoaded = true;
        }
    }

    public static void setAutoLoadEnabled(boolean bl) {
        autoLoadEnabled = bl;
    }

    static void maybeLoad() {
        if (autoLoadEnabled) {
            LibraryLoader.loadLibrary();
        }
    }
}

