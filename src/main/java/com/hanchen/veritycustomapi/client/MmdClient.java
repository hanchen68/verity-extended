package com.hanchen.veritycustomapi.client;

import com.hanchen.veritycustomapi.mmd.MmdPlaybackManager;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.hanchen.veritycustomapi.mmd.MmdDanceProject;

import java.util.Map;

/**
 * 客户端 MMD 命令和扫描入口。
 * 提供 /verity-mmd 命令：list / play <name> / stop / rescan
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MmdClient {

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        // 注册命令时也确保扫描一次（此时 FMLPaths 已完全可用，比 FMLClientSetupEvent 阶段更稳）
        MmdPlaybackManager.get().scanAndLoad();
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal("verity-mmd")
                .then(Commands.literal("list").executes(MmdClient::list))
                .then(Commands.literal("play")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        MmdPlaybackManager.get().listProjects().keySet(), builder))
                                .executes(MmdClient::play)))
                .then(Commands.literal("stop").executes(MmdClient::stop))
                .then(Commands.literal("rescan").executes(MmdClient::rescan))
                .then(Commands.literal("speed")
                        .executes(MmdClient::speedShow)
                        .then(Commands.argument("factor", FloatArgumentType.floatArg(0.1f, 10.0f))
                                .executes(MmdClient::speedSet)))
                .then(Commands.literal("freeze")
                        .executes(MmdClient::freezeShow)
                        .then(Commands.literal("on").executes(MmdClient::freezeOn))
                        .then(Commands.literal("off").executes(MmdClient::freezeOff)))
        );
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        Map<String, MmdDanceProject> projects = MmdPlaybackManager.get().listProjects();
        if (projects.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "未找到 MMD 项目；请在 config/verity_extended/mmd/ 创建舞蹈文件夹"), false);
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("可用 MMD 项目 (" + projects.size() + "):"), false);
        for (MmdDanceProject project : projects.values()) {
            ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                    "  %s  %s  时长=%.2fs%s", project.id(), project.contentSummary(),
                    project.durationSeconds(), project.legacy() ? "  [旧格式]" : "")), false);
        }
        return 1;
    }

    private static int play(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "name");
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            ctx.getSource().sendFailure(Component.literal("当前没有世界"));
            return 0;
        }
        // 多人游戏下 MMD 已禁用：明确报错，不要静默什么都不发生
        if (!MmdPlaybackManager.isPlaybackAvailable()) {
            ctx.getSource().sendFailure(Component.literal(MmdPlaybackManager.MULTIPLAYER_DISABLED_MESSAGE));
            return 0;
        }
        MmdPlaybackManager manager = MmdPlaybackManager.get();
        if (manager.getProject(name) == null) {
            ctx.getSource().sendFailure(Component.literal("未找到 MMD 项目: " + name + "；如刚添加文件，请先执行 /verity-mmd rescan"));
            return 0;
        }
        int count = manager.startPlaybackForDemons(mc.level, name);
        if (count == 0) {
            ctx.getSource().sendFailure(Component.literal("未找到恶魔实体或 MMD 项目: " + name));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.literal("MMD [" + name + "] 已作用于 " + count + " 个恶魔实体"), true);
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) {
        MmdPlaybackManager.get().stopPlaybackForAllDemons();
        ctx.getSource().sendSuccess(() -> Component.literal("已停止所有恶魔实体的 MMD 播放"), true);
        return 1;
    }

    private static int rescan(CommandContext<CommandSourceStack> ctx) {
        MmdPlaybackManager.get().scanAndLoad();
        int n = MmdPlaybackManager.get().listProjects().size();
        ctx.getSource().sendSuccess(() -> Component.literal("扫描完成，共 " + n + " 个舞蹈项目"), true);
        return 1;
    }

    private static int speedShow(CommandContext<CommandSourceStack> ctx) {
        float s = MmdPlaybackManager.get().getPlaybackSpeed();
        ctx.getSource().sendSuccess(() -> Component.literal(String.format("MMD 播放速度: %.2fx", s)), false);
        return 1;
    }

    private static int speedSet(CommandContext<CommandSourceStack> ctx) {
        float s = FloatArgumentType.getFloat(ctx, "factor");
        MmdPlaybackManager.get().setPlaybackSpeed(s);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format("MMD 播放速度已设为 %.2fx", s)), true);
        return 1;
    }

    private static int freezeShow(CommandContext<CommandSourceStack> ctx) {
        boolean v = MmdPlaybackManager.get().isFreezeOnPlay();
        ctx.getSource().sendSuccess(() -> Component.literal("播放时冻结恶魔 AI: " + (v ? "开" : "关")), false);
        return 1;
    }
    private static int freezeOn(CommandContext<CommandSourceStack> ctx) {
        MmdPlaybackManager.get().setFreezeOnPlay(true);
        ctx.getSource().sendSuccess(() -> Component.literal("已开启播放时冻结恶魔 AI"), true);
        return 1;
    }
    private static int freezeOff(CommandContext<CommandSourceStack> ctx) {
        MmdPlaybackManager.get().setFreezeOnPlay(false);
        ctx.getSource().sendSuccess(() -> Component.literal("已关闭播放时冻结恶魔 AI"), true);
        return 1;
    }
}
