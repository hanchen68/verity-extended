package com.hanchen.veritycustomapi.network;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.config.ModConfig;
import varmite.verity.entity.verity.VerityEntity;

@Mod.EventBusSubscriber(modid = OllamaProxyMod.MOD_ID)
public final class VerityChatCommand {
    private VerityChatCommand() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("v")
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(context -> execute(context.getSource(),
                                StringArgumentType.getString(context, "message"), false))));
        dispatcher.register(Commands.literal("v!")
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(context -> execute(context.getSource(),
                                StringArgumentType.getString(context, "message"), true))));
        // 服务端导航：跟随、停止跟随、叫到身边。
        // 这些是服务端权威状态，客户端在别人服务器上改不了，必须服务端执行。
        // 单机时客户端也能直连集成服务器，所以快捷菜单统一发这条指令就能两端通用。
        dispatcher.register(Commands.literal("verity-nav")
                .then(Commands.literal(ServerNavigationService.ACTION_FOLLOW)
                        .executes(context -> navigate(context.getSource(), ServerNavigationService.ACTION_FOLLOW)))
                .then(Commands.literal(ServerNavigationService.ACTION_STOP)
                        .executes(context -> navigate(context.getSource(), ServerNavigationService.ACTION_STOP)))
                .then(Commands.literal(ServerNavigationService.ACTION_COME)
                        .executes(context -> navigate(context.getSource(), ServerNavigationService.ACTION_COME))));
    }

    /** 处理 /verity-nav <动作>：动作只作用于执行者自己名下的 Verity。 */
    private static int navigate(CommandSourceStack source, String action) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception error) {
            source.sendFailure(Component.literal("该指令只能由玩家使用。"));
            return 0;
        }
        if (!ModConfig.SERVER_VERITY_ADAPTER_ENABLED.get()) {
            player.sendSystemMessage(Component.literal("多人 Verity 适配未启用。"));
            return 0;
        }
        if (!ServerNavigationService.apply(player, action)) {
            player.sendSystemMessage(Component.literal("§7【导航】这个动作暂时不可用。"));
            return 0;
        }
        return 1;
    }

    private static boolean isHoldingVerityItem(ServerPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.isEmpty()) continue;
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (id != null && "verity".equals(id.getNamespace())
                    && "verity_item".equals(id.getPath())) {
                return true;
            }
        }
        return false;
    }

    private static int execute(CommandSourceStack source, String message, boolean publish) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception error) {
            source.sendFailure(Component.literal("该指令只能由玩家使用。"));
            return 0;
        }
        // 多人适配开关只对「真的有多个玩家」的场合有意义。
        // 单人世界（含局域网单人）里挡在这里毫无道理：玩家自己的 Verity 就在旁边，
        // 却因为没开多人适配而用不了 /v。
        boolean solo = com.hanchen.veritycustomapi.compat.PrivateChatReplySupport
                .isSoloPlay(player.getServer());
        if (!ModConfig.SERVER_VERITY_ADAPTER_ENABLED.get() && !solo) {
            player.sendSystemMessage(Component.literal("多人 Verity 对话适配未启用。"));
            return 0;
        }
        // 管理员临时禁言（/verity-admin mute）：连 /v 指令一起拦
        if (ServerAdminState.isMuted(player.getUUID())) {
            player.sendSystemMessage(Component.literal("§7【Verity】管理员已暂停你使用 Verity 对话。"));
            return 0;
        }
        // /v! 是「公开」版本：服务器关了 allowPublicVerityChat 就直接拒绝，
        // 不是照旧广播。以前 publish 写死成 false，这个开关其实一直没生效。
        // 单人世界里「广播」只是发给自己，没有泄露风险，不该被这个开关挡住。
        if (publish && !ModConfig.SERVER_VERITY_ALLOW_PUBLIC_CHAT.get() && !solo) {
            player.sendSystemMessage(Component.literal("服务器已关闭 Verity 对话公开广播，请改用 /v。"));
            return 0;
        }
        VerityEntity verity = ServerVerityRegistry.findForPlayer(player);
        if (verity == null || !verity.isAlive()) {
            if (isHoldingVerityItem(player)) {
                player.sendSystemMessage(Component.literal(
                        "§7【Verity】你手里的是 Verity 物品，不是世界里的实体。"
                                + "先按 Q 把它丢到地上，等约 1 秒它变回 Verity 后再用 /v。"));
            } else {
                player.sendSystemMessage(Component.literal("你目前没有可用的 Verity。"));
            }
            return 0;
        }
        String text = message == null ? "" : message.trim();
        if (text.isBlank()) {
            player.sendSystemMessage(Component.literal(publish ? "用法：/v! 你的内容" : "用法：/v 你的内容"));
            return 0;
        }
        if (!com.hanchen.veritycustomapi.compat.OriginalVerityChatBridge.queryAi(
                verity, player, text)) {
            player.sendSystemMessage(Component.literal("原版 Verity 公共 API 暂时不可用。"));
            return 0;
        }
        player.getServer().getPlayerList().broadcastSystemMessage(
                Component.literal("<" + player.getGameProfile().getName() + "> " + text), false);
        return 1;
    }
}
