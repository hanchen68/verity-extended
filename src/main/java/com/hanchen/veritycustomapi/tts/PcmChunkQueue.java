package com.hanchen.veritycustomapi.tts;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.function.LongSupplier;

/**
 * TTS PCM 分块队列。
 *
 * 音频引擎按固定长度来要数据，而 TTS 是分块吐出来的，这个类负责把两头对上：
 * 把已到达的分块拼成引擎要的长度；队列空了就补一小段静音顶上（否则音源会因为没数据而停掉）；
 * 声明收尾后数据放完就返回 {@code null}，让音源自然结束、被引擎回收。
 *
 * 刻意不依赖任何游戏类，方便单独验证。
 *
 * 线程约定：{@link #read} 由音频引擎的线程调用，因此这里绝不能阻塞等待数据 ——
 * 卡住它会拖住整条声音线程。
 */
final class PcmChunkQueue {

    /** 补静音块的长度（毫秒）。块越小，接不上数据时插进去的静音越不容易被听出来。 */
    static final int DEFAULT_SILENCE_CHUNK_MS = 80;
    /** 迟迟没有新数据时的容忍时间（毫秒），超过就不再补静音。 */
    static final long DEFAULT_IDLE_TIMEOUT_MS = 4000L;

    private final AudioFormat format;
    private final int frameSize;
    private final int sampleRate;
    private final int silenceChunkBytes;
    private final long idleTimeoutMs;
    private final LongSupplier clock;

    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private long queuedBytes;
    private byte[] pending;
    private int pendingOffset;
    private boolean finished;
    private boolean closed;
    private long lastDataAtNanos;

    PcmChunkQueue(AudioFormat format) {
        this(format, DEFAULT_SILENCE_CHUNK_MS, DEFAULT_IDLE_TIMEOUT_MS, System::nanoTime);
    }

    PcmChunkQueue(AudioFormat format, int silenceChunkMs, long idleTimeoutMs, LongSupplier clock) {
        if (format == null) throw new IllegalArgumentException("format 不能为空");
        this.format = format;
        this.frameSize = Math.max(2, format.getFrameSize());
        this.sampleRate = Math.max(1, Math.round(format.getSampleRate()));
        this.silenceChunkBytes = Math.max(frameSize, frameSize * sampleRate * silenceChunkMs / 1000);
        this.idleTimeoutMs = Math.max(0L, idleTimeoutMs);
        this.clock = clock != null ? clock : System::nanoTime;
        this.lastDataAtNanos = this.clock.getAsLong();
    }

    AudioFormat format() {
        return format;
    }

    int frameSize() {
        return frameSize;
    }

    int sampleRate() {
        return sampleRate;
    }

    /** 入队一段 PCM。入队后不再引用调用方的数组，调用方可以复用。 */
    synchronized void enqueue(byte[] data, int offset, int length) {
        if (closed || data == null || length <= 0) return;
        if (offset < 0 || offset + length > data.length) return;
        byte[] copy = new byte[length];
        System.arraycopy(data, offset, copy, 0, length);
        queue.addLast(copy);
        queuedBytes += length;
        lastDataAtNanos = clock.getAsLong();
    }

    /** 还没被取走的字节数（含取到一半的那块）。 */
    synchronized long queuedBytes() {
        return queuedBytes;
    }

    synchronized boolean isFinished() {
        return finished;
    }

    synchronized boolean isClosed() {
        return closed;
    }

    /** 声明不会再有新数据；已入队的会继续放完。 */
    synchronized void markFinished() {
        finished = true;
    }

    /** 立即丢弃所有数据并永久关闭。 */
    synchronized void markClosed() {
        closed = true;
        queue.clear();
        queuedBytes = 0L;
        pending = null;
        pendingOffset = 0;
    }

    /**
     * 取最多 {@code requestedBytes} 字节。
     *
     * @return 缓冲区；返回 {@code null} 表示「本次没有数据可给」——
     * 收尾放完、已被关闭、或者数据源卡太久，此时音源会自然停掉
     */
    synchronized ByteBuffer read(int requestedBytes) {
        if (closed || requestedBytes <= 0) return null;

        int capacity = Math.max(frameSize, requestedBytes - requestedBytes % frameSize);
        ByteBuffer output = null;
        int written = 0;

        if (pending == null) takeNextChunkLocked();
        while (written < capacity && pending != null) {
            int available = pending.length - pendingOffset;
            int take = Math.min(available, capacity - written);
            if (output == null) output = newDirectBuffer(capacity);
            output.put(pending, pendingOffset, take);
            written += take;
            pendingOffset += take;
            queuedBytes -= take;
            if (pendingOffset >= pending.length) {
                pending = null;
                pendingOffset = 0;
                takeNextChunkLocked();
            }
        }

        if (written > 0) {
            output.flip();
            return output;
        }

        // 队列空了：已收尾就交给引擎自然结束
        if (finished) return null;
        // 数据源卡住太久，放弃补静音，让音源停掉被回收
        if (clock.getAsLong() - lastDataAtNanos > idleTimeoutMs * 1_000_000L) return null;

        int silenceBytes = Math.min(capacity, silenceChunkBytes);
        silenceBytes -= silenceBytes % frameSize;
        if (silenceBytes <= 0) return null;
        // 直接缓冲区默认全 0，正好是 PCM 静音
        ByteBuffer silence = newDirectBuffer(silenceBytes);
        silence.limit(silenceBytes);
        return silence;
    }

    private void takeNextChunkLocked() {
        if (pending != null || queue.isEmpty()) return;
        pending = queue.pollFirst();
        pendingOffset = 0;
    }

    /** 音频引擎要把缓冲区直接交给 OpenAL，必须是直接缓冲区。 */
    private static ByteBuffer newDirectBuffer(int capacity) {
        return ByteBuffer.allocateDirect(Math.max(2, capacity));
    }
}
