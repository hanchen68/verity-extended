package com.hanchen.veritycustomapi.config;

import com.hanchen.veritycustomapi.OllamaProxyMod;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 配置值的小工具：空值收敛、分层归一、端点比对、密钥清洗、额度格式化。
 *
 * <p>抽自 {@code ConfigManager}。这些方法被上百处调用，但**本身没有任何状态**——
 * 放在 1900 行的门面里，改一个正则都要在 237 个方法之间找半天。
 *
 * <p>约定：**空值一律收敛成确定结果**（空串 / null / 默认值三选一，每个方法各自写明），
 * 调用方不需要再判空。这是这套工具能被放心复用的前提。
 */
public final class ConfigText {

    private ConfigText() {
    }

    /** 有内容就用它，否则用兜底值。 */
    public static String firstNonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    /** 空白串收敛成 {@code null}，便于「有没有配」这种判断。 */
    public static String blankToNull(String value) {
        return value != null && !value.isBlank() ? value : null;
    }

    /** 日志里打印配置值时用：空白显示成 {@code unknown}，避免日志出现空段。 */
    public static String safeLog(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    /** 配置里可能写着空白串；统一收敛成「空串」而不是 null，调用方不用再判空。 */
    public static String trimOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    /** 线路层级归一：只有明确的 {@code paid} 算付费，其余（含 null、拼错）一律按免费。 */
    public static String normalizeTier(String value) {
        return value != null && "paid".equalsIgnoreCase(value.trim()) ? "paid" : "free";
    }

    /**
     * 两个端点是否等价：忽略大小写、首尾空白与结尾的斜杠。
     *
     * <p>用于判断「配置里的地址」和「云端下发的地址」是不是同一个，
     * 不这样归一的话 {@code https://a.com/v1/} 和 {@code https://a.com/v1}
     * 会被当成两个不同端点，导致重复建线路。
     */
    public static boolean sameEndpoint(String first, String second) {
        if (first == null || second == null) return false;
        return first.trim().replaceAll("/+$", "").equalsIgnoreCase(second.trim().replaceAll("/+$", ""));
    }

    /**
     * 清洗 API Key：去掉不可见字符与全角字符，只保留可打印 ASCII。
     *
     * <p>🔴 真实场景：从聊天软件/网页复制 Key 时经常带上零宽空格或全角引号，
     * 肉眼完全看不出来，但请求会 401。这里清掉并记一条日志（只记长度，不记内容）。
     *
     * @return 清洗后的 Key；清洗后为空则返回 {@code null}
     */
    public static String cleanApiKey(String key) {
        if (key == null) return null;
        String cleaned = key.trim().replaceAll("[^\\x20-\\x7E]", "");
        if (!cleaned.equals(key)) {
            OllamaProxyMod.LOGGER.warn("API Key 包含无效字符，已清洗。原长度: {}, 清洗后长度: {}",
                    key.length(), cleaned.length());
        }
        // 清洗后为空串视为无有效 Key
        return cleaned.isBlank() ? null : cleaned;
    }

    /**
     * 规整配对码：去掉空白与零宽字符，再统一大写。
     *
     * <p>🔴 和 {@link #cleanApiKey} 同一类问题：从网页/聊天软件复制配对码时经常带进
     * 零宽空格、全角空格或换行，肉眼完全看不出来，却会让长度从 6 变成 7。
     * 云端对长度做**硬校验**，多一位就直接 400「配对码格式无效」——
     * 玩家对着一个 HTTP 400 根本查不出是自己多粘了一个不可见字符。
     *
     * <p>只清不可见字符，**不动内容本身**：配对码里可能含 {@code -} 之类的符号，
     * 按「只留字母数字」清洗会把它清掉，反而变成另一个错误。
     *
     * @return 规整后的配对码；入参为 {@code null} 时返回空串
     */
    public static String normalizePairingCode(String code) {
        if (code == null) return "";
        StringBuilder builder = new StringBuilder(code.length());
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            // 零宽空格/连接符、BOM 都不算「空白」，Character.isWhitespace 判不出来
            if (c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\uFEFF') continue;
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) continue;
            builder.append(c);
        }
        return builder.toString().toUpperCase(java.util.Locale.ROOT);
    }

    /**
     * 额度展示文本：整数不带小数点，小数最多保留 4 位并去掉末尾 0。
     *
     * <p>用 {@link RoundingMode#DOWN} 而不是四舍五入：额度宁可少显示一点，
     * 也不能显示得比实际多——玩家按显示值估算够用、结果扣费失败，是最差的体验。
     */
    public static String formatCredits(double value) {
        if (value < 0) return "未知";
        if (value == Math.rint(value)) return String.valueOf((long) value);
        return BigDecimal.valueOf(value)
                .setScale(4, RoundingMode.DOWN)
                .stripTrailingZeros()
                .toPlainString();
    }

    /** 画面来源，缺省按摄像头。 */
    public static String safeSource() {
        String source = ModConfig.CAMERA_SOURCE.get();
        return source == null || source.isBlank() ? "camera" : source.trim();
    }
}
