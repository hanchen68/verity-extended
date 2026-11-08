package com.hanchen.veritycustomapi.autonomy;

/**
 * 步骤停滞判定（纯函数，便于单测）。
 *
 * <p>不同步骤的合理耗时差很多：走路一分钟、挖一块两分钟、烧一炉六分钟、
 * 照图纸施工二十五分钟。用同一个超时会把慢作业误杀，也会让真卡住的一步拖太久。
 */
public final class AutonomyStall {

    private AutonomyStall() {
    }

    /** 该类型等待多少 tick 没进展算卡住。 */
    public static long limit(String waiting) {
        if (waiting == null) return 20L * 60L;
        return switch (waiting) {
            case "MINE" -> 20L * 120L;
            case "SHELTER" -> 20L * 60L * 5L;
            case "SMELT" -> 20L * 60L * 6L;
            case "BLUEPRINT" -> 20L * 60L * 25L;
            case "TOOL" -> 20L * 60L * 10L;
            default -> 20L * 60L;
        };
    }

    public static boolean expired(String waiting, long elapsedTicks) {
        return elapsedTicks > limit(waiting);
    }
}
