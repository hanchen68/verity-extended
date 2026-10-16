package com.hanchen.veritycustomapi.mixin;

import com.hanchen.veritycustomapi.compat.PrivateChatReplySupport;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import varmite.verity.entity.verity.VerityEntity;

/**
 * 旧版原版 Verity（6.0.x / 6.1.x）的私聊 / 多播适配，逻辑写在 AiManager.lambda$queryAI$4，参数为 (VerityEntity, String, ServerPlayer, MinecraftServer)。
 * 6.2 起改为 AiManager.sendReplyPart，本 Mixin 在新版会注入失败，改用 AiManagerPrivateChatV2Mixin。
 * 两者由 MixinPlugin 按加载的 Verity 结构二选一，业务逻辑共用 PrivateChatReplySupport。
 */
@Mixin(targets = "varmite.verity.entity.llm.AiManager", remap = false)
public abstract class AiManagerPrivateChatMixin {

    // 注入点 remap = false 表示原样使用，Minecraft 成员名是 SRG，故必须写 SRG 名（PlayerList.broadcastSystemMessage -> m_240416_，可查 build/createSrgToMcp/output.srg）。
    // 处理器参数 = 被重定向调用的参数 + 目标方法参数：lambda$queryAI$4 为 (VerityEntity, String, ServerPlayer, MinecraftServer)。
    @Redirect(
            method = "lambda$queryAI$4",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;m_240416_(Lnet/minecraft/network/chat/Component;Z)V"),
            require = 0,
            remap = false
    )
    private static void verityExtended$sendToOwner(PlayerList playerList, Component message, boolean overlay,
                                                    VerityEntity verity, String answer, ServerPlayer requestedPlayer,
                                                    MinecraftServer server) {
        PrivateChatReplySupport.sendChatReply(playerList, message, overlay,
                verity, answer, requestedPlayer, server);
    }

    @Redirect(
            method = "lambda$queryAI$4",
            at = @At(value = "INVOKE", target = "Lnet/minecraftforge/network/simple/SimpleChannel;send(Lnet/minecraftforge/network/PacketDistributor$PacketTarget;Ljava/lang/Object;)V"),
            require = 0,
            remap = false
    )
    private static void verityExtended$sendTtsToOwner(SimpleChannel channel,
                                                        PacketDistributor.PacketTarget originalTarget,
                                                        Object payload,
                                                        VerityEntity verity,
                                                        String answer,
                                                        ServerPlayer requestedPlayer,
                                                        MinecraftServer server) {
        PrivateChatReplySupport.sendTts(channel, originalTarget, payload, verity, requestedPlayer);
    }
}
