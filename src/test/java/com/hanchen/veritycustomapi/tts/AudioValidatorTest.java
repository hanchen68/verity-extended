package com.hanchen.veritycustomapi.tts;

import java.nio.charset.StandardCharsets;

/**
 * TTS 音频格式校验的自检。
 *
 * <p>这段判断的每个分支都对应一次真实事故：JSON 错误体被当音频播（刺耳噪音）、
 * HTML 错误页被当音频、无标准帧头的 MP3 被误杀（TTS 完全不出声）、
 * 全零缓冲被播放（听着就是坏了）。
 *
 * <p>倾向是「宁可放行也不误杀」：误杀一段正常音频会让玩家听到彻底静音，
 * 而且日志里看不出原因；多放行一段可疑数据最多听一次噪音。
 * 唯一必须拦住的是明显属于「错误响应」的那两种。
 */
public final class AudioValidatorTest {

    private AudioValidatorTest() {
    }

    public static void main(String[] args) {
        rejectsTooShort();
        rejectsJsonErrorBody();
        rejectsHtmlErrorPage();
        acceptsWavByHeader();
        acceptsMp3ByHeader();
        acceptsLargeMp3WithoutHeader();
        rejectsSmallMp3WithoutHeader();
        acceptsPcmByLength();
        rejectsSilentBuffer();
        acceptsLargeUnknownFormat();

        System.out.println("AudioValidator checks passed");
    }

    private static void rejectsTooShort() {
        require(!AudioValidator.isValid(null, "mp3"), "null 不应被当成音频");
        require(!AudioValidator.isValid(new byte[9], "mp3"), "不足 10 字节不应被当成音频");
    }

    /** 额度用尽时服务商会回 JSON 错误体，绝不能交给播放器。 */
    private static void rejectsJsonErrorBody() {
        byte[] body = ("{\"code\":400,\"error\":\"quota exceeded\",\"pad\":\""
                + "x".repeat(80) + "\"}").getBytes(StandardCharsets.UTF_8);
        require(!AudioValidator.isValid(body, "mp3"), "JSON 错误体不应被当成音频");
    }

    /** 网关超时回 HTML 错误页，同样不能播。 */
    private static void rejectsHtmlErrorPage() {
        byte[] body = ("<!DOCTYPE html><html><body>502 Bad Gateway" + "x".repeat(80)
                + "</body></html>").getBytes(StandardCharsets.UTF_8);
        require(!AudioValidator.isValid(body, "mp3"), "HTML 错误页不应被当成音频");
    }

    private static void acceptsWavByHeader() {
        byte[] wav = new byte[2000];
        wav[0] = 'R';
        wav[1] = 'I';
        wav[2] = 'F';
        wav[3] = 'F';
        wav[8] = 'W';
        wav[9] = 'A';
        wav[10] = 'V';
        wav[11] = 'E';
        require(AudioValidator.isValid(wav, "wav"), "带 RIFF/WAVE 头的 wav 应通过");
    }

    private static void acceptsMp3ByHeader() {
        byte[] mp3 = new byte[500];
        // MPEG-1 Layer III 帧同步：11 个 1
        mp3[0] = (byte) 0xFF;
        mp3[1] = (byte) 0xFB;
        require(AudioValidator.isValid(mp3, "mp3"), "带帧同步字的 mp3 应通过");
    }

    /** 有些服务商的 MP3 没有标准帧头，但长度说明它确实是音频——不能误杀。 */
    private static void acceptsLargeMp3WithoutHeader() {
        byte[] mp3 = new byte[20000];
        for (int i = 0; i < mp3.length; i++) mp3[i] = (byte) (i % 200 + 20);
        require(AudioValidator.isValid(mp3, "mp3"), "长度上万的无头 mp3 应放行（宁可听噪音也不能静音）");
    }

    private static void rejectsSmallMp3WithoutHeader() {
        byte[] mp3 = new byte[500];
        for (int i = 0; i < mp3.length; i++) mp3[i] = (byte) (i % 200 + 20);
        require(!AudioValidator.isValid(mp3, "mp3"), "又小又无头的 mp3 应拒绝");
    }

    private static void acceptsPcmByLength() {
        byte[] pcm = new byte[500];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (byte) (i % 100 + 30);
        require(AudioValidator.isValid(pcm, "pcm"), "长度足够的 pcm 应通过");
    }

    /** 全零缓冲听着就是没声音，玩家会以为功能坏了。 */
    private static void rejectsSilentBuffer() {
        byte[] silent = new byte[5000];
        require(!AudioValidator.isValid(silent, "unknown-format"), "全零缓冲应被拒绝");
    }

    private static void acceptsLargeUnknownFormat() {
        byte[] data = new byte[20000];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i % 200 + 20);
        require(AudioValidator.isValid(data, "unknown-format"),
                "未声明格式但长度上万时应尝试播放");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
