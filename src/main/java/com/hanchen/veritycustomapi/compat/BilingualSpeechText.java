package com.hanchen.veritycustomapi.compat;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BilingualSpeechText {
    private static final String DELIMITER = "<|verity_zh|>";
    private static final Gson GSON = new Gson();

    /**
     * 模型自己输出的「译文」标签（自由文本兜底解析用）。
     * 纯英文单词那种要求顶行，免得把台词正文里的普通词误判成标签。
     */
    private static final Pattern TRANSLATION_LABEL = Pattern.compile(
            "(?im)(?:【翻译】|【译文】|【中文】|\\[翻译\\]|\\[译文\\]|\\[中文\\]|\\[ZH\\]"
          + "|翻译\\s*[:：]|译文\\s*[:：]|中文\\s*[:：]"
          + "|\\[translation\\]|^\\s*translation\\s*[:：]|^\\s*chinese\\s*[:：])");
    /** 模型自行输出的「原文」标签。 */
    private static final Pattern ORIGINAL_LABEL = Pattern.compile(
            "(?im)(?:【原文】|【英文】|\\[原文\\]|\\[英文\\]|\\[EN\\]"
          + "|原文\\s*[:：]|英文\\s*[:：]"
          + "|\\[original\\]|\\[english\\]|^\\s*original\\s*[:：]|^\\s*english\\s*[:：])");

    /**
     * 译文开头可能残留的标签。模型常自带 {@code [翻译]} / {@code 译文：}，显示层又统一加 {@code [译] }，
     * 不剥掉就成了 {@code [译] [翻译] 你好。}；只匹配明显像标签的，免得误删正文里的方括号。
     */
    private static final Pattern LEADING_LABEL = Pattern.compile(
            "(?i)^\\s*(?:【(?:原文|译文|翻译|中文|英文|译)】"
          + "|\\[(?:原文|译文|翻译|中文|英文|译|EN|ZH|Original|Translation|Chinese|English)\\]"
          + "|(?:原文|译文|翻译|中文|英文|translation|chinese|original|english)\\s*[:：]"
          + ")\\s*");
    /** 译文结尾孤立的标签，例如模型在中文后面又补一个 {@code [翻译]}。 */
    private static final Pattern TRAILING_LABEL = Pattern.compile(
            "(?i)\\s*(?:【(?:原文|译文|翻译|中文|英文|译)】"
          + "|\\[(?:原文|译文|翻译|中文|英文|译|EN|ZH|Original|Translation|Chinese|English)\\])\\s*$");

    private BilingualSpeechText() {
    }

    /**
     * 中文前面标「这是翻译」用的前缀。双语模式下聊天栏显示中文、朗读念英文，
     * 不标一下玩家会以为 Verity 说的是中文（实体上方的字幕不标，省得界面太吵）。
     */
    public static final String TRANSLATION_MARKER = "[译] ";

    /**
     * 双语消息的中文译文正文（不含 {@code <名字>} 前缀与 {@code [译]} 标记），供放不下标记的 HUD 对话气泡使用。
     *
     * @return 不是双语消息时返回 {@code null}
     */
    public static String translatedContent(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) return null;
        Parts parts = decode(rawMessage);
        if (!parts.bilingual()) return null;
        String cleaned = cleanTranslation(parts.chinese());
        return cleaned.isEmpty() ? null : cleaned;
    }

    /**
     * 双语消息在聊天栏的显示文本：保留原前缀（含多人 {@code @玩家名:} 标记），把英文台词换成带 {@code [译]} 标记的中文。
     * 例如 "&lt;Verity&gt; @Steve: Hello.&lt;|verity_zh|&gt;你好。" -> "&lt;Verity&gt; @Steve: [译] 你好。"
     *
     * @return 不是双语消息时返回 {@code rawMessage} 原样
     */
    public static String toChineseDisplay(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) return rawMessage;
        Parts parts = decode(rawMessage);
        String cleaned = parts.bilingual() ? cleanTranslation(parts.chinese()) : "";
        if (cleaned.isEmpty()) {
            // 不是双语消息，或译文被清洗成空（模型只回了标签）。
            if (!parts.bilingual() && !containsDelimiter(rawMessage)) {
                return rawMessage;
            }
            // 退回英文台词。两种格式前缀归属不同：分隔符格式的 parts.english() 已含 <名字> 前缀，
            // 标签格式的 english 从标签后截取、需补前缀；任何情况都不能把 <|verity_zh|> 原样返回。
            return containsDelimiter(rawMessage)
                    ? parts.english()
                    : prefixOf(rawMessage) + parts.english();
        }
        return prefixOf(rawMessage) + TRANSLATION_MARKER + cleaned;
    }

    /** 文本里是否出现了内部分隔符。 */
    public static boolean containsDelimiter(String text) {
        return text != null && text.contains(DELIMITER);
    }

    /**
     * 去掉内部分隔符和它后面的内容。给「按原文朗读/显示」的兜底路径用——
     * 模型只回一半时，也不能让 TTS 把分隔符念出来。
     */
    public static String stripDelimiter(String text) {
        if (text == null) return "";
        int index = text.indexOf(DELIMITER);
        return index < 0 ? text : text.substring(0, index).trim();
    }

    /** 取出 {@code <名字> @玩家名: } 这一段前缀；识别不到就返回空串。 */
    private static String prefixOf(String rawMessage) {
        String value = rawMessage == null ? "" : rawMessage.trim();
        String stripped = VeritySpeaker.stripPrefix(value);
        if (stripped == null) return "";
        // stripPrefix 会顺手 trim，长度差即前缀（可能同时包含名字和 @玩家名 标记）
        int cut = value.length() - stripped.length();
        return cut <= 0 ? "" : value.substring(0, cut);
    }

    /**
     * 清洗译文：剥掉模型自己加的「原文/翻译/中文」标签，只留译文本身。
     * 显示层还会统一加 {@code [译] }，模型再加一遍就成了 {@code [译] [翻译] 你好。}，必须去掉。
     */
    public static String cleanTranslation(String text) {
        if (text == null) return "";
        String value = text.trim();
        // 开头的标签可能叠了好几层，循环剥干净
        for (int i = 0; i < 4; i++) {
            Matcher leading = LEADING_LABEL.matcher(value);
            if (!leading.find()) break;
            value = value.substring(leading.end()).trim();
        }
        // 结尾孤立的标签
        for (int i = 0; i < 4; i++) {
            Matcher trailing = TRAILING_LABEL.matcher(value);
            if (!trailing.find()) break;
            value = value.substring(0, trailing.start()).trim();
        }
        return value;
    }

    /**
     * 去掉文本首尾的「原文/翻译/中文/英文」标签，规则同 {@link #cleanTranslation}。
     * 朗读前也过一遍，免得 TTS 把模型或聊天翻译插件留下的 {@code [翻译]} 念出来。
     */
    public static String stripLabels(String text) {
        return cleanTranslation(text);
    }

    public static String encode(String english, String chinese) {
        String spoken = english == null ? "" : english.trim();
        String subtitle = chinese == null ? "" : chinese.trim();
        if (spoken.isEmpty() || subtitle.isEmpty()) {
            return spoken;
        }
        return spoken + DELIMITER + subtitle;
    }

    public static Parts decode(String text) {
        String value = text == null ? "" : text.trim();
        int separator = value.indexOf(DELIMITER);
        if (separator < 0) {
            Parts structured = decodeStructured(value);
            if (structured != null) return structured;
            Parts labeled = decodeLabeled(value);
            return labeled == null ? new Parts(value, "", false) : labeled;
        }
        String english = value.substring(0, separator).trim();
        String chinese = value.substring(separator + DELIMITER.length()).trim();
        // 只有一侧有内容时不算双语，但不能把分隔符留在返回文本里，
        // 否则调用方回退到原文时聊天栏会出现 <|verity_zh|>。
        if (english.isEmpty() && chinese.isEmpty()) return new Parts("", "", false);
        if (english.isEmpty()) return new Parts(chinese, "", false);
        if (chinese.isEmpty()) return new Parts(english, "", false);
        return normalize(english, chinese);
    }

    /**
     * 兜住模型无视 JSON 约束、直接吐自由文本的情况，比如「【原文】Hello there. 【翻译】你好呀。」。
     * 认出「原文/翻译」标签就拆成英文和中文两部分，免得标签被打进聊天栏或被 TTS 念出来。
     *
     * @return 解析成功返回双语 Parts；没有可识别的标签返回 {@code null}
     */
    public static Parts decodeLabeled(String value) {
        if (value == null || value.isBlank()) return null;

        Matcher translation = TRANSLATION_LABEL.matcher(value);
        if (!translation.find()) return null;
        int translationIndex = translation.start();
        int translationEnd = translation.end();

        Matcher original = ORIGINAL_LABEL.matcher(value);
        int originalIndex = -1;
        int originalEnd = -1;
        while (original.find()) {
            if (original.start() >= translationIndex) break;
            originalIndex = original.start();
            originalEnd = original.end();
            break;
        }

        String english = originalIndex >= 0
                ? value.substring(originalEnd, translationIndex)
                : value.substring(0, translationIndex);
        String chinese = value.substring(translationEnd);
        english = english.trim();
        chinese = chinese.trim();

        if (chinese.isEmpty()) return null;
        if (english.isEmpty()) {
            // 只给了译文：没有原文可朗读，交给调用方按普通文本处理
            return null;
        }
        // 没有「原文」标签时，要求前缀确实不像中文，避免把中文台词误判成双语
        if (originalIndex < 0 && looksChinese(english)) {
            return null;
        }
        return normalize(english, chinese);
    }

    private static Parts decodeStructured(String value) {
        String normalized = value;
        if (normalized.startsWith("```")) {
            normalized = normalized.replaceFirst("^```(?:json)?\\s*", "")
                    .replaceFirst("\\s*```$", "").trim();
        }
        if (!normalized.startsWith("{")) return null;
        try {
            JsonObject object = GSON.fromJson(normalized, JsonObject.class);
            if (object == null || !object.has("response") || !object.has("translation")) return null;
            if (!object.get("response").isJsonPrimitive()
                    || !object.get("translation").isJsonPrimitive()) return null;
            return normalize(
                    object.get("response").getAsString(),
                    object.get("translation").getAsString()
            );
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Parts normalize(String first, String second) {
        String english = first == null ? "" : first.trim();
        String chinese = second == null ? "" : second.trim();
        if (looksChinese(english) && !looksChinese(chinese)) {
            String swap = english;
            english = chinese;
            chinese = swap;
        }
        return new Parts(english, chinese, !english.isEmpty() && !chinese.isEmpty());
    }

    /**
     * 判断文本是不是已经以中文为主。双语流程本要求模型只出英文台词、译文由代理二次调用拿，
     * 模型仍直接说中文时，就靠这个跳过第二次请求。
     */
    public static boolean isMostlyChinese(String text) {
        return text != null && !text.isBlank() && looksChinese(text);
    }

    private static boolean looksChinese(String text) {
        int chinese = 0;
        int letters = 0;
        for (int i = 0; i < text.length(); i++) {
            char value = text.charAt(i);
            if ((value >= '\u3400' && value <= '\u4dbf')
                    || (value >= '\u4e00' && value <= '\u9fff')) {
                chinese++;
            } else if (Character.isLetter(value)) {
                letters++;
            }
        }
        return chinese > 0 && chinese >= letters;
    }

    public record Parts(String english, String chinese, boolean bilingual) {
    }
}
