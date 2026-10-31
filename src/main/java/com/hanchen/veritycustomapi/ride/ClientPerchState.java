package com.hanchen.veritycustomapi.ride;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端侧的「谁站在谁头上」缓存（按**实体 id** 索引）。
 *
 * <p><b>为什么客户端要单独存一份</b>：专用服务器上客户端拿不到服务端的
 * {@link HeadPerchState}（那是服务端内存里的表），只能靠原版实体位置同步跟随——
 * 而客户端对远程实体的位置是 3 tick 插值，玩家一跑，头上的 Verity 就明显「跟不上、往回拖」。
 *
 * <p>所以服务端每次上下头都广播一条 {@code PerchSyncPayload}，客户端据此每 tick
 * 自己把 Verity 摆到头顶，跟单人游戏里一样跟手。
 *
 * <p>单人游戏（集成服务器）两边在同一个 JVM 里，这张表和服务端的
 * {@link HeadPerchState} 会同时有值——客户端优先用这张表，没有才回退到服务端表。
 */
public final class ClientPerchState {

    /** verity 实体 id -> 承载者玩家实体 id。 */
    private static final Map<Integer, Integer> PERCHED = new ConcurrentHashMap<>();

    private ClientPerchState() {
    }

    /**
     * 更新状态。
     *
     * @param carrierId 承载者实体 id；传负数表示「下来了」
     */
    public static void set(int verityId, int carrierId) {
        if (verityId < 0) return;
        if (carrierId < 0) {
            PERCHED.remove(verityId);
        } else {
            PERCHED.put(verityId, carrierId);
        }
    }

    /** 站在谁头上；没有则返回 {@code null}。 */
    public static Integer carrierOf(int verityId) {
        return PERCHED.get(verityId);
    }

    public static boolean isPerched(int verityId) {
        return PERCHED.containsKey(verityId);
    }

    /** 退出世界时清空：实体 id 每个存档都从 0 开始计数，不清会串到下一个存档。 */
    public static void clear() {
        PERCHED.clear();
    }
}
