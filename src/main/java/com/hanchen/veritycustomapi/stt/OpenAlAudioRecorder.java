package com.hanchen.veritycustomapi.stt;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALC11;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

public final class OpenAlAudioRecorder {
    private static final int FORMAT_MONO16 = 0x1101;
    private static final int[] SAMPLE_RATE_CANDIDATES = new int[] {16000, 44100, 48000, 22050, 8000};

    private volatile long device;
    private int sampleRate = 16000;
    private volatile ByteArrayOutputStream audioBuffer;
    private Thread captureThread;
    private volatile boolean recording;

    public boolean start() {
        if (recording) {
            return false;
        }
        release();
        if (!ALC10.alcIsExtensionPresent(0L, "ALC_EXT_CAPTURE")) {
            OllamaProxyMod.LOGGER.warn("FCL/OpenAL 当前环境不支持 ALC_EXT_CAPTURE，无法使用 OpenAL 录音");
            return false;
        }
        for (int rate : SAMPLE_RATE_CANDIDATES) {
            long candidate = ALC11.alcCaptureOpenDevice((ByteBuffer) null, rate, FORMAT_MONO16, rate * 5);
            if (candidate != 0L) {
                device = candidate;
                sampleRate = rate;
                break;
            }
            OllamaProxyMod.LOGGER.debug("FCL/OpenAL 录音设备打开失败: rate={}", rate);
        }
        if (device == 0L) {
            OllamaProxyMod.LOGGER.warn("FCL/OpenAL 没有可用的录音设备");
            return false;
        }
        audioBuffer = new ByteArrayOutputStream();
        recording = true;
        ALC11.alcCaptureStart(device);
        captureThread = new Thread(this::captureLoop, "OpenAL-Capture-Thread");
        captureThread.setDaemon(true);
        captureThread.start();
        OllamaProxyMod.LOGGER.info("FCL/OpenAL 在线语音识别已开始录音，采样率 {}Hz", sampleRate);
        return true;
    }

    public byte[] stop() {
        recording = false;
        try {
            if (captureThread != null) {
                captureThread.join(2000L);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        drainAvailableSamples();
        byte[] data;
        synchronized (this) {
            data = audioBuffer == null ? new byte[0] : audioBuffer.toByteArray();
        }
        release();
        OllamaProxyMod.LOGGER.info("FCL/OpenAL 录音结束，PCM大小 {} bytes", data.length);
        return data;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public void release() {
        recording = false;
        if (device != 0L) {
            try {
                ALC11.alcCaptureStop(device);
            } catch (Throwable ignored) {
            }
            try {
                ALC11.alcCaptureCloseDevice(device);
            } catch (Throwable ignored) {
            }
            device = 0L;
        }
        captureThread = null;
        if (audioBuffer != null) {
            try { audioBuffer.close(); } catch (Throwable ignored) { }
            audioBuffer = null;
        }
    }

    private void captureLoop() {
        while (recording) {
            try {
                drainAvailableSamples();
                Thread.sleep(25L);
            } catch (Throwable t) {
                OllamaProxyMod.LOGGER.warn("FCL/OpenAL 录音读取异常: {}", t.getMessage());
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void drainAvailableSamples() {
        if (device == 0L || audioBuffer == null) {
            return;
        }
        int samples = ALC10.alcGetInteger(device, ALC11.ALC_CAPTURE_SAMPLES);
        if (samples <= 0) {
            return;
        }
        ByteBuffer buffer = ByteBuffer.allocateDirect(samples * 2);
        ALC11.alcCaptureSamples(device, buffer, samples);
        byte[] bytes = new byte[samples * 2];
        buffer.get(bytes);
        synchronized (this) {
            audioBuffer.write(bytes, 0, bytes.length);
        }
    }
}
