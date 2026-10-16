package com.hanchen.veritycustomapi.stt;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.nio.charset.StandardCharsets;

@OnlyIn(Dist.CLIENT)
public interface VoskNative extends Library {
    // 双重保险：确保 JNA 使用 UTF-8 编码，与OllamaProxyMod里的static块互补
    static boolean initUtf8Encoding() {
        System.setProperty("jna.encoding", "UTF-8");
        return true;
    }

    VoskNative INSTANCE = initUtf8Encoding()
            ? Native.load("vosk", VoskNative.class)
            : null;

    Pointer vosk_model_new(String modelPath);
    void vosk_model_free(Pointer model);

    Pointer vosk_recognizer_new(Pointer model, float sampleRate);
    void vosk_recognizer_free(Pointer recognizer);
    void vosk_recognizer_set_words(Pointer recognizer, int enableWords);

    int vosk_recognizer_accept_waveform(Pointer recognizer, byte[] audioData, int length);
    Pointer vosk_recognizer_result(Pointer recognizer);
    Pointer vosk_recognizer_final_result(Pointer recognizer);

    /**
     * 从原生指针读取完整的UTF-8字符串，完全绕开系统编码
     */
    static String readUtf8String(Pointer ptr) {
        if (ptr == null) return "";
        long strLen = ptr.indexOf(0, (byte) 0);
        if (strLen == -1) return "";
        byte[] rawBytes = ptr.getByteArray(0, (int) strLen);
        return new String(rawBytes, StandardCharsets.UTF_8);
    }
}