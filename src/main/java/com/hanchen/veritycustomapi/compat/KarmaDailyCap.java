package com.hanchen.veritycustomapi.compat;

import com.hanchen.veritycustomapi.config.ModConfig;
import net.minecraft.server.level.ServerLevel;

/** 好感度每日上限的统一入口。只处理正增量，负增量（孤独、受伤等）不受影响。 */
public final class KarmaDailyCap {
    private KarmaDailyCap() {
    }

    /** 当前是否还能再加 {@code amount} 点好感；不能时调用方应跳过加好感。 */
    public static boolean tryConsume(ServerLevel level, float amount) {
        if (level == null || level.getServer() == null || amount <= 0.0F) {
            return true;
        }
        int cap = ModConfig.DAILY_KARMA_GAIN_CAP.get();
        if (cap <= 0) return true;
        return KarmaDailyCapData.get(level).tryConsume(level, amount, cap);
    }

    /** 当天剩余额度，用于决定提示文案（上限为 0 时返回一个很大的值）。 */
    public static float remaining(ServerLevel level) {
        if (level == null || level.getServer() == null) return Float.MAX_VALUE;
        int cap = ModConfig.DAILY_KARMA_GAIN_CAP.get();
        if (cap <= 0) return Float.MAX_VALUE;
        return KarmaDailyCapData.get(level).remaining(level, cap);
    }
}
