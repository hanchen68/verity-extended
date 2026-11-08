/*
 * Decompiled with CFR 0.152.
 */
package com.k2fsa.sherpa.onnx;

import com.k2fsa.sherpa.onnx.LibraryLoader;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationConfig;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class SpokenLanguageIdentification {
    private final Map<String, String> localeMap;
    private long ptr = 0L;

    public SpokenLanguageIdentification(SpokenLanguageIdentificationConfig spokenLanguageIdentificationConfig) {
        LibraryLoader.maybeLoad();
        this.ptr = this.newFromFile(spokenLanguageIdentificationConfig);
        if (this.ptr == 0L) {
            throw new IllegalArgumentException("Invalid SpokenLanguageIdentificationConfig: failed to create native SpokenLanguageIdentification");
        }
        String[] stringArray = Locale.getISOLanguages();
        this.localeMap = new HashMap<String, String>(stringArray.length);
        for (String string : stringArray) {
            Locale locale = new Locale(string);
            this.localeMap.put(string, locale.getDisplayName());
        }
    }

    public String compute(OfflineStream offlineStream) {
        String string = this.compute(this.ptr, offlineStream.getPtr());
        return this.localeMap.getOrDefault(string, string);
    }

    public OfflineStream createStream() {
        long l = this.createStream(this.ptr);
        return new OfflineStream(l);
    }

    protected void finalize() throws Throwable {
        this.release();
    }

    public void release() {
        if (this.ptr == 0L) {
            return;
        }
        this.delete(this.ptr);
        this.ptr = 0L;
    }

    private native void delete(long var1);

    private native long newFromFile(SpokenLanguageIdentificationConfig var1);

    private native long createStream(long var1);

    private native String compute(long var1, long var3);
}

