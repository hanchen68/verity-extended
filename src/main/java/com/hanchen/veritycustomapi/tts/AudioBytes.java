package com.hanchen.veritycustomapi.tts;

import javax.sound.sampled.AudioFormat;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 音频字节层面的小工具：PCM 包 WAV、WAV/MP3 头识别、十六进制打印、JSON 转义。
 *
 * <p>抽自 {@code OnlineTtsService}。这些方法只处理 {@code byte[]}，
 * 不碰网络也不碰播放器状态，所以能独立出来——TTS 出问题时最常需要的就是
 * 「这坨字节到底是什么」，这些函数正是排查工具。
 *
 * <p>⚠️ WAV 头是**小端序**且字段顺序固定，改任何一行都会让播放器识别失败。
 */
public final class AudioBytes {

    private AudioBytes() {
    }

    /**
     * 给裸 PCM 加上 44 字节 WAV 头。
     *
     * <p>很多 TTS 接口返回的是没有文件头的裸 PCM，直接丢给播放器会放不出来，
     * 所以这里按标准 RIFF/WAVE 结构补头。
     */
    public static byte[] toWav(byte[] pcmData, AudioFormat format) throws IOException {
        int sampleRate = (int) format.getSampleRate();
        int channels = format.getChannels();
        int bitsPerSample = format.getSampleSizeInBits();
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        int dataSize = pcmData.length;
        int fileSize = 36 + dataSize;
        ByteArrayOutputStream out = new ByteArrayOutputStream(44 + dataSize);
        writeAscii(out, "RIFF");
        writeIntLE(out, fileSize);
        writeAscii(out, "WAVE");
        writeAscii(out, "fmt ");
        writeIntLE(out, 16);
        writeShortLE(out, 1);
        writeShortLE(out, channels);
        writeIntLE(out, sampleRate);
        writeIntLE(out, byteRate);
        writeShortLE(out, blockAlign);
        writeShortLE(out, bitsPerSample);
        writeAscii(out, "data");
        writeIntLE(out, dataSize);
        out.write(pcmData);
        return out.toByteArray();
    }

    public static void writeAscii(ByteArrayOutputStream out, String value) {
        for (int i = 0; i < value.length(); i++) {
            out.write(value.charAt(i));
        }
    }

    /** 写 4 字节小端整数。 */
    public static void writeIntLE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    /** 写 2 字节小端整数。 */
    public static void writeShortLE(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    /**
     * 在 WAV 里找 {@code data} 块的起点。
     *
     * <p>不能写死 44：真实文件常在 fmt 和 data 之间塞 LIST/fact 等附加块，
     * 写死偏移会把块头当成音频数据，听起来就是一段爆音。
     *
     * @return data 块之后第一个字节的偏移；找不到时退回 44（大多数文件的标准头长度）
     */
    public static int findWavDataStart(byte[] rawData) {
        int offset = 12;
        while (offset + 8 <= rawData.length) {
            int chunkSize = (rawData[offset + 4] & 0xFF)
                    | (rawData[offset + 5] & 0xFF) << 8
                    | (rawData[offset + 6] & 0xFF) << 16
                    | (rawData[offset + 7] & 0xFF) << 24;
            if (rawData[offset] == 'd' && rawData[offset + 1] == 'a'
                    && rawData[offset + 2] == 't' && rawData[offset + 3] == 'a') {
                return offset + 8;
            }
            if (chunkSize < 0) break;
            long next = (long) offset + 8L + chunkSize + (chunkSize & 1);
            if (next > rawData.length) break;
            offset = (int) next;
        }
        return Math.min(44, rawData.length);
    }

    /** 是否是 RIFF/WAVE 头。 */
    public static boolean isWavHeader(byte[] data) {
        if (data.length < 12) return false;
        return data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'A' && data[10] == 'V' && data[11] == 'E';
    }

    /** 是否是 MP3（ID3 标签或帧同步头）。 */
    public static boolean isMp3Header(byte[] data) {
        if (data.length < 3) return false;
        if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xE0) == 0xE0) {
            return true;
        }
        return data[0] == 'I' && data[1] == 'D' && data[2] == '3';
    }

    /** 十六进制打印前 N 个字节，排查「接口返回的到底是什么」时用。 */
    public static String hexDump(byte[] data, int length) {
        if (data == null || data.length == 0) return "empty";
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(length, data.length);
        for (int i = 0; i < limit; i++) {
            sb.append(String.format("%02X ", data[i] & 0xFF));
        }
        return sb.toString().trim();
    }

    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 最小 JSON 字符串转义（用于手工拼请求体）。 */
    public static String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /** 取前 N 个字节当 UTF-8 文本，用于识别「返回的其实是错误 JSON」。 */
    public static String headText(byte[] data, int limit) {
        if (data == null || data.length == 0) return "";
        return new String(data, 0, Math.min(limit, data.length), StandardCharsets.UTF_8);
    }
}
