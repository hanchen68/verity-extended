package com.hanchen.veritycustomapi.stt;

import com.hanchen.veritycustomapi.compat.OriginalVerityVoiceBridge;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.sound.sampled.AudioFormat;

@OnlyIn(Dist.CLIENT)
public class OriginalVeritySpeechRecognizer implements SpeechRecognizer {
    private static final AudioFormat ORIGINAL_FORMAT = new AudioFormat(16000.0f, 16, 1, true, false);

    @Override
    public String recognize(byte[] pcmData) {
        return OriginalVerityVoiceBridge.transcribeAudio(pcmData, ORIGINAL_FORMAT);
    }

    @Override
    public boolean isReady() {
        return OriginalVerityVoiceBridge.isAvailable();
    }

    @Override
    public void shutdown() {
    }
}
