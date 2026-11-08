package com.hanchen.veritycustomapi.mixin;

import com.hanchen.veritycustomapi.compat.PrivateChatReplySupport;
import com.hanchen.veritycustomapi.compat.VerityStructureDetector;
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
 * 6.0.0-beta.9 / 早期 6.1 的私聊 / 多播适配。
 *
 * <p>与 {@link AiManagerPrivateChatMixin}（6.0.0 正式版）是同一件事的两个签名版本：
 * 那个构建里发送回复的 lambda 参数是
 * {@code (VerityEntity, String, ServerPlayer, MinecraftServer)}，
 * 而 6.0.0 正式版多出 {@code boolean} 与 {@code ServerLevel}、顺序也不同。
 *
 * <p>🔴 为什么必须拆成两个类：{@code @Redirect} 的处理器签名必须与被注入方法的参数
 * 完全一致，否则是 {@code InvalidInjectionException}，整个 AiManager 类加载失败。
 * 两个构建只能用两个类，由 {@code MixinPlugin} 按描述符检测结果二选一。
 *
 * <p>方法选择器同样只写描述符，不写 lambda 编号（beta.9 是 $4，正式版是 $7）。
 */
@Mixin(targets = "varmite.verity.entity.llm.AiManager", remap = false)
public abstract class AiManagerPrivateChatLegacyMixin {

    @Redirect(
            method = VerityStructureDetector.REPLY_LAMBDA_DESC_BETA9,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;m_240416_(Lnet/minecraft/network/chat/Component;Z)V"),
            require = 0,
            remap = false
    )
    private static void verityExtended$sendToOwnerLegacy(PlayerList playerList, Component message, boolean overlay,
                                                          VerityEntity verity, String answer,
                                                          ServerPlayer requestedPlayer,
                                                          MinecraftServer server) {
        PrivateChatReplySupport.sendChatReply(playerList, message, overlay,
                verity, answer, requestedPlayer, server);
    }

    @Redirect(
            method = VerityStructureDetector.REPLY_LAMBDA_DESC_BETA9,
            at = @At(value = "INVOKE", target = "Lnet/minecraftforge/network/simple/SimpleChannel;send(Lnet/minecraftforge/network/PacketDistributor$PacketTarget;Ljava/lang/Object;)V"),
            require = 0,
            remap = false
    )
    private static void verityExtended$sendTtsToOwnerLegacy(SimpleChannel channel,
                                                             PacketDistributor.PacketTarget originalTarget,
                                                             Object payload,
                                                             VerityEntity verity, String answer,
                                                             ServerPlayer requestedPlayer,
                                                             MinecraftServer server) {
        PrivateChatReplySupport.sendTts(channel, originalTarget, payload, verity, requestedPlayer);
    }
}
