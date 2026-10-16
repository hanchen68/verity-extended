package com.hanchen.veritycustomapi.tts;

import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.mixin.SoundEngineAccessor;
import com.hanchen.veritycustomapi.mixin.SoundManagerAccessor;
import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.Library;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 把扩展 TTS 的 PCM 音频交给游戏原版音频引擎播放。
 *
 * 做法：从原版 {@link SoundEngine} 里取出 {@link ChannelAccess}，申请一个流式通道，
 * 把 {@link AudioStream} 挂上去。原版每 tick 都会对「通道集合里的所有通道」调用
 * {@code updateStream()}，所以挂上去的流会被持续拉取，音频也就跟着原版一起混音输出。
 *
 * 比自己开一个 Java Sound 输出设备省事：主音量滑块（原版通过 OpenAL listener 增益实现）
 * 会自动作用到这段音频上，也不用额外占用/切换音频设备，还能按实体坐标做 3D 定位。
 *
 * 拿不到通道（专用服务器、音频未初始化、流式通道池已满、OpenAL 异常）时
 * {@link #open} 返回 {@code null}，调用方回退到原有的 Java Sound 路径。
 */
@OnlyIn(Dist.CLIENT)
public final class VanillaAudioSink implements AutoCloseable {

    /** 申请通道的等待上限；超时视为原版音频不可用。 */
    private static final long ACQUIRE_TIMEOUT_MS = 2000L;
    /** 流式模式下攒够这么多数据才开播，避免开头先播一段静音。 */
    private static final int PRESTART_MS = 250;
    /** 等待播放结束时过早放弃的兜底余量（毫秒）。 */
    private static final long WAIT_TAIL_MS = 3000L;

    /** 正在播放的实例；用于客户端 tick 刷新 3D 位置，以及外部强制停止。 */
    private static final Set<VanillaAudioSink> LIVE =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    private final ChannelAccess.ChannelHandle handle;
    private final PcmStream stream;
    private final float volume;
    private final int entityId;
    private final float attenuation;

    private volatile boolean started;
    private volatile boolean closed;

    private VanillaAudioSink(ChannelAccess.ChannelHandle handle, AudioFormat format,
                             float volume, int entityId, float attenuation) {
        this.handle = handle;
        this.stream = new PcmStream(format);
        this.volume = volume;
        this.entityId = entityId;
        this.attenuation = attenuation;
    }

    // ------------------------------------------------------------------ 对外入口

    /**
     * 一次给全整段 PCM 并同步等到播完。返回 true 表示这段音频已由原版通道处理，
     * 调用方不要再走 Java Sound；返回 false 表示没接管，调用方按原逻辑播放。
     *
     * @param entityId 播放源归属的实体，用于 3D 定位；-1 表示不定位
     */
    public static boolean play(AudioFormat format, byte[] pcm, int offset, int length,
                               float volume, int entityId) {
        if (!ConfigManager.isTtsVanillaChannelEnabled()) return false;
        if (pcm == null || length <= 0 || offset < 0 || offset + length > pcm.length) return false;
        VanillaAudioSink sink = open(format, volume, entityId);
        if (sink == null) return false;
        try {
            sink.push(pcm, offset, length);
            sink.finish();
            sink.awaitDone(estimateDurationMs(format, length) + WAIT_TAIL_MS);
            return true;
        } finally {
            sink.close();
        }
    }

    /** 申请通道并返回一个可增量喂数据的实例；失败返回 null。 */
    public static VanillaAudioSink open(AudioFormat format, float volume) {
        return open(format, volume, -1);
    }

    /** @param entityId 播放源归属的实体，用于 3D 定位；-1 表示不定位 */
    public static VanillaAudioSink open(AudioFormat format, float volume, int entityId) {
        if (format == null) return null;
        if (!isSupportedFormat(format)) return null;
        float attenuation = entityId >= 0 ? ConfigManager.getTtsAttenuationRadius() : 0.0F;
        try {
            ChannelAccess channelAccess = resolveChannelAccess();
            if (channelAccess == null) return null;
            CompletableFuture<ChannelAccess.ChannelHandle> pending =
                    channelAccess.createHandle(Library.Pool.STREAMING);
            ChannelAccess.ChannelHandle acquired = pending.get(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            // 流式通道池被占满时原版会给出 null，这里直接放弃、交给调用方回退
            if (acquired == null) return null;
            return new VanillaAudioSink(acquired, format, volume, entityId, attenuation);
        } catch (Throwable error) {
            if (ConfigManager.isDevMode()) {
                OllamaProxyMod.LOGGER.debug("原版音频通道不可用，改用 Java Sound: {}", error.toString());
            }
            return null;
        }
    }

    /** 增量喂入一段 PCM（流式 TTS 用）。 */
    public void push(byte[] data, int offset, int length) {
        if (closed || data == null || length <= 0) return;
        if (offset < 0 || offset + length > data.length) return;
        stream.enqueue(data, offset, length);
        maybeStart();
    }

    /** 声明不会再有新数据了；已喂入的音频播完后通道会自然回收。 */
    public void finish() {
        if (closed) return;
        stream.markFinished();
        maybeStart();
    }

    /** 轮询等待播放结束；返回 true 表示已结束（或已关闭）。 */
    public boolean awaitDone(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        while (System.currentTimeMillis() < deadline) {
            if (closed || handle.isStopped()) return true;
            try {
                Thread.sleep(20L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return true;
            }
        }
        return closed || handle.isStopped();
    }

    /**
     * 关闭。这里不主动调 {@code handle.release()}：原版会在下一次 tick 发现通道已停止后
     * 自己回收，从别的线程抢着释放会和声音线程打架。我们只负责让它停下来。
     */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        LIVE.remove(this);
        stream.markClosed();
        try {
            if (started) {
                handle.execute(Channel::stop);
            } else {
                // 从未 attach 过：直接 stop 只会让它停在 AL_INITIAL，原版不会回收。
                // 挂上这个已关闭的流再 play，音源会立刻进入 AL_STOPPED，下一 tick 就被收走。
                handle.execute(channel -> {
                    channel.attachBufferStream(stream);
                    channel.play();
                });
            }
        } catch (Throwable error) {
            if (ConfigManager.isDevMode()) {
                OllamaProxyMod.LOGGER.debug("释放原版音频通道失败: {}", error.toString());
            }
        }
    }

    // ------------------------------------------------------------------ 3D 定位

    /** 客户端 tick：把播放中的通道挪到实体当前坐标，让声音跟着实体走。 */
    public static void tickClient(Minecraft minecraft) {
        if (LIVE.isEmpty()) return;
        if (minecraft == null || minecraft.level == null) return;
        for (VanillaAudioSink sink : LIVE) {
            sink.refreshPosition(minecraft);
        }
    }

    /** 立即停止所有正在通过原版通道播放的 TTS（切会话、玩家主动打断时用）。 */
    public static void stopAll() {
        if (LIVE.isEmpty()) return;
        for (VanillaAudioSink sink : LIVE.toArray(new VanillaAudioSink[0])) {
            try {
                sink.close();
            } catch (Throwable ignored) {
            }
        }
        LIVE.clear();
    }

    /** 客户端退出时清理，避免残留静态引用。 */
    public static void clearAll() {
        stopAll();
    }

    private void refreshPosition(Minecraft minecraft) {
        if (closed || !started || entityId < 0 || attenuation <= 0.0F) return;
        if (handle.isStopped()) {
            LIVE.remove(this);
            return;
        }
        Entity entity = minecraft.level.getEntity(entityId);
        if (entity == null) return;
        Vec3 position = entity.position();
        try {
            handle.execute(channel -> channel.setSelfPosition(position));
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ 内部实现

    private void maybeStart() {
        if (started || closed) return;
        if (!stream.isFinished() && stream.queuedBytes() < startThresholdBytes()) return;
        start();
    }

    private synchronized void start() {
        if (started || closed) return;
        started = true;
        boolean spatial = entityId >= 0 && attenuation > 0.0F;
        try {
            handle.execute(channel -> {
                channel.setVolume(Math.max(0.0F, Math.min(4.0F, volume)));
                channel.setPitch(1.0F);
                if (spatial) {
                    channel.setRelative(false);
                    channel.linearAttenuation(attenuation);
                } else {
                    // 不做定位：贴着听者播放且不衰减，等价于「干净的一条声道」
                    channel.setRelative(true);
                    channel.disableAttenuation();
                }
                channel.attachBufferStream(stream);
                channel.play();
            });
        } catch (Throwable error) {
            started = false;
            if (ConfigManager.isDevMode()) {
                OllamaProxyMod.LOGGER.debug("原版音频通道启动失败: {}", error.toString());
            }
            close();
            return;
        }
        LIVE.add(this);
    }

    private int startThresholdBytes() {
        return Math.max(stream.frameSize(),
                stream.frameSize() * stream.sampleRate() * PRESTART_MS / 1000);
    }

    private static boolean isSupportedFormat(AudioFormat format) {
        int channels = format.getChannels();
        int bits = format.getSampleSizeInBits();
        // 原版 OpenAlUtil.audioFormatToOpenAl 只认 1/2 声道 + 8/16 位
        return (channels == 1 || channels == 2) && (bits == 8 || bits == 16);
    }

    private static ChannelAccess resolveChannelAccess() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) return null;
        SoundEngine engine = ((SoundManagerAccessor) minecraft.getSoundManager())
                .verityExtended$getSoundEngine();
        if (engine == null) return null;
        return ((SoundEngineAccessor) engine).verityExtended$getChannelAccess();
    }

    private static long estimateDurationMs(AudioFormat format, int bytes) {
        int frameSize = Math.max(1, format.getFrameSize());
        double sampleRate = Math.max(1.0, format.getSampleRate());
        return Math.round(bytes / (double) frameSize / sampleRate * 1000.0);
    }

    // ------------------------------------------------------------------ 音频流

    /**
     * 被原版音频引擎按需拉取的 PCM 流。
     *
     * 真正的拼接/补静音逻辑在 {@link PcmChunkQueue} 里，这里只负责把它接到原版接口上。
     */
    private static final class PcmStream implements AudioStream {

        private final PcmChunkQueue queue;

        private PcmStream(AudioFormat format) {
            this.queue = new PcmChunkQueue(format);
        }

        @Override
        public AudioFormat getFormat() {
            return queue.format();
        }

        int frameSize() {
            return queue.frameSize();
        }

        int sampleRate() {
            return queue.sampleRate();
        }

        void enqueue(byte[] data, int offset, int length) {
            queue.enqueue(data, offset, length);
        }

        long queuedBytes() {
            return queue.queuedBytes();
        }

        boolean isFinished() {
            return queue.isFinished();
        }

        void markFinished() {
            queue.markFinished();
        }

        void markClosed() {
            queue.markClosed();
        }

        @Override
        public ByteBuffer read(int requestedBytes) throws IOException {
            return queue.read(requestedBytes);
        }

        @Override
        public void close() throws IOException {
            queue.markClosed();
        }
    }
}
