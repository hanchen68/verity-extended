package com.hanchen.veritycustomapi.stt;

import com.hanchen.veritycustomapi.util.Silent;

import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.compat.RuntimePlatform;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.sound.sampled.*;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 语音识别主服务，统一管理音频采集与识别器调度
 */
@OnlyIn(Dist.CLIENT)
public class VoiceRecognitionService {
    private static final int TARGET_SAMPLE_RATE = 16000;
    private static final int SAMPLE_SIZE_BITS = 16;
    private static final int CHANNELS = 1;
    private static final boolean SIGNED = true;

    private volatile SpeechRecognizer recognizer;
    private TargetDataLine microphone;
    private AndroidAudioRecorder androidAudioRecorder;
    private OpenAlAudioRecorder openAlAudioRecorder;
    private Thread captureThread;
    private ByteArrayOutputStream audioBuffer;
    private final AtomicBoolean isRecording = new AtomicBoolean(false);
    private int actualSampleRate = TARGET_SAMPLE_RATE;
    private boolean captureBigEndian = false;
    private volatile String unavailableReason = "语音识别模块尚未初始化完成";
    private final Object resourceLock = new Object();

    /**
     * 无参构造：从配置文件自动读取模式与路径
     */
    public VoiceRecognitionService() {
        try {
            initRecognizer();
        } catch (Throwable t) {
            OllamaProxyMod.LOGGER.error("语音识别服务初始化失败，语音功能不可用", t);
            recognizer = null;
        }
    }

    /**
     * 带参构造：直接指定 Vosk 模型路径（兼容旧代码）
     */
    public VoiceRecognitionService(String voskModelPath) {
        if (RuntimePlatform.isAndroidRuntime()) {
            OllamaProxyMod.LOGGER.warn("检测到 Android/FCL 运行环境，已禁用指定路径的本地 Vosk 语音识别");
            unavailableReason = "当前是 Android/FCL 环境，离线识别不可用";
            recognizer = null;
            return;
        }
        try {
            String runtimeIssue = VoskRuntimeSupport.getUnavailableReason();
            if (runtimeIssue != null) {
                unavailableReason = runtimeIssue;
                recognizer = null;
                return;
            }
            Class<?> voskClass = Class.forName("com.hanchen.veritycustomapi.stt.LocalVoskRecognizer");
            recognizer = (SpeechRecognizer) voskClass
                    .getConstructor(String.class)
                    .newInstance(voskModelPath);
            OllamaProxyMod.LOGGER.debug("语音识别模式：本地 Vosk 离线模式（指定路径）");
            unavailableReason = recognizer != null && recognizer.isReady()
                    ? null
                    : buildLocalUnavailableReason(voskModelPath);
        } catch (Throwable t) {
            OllamaProxyMod.LOGGER.error("语音识别服务初始化失败，语音功能不可用", t);
            unavailableReason = buildLocalUnavailableReason(voskModelPath);
            recognizer = null;
        }
    }

    private void initRecognizer() {
        ConfigManager.RecognitionMode mode = ConfigManager.getRecognitionMode();
        if (RuntimePlatform.isAndroidRuntime() && mode == ConfigManager.RecognitionMode.LOCAL) {
            mode = ConfigManager.RecognitionMode.API;
            OllamaProxyMod.LOGGER.warn("检测到 Android/FCL 运行环境，已将本地 Vosk 语音识别降级为在线 API 模式");
        }
        if (mode == ConfigManager.RecognitionMode.API) {
            String formatStr = ConfigManager.getSpeechRequestFormat().toLowerCase();
            String authStr = ConfigManager.getSpeechAuthType().toLowerCase();
            String responseField = ConfigManager.getSpeechResponseField();
            
            ApiSpeechRecognizer.RequestFormat format = ApiSpeechRecognizer.RequestFormat.MULTIPART_FORM_DATA;
            if (formatStr.contains("json")) {
                format = ApiSpeechRecognizer.RequestFormat.JSON;
            } else if (formatStr.contains("url")) {
                format = ApiSpeechRecognizer.RequestFormat.URL_ENCODED;
            }
            
            ApiSpeechRecognizer.AuthType authType = ApiSpeechRecognizer.AuthType.BEARER;
            if (authStr.contains("api_key_header")) {
                authType = ApiSpeechRecognizer.AuthType.API_KEY_HEADER;
            } else if (authStr.contains("api_key_param")) {
                authType = ApiSpeechRecognizer.AuthType.API_KEY_PARAM;
            } else if (authStr.contains("custom")) {
                authType = ApiSpeechRecognizer.AuthType.CUSTOM_HEADER;
            }
            
            recognizer = new ApiSpeechRecognizer(
                    ConfigManager.getSpeechApiUrl(),
                    ConfigManager.getSpeechApiKey(),
                    ConfigManager.getSpeechApiModel(),
                    format,
                    authType,
                    responseField,
                    null
            );
            OllamaProxyMod.LOGGER.debug("语音识别模式：在线 API 模式（格式：{}，认证：{}，响应字段：{}）", 
                    format, authType, responseField);
            unavailableReason = recognizer != null && recognizer.isReady() ? null : "在线语音识别服务不可用";
        } else {
            try {
                String runtimeIssue = VoskRuntimeSupport.getUnavailableReason();
                if (runtimeIssue != null) {
                    unavailableReason = runtimeIssue;
                    recognizer = null;
                    return;
                }
                Class<?> voskClass = Class.forName("com.hanchen.veritycustomapi.stt.LocalVoskRecognizer");
                recognizer = (SpeechRecognizer) voskClass
                        .getConstructor(String.class)
                        .newInstance(ConfigManager.getVoskModelPath());
                OllamaProxyMod.LOGGER.debug("语音识别模式：本地 Vosk 离线模式");
                unavailableReason = recognizer != null && recognizer.isReady()
                        ? null
                        : buildLocalUnavailableReason(ConfigManager.getVoskModelPath());
            } catch (Throwable t) {
                OllamaProxyMod.LOGGER.error("Vosk 库未找到或初始化失败，离线语音识别不可用", t);
                unavailableReason = buildLocalUnavailableReason(ConfigManager.getVoskModelPath());
                recognizer = null;
            }
        }
    }

    public boolean isReady() {
        return recognizer != null && recognizer.isReady();
    }

    public boolean isRecording() {
        return isRecording.get();
    }

    public String getUnavailableReason() {
        if (isReady()) {
            return null;
        }
        if (unavailableReason != null && !unavailableReason.isBlank()) {
            return unavailableReason;
        }
        return "语音识别模块未就绪，请稍后再试";
    }

    public boolean startListening() {
        if (!isReady() || !isRecording.compareAndSet(false, true)) return false;
        if (RuntimePlatform.isAndroidRuntime() || RuntimePlatform.hasAndroidAudioRecord()) {
            boolean started = RuntimePlatform.hasAndroidAudioRecord()
                    ? startAndroidListening()
                    : startOpenAlListening();
            if (!started) releaseResources();
            return started;
        }
        try {
            int preferredRate = ConfigManager.getSpeechSampleRate();
            int[] rateCandidates = new int[]{preferredRate, 48000, 44100, 32000, 24000, 22050, 16000, 11025, 8000};

            TargetDataLine line = null;
            AudioFormat selectedFormat = null;
            for (int rate : rateCandidates) {
                if (rate <= 0) continue;
                for (boolean bigEndian : new boolean[]{false, true}) {
                    AudioFormat format = new AudioFormat(rate, SAMPLE_SIZE_BITS, CHANNELS, SIGNED, bigEndian);
                    DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
                    if (!AudioSystem.isLineSupported(info)) continue;
                    try {
                        line = (TargetDataLine) AudioSystem.getLine(info);
                        line.open(format);
                        selectedFormat = format;
                        captureBigEndian = bigEndian;
                        actualSampleRate = rate;
                        break;
                    } catch (javax.sound.sampled.LineUnavailableException e) {
                        // 格式声明支持但实际打开失败，尝试下一个
                        if (line != null) { try { line.close(); } catch (Exception ignored) { Silent.ignore("VoiceRecognitionService", ignored); } line = null; }
                    }
                }
                if (line != null) break;
            }

            if (line == null || selectedFormat == null) {
                if (RuntimePlatform.hasAndroidMediaClasses()) {
                    OllamaProxyMod.LOGGER.warn("桌面音频采集不可用，回退 Android/FCL AudioRecord");
                    return startAndroidListening();
                }
                if (RuntimePlatform.isAndroidRuntime()) {
                    OllamaProxyMod.LOGGER.warn("桌面音频采集不可用，回退 FCL/OpenAL Capture");
                    return startOpenAlListening();
                }
                OllamaProxyMod.LOGGER.error("当前系统不支持任何可用的音频采集格式（16-bit mono PCM），请检查音频输入设备");
                releaseResources();
                return false;
            }

            microphone = line;
            microphone.start();
            audioBuffer = new ByteArrayOutputStream();
            isRecording.set(true);
            captureThread = new Thread(this::captureLoop, "Audio-Capture-Thread");
            captureThread.setDaemon(true);
            captureThread.start();
            return true;
        } catch (Throwable t) {
            if (RuntimePlatform.hasAndroidMediaClasses()) {
                OllamaProxyMod.LOGGER.warn("桌面录音启动失败，回退 Android/FCL AudioRecord", t);
                releaseResources();
                return startAndroidListening();
            }
            if (RuntimePlatform.isAndroidRuntime()) {
                OllamaProxyMod.LOGGER.warn("桌面录音启动失败，回退 FCL/OpenAL Capture", t);
                releaseResources();
                return startOpenAlListening();
            }
            OllamaProxyMod.LOGGER.error("启动录音失败", t);
            releaseResources();
            return false;
        }
    }

    public String stopListening() {
        if (!isRecording.get()) return "";
        if (openAlAudioRecorder != null) {
            return stopOpenAlListening();
        }
        if (RuntimePlatform.isAndroidRuntime() || androidAudioRecorder != null) {
            return stopAndroidListening();
        }
        isRecording.set(false);
        try {
            if (captureThread != null) captureThread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String result = "";
        try {
            byte[] audioData = audioBuffer.toByteArray();
            if (audioData.length > 1024 && recognizer != null) {
                byte[] pcm16le = audioData;
                if (captureBigEndian) {
                    pcm16le = swap16(pcm16le);
                }
                if (actualSampleRate > 0 && actualSampleRate != TARGET_SAMPLE_RATE) {
                    pcm16le = resamplePcm16Mono(pcm16le, actualSampleRate, TARGET_SAMPLE_RATE);
                }
                result = recognizer.recognize(pcm16le);
            }
        } catch (Throwable t) {
            OllamaProxyMod.LOGGER.error("语音识别执行失败", t);
        }
        releaseResources();
        return result;
    }

    private boolean startAndroidListening() {
        androidAudioRecorder = new AndroidAudioRecorder();
        if (!androidAudioRecorder.start()) {
            androidAudioRecorder = null;
            return startOpenAlListening();
        }
        actualSampleRate = androidAudioRecorder.getSampleRate();
        captureBigEndian = false;
        isRecording.set(true);
        return true;
    }

    private boolean startOpenAlListening() {
        openAlAudioRecorder = new OpenAlAudioRecorder();
        if (!openAlAudioRecorder.start()) {
            openAlAudioRecorder = null;
            return false;
        }
        actualSampleRate = openAlAudioRecorder.getSampleRate();
        captureBigEndian = false;
        isRecording.set(true);
        return true;
    }

    private String stopAndroidListening() {
        isRecording.set(false);
        byte[] pcm16le = new byte[0];
        try {
            pcm16le = androidAudioRecorder != null ? androidAudioRecorder.stop() : new byte[0];
            androidAudioRecorder = null;
            return recognizeCapturedPcm(pcm16le, "Android/FCL");
        } catch (Throwable t) {
            OllamaProxyMod.LOGGER.error("Android/FCL 在线语音识别执行失败", t);
            return "";
        } finally {
            releaseResources();
        }
    }

    private String stopOpenAlListening() {
        isRecording.set(false);
        try {
            byte[] pcm16le = openAlAudioRecorder != null ? openAlAudioRecorder.stop() : new byte[0];
            openAlAudioRecorder = null;
            return recognizeCapturedPcm(pcm16le, "FCL/OpenAL");
        } catch (Throwable t) {
            OllamaProxyMod.LOGGER.error("FCL/OpenAL 在线语音识别执行失败", t);
            return "";
        } finally {
            releaseResources();
        }
    }

    private String recognizeCapturedPcm(byte[] pcm16le, String sourceName) throws Exception {
        if (pcm16le.length <= 1024) {
            OllamaProxyMod.LOGGER.warn("{} 录音数据过短: {} bytes，可能未授予麦克风权限、系统拦截录音或按键时间过短", sourceName, pcm16le.length);
            return "";
        }
        if (recognizer == null) {
            return "";
        }
        if (actualSampleRate > 0 && actualSampleRate != TARGET_SAMPLE_RATE) {
            OllamaProxyMod.LOGGER.info("{} 录音采样率 {}Hz，识别前重采样到 {}Hz", sourceName, actualSampleRate, TARGET_SAMPLE_RATE);
            pcm16le = resamplePcm16Mono(pcm16le, actualSampleRate, TARGET_SAMPLE_RATE);
        }
        return recognizer.recognize(pcm16le);
    }

    /**
     * 直接从 WAV 字节数组识别语音（兼容旧代码调用）
     */
    public String recognizeFromWav(byte[] audioData) {
        if (!isReady() || audioData == null || audioData.length < 44) {
            return "";
        }
        try {
            // 跳过 WAV 文件头 44 字节，取出 PCM 数据
            byte[] pcmData = new byte[audioData.length - 44];
            System.arraycopy(audioData, 44, pcmData, 0, pcmData.length);
            return recognizer.recognize(pcmData);
        } catch (Throwable t) {
            OllamaProxyMod.LOGGER.error("WAV 音频识别失败", t);
            return "";
        }
    }

    private void captureLoop() {
        byte[] buffer = new byte[4096];
        try {
            while (isRecording.get()) {
                TargetDataLine line;
                ByteArrayOutputStream buf;
                synchronized (resourceLock) {
                    line = microphone;
                    buf = audioBuffer;
                }
                if (line == null || buf == null) break;
                int read = line.read(buffer, 0, buffer.length);
                if (read > 0) buf.write(buffer, 0, read);
            }
        } catch (Throwable t) {
            OllamaProxyMod.LOGGER.error("音频采集过程异常", t);
        }
    }

    private void releaseResources() {
        synchronized (resourceLock) {
            try {
                if (androidAudioRecorder != null) {
                    androidAudioRecorder.release();
                    androidAudioRecorder = null;
                }
                if (openAlAudioRecorder != null) {
                    openAlAudioRecorder.release();
                    openAlAudioRecorder = null;
                }
                if (microphone != null) {
                    microphone.stop();
                    microphone.close();
                    microphone = null;
                }
                if (audioBuffer != null) {
                    audioBuffer.close();
                    audioBuffer = null;
                }
            } catch (Throwable t) {
                OllamaProxyMod.LOGGER.error("音频资源释放异常", t);
            }
        }
        isRecording.set(false);
        actualSampleRate = TARGET_SAMPLE_RATE;
        captureBigEndian = false;
    }

    /**
     * 兼容旧代码的 close 方法
     */
    public void close() {
        shutdown();
    }

    public void shutdown() {
        cancelListening();
        try {
            if (recognizer != null) {
                recognizer.shutdown();
                recognizer = null;
            }
        } catch (Throwable t) {
            OllamaProxyMod.LOGGER.error("识别器资源释放异常", t);
        }
        unavailableReason = "语音识别模块尚未初始化完成";
    }

    public void cancelListening() {
        isRecording.set(false);
        Thread thread = captureThread;
        captureThread = null;
        if (thread != null) {
            thread.interrupt();
        }
        releaseResources();
    }

    private String buildLocalUnavailableReason(String modelPath) {
        String runtimeIssue = VoskRuntimeSupport.getUnavailableReason();
        if (runtimeIssue != null) {
            return runtimeIssue;
        }
        Path resolvedModelPath = (modelPath == null || modelPath.isBlank()) ? null : Paths.get(modelPath);
        if (!VoskRuntimeSupport.hasUsableModel(resolvedModelPath)) {
            return "离线语音模型未就位，请先安装或检查模型路径";
        }
        return "离线语音识别初始化失败，请查看日志";
    }

    private static byte[] swap16(byte[] bytes) {
        if (bytes == null || bytes.length < 2) return bytes;
        int len = bytes.length - (bytes.length % 2);
        byte[] out = new byte[len];
        for (int i = 0; i < len; i += 2) {
            out[i] = bytes[i + 1];
            out[i + 1] = bytes[i];
        }
        return out;
    }

    private static byte[] resamplePcm16Mono(byte[] pcm16le, int fromRate, int toRate) {
        if (pcm16le == null) return new byte[0];
        if (fromRate <= 0 || toRate <= 0 || fromRate == toRate) return pcm16le;

        int inSamples = pcm16le.length / 2;
        if (inSamples <= 1) return pcm16le;

        int outSamples = (int) Math.max(1, Math.round(inSamples * (double) toRate / (double) fromRate));
        byte[] out = new byte[outSamples * 2];

        for (int i = 0; i < outSamples; i++) {
            double pos = i * (double) fromRate / (double) toRate;
            int idx = (int) Math.floor(pos);
            double frac = pos - idx;
            if (idx < 0) idx = 0;
            if (idx >= inSamples - 1) idx = inSamples - 2;
            if (idx < 0) continue; // 无法处理的情况，跳过

            short s0 = (short) ((pcm16le[idx * 2] & 0xff) | (pcm16le[idx * 2 + 1] << 8));
            short s1 = (short) ((pcm16le[(idx + 1) * 2] & 0xff) | (pcm16le[(idx + 1) * 2 + 1] << 8));

            int sample = (int) Math.round(s0 * (1.0 - frac) + s1 * frac);
            if (sample > Short.MAX_VALUE) sample = Short.MAX_VALUE;
            if (sample < Short.MIN_VALUE) sample = Short.MIN_VALUE;

            out[i * 2] = (byte) (sample & 0xff);
            out[i * 2 + 1] = (byte) ((sample >> 8) & 0xff);
        }

        return out;
    }
}
