package com.hanchen.veritycustomapi.ride;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 骑乘时记录并恢复 Verity 的原版上台阶高度（实体 id -> maxUpStep）。 */
public final class VerityStepStore {
    private static final Map<Integer, Float> ORIGINAL = new ConcurrentHashMap<>();

    private VerityStepStore() {
    }

    public static void storeIfAbsent(int entityId, float value) {
        ORIGINAL.putIfAbsent(entityId, value);
    }

    public static Float take(int entityId) {
        return ORIGINAL.remove(entityId);
    }

    public static void clear() {
        ORIGINAL.clear();
    }
}
