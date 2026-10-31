package com.hanchen.veritycustomapi.ai;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 「当前正在和 Verity 对话的玩家」。
 *
 * <p><b>为什么需要</b>：代理是一个独立的 HTTP 服务，收到的是 JSON 请求体，
 * 里面没有玩家身份。但新增的本地工具（扫描方块、读箱子、采集、跑任务）
 * 全都要一个 {@link ServerPlayer} 才能知道「在谁身边做事」。
 *
 * <p>做法是：游戏层在玩家真正开口（聊天/右键 Verity）时登记一次，
 * 代理层执行工具时取最近一次登记的玩家。多人服务器上如果有多人同时说话，
 * 后登记的会覆盖前者——这是可接受的取舍：工具调用总是紧跟在某句话之后，
 * 时间差在秒级，而且真正的「多玩家同时对话」在单人/小服里几乎不会发生。
 *
 * <p>兜底：登记失效（下线/死亡）且当前只有一个玩家在线时，直接用他。
 * 一个都不满足就返回 {@code null}，调用方必须处理这种情况（回一句「没找到你」而不是抛异常）。
 */
public final class LocalToolContext {

    private static volatile ServerPlayer last;
    private static volatile long lastAt;

    private LocalToolContext() {
    }

    /** 玩家开口时登记。只存弱引用语义：玩家下线后由 {@link #current} 判定失效。 */
    public static void touch(ServerPlayer player) {
        if (player == null) return;
        last = player;
        lastAt = System.currentTimeMillis();
    }

    /**
     * 取「当前对话的玩家」。
     *
     * @return 找不到时返回 {@code null}
     */
    public static ServerPlayer current(MinecraftServer server) {
        if (server == null) return null;

        ServerPlayer cached = last;
        if (cached != null && isUsable(cached)) return cached;

        // 兜底：单人环境直接认定唯一在线玩家
        var players = server.getPlayerList().getPlayers();
        if (players.size() == 1) {
            ServerPlayer only = players.get(0);
            if (isUsable(only)) {
                last = only;
                return only;
            }
        }
        return null;
    }

    /** 距离上次登记过了多久（毫秒），用于日志排查。 */
    public static long ageMillis() {
        return lastAt == 0 ? -1 : System.currentTimeMillis() - lastAt;
    }

    private static boolean isUsable(ServerPlayer player) {
        try {
            return player.isAlive() && !player.hasDisconnected() && player.connection != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 服务器停止时清掉，避免持有已失效的玩家引用。 */
    public static void clear() {
        last = null;
        lastAt = 0;
    }
}
