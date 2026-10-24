package com.hanchen.veritycustomapi.compat;

import com.hanchen.veritycustomapi.util.Silent;

import java.util.regex.Pattern;

/**
 * 认出聊天栏里的 Verity 台词。
 * 原版把回复广播成 {@code <名字> 文本}，名字取自 {@code VerityConfig.VERITY_CUSTOM_NAME}；
 * 这里统一从原版配置读名字、按名字缓存正则，读不到就回退默认名。
 */
public final class VeritySpeaker {
    private static final String FALLBACK_NAME = "Verity";

    /**
     * 服务端多人适配加在回复前的 {@code @玩家名:} 标记。TTS 不该念出玩家名，
     * 而且 {@code TtsManager} 按文本去重、聊天栏和 TTS 包两路得拿到同一段字，不剥就会重复朗读。
     */
    private static final Pattern OWNER_MARKER = Pattern.compile("^@\\S{1,16}\\s*[:：]\\s*");

    private static volatile String cachedName;
    private static volatile Pattern cachedPattern;

    private VeritySpeaker() {
    }

    /** 原版配置里的 Verity 名字；读不到时回退 {@code Verity}。 */
    public static String currentName() {
        try {
            Object configValue = Class.forName("varmite.verity.VerityConfig")
                    .getField("VERITY_CUSTOM_NAME").get(null);
            Object value = configValue.getClass().getMethod("get").invoke(configValue);
            if (value instanceof String name && !name.isBlank()) {
                return name.trim();
            }
        } catch (Throwable ignored) { Silent.ignore("VeritySpeaker", ignored); }
        return FALLBACK_NAME;
    }

    /**
     * 去掉 {@code <名字>} / {@code [名字]} / {@code 【名字】} / {@code 名字:} 前缀，
     * 以及服务端适配加的 {@code @玩家名:} 标记（前缀里允许夹带 {@code §x} 颜色代码）。
     *
     * @return 台词正文；这一行不是 Verity 的发言、或去掉前缀后为空时返回 {@code null}
     */
    public static String stripPrefix(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) return null;
        java.util.regex.Matcher matcher = pattern().matcher(rawMessage);
        if (!matcher.find()) return null;
        String text = rawMessage.substring(matcher.end()).trim();
        text = OWNER_MARKER.matcher(text).replaceFirst("").trim();
        return text.isEmpty() ? null : text;
    }

    private static Pattern pattern() {
        String name = currentName();
        Pattern pattern = cachedPattern;
        if (pattern != null && name.equals(cachedName)) {
            return pattern;
        }
        String quoted = Pattern.quote(name);
        // 两种形态：带括号 <名字>/[名字]/【名字】，与不带括号 名字:。
        // 括号内允许名字后跟少量装饰字符：实测 getName() 可能渲染成 "Verity?"（名字键未解析时出现问号），
        // 只认 "<Verity>" 会留下 "?>"，导致后续 @玩家名 标记剥不掉、TTS 念出玩家名且去重失效。
        Pattern built = Pattern.compile(
                "^\\s*(?:(?:§.)\\s*)?(?:<|\\[|【)\\s*(?:(?:§.)\\s*)?"
                        + quoted + "[^>\\]】]{0,8}(?:>|\\]|】)\\s*[:：]?\\s*"
                        + "|^\\s*(?:(?:§.)\\s*)?" + quoted + "\\s*[:：]\\s*",
                Pattern.CASE_INSENSITIVE);
        cachedName = name;
        cachedPattern = built;
        return built;
    }
}
