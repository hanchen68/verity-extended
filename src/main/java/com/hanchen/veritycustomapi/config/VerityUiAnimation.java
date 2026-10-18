package com.hanchen.veritycustomapi.config;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** 自绘 UI 的轻量动画工具：按毫秒推进，不依赖 tick，窗口卡顿时也不会越飘越远。 */
@OnlyIn(Dist.CLIENT)
public final class VerityUiAnimation {

    private VerityUiAnimation() {
    }

    public static double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    /** 入场/切页的统一缓动：起步快、收尾稳。 */
    public static double easeOutCubic(double t) {
        double p = clamp01(t);
        double remain = 1.0D - p;
        return 1.0D - remain * remain * remain;
    }

    /** 每次最多靠近 {@code maxDelta}，避免掉帧后一帧跳完整个动画。 */
    public static double approach(double current, double target, double maxDelta) {
        double diff = target - current;
        if (Math.abs(diff) <= maxDelta) return target;
        return current + Math.signum(diff) * maxDelta;
    }

    /** 两帧之间的毫秒差；首帧或长时间未渲染时按 16ms 处理，并夹住 50ms。 */
    public static long frameDelta(long lastFrameMs, long nowMs) {
        if (lastFrameMs <= 0L) return 16L;
        return Math.max(0L, Math.min(50L, nowMs - lastFrameMs));
    }
}
