package com.hanchen.veritycustomapi.compat;

public final class VerityPatCooldownTest {
    private VerityPatCooldownTest() {
    }

    public static void main(String[] args) {
        require(VerityPatResponseService.COOLDOWN_TICKS == 1200L, "pat response cooldown is not 60 seconds");
        require(VerityPatResponseService.isCoolingDown(100L, 1300L), "fresh cooldown was not active");
        require(VerityPatResponseService.isCoolingDown(1299L, 1300L), "cooldown ended one tick early");
        require(!VerityPatResponseService.isCoolingDown(1300L, 1300L), "cooldown did not end at its boundary");
        require(!VerityPatResponseService.isCoolingDown(1400L, 1300L), "expired cooldown remained active");
        System.out.println("Verity PatPat response cooldown checks passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
