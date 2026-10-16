package com.hanchen.veritycustomapi.compat;

import java.util.concurrent.ConcurrentHashMap;

public final class VeritySubtitleManager {
    private static final ConcurrentHashMap<Integer, Subtitle> SUBTITLES = new ConcurrentHashMap<>();

    private VeritySubtitleManager() {
    }

    public static void show(int entityId, String text, String spokenText) {
        if (entityId < 0 || text == null || text.isBlank()) {
            return;
        }
        int spokenLength = spokenText == null ? 0 : spokenText.length();
        long duration = Math.max(5000L, Math.min(25000L, 1800L + spokenLength * 120L));
        SUBTITLES.put(entityId, new Subtitle(text.trim(), System.currentTimeMillis() + duration));
    }

    public static String get(int entityId) {
        Subtitle subtitle = SUBTITLES.get(entityId);
        if (subtitle == null) {
            return null;
        }
        if (System.currentTimeMillis() > subtitle.expiresAt()) {
            SUBTITLES.remove(entityId, subtitle);
            return null;
        }
        return subtitle.text();
    }

    public static void clear() {
        SUBTITLES.clear();
    }

    private record Subtitle(String text, long expiresAt) {
    }
}
