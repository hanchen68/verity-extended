package com.hanchen.veritycustomapi.compat;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 每个存档、每个游戏日的好感度增长额度。
 *
 * <p>原版 AI 自带 {@code increase_karma} 工具，对话随时可能 +1；扩展的喂食/送礼也会 +1。
 * 三条路径都走 {@code ModEvents.updateAndSyncKarma}，这里按存档记录当天已经涨了多少，
 * 超过上限的增量由 {@link com.hanchen.veritycustomapi.mixin.VerityKarmaCapMixin} 拦掉。
 *
 * <p>上限为 0 表示不限制，此时不会写盘。
 */
public final class KarmaDailyCapData extends SavedData {
    private static final String DATA_NAME = "verity_extended_karma_cap";

    private long day = Long.MIN_VALUE;
    private float gained;

    public static KarmaDailyCapData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage()
                .computeIfAbsent(KarmaDailyCapData::load, KarmaDailyCapData::new, DATA_NAME);
    }

    private static KarmaDailyCapData load(CompoundTag tag) {
        KarmaDailyCapData data = new KarmaDailyCapData();
        data.day = tag.getLong("day");
        data.gained = tag.getFloat("gained");
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putLong("day", day);
        tag.putFloat("gained", gained);
        return tag;
    }

    /** 尝试消耗当天额度；额度不足时返回 false，调用方应放弃这次加好感。 */
    public boolean tryConsume(ServerLevel level, float amount, int cap) {
        if (amount <= 0.0F || cap <= 0) return true;
        long currentDay = currentDay(level);
        if (day != currentDay) {
            day = currentDay;
            gained = 0.0F;
        }
        if (gained + amount > cap + 1.0E-4F) {
            return false;
        }
        gained += amount;
        setDirty();
        return true;
    }

    /** 当天还剩多少额度；上限为 0 时返回 Float.MAX_VALUE。 */
    public float remaining(ServerLevel level, int cap) {
        if (cap <= 0) return Float.MAX_VALUE;
        long currentDay = currentDay(level);
        if (day != currentDay) return cap;
        return Math.max(0.0F, cap - gained);
    }

    private static long currentDay(ServerLevel level) {
        return level.getDayTime() / 24000L;
    }
}
