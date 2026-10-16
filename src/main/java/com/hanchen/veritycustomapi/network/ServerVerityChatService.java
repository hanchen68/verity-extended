package com.hanchen.veritycustomapi.network;

import com.hanchen.veritycustomapi.compat.OriginalVerityChatBridge;
import com.hanchen.veritycustomapi.config.ModConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.ServerChatEvent;
import varmite.verity.entity.verity.VerityEntity;

/**
 * 多人环境下的聊天入口适配，由 ServerVerityChatMixin 注入到原版 ModEvents.onPlayerChat 头部。
 * 返回 true 表示这条聊天由适配器接管；Mixin 取消的是原版处理方法本身，不是 {@link ServerChatEvent}，
 * 所以玩家发言仍照常广播到聊天栏。
 */
public final class ServerVerityChatService {
    private ServerVerityChatService() {}

    public static boolean handle(ServerChatEvent event) {
        if (event == null || !ModConfig.SERVER_VERITY_ADAPTER_ENABLED.get()) return false;

        String message = event.getRawText();
        // 语音输入和旧客户端会把指令当聊天发过来（sendChat("/v …")），得兼容 /v 前缀，
        // 否则会被下面的 requireCommand 拦掉。
        boolean commandRequest = message != null && message.startsWith("/v ");
        if (commandRequest) {
            message = message.substring("/v ".length()).trim();
            // 这条原文带着 /v 前缀，不该原样显示，取消掉 ServerChatEvent。
            event.setCanceled(true);
        } else {
            // 普通聊天：requireCommand 开着就不触发 Verity，只拦原版处理，消息照常显示；
            // 对话走 /v、/v! 指令（VerityChatCommand），单人游戏直接对话不受这个开关限制。
            if (ModConfig.SERVER_VERITY_REQUIRE_COMMAND.get() && !isSoloPlay(event)) return true;
            if (!ModConfig.SERVER_VERITY_PRIVATE_CHAT.get()) return false;
            message = message == null ? "" : message.trim();
            // privateChat 只管回复发给谁，这里不能取消 ServerChatEvent，不然玩家看不到自己的发言。
            // 挡掉原版重复回复靠 Mixin 的 callback.cancel()（本方法返回 true 时触发）。
        }
        if (message == null || message.isBlank()) return false;

        ServerPlayer player = event.getPlayer();
        // 管理员临时禁言：直接拦掉，不回复也不扣额度
        if (ServerAdminState.isMuted(player.getUUID())) return true;
        VerityEntity verity = ServerVerityRegistry.findForPlayer(player);
        if (verity == null || !verity.isAlive()) return false;
        // 玩家说"下来"这类话：本地直接解除站头/骑乘，不转发 AI、不扣额度
        if (com.hanchen.veritycustomapi.ride.PerchRideState.tryHandleDownRequest(player, verity, message)) {
            return true;
        }
        if (!OriginalVerityChatBridge.queryAi(verity, player, message)) {
            player.sendSystemMessage(Component.literal("原版对话接口暂时不可用，请稍后再试。"));
        }
        // 返回 true 只是表示这条聊天由适配器接管，Mixin 据此取消原版 onPlayerChat，
        // 免得原版重复回复；玩家自己的发言仍正常广播。
        return true;
    }

    /**
     * 是否纯单人（集成服务器且只有一名玩家），判定要和 AiManagerPrivateChatMixin#isSoloPlay 一致。
     * 局域网开放后 isSingleplayer 还是 true，但可能已经有别人了，所以还得看玩家数。
     */
    private static boolean isSoloPlay(ServerChatEvent event) {
        try {
            ServerPlayer player = event.getPlayer();
            if (player == null || player.getServer() == null) return false;
            return player.getServer().isSingleplayer()
                    && player.getServer().getPlayerList().getPlayerCount() <= 1;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
