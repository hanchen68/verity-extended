package com.hanchen.veritycustomapi.tts;

import com.hanchen.veritycustomapi.OllamaProxyMod;

import java.nio.charset.StandardCharsets;

/**
 * 判断「上游回来的这一坨字节到底是不是音频」。
 *
 * <p>抽自 {@code OnlineTtsService}。抽出的理由不是行数，而是**这里每一个分支都是一次事故的产物**，
 * 散在 1900 行的服务类里改动风险极高，没人敢动。
 *
 * <p>真实踩过的坑（按发生顺序）：
 * <ul>
 *   <li>服务商额度用尽时返回 **JSON 错误体**，被当成音频交给播放器 → 刺耳噪音。→ 先查 JSON 错误。</li>
 *   <li>网关超时返回 **HTML 错误页**，同样被当音频。→ 查 <code>&lt;!doctype</code> / <code>&lt;html</code>。</li>
 *   <li>有的服务商 MP3 **不带标准帧头**，但长度上万，确实是音频。→ 长度兜底放行。</li>
 *   <li>空数据被误判成「超短音频」播放。→ 先卡最小长度。</li>
 *   <li>静音 PCM 全是 0 字节，听着就是没声音，玩家以为坏了。→ 数零字节比例。</li>
 * </ul>
 *
 * <p>判据的整体倾向是**宁可放行也不误杀**：多放行一段可疑数据的代价是听一次噪音，
 * 误杀一段正常音频的代价是「TTS 完全不出声」——后者玩家根本查不出原因。
 * 唯一的例外是「明显是错误响应」的那两种（JSON / HTML），它们一定要拦。
 */
public final class AudioValidator {

    private AudioValidator() {
    }

    /**
     * @param data   上游返回的原始字节
     * @param format 服务商声明的格式（wav / mp3 / pcm / 其他）
     * @return 是否像一段可播放的音频
     */
    public static boolean isValid(byte[] data, String format) {
        if (data == null || data.length < 10) {
            return false;
        }

        String head = new String(data, 0, Math.min(100, data.length), StandardCharsets.UTF_8);

        // 错误响应优先拦：把 JSON/HTML 当音频播是最刺耳的一种失败
        if (head.contains("{") && (head.contains("error") || head.contains("code"))) {
            OllamaProxyMod.LOGGER.warn("TTS响应包含错误JSON: {}", truncate(head));
            return false;
        }
        if (head.contains("<!doctype") || head.contains("<!DOCTYPE")
                || head.contains("<html") || head.contains("<HTML")) {
            OllamaProxyMod.LOGGER.warn("TTS响应包含HTML错误页面: {}", truncate(head));
            return false;
        }

        String normalized = format == null ? "" : format;

        if (normalized.equalsIgnoreCase("wav") && AudioBytes.isWavHeader(data)) {
            return true;
        }

        if (normalized.equalsIgnoreCase("mp3")) {
            if (AudioBytes.isMp3Header(data)) {
                return true;
            }
            if (data.length > 10000) {
                // 有些服务商的 MP3 没有标准帧头（或被加了前后附加数据），但长度说明它确实是音频
                OllamaProxyMod.LOGGER.warn("MP3数据长度>10000但无标准头，尝试播放");
                return true;
            }
            return false;
        }

        if (normalized.equalsIgnoreCase("pcm") && data.length > 100) {
            return true;
        }

        // 兜底：数前 100 字节里的 0，全零基本就是静音或空缓冲
        int nullCount = 0;
        for (int i = 0; i < Math.min(100, data.length); i++) {
            if (data[i] == 0) nullCount++;
        }
        if (nullCount > 80) {
            return false;
        }

        if (data.length > 10000) {
            OllamaProxyMod.LOGGER.warn("TTS数据长度>10000字节但无法识别格式，尝试播放");
            return true;
        }

        OllamaProxyMod.LOGGER.warn("TTS数据格式无法识别，长度: {} 字节，前20字节: {}",
                data.length, AudioBytes.hexDump(data, 20));
        return false;
    }

    private static String truncate(String text) {
        return text.length() > 200 ? text.substring(0, 200) : text;
    }
}
