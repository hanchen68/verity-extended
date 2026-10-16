package com.hanchen.veritycustomapi.stt;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 语音识别器通用接口
 */
@OnlyIn(Dist.CLIENT)
public interface SpeechRecognizer {
    /**
     * @param pcmData 16kHz 16bit 单声道 PCM 原始音频数据
     * @return 识别后的文本内容
     * @throws Exception 识别过程异常
     */
    String recognize(byte[] pcmData) throws Exception;

    /** 识别器是否就绪可用 */
    boolean isReady();

    /** 释放识别器资源 */
    void shutdown();
}
