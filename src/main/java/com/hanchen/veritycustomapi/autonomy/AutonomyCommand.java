package com.hanchen.veritycustomapi.autonomy;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.network.ServerVerityRegistry;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import varmite.verity.entity.verity.VerityEntity;

@Mod.EventBusSubscriber(modid = OllamaProxyMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class AutonomyCommand {
    private AutonomyCommand() {}
    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        var root = Commands.literal("verity-autonomy");
        for (String action : new String[]{"on","off","status","plan","stop","resume","hold","companion","explore","revoke","selftest"}) {
            root.then(Commands.literal(action).executes(ctx -> execute(ctx.getSource().getPlayerOrException(), action, 60)));
        }
        root.then(Commands.literal("wait").then(Commands.argument("seconds", IntegerArgumentType.integer(1,600))
                .executes(ctx -> execute(ctx.getSource().getPlayerOrException(), "wait", IntegerArgumentType.getInteger(ctx,"seconds")))));
        root.then(Commands.literal("grant").then(Commands.argument("seconds", IntegerArgumentType.integer(1,600))
                .executes(ctx -> execute(ctx.getSource().getPlayerOrException(), "grant", IntegerArgumentType.getInteger(ctx,"seconds")))));
        event.getDispatcher().register(root);
    }
    private static int execute(ServerPlayer player, String action, int seconds) {
        VerityEntity verity = ServerVerityRegistry.findForPlayer(player);
        if (verity == null || !ServerVerityRegistry.owns(player, verity)) {
            reply(player, "没有找到归属于你的 Verity。"); return 0;
        }
        if (!java.util.Set.of("status", "plan").contains(action)) {
            AutonomyDecisionService.cancel(verity);
            AutonomyAgenda.interrupted(verity, "玩家手动控制：" + action);
        }
        if (java.util.Set.of("off", "stop", "wait", "hold", "companion", "resume", "revoke").contains(action))
            AutonomyActions.cancelOwnedJob(player, verity);
        switch (action) {
            case "on" -> {
                VerityAutonomy.enable(verity, true);
                com.hanchen.veritycustomapi.OllamaProxyMod.LOGGER.info("[Autonomy] enabled entity={} uuid={} owner={}", verity.getId(), verity.getUUID(), player.getUUID());
                reply(player, "实体 #" + verity.getId() + " 已开启全自主：可自行观察、移动、采集、种植和整理，无需逐次授权；后台模型会消耗额度。保留工具安全边界，随时可叫停。");
            }
            case "off" -> { VerityAutonomy.enable(verity,false); AutonomyActions.revoke(verity); reply(player,"已关闭模型自主。"); }
            case "status", "plan" -> reply(player, "实体 #" + verity.getId() + "（" + verity.getUUID() + "）；" + VerityAutonomy.describe(verity)
                    + "；线路：" + AutonomyDecisionService.routeDescription());
            case "stop" -> { VerityAutonomy.stop(verity); AutonomyActions.revoke(verity); reply(player,"已停止自主行动。"); }
            case "resume", "companion" -> { VerityAutonomy.resume(verity); AutonomyBrain.backToCompanion(verity); reply(player,"已回到陪伴计划。"); }
            case "hold" -> { AutonomyBrain.holdHere(verity); reply(player,"Verity 将守在当前位置。"); }
            case "wait" -> { VerityAutonomy.waitFor(verity, seconds); reply(player,"等待 " + seconds + " 秒。"); }
            case "explore" -> { AutonomyBrain.scoutAround(verity); reply(player,"已请求在安全可达范围内探索。"); }
            case "grant" -> {
                VerityAutonomy.enable(verity, true);
                reply(player,"旧 grant 命令已兼容为开启全自主，不再使用授权倒计时；后台模型请求会消耗额度。");
            }
            case "selftest" -> {
                reply(player, com.hanchen.veritycustomapi.body.VeritySelfTest.start(verity, player));
            }
            case "revoke" -> { VerityAutonomy.stop(verity); AutonomyActions.revoke(verity); reply(player,"已暂停全自主；使用 resume 恢复，或 off 关闭。"); }
            default -> { return 0; }
        }
        return 1;
    }
    private static void reply(ServerPlayer player,String message) { player.displayClientMessage(Component.literal(message),false); }
}
