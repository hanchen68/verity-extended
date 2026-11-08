package com.hanchen.veritycustomapi.autonomy;

/** Conservative completion evidence independent from model prose. */
public final class AutonomyEvidence {
    private AutonomyEvidence() {}
    public static boolean knownFailure(String result) {
        if (result == null || result.isBlank()) return true;
        return result.startsWith("失败") || result.contains("没有找到") || result.contains("没找到")
                || result.contains("不是一个容器") || result.contains("不是告示牌")
                || result.contains("我自己没有独立背包") || result.contains("未验证")
                || result.contains("未检测到") || result.contains("没有检测到");
    }
    public static boolean mayReview(int completed, int failures, boolean pending, boolean exhausted) {
        return completed > 0 && failures == 0 && !pending && exhausted;
    }
}
