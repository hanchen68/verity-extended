package com.hanchen.veritycustomapi.compat;

public final class VerityGrowthStateTest {
    private VerityGrowthStateTest() {
    }

    public static void main(String[] args) {
        assertScale(1.0F, VerityGrowthMath.scaleForFeedCount(-1));
        assertScale(1.0F, VerityGrowthMath.scaleForFeedCount(0));
        assertScale(1.1F, VerityGrowthMath.scaleForFeedCount(1));
        assertScale(2.0F, VerityGrowthMath.scaleForFeedCount(10));
        assertScale(3.0F, VerityGrowthMath.scaleForFeedCount(20));
        assertScale(11.0F, VerityGrowthMath.scaleForFeedCount(100));
        assertDouble(0.0D, VerityGrowthMath.groundedYOffset(1.0F));
        assertDouble(0.375D, VerityGrowthMath.groundedYOffset(2.0F));
        System.out.println("Verity growth state checks passed");
    }

    private static void assertScale(float expected, float actual) {
        if (Math.abs(expected - actual) > 0.0001F) {
            throw new AssertionError("expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertDouble(double expected, double actual) {
        if (Math.abs(expected - actual) > 0.0001D) {
            throw new AssertionError("expected=" + expected + ", actual=" + actual);
        }
    }
}
