package com.hanchen.veritycustomapi.network;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.compat.StellarRemoteCompat;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** /verity-stellar：手动让 Verity 试放星之遥控器技能，便于在游戏里验证适配。 */
@Mod.EventBusSubscriber(modid = OllamaProxyMod.MOD_ID)
public final class StellarRemoteCommand {
    private StellarRemoteCommand() {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("verity-stellar")
                .then(Commands.literal("list").executes(context -> list(context.getSource())))
                .then(Commands.literal("hold")
                        .executes(context -> hold(context.getSource(), 10))
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 60))
                                .executes(context -> hold(context.getSource(),
                                        IntegerArgumentType.getInteger(context, "seconds")))))
                .then(Commands.literal("cast")
                        .then(Commands.argument("args", StringArgumentType.greedyString())
                                .executes(context -> cast(context.getSource(),
                                        StringArgumentType.getString(context, "args"))))));
    }

    private static int hold(CommandSourceStack source, int seconds) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception error) {
            source.sendFailure(Component.literal("这条指令只能由玩家执行。"));
            return 0;
        }
        String result = StellarRemoteCompat.previewHold(player, seconds);
        source.sendSuccess(() -> Component.literal(result), false);
        return 1;
    }

    private static int list(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(StellarRemoteCompat.describeSkills()), false);
        return 1;
    }

    private static int cast(CommandSourceStack source, String raw) {
        ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (Exception error) {
            source.sendFailure(Component.literal("这条指令只能由玩家执行。"));
            return 0;
        }
        String text = raw == null ? "" : raw.trim();
        int split = text.indexOf(' ');
        String skill = split < 0 ? text : text.substring(0, split);
        String aim = split < 0 ? "" : text.substring(split + 1).trim();
        String result = StellarRemoteCompat.cast(player, skill, aim);
        source.sendSuccess(() -> Component.literal(result), false);
        return 1;
    }
}