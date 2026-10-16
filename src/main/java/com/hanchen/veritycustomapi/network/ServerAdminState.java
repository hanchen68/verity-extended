package com.hanchen.veritycustomapi.network;

import com.hanchen.veritycustomapi.config.ModConfig;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端管理员的运行时状态：临时禁言、按玩家覆盖的实体配额。
 * 这些只存在内存里，服务器重启就恢复默认；要长期生效的开关用 /verity-admin config。
 */
public final class ServerAdminState {
    private static final Set<UUID> MUTED = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Integer> LIMIT_OVERRIDES = new ConcurrentHashMap<>();

    private ServerAdminState() {
    }

    /** 该玩家是否被管理员禁了 Verity 对话。 */
    public static boolean isMuted(UUID player) {
        return player != null && MUTED.contains(player);
    }

    /** 设置或解除禁言。 */
    public static void setMuted(UUID player, boolean muted) {
        if (player == null) return;
        if (muted) {
            MUTED.add(player);
        } else {
            MUTED.remove(player);
        }
    }

    public static Set<UUID> mutedPlayers() {
        return Set.copyOf(MUTED);
    }

    /**
     * 该玩家当前生效的实体上限：管理员单独设过就按设的，否则用配置里的默认值。
     */
    public static int effectiveLimit(UUID player) {
        Integer override = player == null ? null : LIMIT_OVERRIDES.get(player);
        return override != null ? override : ModConfig.SERVER_VERITY_MAX_ENTITIES_PER_PLAYER.get();
    }

    /** 该玩家有没有被管理员单独设过配额。 */
    public static boolean hasLimitOverride(UUID player) {
        return player != null && LIMIT_OVERRIDES.containsKey(player);
    }

    /** 设置配额；{@code limit} 传 null 就是恢复用配置值。 */
    public static void setLimit(UUID player, Integer limit) {
        if (player == null) return;
        if (limit == null) {
            LIMIT_OVERRIDES.remove(player);
        } else {
            LIMIT_OVERRIDES.put(player, limit);
        }
    }

    public static Map<UUID, Integer> limitOverrides() {
        return Map.copyOf(LIMIT_OVERRIDES);
    }

    /** 服务器停止时清空，免得跨存档串味。 */
    public static void clear() {
        MUTED.clear();
        LIMIT_OVERRIDES.clear();
    }
}
