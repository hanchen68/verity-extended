package com.hanchen.veritycustomapi.compat;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.StringConcatFactory;

public final class VerityLocalizationTest {
    private VerityLocalizationTest() {
    }

    public static void main(String[] args) throws Throwable {
        VerityLocalization.setEnabled(true);
        VerityLocalization.load();

        String original = "Microphone switched to: \u0001";
        String translated = VerityLocalization.translateConcatRecipe(original);
        assertEquals("麦克风已切换为： \u0001", translated);
        assertEquals(1, VerityLocalization.countConcatMarkers(translated));

        CallSite site = StringConcatFactory.makeConcatWithConstants(
                MethodHandles.lookup(),
                "makeConcatWithConstants",
                MethodType.methodType(String.class, String.class),
                translated);
        String message = (String) site.dynamicInvoker().invokeExact("USB Microphone");
        assertEquals("麦克风已切换为： USB Microphone", message);
        System.out.println("Verity localization checks passed");
    }

    private static void assertEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected=" + expected + ", actual=" + actual);
        }
    }
}
