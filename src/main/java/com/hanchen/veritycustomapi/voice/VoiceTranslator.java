package com.hanchen.veritycustomapi.voice;

import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.memory.ChatCompletionClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 朗读前的台词翻译。
 *
 * 用途是「台词写一种语言、念另一种语言」：显示语言决定模型写什么，
 * 朗读语言决定合成器念什么，两者不一致时在这里把台词翻过去。
 *
 * 读超时压得很短，失败就退回原文——念原语言总比不念强。同一句常被重复朗读
 * （字幕、TTS 包两条来源），所以结果要缓存，不缓存就是白花钱。走内置付费线路时
 * 按对话同样的规则预留/结算额度，不能让翻译白蹭。
 *
 */
public final class VoiceTranslator {

    /** 翻译读超时：比对话短得多，晚回来就变成「先安静几秒再开口」。 */
    private static final int READ_TIMEOUT_MS = 8000;
    private static final int CACHE_MAX = 256;
    private static final int MAX_INPUT_CHARS = 1200;

    private static final Map<String, String> CACHE = new LinkedHashMap<>(64, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > CACHE_MAX;
        }
    };

    private static final String SYSTEM_PROMPT =
            "你是台词翻译。把用户给出的台词翻译成目标语言。"
                    + "只输出译文本身：不要解释、不要引号、不要标注语言名、不要保留原文。"
                    + "保持原有的语气、称呼和人称，句子长度尽量接近原文，方便配音。";

    private VoiceTranslator() {
    }

    /**
     * 这句台词要不要翻译。
     *
     * 纯判断，不碰网络：朗读语言为「跟随」= 不翻译；朗读语言和显示语言相同 = 不翻译。
     */
    public static boolean needsTranslation(VoiceLanguage display, VoiceLanguage speak) {
        if (speak == null || speak == VoiceLanguage.FOLLOW) return false;
        return speak != display;
    }

    /**
     * 算出实际要念的文本。
     *
     * 不需要翻译、或翻译失败时原样返回，调用方不必再判空。
     */
    public static String forSpeech(String text, VoiceLanguage display, VoiceLanguage speak) {
        if (text == null || text.isBlank() || !needsTranslation(display, speak)) {
            return text;
        }
        String translated = translate(text, speak);
        return translated == null || translated.isBlank() ? text : translated;
    }

    /** 翻译一句台词；失败返回 {@code null}。结果带缓存。 */
    public static String translate(String text, VoiceLanguage target) {
        if (text == null || text.isBlank() || target == null || target == VoiceLanguage.FOLLOW) {
            return null;
        }
        String input = text.strip();
        if (input.length() > MAX_INPUT_CHARS) {
            input = input.substring(0, MAX_INPUT_CHARS);
        }
        String key = target.name() + "\u0000" + input;
        synchronized (CACHE) {
            String hit = CACHE.get(key);
            if (hit != null) return hit;
        }
        try {
            String raw = requestTranslation(input, target);
            String polished = polish(raw);
            if (polished.isEmpty()) return null;
            synchronized (CACHE) {
                CACHE.put(key, polished);
            }
            return polished;
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.warn("台词翻译失败，改用原文朗读: {}", error.getMessage());
            return null;
        }
    }

    /** 清洗模型输出：去掉包裹引号、开头多余的语言标签、连续空白。 */
    public static String polish(String raw) {
        if (raw == null) return "";
        String value = raw.strip();
        value = stripLeadingTag(value);
        value = stripWrappingQuotes(value);
        return value.replaceAll("\\s*\\n\\s*", " ").replaceAll("[ \\t]{2,}", " ").strip();
    }

    /** 供自检使用：清空缓存。 */
    public static void clearCacheForTesting() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    private static String requestTranslation(String text, VoiceLanguage target) throws Exception {
        ConfigManager.ChatRoute route = resolveRoute();
        if (route == null) return null;
        String user = "目标语言：" + target.promptName() + "\n台词：\n" + text;
        String requestJson = ChatCompletionClient.toJson(
                ChatCompletionClient.buildRequest(route.model(), SYSTEM_PROMPT, user));

        // 计费已交给云端：配音语言转换不再预授权/结算/上报。
        String responseBody;
        try {
            responseBody = ChatCompletionClient.complete(route, requestJson, READ_TIMEOUT_MS);
        } catch (Exception error) {
            throw error;
        }
        String body = ChatCompletionClient.extractText(responseBody);
        return body.isBlank() ? null : body;
    }

    /** 翻译专用端点优先；没配就走当前对话线路。 */
    private static ConfigManager.ChatRoute resolveRoute() {
        if (ConfigManager.hasVoiceTranslateEndpoint()) {
            return new ConfigManager.ChatRoute("voice-translate", "custom", null,
                    ConfigManager.getVoiceTranslateUrl(), ConfigManager.getVoiceTranslateApiKey(),
                    ConfigManager.getVoiceTranslateModel(), 0);
        }
        try {
            List<ConfigManager.ChatRoute> routes = ConfigManager.snapshotChatRoutes();
            return routes.isEmpty() ? null : routes.get(0);
        } catch (Throwable error) {
            return null;
        }
    }

    private static String stripWrappingQuotes(String value) {
        if (value.length() < 2) return value;
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        boolean paired = (first == '"' && last == '"')
                || (first == '\'' && last == '\'')
                || (first == '“' && last == '”')
                || (first == '「' && last == '」')
                || (first == '『' && last == '』');
        return paired ? value.substring(1, value.length() - 1).strip() : value;
    }

    /** 去掉模型爱加的「翻译：」「译文：」这类前缀。 */
    private static String stripLeadingTag(String value) {
        String[] tags = {"译文：", "译文:", "翻译：", "翻译:", "translation:", "translated text:"};
        String lowered = value.toLowerCase(java.util.Locale.ROOT);
        for (String tag : tags) {
            if (lowered.startsWith(tag)) {
                return value.substring(tag.length()).strip();
            }
        }
        return value;
    }
}
