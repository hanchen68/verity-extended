package com.hanchen.veritycustomapi.compat;

public final class OverheadOnlySpeechTextTest {
    private OverheadOnlySpeechTextTest() {
    }

    public static void main(String[] args) {
        OverheadOnlySpeechText.Parts marked = OverheadOnlySpeechText.decode(
                OverheadOnlySpeechText.encode("Hello, Verity.")
        );
        assertEquals("Hello, Verity.", marked.text());
        assertTrue(marked.overheadOnly());

        String bilingual = BilingualSpeechText.encode("Hello.", "你好。");
        OverheadOnlySpeechText.Parts combined = OverheadOnlySpeechText.decode(
                OverheadOnlySpeechText.encode(bilingual)
        );
        BilingualSpeechText.Parts decoded = BilingualSpeechText.decode(combined.text());
        assertEquals("Hello.", decoded.english());
        assertEquals("你好。", decoded.chinese());
        assertTrue(combined.overheadOnly());
        assertTrue(decoded.bilingual());

        OverheadOnlySpeechText.Parts plain = OverheadOnlySpeechText.decode("Plain text");
        assertEquals("Plain text", plain.text());
        assertFalse(plain.overheadOnly());
        assertEquals("", OverheadOnlySpeechText.encode("  "));

        System.out.println("Overhead-only speech text checks passed");
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertTrue(boolean value) {
        if (!value) {
            throw new AssertionError("expected=true, actual=false");
        }
    }

    private static void assertFalse(boolean value) {
        if (value) {
            throw new AssertionError("expected=false, actual=true");
        }
    }
}
