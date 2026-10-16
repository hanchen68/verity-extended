package com.hanchen.veritycustomapi.compat;

public final class OverheadOnlySpeechText {
    private static final String MARKER = "<|verity_overhead|>";

    private OverheadOnlySpeechText() {
    }

    public static String encode(String text) {
        String value = text == null ? "" : text.trim();
        return value.isEmpty() ? value : value + MARKER;
    }

    public static Parts decode(String text) {
        String value = text == null ? "" : text.trim();
        int marker = value.indexOf(MARKER);
        if (marker < 0) {
            return new Parts(value, false);
        }
        return new Parts((value.substring(0, marker) + value.substring(marker + MARKER.length())).trim(), true);
    }

    public record Parts(String text, boolean overheadOnly) {
    }
}
