package com.hanchen.veritycustomapi.client;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 客户端命令 /verity-ai：导航、定时提醒、查看对话记录与生成日记。 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = OllamaProxyMod.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class VerityAiCommand {
    private VerityAiCommand() {
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("verity-ai")
                .then(Commands.literal("follow").executes(context -> navigate("follow")))
                .then(Commands.literal("stop").executes(context -> navigate("stop")))
                .then(Commands.literal("come").executes(context -> navigate("come")))
                .then(Commands.literal("goto")
                        .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                                .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                        .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                                .executes(context -> {
                                                    VerityNavigationService.navigateTo(
                                                            DoubleArgumentType.getDouble(context, "x"),
                                                            DoubleArgumentType.getDouble(context, "y"),
                                                            DoubleArgumentType.getDouble(context, "z"),
                                                            "目的地");
                                                    return 1;
                                                })))))
                .then(Commands.literal("remind")
                        .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 1440))
                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            ReminderManager.add(
                                                    IntegerArgumentType.getInteger(context, "minutes") * 60_000L,
                                                    StringArgumentType.getString(context, "text"));
                                            return 1;
                                        }))))
                .then(Commands.literal("remind-help").executes(context -> {
                    feedback("§b【提醒】两种用法：");
                    feedback("§7· 直接说「20 分钟后提醒我收菜」");
                    feedback("§7· 或输入 /verity-ai remind 20 收菜");
                    if (!ReminderManager.pending().isEmpty()) {
                        feedback("§7当前待提醒 " + ReminderManager.pending().size() + " 条");
                    }
                    return 1;
                }))
                .then(Commands.literal("log").executes(context -> {
                    openScreen();
                    return 1;
                }))
                .then(Commands.literal("diary").executes(context -> {
                    openScreen();
                    DiaryManager.requestGenerate(message -> feedback(message));
                    return 1;
                }))
                .then(Commands.literal("status").executes(context -> {
                    feedback("§b【Verity】跟随：" + (VerityNavigationService.isFollowing() ? "开着" : "关着")
                            + " · 导航：" + (VerityNavigationService.current() == null ? "没有" : "进行中")
                            + " · 待提醒：" + ReminderManager.pending().size() + " 条");
                    return 1;
                }))
        );
    }

    /**
     * 分发导航动作。
     *
     * 单人 / 局域网主机直接走本地集成服务器，跟以前一样。
     * 连别人的服务器时客户端拿不到权威状态，就改发服务端指令 {@code /verity-nav} 让它去执行，
     * 这样快捷菜单和这条指令在多人游戏里也都能用。
     */
    private static int navigate(String action) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getSingleplayerServer() != null) {
            switch (action) {
                case "follow" -> VerityNavigationService.startFollow();
                case "stop" -> VerityNavigationService.stopAll();
                case "come" -> VerityNavigationService.comeToPlayer();
                default -> {
                }
            }
            return 1;
        }
        if (minecraft.player == null) {
            return 0;
        }
        minecraft.player.connection.sendCommand("verity-nav " + action);
        return 1;
    }

    private static void openScreen() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> minecraft.setScreen(new ConversationLogScreen(null)));
    }

    private static void feedback(String text) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(Component.literal(text), false);
            }
        });
    }
}
