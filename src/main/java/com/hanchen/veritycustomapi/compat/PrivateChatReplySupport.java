package com.hanchen.veritycustomapi.compat;

import com.hanchen.veritycustomapi.config.ModConfig;
import com.hanchen.veritycustomapi.network.ServerVerityRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import varmite.verity.entity.verity.VerityEntity;
import varmite.verity.network.PlayTtsPayload;

/** 原版回复发送逻辑（TTS + 聊天广播）的共用实现，6.0/6.2 两个入口的 Mixin 都走这里。 */
public final class PrivateChatReplySupport {

    private PrivateChatReplySupport() {
    }

    /** 聊天栏显示名：优先原版配置的自定义名；实体名解析不出时会带 "?"，不能直接用。 */
    public static String configuredVerityName(VerityEntity verity) {
        try {
            Object value = varmite.verity.VerityConfig.VERITY_CUSTOM_NAME.get();
            if (value instanceof String name && !name.isBlank()) return name.trim();
        } catch (Throwable ignored) {
        }
        try {
            String name = verity.getName().getString();
            if (name != null && !name.isBlank()) return name;
        } catch (Throwable ignored) {
        }
        return "Verity";
    }

    /** 纯单人判定：集成服务器且在线 ≤1 人。开过局域网后 isSingleplayer 仍为 true，不能只看它。 */
    public static boolean isSoloPlay(MinecraftServer server) {
        if (server == null) return false;
        try {
            return server.isSingleplayer() && server.getPlayerList().getPlayerCount() <= 1;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 改写原版聊天广播：多人加 @玩家名；开启私聊回复时只发给实体归属者，其余保持全局广播。 */
    public static void sendChatReply(PlayerList playerList, Component message, boolean overlay,
                              VerityEntity verity, String answer, ServerPlayer requestedPlayer,
                              MinecraftServer server) {
        // 站头/骑乘报告后的回复扫描：AI 明确拒绝才让 Verity 下来
        try {
            com.hanchen.veritycustomapi.ride.PerchRideState.scanReply(verity, answer);
        } catch (Throwable ignored) {
        }
        if (!ModConfig.SERVER_VERITY_ADAPTER_ENABLED.get()) {
            playerList.broadcastSystemMessage(message, overlay);
            return;
        }
        if (requestedPlayer != null && !isSoloPlay(server)) {
            String verityName = configuredVerityName(verity);
            MutableComponent decorated = Component.literal("<" + verityName + "> @"
                    + requestedPlayer.getGameProfile().getName() + ": ")
                    .append(ChatFileLinks.decorate(answer));
            playerList.broadcastSystemMessage(decorated, overlay);
            return;
        }
        if (!ModConfig.SERVER_VERITY_PRIVATE_CHAT.get()) {
            playerList.broadcastSystemMessage(message, overlay);
            return;
        }
        ServerPlayer recipient = ServerVerityRegistry.resolveOwner(verity);
        if (recipient != null && recipient.isAlive()) {
            MutableComponent decorated = Component.literal(
                    "<" + configuredVerityName(verity) + "> ")
                    .append(ChatFileLinks.decorate(answer));
            recipient.sendSystemMessage(decorated, overlay);
        } else {
            playerList.broadcastSystemMessage(message, overlay);
        }
    }

    /** 改写 TTS 包发送：开启私聊回复时只发给触发者，避免全服都听到。 */
    public static void sendTts(SimpleChannel channel, PacketDistributor.PacketTarget originalTarget,
                        Object payload, VerityEntity verity, ServerPlayer requestedPlayer) {
        if (!ModConfig.SERVER_VERITY_ADAPTER_ENABLED.get()
                || !ModConfig.SERVER_VERITY_PRIVATE_CHAT.get()
                || !(payload instanceof PlayTtsPayload)) {
            channel.send(originalTarget, payload);
            return;
        }
        ServerPlayer recipient = requestedPlayer != null
                ? requestedPlayer
                : ServerVerityRegistry.resolveOwner(verity);
        if (recipient != null && recipient.isAlive()) {
            channel.send(PacketDistributor.PLAYER.with(() -> recipient), payload);
        } else {
            channel.send(originalTarget, payload);
        }
    }
}
