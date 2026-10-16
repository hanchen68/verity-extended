package com.hanchen.veritycustomapi.ride;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「Verity 站在某个玩家头上」这一状态。
 *
 * 没用乘客系统：把玩家当载具、Verity 当乘客这条路要一路对抗原版——
 * 服务端 {@code ServerLevel.tickPassenger} 在乘客不在 tick 列表时会直接 {@code stopRiding()}；
 * 而乘客照样会被 tick，{@code LivingEntity.tick()} 会走 {@code aiStep -> travel}，
 * Verity 自己的重力与移动逻辑会和「每帧把 Verity 摆到头上的定位」互相打架。实测表现就是站不住。
 *
 * 于是改成状态 + 自己定位：不占用乘客位，只记「Verity 挂在谁身上」，
 * 每帧把 Verity 摆到该玩家头上并关掉重力，同时掐掉 Verity 自己的移动。
 * 思路和「被扛在肩上」那套一样，只是挂点不同。
 *
 * 状态是服务端权威的：客户端靠原版实体位置同步跟随，不需要额外的同步包。
 */
public final class HeadPerchState {

    /** verity 的 UUID -> 承载它的玩家 UUID。 */
    private static final Map<UUID, UUID> PERCHED = new ConcurrentHashMap<>();

    private HeadPerchState() {
    }

    /** 记录 Verity 开始站在该玩家头上。 */
    public static void perch(UUID verityId, UUID playerId) {
        if (verityId == null || playerId == null) {
            return;
        }
        PERCHED.put(verityId, playerId);
    }

    /** 解除 Verity 的站头状态。 */
    public static void release(UUID verityId) {
        if (verityId != null) {
            PERCHED.remove(verityId);
        }
    }

    /** Verity 正站在谁头上；没有则返回 null。 */
    public static UUID carrierOf(UUID verityId) {
        return verityId == null ? null : PERCHED.get(verityId);
    }

    /** Verity 是否处于站头状态。 */
    public static boolean isPerched(UUID verityId) {
        return carrierOf(verityId) != null;
    }

    /** 该玩家头上是否已经站着一只。 */
    public static boolean isCarrying(UUID playerId) {
        return playerId != null && PERCHED.containsValue(playerId);
    }

    /** 服务器停止时清理，避免跨存档残留。 */
    public static void clear() {
        PERCHED.clear();
    }
}
