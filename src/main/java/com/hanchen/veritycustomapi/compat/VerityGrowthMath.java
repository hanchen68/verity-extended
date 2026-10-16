package com.hanchen.veritycustomapi.compat;

public final class VerityGrowthMath {
    private static final float SCALE_PER_FEED = 0.1F;

    private VerityGrowthMath() {
    }

    public static int boundFeedCount(int feedCount) {
        return Math.max(0, feedCount);
    }

    public static float scaleForFeedCount(int feedCount) {
        return 1.0F + boundFeedCount(feedCount) * SCALE_PER_FEED;
    }

    public static double groundedYOffset(float growthScale) {
        return Math.max(0.0D, (growthScale - 1.0F) * 0.375D);
    }
}
