package com.hanchen.veritycustomapi.compat;

public final class VerityPatPatCompatTest {
    private VerityPatPatCompatTest() {
    }

    public static void main(String[] args) {
        if (!VerityPatPatCompat.isAvailable()) {
            throw new AssertionError("PatPat 1.3.1 renderer bridge was not resolved");
        }
        System.out.println("Verity PatPat compatibility bridge resolved");
    }
}
