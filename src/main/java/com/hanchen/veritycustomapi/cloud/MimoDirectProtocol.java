package com.hanchen.veritycustomapi.cloud;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class MimoDirectProtocol {
    private static final String ALLOWED_HOST = "api.xiaomimimo.com";

    private MimoDirectProtocol() {
    }

    public static URI requireEndpoint(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("MiMo API 地址为空");
        URI uri = URI.create(value.trim());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !ALLOWED_HOST.equalsIgnoreCase(uri.getHost())
                || uri.getUserInfo() != null || uri.getPort() != -1) {
            throw new IllegalArgumentException("MiMo API 地址未通过安全校验");
        }
        String path = uri.getPath();
        if (path == null || path.isBlank() || "/".equals(path)) {
            path = "/v1/chat/completions";
        } else if (!path.endsWith("/chat/completions")) {
            path = (path.endsWith("/") ? path.substring(0, path.length() - 1) : path) + "/chat/completions";
        }
        return URI.create("https://" + ALLOWED_HOST + path);
    }

    public static byte[] parseAudio(int status, byte[] body) {
        return parseAudio(status, "application/json", body);
    }

    public static byte[] parseAudio(int status, String contentType, byte[] body) {
        if (contentType != null && !contentType.isBlank()
                && !contentType.toLowerCase(java.util.Locale.ROOT).contains("json")) return null;
        if (status != 200 || body == null || body.length == 0) return null;
        try {
            JsonObject root = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject message = root.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message");
            String encoded = message.getAsJsonObject("audio").get("data").getAsString();
            byte[] audio = Base64.getDecoder().decode(encoded);
            return isWav(audio) ? audio : null;
        } catch (RuntimeException error) {
            return null;
        }
    }

    public static String normalizeVoice(String voice) {
        if (voice == null || voice.isBlank()) return voice;
        return switch (voice.toLowerCase(java.util.Locale.ROOT)) {
            case "bingtang", "bing_tang", "冰糖" -> "冰糖";
            case "moli", "mo_li", "茉莉" -> "茉莉";
            case "suda", "su_da", "苏打" -> "苏打";
            case "baihua", "bai_hua", "白桦" -> "白桦";
            case "mia" -> "Mia";
            case "chloe" -> "Chloe";
            case "milo" -> "Milo";
            case "dean" -> "Dean";
            case "mimo_default", "default" -> "mimo_default";
            default -> voice;
        };
    }

    public static String audioMime(byte[] audio) {
        if (audio != null && audio.length >= 12
                && audio[0] == 'R' && audio[1] == 'I' && audio[2] == 'F' && audio[3] == 'F'
                && audio[8] == 'W' && audio[9] == 'A' && audio[10] == 'V' && audio[11] == 'E') {
            return "audio/wav";
        }
        if (audio != null && audio.length >= 3 && audio[0] == 'I' && audio[1] == 'D' && audio[2] == '3') {
            return "audio/mpeg";
        }
        if (audio != null && audio.length >= 2 && (audio[0] & 0xFF) == 0xFF && (audio[1] & 0xE0) == 0xE0) {
            return "audio/mpeg";
        }
        return null;
    }

    private static boolean isWav(byte[] audio) {
        return audio != null && audio.length >= 12
                && audio[0] == 'R' && audio[1] == 'I' && audio[2] == 'F' && audio[3] == 'F'
                && audio[8] == 'W' && audio[9] == 'A' && audio[10] == 'V' && audio[11] == 'E';
    }
}
