package com.hanchen.veritycustomapi.compat;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TTS 播放期间把 VerityEntity.clientIsTalking 置 true，
 * 让实体渲染器放出说话动画（跟着声音一伸一缩的那种）。
 */
@OnlyIn(Dist.CLIENT)
public class VerityEntityTalkAnimator {
    private static final AtomicLong GENERATION = new AtomicLong();
    private static final ConcurrentHashMap<Integer, TalkState> TALKING = new ConcurrentHashMap<>();

    private static final class TalkState {
        private final long token;
        private volatile float level;
        private volatile long updatedAtNanos;
        private volatile byte[] timeline;
        private volatile int sampleRate;
        private volatile int frameSize;
        private volatile long timelineStartedAtNanos;

        private TalkState(long token) {
            this.token = token;
            this.updatedAtNanos = System.nanoTime();
        }
    }

    public static long begin(int entityId) {
        if (entityId < 0) return 0;
        long token = GENERATION.incrementAndGet();
        TALKING.put(entityId, new TalkState(token));
        return token;
    }

    public static void end(int entityId, long token) {
        if (entityId < 0 || token == 0) return;
        TALKING.computeIfPresent(entityId, (id, state) -> state.token == token ? null : state);
    }

    public static boolean isTalking(int entityId) {
        return entityId >= 0 && TALKING.containsKey(entityId);
    }

    public static void updatePcm(int entityId, long token, byte[] pcm, int offset, int length) {
        TalkState state = TALKING.get(entityId);
        if (state == null || state.token != token) return;
        state.timeline = null;
        updateLevel(state, measureLevel(pcm, offset, length));
    }

    public static void startPcmTimeline(
            int entityId,
            long token,
            byte[] pcm,
            int sampleRate,
            int frameSize
    ) {
        TalkState state = TALKING.get(entityId);
        if (state == null || state.token != token || pcm == null || pcm.length == 0) return;
        state.timeline = pcm;
        state.sampleRate = Math.max(1, sampleRate);
        state.frameSize = Math.max(2, frameSize);
        state.timelineStartedAtNanos = System.nanoTime();
        state.updatedAtNanos = state.timelineStartedAtNanos;
    }

    public static float getAudioLevel(int entityId) {
        TalkState state = TALKING.get(entityId);
        if (state == null) return -1.0F;
        byte[] timeline = state.timeline;
        if (timeline != null) {
            long elapsedNanos = Math.max(0L, System.nanoTime() - state.timelineStartedAtNanos);
            long frame = Math.round(elapsedNanos * state.sampleRate / 1_000_000_000.0);
            long byteOffset = frame * state.frameSize;
            int offset = (int) Math.min(timeline.length, Math.max(0L, byteOffset));
            int window = Math.max(state.frameSize, state.sampleRate * state.frameSize / 30);
            updateLevel(state, measureLevel(timeline, offset, Math.min(window, timeline.length - offset)));
        } else {
            long quietNanos = System.nanoTime() - state.updatedAtNanos;
            if (quietNanos > 120_000_000L) {
                state.level *= 0.82F;
                state.updatedAtNanos = System.nanoTime();
            }
        }
        return state.level;
    }

    private static void updateLevel(TalkState state, float target) {
        float factor = target > state.level ? 0.65F : 0.28F;
        state.level += (target - state.level) * factor;
        state.updatedAtNanos = System.nanoTime();
    }

    private static float measureLevel(byte[] pcm, int offset, int length) {
        if (pcm == null || length < 2 || offset < 0 || offset >= pcm.length) return 0.0F;
        int end = Math.min(pcm.length, offset + length);
        double sumSquares = 0.0;
        int samples = 0;
        for (int i = offset; i + 1 < end; i += 2) {
            int sample = (short) ((pcm[i + 1] << 8) | (pcm[i] & 0xFF));
            double normalized = sample / 32768.0;
            sumSquares += normalized * normalized;
            samples++;
        }
        if (samples == 0) return 0.0F;
        double rms = Math.sqrt(sumSquares / samples);
        double normalized = (rms - 0.006) / 0.19;
        return (float) Math.max(0.0, Math.min(1.0, Math.sqrt(Math.max(0.0, normalized))));
    }

    public static void clear() {
        TALKING.clear();
    }
}
