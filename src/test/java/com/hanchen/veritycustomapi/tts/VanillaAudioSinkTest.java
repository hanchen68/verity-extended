package com.hanchen.veritycustomapi.tts;

import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.util.function.LongSupplier;

/**
 * TTS 音频队列的自检。
 *
 * 这一层是「把 TTS 的 PCM 塞进游戏原版音频通道」的搬运工，出问题都很隐蔽：
 * 少放几个字节就是听感上的杂音，提前返回 null 会把整段语音截断，
 * 该补静音时不补则会让音源停掉、后面的数据全丢。这几条边界都钉在这。
 */
public final class VanillaAudioSinkTest {

    /** 与原版 TTS 一致的格式：24kHz / 16bit / 单声道 / 有符号 / 小端。 */
    private static final AudioFormat FORMAT = new AudioFormat(24000.0F, 16, 1, true, false);

    private VanillaAudioSinkTest() {
    }

    public static void main(String[] args) {
        readsBackExactlyWhatWasPushed();
        mergesChunksAcrossBoundaries();
        keepsRemainderAcrossReads();
        alignsRequestedSizeToFrameSize();
        fillsSilenceWhileWaitingForData();
        stopsFillingSilenceAfterIdleTimeout();
        drainsThenEndsAfterFinish();
        dropsEverythingWhenClosed();
        ignoresBadEnqueue();
        ignoresReadsAfterFinishWithoutData();

        System.out.println("Vanilla audio sink queue checks passed");
    }

    // 用例

    private static void readsBackExactlyWhatWasPushed() {
        PcmChunkQueue queue = newQueue();
        byte[] source = pattern(4800);
        queue.enqueue(source, 0, source.length);

        ByteBuffer out = queue.read(48000);
        assertNotNull(out, "有数据时必须返回缓冲区");
        assertEquals(4800, out.remaining(), "应原样返回入队的字节数");
        assertBytes(source, out, "内容必须逐字节一致");
        assertEquals(0L, queue.queuedBytes(), "取完后队列应为空");
    }

    private static void mergesChunksAcrossBoundaries() {
        PcmChunkQueue queue = newQueue();
        queue.enqueue(pattern(1000), 0, 1000);
        queue.enqueue(pattern(1000), 0, 1000);

        ByteBuffer out = queue.read(48000);
        assertNotNull(out, "两块小数据应合并成一次读取");
        assertEquals(2000, out.remaining(), "合并后长度应为两块之和");
        assertBytes(pattern(1000), out, "第一块内容必须一致");
        assertBytes(pattern(1000), out, "第二块内容必须一致");
    }

    private static void keepsRemainderAcrossReads() {
        PcmChunkQueue queue = newQueue();
        byte[] source = pattern(3000);
        queue.enqueue(source, 0, source.length);

        ByteBuffer first = queue.read(2048);
        assertNotNull(first, "第一次读取应有数据");
        assertEquals(2048, first.remaining(), "第一次应取满请求长度");
        assertBytes(slice(source, 0, 2048), first, "第一段内容必须一致");

        ByteBuffer second = queue.read(2048);
        assertNotNull(second, "剩余数据必须能继续取出来");
        assertEquals(952, second.remaining(), "剩余长度应为 3000-2048");
        assertBytes(slice(source, 2048, 3000), second, "第二段内容必须一致");
        assertEquals(0L, queue.queuedBytes(), "全部取完后队列应为空");
    }

    private static void alignsRequestedSizeToFrameSize() {
        PcmChunkQueue queue = newQueue();
        queue.enqueue(pattern(4800), 0, 4800);

        // 请求 3 字节会被对齐到 1 帧（2 字节），避免交出半帧数据
        ByteBuffer out = queue.read(3);
        assertNotNull(out, "应能读出数据");
        assertEquals(2, out.remaining(), "请求长度必须向下对齐到整帧");
        assertEquals(4798L, queue.queuedBytes(), "只应消耗对齐后的字节数");
    }

    private static void fillsSilenceWhileWaitingForData() {
        long[] now = {0L};
        PcmChunkQueue queue = newQueue(now);

        // 还没收到任何数据、也没收尾：必须补静音，否则音源会因为没数据直接停掉
        ByteBuffer silence = queue.read(48000);
        assertNotNull(silence, "数据没到时要补静音撑住音源");
        assertEquals(3840, silence.remaining(), "静音块应为 80ms（24000*2*80/1000）");
        assertAllZero(silence, "补的必须是真静音");
        assertFalse(queue.isFinished(), "此时还没收尾");
    }

    private static void stopsFillingSilenceAfterIdleTimeout() {
        long[] now = {0L};
        PcmChunkQueue queue = newQueue(now);

        now[0] = 4_000_000_001L;
        assertNull(queue.read(48000), "数据源卡太久后必须放弃，让音源自然停掉");

        // 收到新数据后应恢复
        queue.enqueue(pattern(480), 0, 480);
        now[0] = 4_000_000_002L;
        ByteBuffer out = queue.read(48000);
        assertNotNull(out, "重新有数据后必须继续输出");
        assertEquals(480, out.remaining(), "新数据的长度必须一致");
    }

    private static void drainsThenEndsAfterFinish() {
        PcmChunkQueue queue = newQueue();
        queue.enqueue(pattern(1000), 0, 1000);
        queue.markFinished();

        ByteBuffer out = queue.read(48000);
        assertNotNull(out, "收尾后已入队的数据仍要放完");
        assertEquals(1000, out.remaining(), "长度必须一致");
        assertNull(queue.read(48000), "放完后必须返回 null 让音源结束，而不是补静音");
        assertNull(queue.read(48000), "结束状态应保持");
    }

    private static void dropsEverythingWhenClosed() {
        PcmChunkQueue queue = newQueue();
        queue.enqueue(pattern(1000), 0, 1000);
        queue.markClosed();

        assertEquals(0L, queue.queuedBytes(), "关闭应丢弃全部积压数据");
        assertNull(queue.read(48000), "关闭后不能再输出数据");

        queue.enqueue(pattern(1000), 0, 1000);
        assertEquals(0L, queue.queuedBytes(), "关闭后入队应被忽略");
    }

    private static void ignoresBadEnqueue() {
        PcmChunkQueue queue = newQueue();
        byte[] source = pattern(16);

        queue.enqueue(null, 0, 4);
        queue.enqueue(source, 0, 0);
        queue.enqueue(source, -1, 4);
        queue.enqueue(source, 8, 16);
        assertEquals(0L, queue.queuedBytes(), "非法入队必须被忽略");

        // 入队后不得再引用调用方的数组
        byte[] reference = pattern(16);
        queue.enqueue(reference, 4, 8);
        java.util.Arrays.fill(reference, (byte) 0x7F);
        ByteBuffer out = queue.read(48000);
        assertNotNull(out, "合法入队应生效");
        assertEquals(8, out.remaining(), "长度应为入队长度");
        assertBytes(slice(pattern(16), 4, 12), out, "入队时必须复制，后续改动源数组不应影响队列");
    }

    private static void ignoresReadsAfterFinishWithoutData() {
        PcmChunkQueue queue = newQueue();
        queue.markFinished();
        assertNull(queue.read(48000), "没数据又已收尾时必须直接结束");

        PcmChunkQueue closed = newQueue();
        assertNull(closed.read(0), "请求长度非法时应返回 null");
        assertNull(closed.read(-8), "请求长度非法时应返回 null");
    }

    // 工具

    private static PcmChunkQueue newQueue() {
        return newQueue(new long[]{0L});
    }

    private static PcmChunkQueue newQueue(long[] now) {
        LongSupplier clock = () -> now[0];
        return new PcmChunkQueue(FORMAT, PcmChunkQueue.DEFAULT_SILENCE_CHUNK_MS,
                PcmChunkQueue.DEFAULT_IDLE_TIMEOUT_MS, clock);
    }

    private static byte[] pattern(int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) ((i * 31 + 7) & 0xFF);
        }
        return data;
    }

    private static byte[] slice(byte[] source, int from, int to) {
        byte[] out = new byte[to - from];
        System.arraycopy(source, from, out, 0, out.length);
        return out;
    }

    private static void assertBytes(byte[] expected, ByteBuffer actual, String message) {
        int length = expected.length;
        if (actual.remaining() < length) {
            throw new AssertionError(message + ": 可用字节不足 expected=" + length + ", actual=" + actual.remaining());
        }
        int base = actual.position();
        for (int i = 0; i < length; i++) {
            byte got = actual.get(base + i);
            if (expected[i] != got) {
                throw new AssertionError(message + ": 第 " + i + " 字节不符 expected="
                        + (expected[i] & 0xFF) + ", actual=" + (got & 0xFF));
            }
        }
        actual.position(base + length);
    }

    private static void assertAllZero(ByteBuffer buffer, String message) {
        int base = buffer.position();
        for (int i = 0; i < buffer.remaining(); i++) {
            if (buffer.get(base + i) != 0) {
                throw new AssertionError(message + ": 第 " + i + " 字节不是 0");
            }
        }
    }

    private static void assertEquals(long expected, long actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertNotNull(Object value, String message) {
        if (value == null) {
            throw new AssertionError(message);
        }
    }

    private static void assertNull(Object value, String message) {
        if (value != null) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean value, String message) {
        if (value) {
            throw new AssertionError(message);
        }
    }
}
