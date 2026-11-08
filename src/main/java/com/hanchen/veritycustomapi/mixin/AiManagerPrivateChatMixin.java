package com.hanchen.veritycustomapi.mixin;

import com.hanchen.veritycustomapi.compat.PrivateChatReplySupport;
import com.hanchen.veritycustomapi.compat.VerityStructureDetector;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import varmite.verity.entity.verity.VerityEntity;

/**
 * Verity 6.0.0 正式版的私聊 / 多播适配（旧版回复链路）。
 *
 * <p>🔴 这是 2026-11-07 修的那个「服务器里 @ 它，它不会 @ 回那个人」的根因所在。
 *
 * <p>问题链条：
 * <ol>
 *   <li>真正「发 TTS + 广播聊天」的是
 *       {@code lambda$queryAI$7(boolean, ServerLevel, ServerPlayer, String, MinecraftServer, VerityEntity)}；</li>
 *   <li>旧代码注在 {@code lambda$queryAI$4} —— 那只是一个
 *       {@code aload_0; areturn} 的取值 lambda，跟发送毫无关系；</li>
 *   <li>{@code require = 0} 让「找不到注入点」静默跳过，于是
 *       {@link PrivateChatReplySupport#sendChatReply} 从未执行，
 *       @ 回复逻辑等于不存在。</li>
 * </ol>
 *
 * <p>🔴 <b>方法选择器只写描述符、不写方法名</b>：lambda 编号随构建变化
 * （6.0.0 是 $7、beta.9 是 $4），写死编号换个构建就失效。描述符由参数类型决定，
 * 是稳定的；而且两种构建的参数顺序不同，正好可以用来区分形态。
 */
@Mixin(targets = "varmite.verity.entity.llm.AiManager", remap = false)
public abstract class AiManagerPrivateChatMixin {

    // remap = false：Minecraft 成员名必须写 SRG（PlayerList.broadcastSystemMessage -> m_240416_）。
    @Redirect(
            method = VerityStructureDetector.REPLY_LAMBDA_DESC_600,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;m_240416_(Lnet/minecraft/network/chat/Component;Z)V"),
            require = 0,
            remap = false
    )
    private static void verityExtended$sendToOwner(PlayerList playerList, Component message, boolean overlay,
                                                    boolean flag, ServerLevel level,
                                                    ServerPlayer requestedPlayer, String answer,
                                                    MinecraftServer server, VerityEntity verity) {
        PrivateChatReplySupport.sendChatReply(playerList, message, overlay,
                verity, answer, requestedPlayer, server);
    }

    @Redirect(
            method = VerityStructureDetector.REPLY_LAMBDA_DESC_600,
            at = @At(value = "INVOKE", target = "Lnet/minecraftforge/network/simple/SimpleChannel;send(Lnet/minecraftforge/network/PacketDistributor$PacketTarget;Ljava/lang/Object;)V"),
            require = 0,
            remap = false
    )
    private static void verityExtended$sendTtsToOwner(SimpleChannel channel,
                                                        PacketDistributor.PacketTarget originalTarget,
                                                        Object payload,
                                                        boolean flag, ServerLevel level,
                                                        ServerPlayer requestedPlayer, String answer,
                                                        MinecraftServer server, VerityEntity verity) {
        // 只接管「发给谁」：原版这里有两处 send（附近实体 / 玩家自己），签名相同，统一交给 sendTts 判断。
        PrivateChatReplySupport.sendTts(channel, originalTarget, payload, verity, requestedPlayer);
    }
}
