package com.hanchen.veritycustomapi.cloud;

import com.hanchen.veritycustomapi.util.Silent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;

public final class UsageMeter {
    private UsageMeter() {
    }

    public static ChatUsage chat(String requestJson, String responseJson, String outputText) {
        return chat(requestJson, responseJson, outputText, true);
    }

    public static ChatUsage chat(String requestJson, String responseJson, String outputText, boolean allowEstimate) {
        Integer input = null;
        Integer output = null;
        Integer total = null;
        try {
            JsonObject root = JsonParser.parseString(responseJson).getAsJsonObject();
            if (root.has("usage") && root.get("usage").isJsonObject()) {
                JsonObject usage = root.getAsJsonObject("usage");
                input = integer(usage, "input_tokens", "prompt_tokens");
                output = integer(usage, "output_tokens", "completion_tokens");
                total = integer(usage, "total_tokens");
            }
        } catch (Exception ignored) { Silent.ignore("UsageMeter", ignored); }
        if (!allowEstimate && (input == null || output == null)) return null;
        if (input == null) input = estimateTokens(extractText(requestJson));
        if (output == null) output = estimateTokens(outputText);
        long normalizedTotal = (long) Math.max(0, input) + Math.max(0, output);
        total = (int) Math.min(Integer.MAX_VALUE, normalizedTotal);
        return new ChatUsage(input, output, total);
    }

    public static double audioSeconds(byte[] pcmData, int sampleRate, int bitsPerSample, int channels) {
        if (pcmData == null || pcmData.length == 0 || sampleRate <= 0 || bitsPerSample <= 0 || channels <= 0) return 0;
        double bytesPerSecond = sampleRate * (bitsPerSample / 8.0) * channels;
        return pcmData.length / bytesPerSecond;
    }

    public static int characters(String text) {
        if (text == null || text.isEmpty()) return 0;
        return text.length();
    }

    public static int estimateTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        int codePoints = text.codePointCount(0, text.length());
        int utf8Bytes = text.getBytes(StandardCharsets.UTF_8).length;
        return Math.max(1, Math.max((codePoints + 2) / 3, (utf8Bytes + 3) / 4));
    }

    private static Integer integer(JsonObject object, String... names) {
        for (String name : names) {
            if (object.has(name) && !object.get(name).isJsonNull()) {
                try {
                    return Math.max(0, object.get(name).getAsInt());
                } catch (Exception ignored) { Silent.ignore("UsageMeter", ignored); }
            }
        }
        return null;
    }

    private static String extractText(String json) {
        if (json == null || json.isBlank()) return "";
        try {
            StringBuilder text = new StringBuilder();
            appendStrings(JsonParser.parseString(json), text);
            return text.toString();
        } catch (Exception ignored) {
            return json;
        }
    }

    private static void appendStrings(JsonElement element, StringBuilder target) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            target.append(element.getAsString()).append('\n');
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) appendStrings(child, target);
            return;
        }
        if (element.isJsonObject()) {
            for (var entry : element.getAsJsonObject().entrySet()) {
                if (!"model".equals(entry.getKey())) appendStrings(entry.getValue(), target);
            }
        }
    }

    public record ChatUsage(int inputTokens, int outputTokens, int totalTokens) {
    }
}
