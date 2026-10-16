package com.hanchen.veritycustomapi.compat;

import com.hanchen.veritycustomapi.OllamaProxyMod;

import java.nio.charset.StandardCharsets;

/**
 * 给玩家亲口说的话补上原版要的语音上下文标记。
 * 原版 6.2 起靠查询文本里有没有 {@code SPEECH KARMA EVALUATION} 来判断这是玩家发言，
 * 缺了它 {@code pickup_nearby_items} 这类工具会直接拒绝；模板现场从原版类里读，读不到就退化成最小前缀。
 */
public final class VeritySpeechContext {

    private VeritySpeechContext() {
    }

    /** 原版用来判定「这是玩家亲口说的话」的标记。 */
    public static final String MARKER = "SPEECH KARMA EVALUATION";

    private static final String MOD_EVENTS_RESOURCE = "varmite/verity/event/ModEvents.class";

    /** 提取失败时的兜底：至少让标记存在，闸门就能打开。 */
    private static final String FALLBACK_PREFIX = "<SYSTEM> " + MARKER + ": Message: ";

    private static volatile boolean resolved;
    /** 空串表示原版不支持该机制（旧版），此时不做任何改动。 */
    private static volatile String prefix = "";

    /** 按需给查询补上语音上下文前缀；旧版原版原样返回。 */
    public static String decorate(String query) {
        String resolvedPrefix = prefix();
        if (resolvedPrefix.isEmpty() || query == null) return query;
        return resolvedPrefix + query;
    }

    /** 原版是否支持该机制（用于日志与自检）。 */
    public static boolean isSupported() {
        return !prefix().isEmpty();
    }

    private static String prefix() {
        if (resolved) return prefix;
        synchronized (VeritySpeechContext.class) {
            if (resolved) return prefix;
            try (java.io.InputStream in = VeritySpeechContext.class.getClassLoader()
                    .getResourceAsStream(MOD_EVENTS_RESOURCE)) {
                if (in == null) {
                    prefix = "";
                } else {
                    byte[] classBytes = in.readAllBytes();
                    String template = extractTemplate(classBytes);
                    if (template != null && !template.isEmpty()) {
                        prefix = template;
                        OllamaProxyMod.LOGGER.info(
                                "检测到原版语音上下文机制（{}），已启用玩家发言标记（模板 {} 字符）",
                                MARKER, template.length());
                    } else if (containsMarker(classBytes)) {
                        // 有标记但未提取出完整模板，用兜底前缀即可打开闸门
                        prefix = FALLBACK_PREFIX;
                        OllamaProxyMod.LOGGER.warn(
                                "原版含 {} 但未提取到完整模板，已使用兜底前缀", MARKER);
                    } else {
                        prefix = "";
                    }
                }
            } catch (Throwable error) {
                OllamaProxyMod.LOGGER.warn("解析原版语音上下文机制失败，按不支持处理: {}", error.toString());
                prefix = "";
            } finally {
                resolved = true;
            }
            return prefix;
        }
    }

    private static boolean containsMarker(byte[] classBytes) {
        return indexOf(classBytes, MARKER.getBytes(StandardCharsets.UTF_8)) >= 0;
    }

    /**
     * 从 class 常量池里把含标记的那一整条字符串抠出来。
     * UTF-8 常量是连续明文，命中标记后往两边扩到可打印 ASCII 的边界就能还原整条（模板以 {@code Message: } 结尾）。
     */
    private static String extractTemplate(byte[] classBytes) {
        byte[] marker = MARKER.getBytes(StandardCharsets.UTF_8);
        int hit = indexOf(classBytes, marker);
        if (hit < 0) return null;
        int start = hit;
        while (start > 0 && isPrintable(classBytes[start - 1])) start--;
        int end = hit + marker.length;
        while (end < classBytes.length && isPrintable(classBytes[end])) end++;
        String template = new String(classBytes, start, end - start, StandardCharsets.UTF_8);
        if (template.isBlank()) return null;
        // 相邻可打印常量可能被连读，故要求以 <SYSTEM> 开头、以 Message: 结尾；
        // 任一不满足即返回 null，由调用方退化为兜底前缀。
        if (!template.startsWith("<SYSTEM>")) return null;
        if (!template.endsWith("Message:")) return null;
        return template;
    }

    private static boolean isPrintable(byte value) {
        return value >= 0x20 && value <= 0x7E;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        if (haystack == null || needle == null || needle.length == 0) return -1;
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
