package com.hanchen.veritycustomapi.network;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.config.ModConfig;
import varmite.verity.entity.verity.VerityEntity;

@Mod.EventBusSubscriber(modid = OllamaProxyMod.MOD_ID)
public final class VerityChatCommand {
    private VerityChatCommand() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        // /v 也是语音识别的发送通道（VerityChatSender），单人世界必须保留。
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

    private static int execute(CommandSourceStack source, String message, boolean publish) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception error) {
            source.sendFailure(Component.literal("该指令只能由玩家使用。"));
            return 0;
        }
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
        String text = message == null ? "" : message.trim();
        if (text.isBlank()) {
            player.sendSystemMessage(Component.literal(publish ? "用法：/v! 你的内容" : "用法：/v 你的内容"));
            return 0;
        }
        VerityEntity verity = ServerVerityRegistry.findForPlayer(player);
        if (verity == null || !verity.isAlive()) {
            if (startHeldItemChat(player, text)) {
                return 1;
            }
            player.sendSystemMessage(Component.literal("你目前没有可用的 Verity。"));
            return 0;
        }
        if (!com.hanchen.veritycustomapi.compat.OriginalVerityChatBridge.queryAi(
                verity, player, text)) {
            player.sendSystemMessage(Component.literal("原版 Verity 公共 API 暂时不可用。"));
            return 0;
        }
        broadcastChat(player, text);
        return 1;
    }

    /**
     * 手持 verity_item 时：把物品放到脚下，等它变回实体后自动发送这句话。
     * 原版物品需要落地约 20 tick 才会生成 Verity，所以这里等 1.6 秒。
     */
    private static boolean startHeldItemChat(ServerPlayer player, String text) {
        InteractionHand hand = findHeldVerityItem(player);
        if (hand == null) return false;
        MinecraftServer server = player.getServer();
        if (server == null) return false;

        ItemStack held = player.getItemInHand(hand);
        ItemStack dropped = held.copy();
        dropped.setCount(1);
        if (!player.getAbilities().instabuild) {
            held.shrink(1);
            if (held.isEmpty()) {
                player.setItemInHand(hand, ItemStack.EMPTY);
            }
        }
        if (player.drop(dropped, false) == null) {
            player.sendSystemMessage(Component.literal("§c【Verity】放下物品失败，请确认脚下有空间。"));
            return false;
        }

        ServerLevel level = player.serverLevel();
        Set<UUID> before = new HashSet<>();
        for (VerityEntity existing : level.getEntitiesOfClass(VerityEntity.class,
                player.getBoundingBox().inflate(16.0D))) {
            before.add(existing.getUUID());
        }
        player.sendSystemMessage(Component.literal("§7【Verity】已放下物品，等它变回实体后自动发送…"));

        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(1600L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            server.execute(() -> {
                if (player.isRemoved()) return;
                VerityEntity spawned = null;
                double best = Double.MAX_VALUE;
                for (VerityEntity candidate : level.getEntitiesOfClass(VerityEntity.class,
                        player.getBoundingBox().inflate(16.0D))) {
                    if (!candidate.isAlive() || before.contains(candidate.getUUID())) continue;
                    double distance = player.distanceToSqr(candidate);
                    if (distance < best) {
                        best = distance;
                        spawned = candidate;
                    }
                }
                if (spawned == null) {
                    player.sendSystemMessage(Component.literal(
                            "§c【Verity】物品没有变回 Verity，请确认脚下是安全、可站立的地面。"));
                    return;
                }
                if (!com.hanchen.veritycustomapi.compat.OriginalVerityChatBridge.queryAi(
                        spawned, player, text)) {
                    player.sendSystemMessage(Component.literal("原版 Verity 公共 API 暂时不可用。"));
                    return;
                }
                broadcastChat(player, text);
            });
        }, "Verity-Item-Chat");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    private static InteractionHand findHeldVerityItem(ServerPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.isEmpty()) continue;
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
            if (id != null && "verity".equals(id.getNamespace())
                    && "verity_item".equals(id.getPath())) {
                return hand;
            }
        }
        return null;
    }

    private static void broadcastChat(ServerPlayer player, String text) {
        player.getServer().getPlayerList().broadcastSystemMessage(
                Component.literal("<" + player.getGameProfile().getName() + "> " + text), false);
    }
}
