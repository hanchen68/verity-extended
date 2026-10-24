package com.hanchen.veritycustomapi.ride;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 骑乘时记录并恢复 Verity 的原版上台阶高度（实体 id -> maxUpStep）。
 *
 * <p>🔴 <b>用实体 id 当键有两个坑，都不是理论上的</b>：
 * <ol>
 *   <li><b>会泄漏</b>：只要有一条路径没走到 {@link #take}（Verity 被 {@code /kill}、
 *       骑乘中死亡、下骑后立刻走远导致 chunk 卸载），这一项就永久留着；</li>
 *   <li><b>会串味</b>：实体 id 是<b>可复用</b>的。旧项没清掉时，新生成/新加载的实体
 *       可能拿到同一个 id，于是它一上骑就被「恢复」成上一个实体的步高——
 *       表现为「这只 Verity 突然能上两格台阶」或者反过来「上不去台阶」。</li>
 * </ol>
 *
 * <p>所以除了原有「用完即删」，再加一层容量上限兜底：正常情况下表里最多只有
 * 「当前被骑乘的 Verity 数量」条（几乎永远是 0~2），一旦超过上限说明有路径漏了清理，
 * 直接整体丢弃——丢的只是「恢复原始步高」这个便利，实体的步高下一 tick 会被重新记录，
 * 不会留下永久错误状态。宁可少恢复一次，也不要串到别的实体身上。
 */
public final class VerityStepStore {
    private static final Map<Integer, Float> ORIGINAL = new ConcurrentHashMap<>();

    /**
     * 泄漏判定上限。同时被骑乘的 Verity 不可能有这么多，
     * 超过就说明有路径没清，整体作废（见类注释）。
     */
    private static final int MAX_ENTRIES = 64;

    private VerityStepStore() {
    }

    public static void storeIfAbsent(int entityId, float value) {
        // 先清一遍超限的残留，再做正常逻辑
        if (ORIGINAL.size() >= MAX_ENTRIES) {
            ORIGINAL.clear();
        }
        ORIGINAL.putIfAbsent(entityId, value);
    }

    public static Float take(int entityId) {
        return ORIGINAL.remove(entityId);
    }

    public static void clear() {
        ORIGINAL.clear();
    }
}
