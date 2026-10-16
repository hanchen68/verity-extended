package com.hanchen.veritycustomapi.plugin;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * {@code /verity-plugin}：玩家的插件管理入口。
 *
 * 玩家只能看到和管理自己的插件；管理员（权限 2）可以管理全部。
 * 生成是异步的（要联网），所以先回一句「正在生成」，完成后把结果发回给玩家。
 */
@Mod.EventBusSubscriber(modid = OllamaProxyMod.MOD_ID)
public final class PluginCommand {

    private static final int ADMIN_PERMISSION = 2;

    private PluginCommand() {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("verity-plugin")
                .then(Commands.literal("list").executes(PluginCommand::list))
                .then(Commands.literal("create")
                        .then(Commands.argument("description", StringArgumentType.greedyString())
                                .executes(PluginCommand::create)))
                .then(Commands.literal("enable")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(context -> setEnabled(context, true))))
                .then(Commands.literal("disable")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(context -> setEnabled(context, false))))
                .then(Commands.literal("show")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(PluginCommand::show)))
                .then(Commands.literal("delete")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(PluginCommand::delete)))
                .then(Commands.literal("reload").executes(PluginCommand::reload))
                .then(Commands.literal("ui").executes(PluginCommand::openUi)));
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        boolean admin = source.hasPermission(ADMIN_PERMISSION);
        List<PluginDefinition> plugins = PluginStore.all();
        int shown = 0;
        for (PluginDefinition plugin : plugins) {
            if (!admin && player != null && !plugin.belongsTo(player.getUUID())) {
                continue;
            }
            shown++;
            source.sendSuccess(() -> Component.literal(
                            (plugin.enabled ? "§a[开] " : "§7[关] ") + plugin.id
                                    + " §f" + plugin.name
                                    + " §7(" + plugin.trigger.type + " → " + plugin.actions.size() + " 个动作)")
                    .withStyle(ChatFormatting.WHITE), false);
        }
        int total = shown;
        source.sendSuccess(() -> Component.literal("共 " + total + " 个插件（目录："
                + PluginStore.directory() + "）"), false);
        if (total == 0) {
            source.sendSuccess(() -> Component.literal(
                    "用 /verity-plugin create 你想要的功能 来生成一个，例如："
                            + "/verity-plugin create 挖矿的时候自动整理背包"), false);
        }
        return shown;
    }

    private static int create(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.literal("这个指令需要在游戏内执行"));
            return 0;
        }
        if (!PluginEngine.enabled()) {
            source.sendFailure(Component.literal("插件功能没开启（配置里 plugin.enabled = false）"));
            return 0;
        }
        String description = StringArgumentType.getString(context, "description");
        source.sendSuccess(() -> Component.literal("§b正在把你的想法变成插件，请稍等…"), false);
        // 联网生成必须离开主线程，否则会把服务器卡住
        Thread worker = new Thread(() -> {
            PluginGenerator.Outcome outcome = PluginGenerator.generate(description);
            player.getServer().execute(() -> report(player, outcome));
        }, "Verity-Plugin-Generate");
        worker.setDaemon(true);
        worker.start();
        return 1;
    }

    private static void report(ServerPlayer player, PluginGenerator.Outcome outcome) {
        if (!outcome.ok()) {
            player.sendSystemMessage(Component.literal("§c插件生成失败：" + String.join("；", outcome.errors())));
            player.sendSystemMessage(Component.literal(
                    "§7如果提示缺少某个动作，说明当前版本还做不到这个效果——可以换个说法，或者等后续版本补上。"));
            return;
        }
        PluginDefinition generated = outcome.plugin();
        String id = uniqueId(generated.id, player.getUUID().toString());
        PluginDefinition stamped = new PluginDefinition(id, generated.name, generated.description,
                player.getUUID().toString(), true, generated.trigger,
                generated.conditions, generated.actions);
        if (!PluginStore.save(stamped)) {
            player.sendSystemMessage(Component.literal("§c插件保存失败，请查看日志"));
            return;
        }
        player.sendSystemMessage(Component.literal("§a插件已生成：" + id + " —— " + stamped.name));
        player.sendSystemMessage(Component.literal("§7用 /verity-plugin show " + id
                + " 看它的内容，/verity-plugin disable " + id + " 可以随时关掉"));
    }

    private static int setEnabled(CommandContext<CommandSourceStack> context, boolean enabled) {
        CommandSourceStack source = context.getSource();
        String id = StringArgumentType.getString(context, "id");
        PluginDefinition plugin = PluginStore.get(id);
        if (plugin == null || !canManage(source, plugin)) {
            source.sendFailure(Component.literal("找不到这个插件（或它不是你的）"));
            return 0;
        }
        PluginStore.setEnabled(id, enabled);
        source.sendSuccess(() -> Component.literal("插件 " + id + (enabled ? " 已启用" : " 已停用")), false);
        return 1;
    }

    private static int show(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String id = StringArgumentType.getString(context, "id");
        PluginDefinition plugin = PluginStore.get(id);
        if (plugin == null || !canManage(source, plugin)) {
            source.sendFailure(Component.literal("找不到这个插件（或它不是你的）"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(PluginStore.toJson(plugin).toString()), false);
        return 1;
    }

    private static int delete(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String id = StringArgumentType.getString(context, "id");
        PluginDefinition plugin = PluginStore.get(id);
        if (plugin == null || !canManage(source, plugin)) {
            source.sendFailure(Component.literal("找不到这个插件（或它不是你的）"));
            return 0;
        }
        PluginStore.delete(id);
        source.sendSuccess(() -> Component.literal("插件 " + id + " 已删除"), false);
        return 1;
    }

    /** 打开图形界面：服务端只发一个信号，界面由客户端弹出。 */
    private static int openUi(CommandContext<CommandSourceStack> context) {
        ServerPlayer player = context.getSource().getPlayer();
        if (player == null) {
            context.getSource().sendFailure(Component.literal("这个指令需要在游戏内执行"));
            return 0;
        }
        com.hanchen.veritycustomapi.network.PluginNetwork.pushOpenUi(player);
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> context) {
        int count = PluginStore.loadAll();
        context.getSource().sendSuccess(() -> Component.literal("已重新加载 " + count + " 个插件"), false);
        return count;
    }

    private static boolean canManage(CommandSourceStack source, PluginDefinition plugin) {
        if (source.hasPermission(ADMIN_PERMISSION)) {
            return true;
        }
        ServerPlayer player = source.getPlayer();
        return player != null && plugin.belongsTo(player.getUUID());
    }

    /** id 撞车时加后缀，不覆盖别人的插件。 */
    private static String uniqueId(String base, String owner) {
        String candidate = base;
        int suffix = 2;
        while (true) {
            PluginDefinition existing = PluginStore.get(candidate);
            if (existing == null) {
                return candidate;
            }
            if (owner.equals(existing.owner)) {
                return candidate;
            }
            candidate = base + "_" + suffix;
            suffix++;
            if (suffix > 99) {
                return base + "_" + System.currentTimeMillis() % 100000;
            }
        }
    }
}
