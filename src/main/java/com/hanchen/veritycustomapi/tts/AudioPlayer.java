package com.hanchen.veritycustomapi.tts;

import com.hanchen.veritycustomapi.util.Silent;

import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.compat.RuntimePlatform;
import com.hanchen.veritycustomapi.compat.VerityEntityTalkAnimator;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.AudioSystem;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@OnlyIn(Dist.CLIENT)
public class AudioPlayer {
    private static volatile SourceDataLine currentLine;
    private static volatile ExecutorService playbackExecutor = createPlaybackExecutor();

    private static ExecutorService createPlaybackExecutor() {
        return new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(2),
                runnable -> {
                    Thread thread = new Thread(runnable, "TTS-PCM-Player");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    private static synchronized ExecutorService ensurePlaybackExecutor() {
        if (playbackExecutor == null || playbackExecutor.isShutdown()) {
            playbackExecutor = createPlaybackExecutor();
        }
        return playbackExecutor;
    }

    public static void stopPlayback() {
        SourceDataLine line;
        synchronized (AudioPlayer.class) {
            line = currentLine;
            currentLine = null;
        }
        if (line != null) {
            try {
                line.stop();
                line.flush();
                line.close();
            } catch (Throwable ignored) { Silent.ignore("AudioPlayer", ignored); }
        }
        VanillaAudioSink.stopAll();
    }

    // 异步播放，不指定发声实体（entityId = -1）
    public static void playAsync(float[] pcmData) {
        playAsync(pcmData, -1);
    }

    public static void playAsync(float[] pcmData, int entityId) {
        if (pcmData == null || pcmData.length == 0) return;
        try {
            ensurePlaybackExecutor().execute(() -> playPcm(pcmData, entityId));
        } catch (RejectedExecutionException e) {
            OllamaProxyMod.LOGGER.warn("本地 TTS 播放队列已满，已丢弃本次音频");
        }
    }

    public static synchronized void shutdown() {
        stopPlayback();
        ExecutorService executor = playbackExecutor;
        playbackExecutor = null;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private static void playPcm(float[] pcmData, int entityId) {
        int sampleRate = ConfigManager.getTtsSampleRate();
        AudioFormat format = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sampleRate,
                16,
                1,
                2,
                sampleRate,
                false
        );

        // float转16位PCM字节
        byte[] bytes = new byte[pcmData.length * 2];
        for (int i = 0; i < pcmData.length; i++) {
            int sample = (int) (Math.max(-1f, Math.min(1f, pcmData[i])) * 32767);
            bytes[i * 2] = (byte) (sample & 0xFF);
            bytes[i * 2 + 1] = (byte) ((sample >> 8) & 0xFF);
        }

        long talkToken = com.hanchen.veritycustomapi.compat.VerityEntityTalkAnimator.begin(entityId);
        try {
            com.hanchen.veritycustomapi.compat.VerityEntityTalkAnimator.startPcmTimeline(
                    entityId, talkToken, bytes, sampleRate, format.getFrameSize());

            // 优先交给游戏原版音频通道：跟随主音量滑块、与其它声音统一混音、可选 3D 定位。
            // 🔴 这条路走的是 MC 自己的 OpenAL Channel，**不依赖 Java Sound**，
            // 所以手机端（FCL / Pojav / Zalith）也能出声——不能因为「Android 没有 Java Sound」
            // 就把整个播放提前 return 掉，那会让手机端 TTS 完全静音。
            if (VanillaAudioSink.play(format, bytes, 0, bytes.length,
                    (float) ConfigManager.getTtsVolume(), entityId)) {
                return;
            }

            // 原版通道拿不到才回退 Java Sound；Android 上它确实不可用，安静跳过。
            if (RuntimePlatform.isAndroidRuntime()) {
                OllamaProxyMod.LOGGER.warn(
                        "原版音频通道不可用，且 Android/FCL 环境不支持 Java Sound 回退，本次 TTS 未播放");
                return;
            }
            writeToJavaSound(format, bytes);
        } catch (Throwable e) {
            OllamaProxyMod.LOGGER.warn("音频播放失败，当前运行环境可能不支持 Java Sound: {}", e.getMessage());
        } finally {
            com.hanchen.veritycustomapi.compat.VerityEntityTalkAnimator.end(entityId, talkToken);
        }
    }

    /** 扩展自带的 Java Sound 输出路径；原版音频通道不可用时的回退。 */
    private static void writeToJavaSound(AudioFormat format, byte[] bytes) throws Exception {
        SourceDataLine line = null;
        try {
            line = AudioSystem.getSourceDataLine(format);
            synchronized (AudioPlayer.class) {
                currentLine = line;
            }
            line.open(format);
            line.start();
            line.write(bytes, 0, bytes.length);
            line.drain();
            line.stop();
            line.close();
        } finally {
            if (line != null) {
                try {
                    line.close();
                } catch (Throwable ignored) { Silent.ignore("AudioPlayer", ignored); }
            }
            synchronized (AudioPlayer.class) {
                if (currentLine == line) {
                    currentLine = null;
                }
            }
        }
    }
}
