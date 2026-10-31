package com.hanchen.veritycustomapi.network;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

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
        // 战斗开关：/verity-combat on | off | status（不带参数即查看状态）。
        // 状态是服务端权威的静态开关，客户端在别人服务器上改不了，所以必须走指令。
        dispatcher.register(Commands.literal("verity-combat")
                .then(Commands.literal("on")
                        .executes(context -> setCombatAssist(context.getSource(), true)))
                .then(Commands.literal("off")
                        .executes(context -> setCombatAssist(context.getSource(), false)))
                .then(Commands.literal("status")
                        .executes(context -> reportCombatAssist(context.getSource())))
                // /verity-combat test：立刻对附近最近的活体目标召唤一次恶魔替身。
                // 用来把「触发链路」和「召唤链路」分开——测试时不用真等被打死。
                .then(Commands.literal("test")
                        .executes(context -> testAwaken(context.getSource())))
                .executes(context -> reportCombatAssist(context.getSource())));
        // 清空上下文：/verity-context clear | restore | status
        // 用途是兜底「历史被污染导致上游 400」——最典型的就是
        // 「assistant 声明了 tool_calls 却没有对应的 tool 回复」。
        // 配对问题已经由 ToolCallNormalizer 自动补齐，这里只留一个人能按的开关。
        dispatcher.register(Commands.literal("verity-context")
                .then(Commands.literal("clear")
                        .executes(context -> clearContext(context.getSource())))
                .then(Commands.literal("restore")
                        .executes(context -> restoreContext(context.getSource())))
                .then(Commands.literal("status")
                        .executes(context -> reportContext(context.getSource())))
                .executes(context -> reportContext(context.getSource())));
    }

    /** /verity-context clear：之后的请求不再携带历史对话。 */
    private static int clearContext(CommandSourceStack source) {
        com.hanchen.veritycustomapi.proxy.ContextReset.suppress();
        source.sendSuccess(() -> Component.literal(
                "§a【上下文】已清空。之后的对话不再携带历史（人设与长期记忆仍保留），"
                        + "用 /verity-context restore 恢复"), false);
        return 1;
    }

    /** /verity-context restore：恢复正常历史。 */
    private static int restoreContext(CommandSourceStack source) {
        com.hanchen.veritycustomapi.proxy.ContextReset.restore();
        source.sendSuccess(() -> Component.literal("§a【上下文】已恢复正常历史"), false);
        return 1;
    }

    /** /verity-context status：查看当前是否处于清空模式。 */
    private static int reportContext(CommandSourceStack source) {
        boolean cleared = com.hanchen.veritycustomapi.proxy.ContextReset.isSuppressed();
        source.sendSuccess(() -> Component.literal(cleared
                ? "§e【上下文】当前为「已清空」：每次请求只保留人设与最后一句话，已丢弃 "
                        + com.hanchen.veritycustomapi.proxy.ContextReset.droppedRequests() + " 次"
                : "§a【上下文】正常携带历史"), false);
        return 1;
    }

    /** 处理 /verity-combat on|off。 */
    private static int setCombatAssist(CommandSourceStack source, boolean enabled) {
        com.hanchen.veritycustomapi.combat.CombatAuraManager.combatAssistEnabled = enabled;
        source.sendSuccess(() -> Component.literal("自动护主已" + (enabled ? "开启" : "关闭")), false);
        return 1;
    }

    /** 处理 /verity-combat status。 */
    private static int reportCombatAssist(CommandSourceStack source) {
        boolean enabled = com.hanchen.veritycustomapi.combat.CombatAuraManager.combatAssistEnabled;
        source.sendSuccess(() -> Component.literal("自动护主：" + (enabled ? "开启" : "关闭")), false);

        // 觉醒状态：开关 / 是否已召唤 / 冷却，排查「为什么没生效」时一眼能看出卡在哪
        boolean awakening = com.hanchen.veritycustomapi.config.ModConfig.VERITY_DEMON_AWAKENING.get();
        ServerPlayer player = source.getPlayer();
        boolean active = player != null
                && com.hanchen.veritycustomapi.combat.MonstrousManager.isAwakened(player.getUUID());
        String state = !awakening ? "已关闭" : active ? "已召唤恶魔替身" : "待触发";
        source.sendSuccess(() -> Component.literal("残血深渊觉醒：" + state
                + "（触发条件：被最大生命 ≥200 的 Boss 打中，或血量 ≤40% 时被 ≥100 的精英打中）"), false);
        return 1;
    }

    /** 处理 /verity-combat test：手动触发一次觉醒，用于排查「到底卡在哪一步」。 */
    private static int testAwaken(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("这个指令只能由玩家执行"));
            return 0;
        }
        varmite.verity.entity.verity.VerityEntity verity =                com.hanchen.veritycustomapi.network.ServerVerityRegistry.findForPlayer(player);
        if (verity == null) {
            source.sendFailure(Component.literal("§c找不到你的 Verity（同维度内没有活着的实体）"));
            return 0;
        }
        net.minecraft.world.entity.LivingEntity target = player.level()
                .getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                        player.getBoundingBox().inflate(16.0D),
                        entity -> entity != player && entity != verity && entity.isAlive()
                                && !(entity instanceof ServerPlayer))
                .stream()
                .min(java.util.Comparator.comparingDouble(player::distanceToSqr))
                .orElse(null);
        if (target == null) {
            source.sendFailure(Component.literal("§c附近 16 格内没有可作为目标的活动实体，先放一只怪再试"));
            return 0;
        }
        boolean ok = com.hanchen.veritycustomapi.combat.MonstrousManager
                .transform(player, verity, target);
        if (ok) {
            source.sendSuccess(() -> Component.literal("§a觉醒已触发（测试）：恶魔已对 "
                    + target.getName().getString() + " 召唤"), false);
        } else {
            source.sendSuccess(() -> Component.literal(
                    "§c没有触发，看日志里的 [觉醒] 行——会写清是开关关了、拿不到主人 UUID、"
                            + "还是已在冷却/已觉醒"), false);
        }
        return 1;
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
        // 不再做「有没有 Verity」的前置检测：那个检测会把「Verity 是物品形态
        // （拿在手上或收在背包里）」判成「没有 Verity」。
        // 现在没有实体时用「游离实体」发起对话（见 queryAiDetached），
        // 既不拦人，也不会动玩家背包。
        VerityEntity verity = ServerVerityRegistry.findForPlayer(player);
        boolean ok = verity != null && verity.isAlive()
                ? com.hanchen.veritycustomapi.compat.OriginalVerityChatBridge.queryAi(verity, player, text)
                : com.hanchen.veritycustomapi.compat.OriginalVerityChatBridge.queryAiDetached(player, text);
        if (!ok) {
            player.sendSystemMessage(Component.literal("原版 Verity 公共 API 暂时不可用。"));
            return 0;
        }
        broadcastChat(player, text);
        return 1;
    }

    private static void broadcastChat(ServerPlayer player, String text) {
        player.getServer().getPlayerList().broadcastSystemMessage(
                Component.literal("<" + player.getGameProfile().getName() + "> " + text), false);
    }
}
