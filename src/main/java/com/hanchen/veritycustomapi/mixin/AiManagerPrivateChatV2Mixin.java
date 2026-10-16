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
 * 新版原版 Verity（6.2 起）的私聊 / 多播适配。
 * 6.2 起「播 TTS + 广播聊天」从 lambda$queryAI$4 抽成 AiManager.sendReplyPart(MinecraftServer, VerityEntity, ServerPlayer, String, boolean)，
 * 参数顺序和数量都变了，旧 Mixin 注入不上（签名不匹配 require = 0 也救不了），需另对准新入口；两个 Mixin 由 MixinPlugin 按加载的 Verity 结构二选一，逻辑共用 PrivateChatReplySupport。
 */
@Mixin(targets = "varmite.verity.entity.llm.AiManager", remap = false)
public abstract class AiManagerPrivateChatV2Mixin {

    // 注入点 remap = false 表示原样使用，Minecraft 成员名是 SRG（PlayerList.broadcastSystemMessage -> m_240416_），SimpleChannel.send 未混淆。
    // 处理器参数 = 被重定向调用的参数 + 目标方法参数：sendReplyPart(MinecraftServer, VerityEntity, ServerPlayer, String, boolean)；末尾 boolean 语义未确认，只改写「发给谁」，不依赖它。
    @Redirect(
            method = "sendReplyPart",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;m_240416_(Lnet/minecraft/network/chat/Component;Z)V"),
            require = 0,
            remap = false
    )
    private static void verityExtended$sendReplyToOwner(PlayerList playerList, Component message, boolean overlay,
                                                        MinecraftServer server, VerityEntity verity,
                                                        ServerPlayer requestedPlayer, String answer,
                                                        boolean replyFlag) {
        PrivateChatReplySupport.sendChatReply(playerList, message, overlay,
                verity, answer, requestedPlayer, server);
    }

    @Redirect(
            method = "sendReplyPart",
            at = @At(value = "INVOKE", target = "Lnet/minecraftforge/network/simple/SimpleChannel;send(Lnet/minecraftforge/network/PacketDistributor$PacketTarget;Ljava/lang/Object;)V"),
            require = 0,
            remap = false
    )
    private static void verityExtended$sendTtsToOwnerV2(SimpleChannel channel,
                                                        PacketDistributor.PacketTarget originalTarget,
                                                        Object payload,
                                                        MinecraftServer server, VerityEntity verity,
                                                        ServerPlayer requestedPlayer, String answer,
                                                        boolean replyFlag) {
        PrivateChatReplySupport.sendTts(channel, originalTarget, payload, verity, requestedPlayer);
    }
}
