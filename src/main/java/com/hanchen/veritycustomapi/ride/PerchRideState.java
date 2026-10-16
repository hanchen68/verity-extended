package com.hanchen.veritycustomapi.ride;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import varmite.verity.entity.verity.VerityEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 站头/骑乘报告后的待扫描状态：等 AI 回复回来扫描拒绝词。
 * 玩家没有等到回复或回复不含拒绝词时状态自动过期，不会一直挂着。
 */
public final class PerchRideState {
    /** 过期时长（游戏刻）：约 30 秒，够一次正常对话往返。 */
    private static final long EXPIRE_TICKS = 600L;

    private record Pending(UUID playerId, boolean perch, long expireAtGameTime) {
    }

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

    private PerchRideState() {
    }

    public static void mark(VerityEntity verity, ServerPlayer player, boolean perch) {
        if (verity == null || player == null) return;
        long now = gameTick(verity);
        PENDING.put(verity.getUUID(), new Pending(player.getUUID(), perch, now + EXPIRE_TICKS));
    }

    /** AI 回复到达：扫描拒绝词，命中就解除对应状态并给玩家提示。 */
    public static void scanReply(VerityEntity verity, String answer) {
        if (verity == null || verity.isRemoved()) return;
        Pending pending = PENDING.remove(verity.getUUID());
        if (pending == null) return;
        long now = gameTick(verity);
        if (now > pending.expireAtGameTime()) return;
        ServerPlayer player = lookupPlayer(verity, pending.playerId());
        if (player == null) return;
        // 骑上/站头后的普通 AI 回复不能被当成拒绝。原版回复里经常出现「不行、不要」
        // 等上下文词，扫描它会导致玩家一移动/一收到回复就莫名其妙被强制下车。
        // 下车只由玩家的明确本地指令、快捷键或实体关系本身决定；不再让自然语言误判。
        // 保留消费 pending，避免同一条回复重复处理。
    }

    /**
     * 玩家聊天里说"下来"之类的话：本地直接解除，不转发 AI。
     * 返回 true 表示这条消息已处理（调用方不要再传给 AI）。
     */
    public static boolean tryHandleDownRequest(ServerPlayer player, VerityEntity verity, String message) {
        if (player == null || verity == null || !verity.isAlive()) return false;
        if (!AiDownWords.isDownRequest(message)) return false;
        boolean acted = false;
        if (verity.getVehicle() == player) {
            verity.stopRiding();
            acted = true;
        }
        if (player.getVehicle() == verity) {
            player.stopRiding();
            acted = true;
        }
        if (!acted) return false;
        String name = safeName(verity);
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                name + "：好的，已经下来了～"));
        return true;
    }

    public static void clear() {
        PENDING.clear();
    }

    private static long gameTick(VerityEntity verity) {
        try {
            return verity.level().getGameTime();
        } catch (Throwable error) {
            return System.currentTimeMillis() / 50L;
        }
    }

    private static ServerPlayer lookupPlayer(VerityEntity verity, UUID playerId) {
        try {
            MinecraftServer server = verity.getServer();
            if (server == null) return null;
            return server.getPlayerList().getPlayer(playerId);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String safeName(VerityEntity verity) {
        try {
            String name = verity.getName().getString();
            return name == null || name.isBlank() ? "Verity" : name;
        } catch (Throwable ignored) {
            return "Verity";
        }
    }
}
