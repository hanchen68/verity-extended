package com.hanchen.veritycustomapi.stt;

import com.hanchen.veritycustomapi.util.Silent;

import com.hanchen.veritycustomapi.OllamaProxyMod;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Method;

public final class AndroidAudioRecorder {
    private static final int CHANNEL_IN_MONO = 16;
    private static final int ENCODING_PCM_16BIT = 2;
    private static final int AUDIO_SOURCE_DEFAULT = 0;
    private static final int AUDIO_SOURCE_MIC = 1;
    private static final int AUDIO_SOURCE_VOICE_UPLINK = 2;
    private static final int AUDIO_SOURCE_VOICE_DOWNLINK = 3;
    private static final int AUDIO_SOURCE_VOICE_CALL = 4;
    private static final int AUDIO_SOURCE_CAMCORDER = 5;
    private static final int AUDIO_SOURCE_VOICE_RECOGNITION = 6;
    private static final int AUDIO_SOURCE_VOICE_COMMUNICATION = 7;
    private static final int[] SAMPLE_RATE_CANDIDATES = new int[] {16000, 44100, 48000, 22050, 8000};
    private static final int[] AUDIO_SOURCE_CANDIDATES = new int[] {AUDIO_SOURCE_VOICE_RECOGNITION, AUDIO_SOURCE_VOICE_COMMUNICATION, AUDIO_SOURCE_MIC, AUDIO_SOURCE_DEFAULT, AUDIO_SOURCE_CAMCORDER, AUDIO_SOURCE_VOICE_CALL, AUDIO_SOURCE_VOICE_UPLINK, AUDIO_SOURCE_VOICE_DOWNLINK};

    private Object recorder;
    private int sampleRate = 16000;
    private Method readMethod;
    private Method stopMethod;
    private Method releaseMethod;
    private Thread captureThread;
    private ByteArrayOutputStream audioBuffer;
    private volatile boolean recording;

    public boolean start() {
        if (recording) {
            return false;
        }
        try {
            release();
            Class<?> audioRecordClass = Class.forName("android.media.AudioRecord");
            Method getMinBufferSize = audioRecordClass.getMethod("getMinBufferSize", int.class, int.class, int.class);
            Method getState = audioRecordClass.getMethod("getState");
            Method startRecording = audioRecordClass.getMethod("startRecording");
            readMethod = audioRecordClass.getMethod("read", byte[].class, int.class, int.class);
            stopMethod = audioRecordClass.getMethod("stop");
            releaseMethod = audioRecordClass.getMethod("release");

            int bufferSize = 0;
            int selectedSource = AUDIO_SOURCE_MIC;
            for (int rate : SAMPLE_RATE_CANDIDATES) {
                int minBufferSize = (Integer) getMinBufferSize.invoke(null, rate, CHANNEL_IN_MONO, ENCODING_PCM_16BIT);
                if (minBufferSize <= 0) {
                    OllamaProxyMod.LOGGER.debug("Android/FCL 麦克风采样率不可用: rate={}, minBuffer={}", rate, minBufferSize);
                    continue;
                }
                for (int source : AUDIO_SOURCE_CANDIDATES) {
                    Object candidate = null;
                    try {
                        int candidateBuffer = Math.max(minBufferSize * 4, rate);
                        candidate = audioRecordClass
                                .getConstructor(int.class, int.class, int.class, int.class, int.class)
                                .newInstance(source, rate, CHANNEL_IN_MONO, ENCODING_PCM_16BIT, candidateBuffer);
                        int state = (Integer) getState.invoke(candidate);
                        if (state == 1) {
                            recorder = candidate;
                            sampleRate = rate;
                            bufferSize = candidateBuffer;
                            selectedSource = source;
                            break;
                        }
                    } catch (Throwable t) {
                        OllamaProxyMod.LOGGER.debug("Android/FCL 麦克风候选初始化失败: source={}, rate={}, error={}", source, rate, t.getMessage());
                    }
                    tryReleaseCandidate(candidate, audioRecordClass);
                }
                if (recorder != null) {
                    break;
                }
            }

            if (recorder == null || bufferSize <= 0) {
                OllamaProxyMod.LOGGER.warn("Android/FCL 麦克风初始化失败，所有 AudioRecord 候选均不可用");
                release();
                return false;
            }

            audioBuffer = new ByteArrayOutputStream();
            recording = true;
            startRecording.invoke(recorder);
            final int selectedBufferSize = bufferSize;
            captureThread = new Thread(() -> captureLoop(selectedBufferSize), "Android-AudioRecord-Capture");
            captureThread.setDaemon(true);
            captureThread.start();
            OllamaProxyMod.LOGGER.info("Android/FCL 在线语音识别已开始录音，采样率 {}Hz，音源 {}", sampleRate, selectedSource);
            return true;
        } catch (ClassNotFoundException e) {
            // AudioRecord 在此平台不可用（桌面端/FCL 部分版本），静默回退
            OllamaProxyMod.LOGGER.debug("Android/FCL AudioRecord 类不可用: {}", e.getMessage());
            release();
            return false;
        } catch (Throwable t) {
            OllamaProxyMod.LOGGER.warn("Android/FCL 启动 AudioRecord 录音失败，请检查麦克风权限", t);
            release();
            return false;
        }
    }

    public byte[] stop() {
        recording = false;
        stopRecorder();
        if (captureThread != null) {
            try {
                captureThread.join(2000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            captureThread = null;
        }
        byte[] result;
        synchronized (this) {
            result = audioBuffer != null ? audioBuffer.toByteArray() : new byte[0];
        }
        release();
        OllamaProxyMod.LOGGER.info("Android/FCL 在线语音识别录音结束，PCM 大小: {} bytes", result.length);
        return result;
    }

    public boolean isRecording() {
        return recording;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    private static void tryReleaseCandidate(Object candidate, Class<?> audioRecordClass) {
        if (candidate == null) {
            return;
        }
        try {
            audioRecordClass.getMethod("release").invoke(candidate);
        } catch (Throwable ignored) { Silent.ignore("AndroidAudioRecorder", ignored); }
    }

    private void captureLoop(int bufferSize) {
        byte[] buffer = (byte[]) Array.newInstance(byte.class, Math.max(4096, bufferSize));
        while (recording) {
            try {
                int read = (Integer) readMethod.invoke(recorder, buffer, 0, buffer.length);
                if (read > 0) {
                    synchronized (this) {
                        if (audioBuffer != null) {
                            audioBuffer.write(buffer, 0, read);
                        }
                    }
                } else if (read < 0) {
                    if (recording) {
                        OllamaProxyMod.LOGGER.warn("Android/FCL AudioRecord 读取失败: code={}", read);
                        Thread.sleep(20L);
                    }
                }
            } catch (Throwable t) {
                if (recording) {
                    OllamaProxyMod.LOGGER.warn("Android/FCL 麦克风采集异常", t);
                }
                recording = false;
            }
        }
    }

    public void release() {
        recording = false;
        stopRecorder();
        try {
            if (recorder != null && releaseMethod != null) {
                releaseMethod.invoke(recorder);
            }
        } catch (Throwable ignored) { Silent.ignore("AndroidAudioRecorder", ignored); }
        recorder = null;
        readMethod = null;
        stopMethod = null;
        releaseMethod = null;
        synchronized (this) {
            try {
                if (audioBuffer != null) {
                    audioBuffer.close();
                }
            } catch (Throwable ignored) { Silent.ignore("AndroidAudioRecorder", ignored); }
            audioBuffer = null;
        }
    }

    private void stopRecorder() {
        try {
            if (recorder != null && stopMethod != null) {
                stopMethod.invoke(recorder);
            }
        } catch (Throwable ignored) { Silent.ignore("AndroidAudioRecorder", ignored); }
    }
}
