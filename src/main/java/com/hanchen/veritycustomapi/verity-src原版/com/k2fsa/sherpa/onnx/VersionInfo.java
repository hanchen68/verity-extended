/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import com.k2fsa.sherpa.onnx.LibraryLoader;

public class VersionInfo {
    public static String getVersion() {
        LibraryLoader.maybeLoad();
        return VersionInfo.getVersionStr2();
    }

    public static String getGitSha1() {
        LibraryLoader.maybeLoad();
        return VersionInfo.getGitSha12();
    }

    public static String getGitDate() {
        LibraryLoader.maybeLoad();
        return VersionInfo.getGitDate2();
    }

    private static native String getVersionStr2();

    private static native String getGitSha12();

    private static native String getGitDate2();
}

