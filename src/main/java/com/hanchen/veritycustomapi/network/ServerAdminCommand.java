package com.hanchen.veritycustomapi.network;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.config.ModConfig;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 服务端管理员指令 /verity-admin，分三类子指令：状态与实体管理、运行时改服务端配置、玩家级管控。
 * 全部要求权限等级 2（OP）。mute / limit 是运行时设置，重启就没了；config 会写回配置文件。
 */
@Mod.EventBusSubscriber(modid = OllamaProxyMod.MOD_ID)
public final class ServerAdminCommand {
    /** 要求 OP 的权限等级，跟 /kill 这些原版管理指令一致。 */
    private static final int REQUIRED_PERMISSION = 2;

    private ServerAdminCommand() {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("verity-admin")
                .requires(source -> source.hasPermission(REQUIRED_PERMISSION))
                .then(Commands.literal("status").executes(ServerAdminCommand::status))
                .then(Commands.literal("list").executes(ServerAdminCommand::list))
                .then(Commands.literal("cleanup").executes(ServerAdminCommand::cleanup))
                .then(Commands.literal("purge")
                        .executes(ServerAdminCommand::purgeHint)
                        .then(Commands.literal("confirm").executes(ServerAdminCommand::purgeAll)))
                .then(Commands.literal("unclaim")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(context -> unclaim(context,
                                        StringArgumentType.getString(context, "player")))))
                .then(Commands.literal("config")
                        .executes(ServerAdminCommand::configList)
                        .then(Commands.argument("key", StringArgumentType.word())
                                .then(Commands.argument("value", StringArgumentType.word())
                                        .executes(context -> configSet(context,
                                                StringArgumentType.getString(context, "key"),
                                                StringArgumentType.getString(context, "value"))))))
                .then(Commands.literal("player")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(context -> player(context,
                                        StringArgumentType.getString(context, "player")))))
                .then(Commands.literal("mute")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .then(Commands.argument("state", StringArgumentType.word())
                                        .executes(context -> mute(context,
                                                StringArgumentType.getString(context, "player"),
                                                StringArgumentType.getString(context, "state"))))))
                .then(Commands.literal("limit")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .then(Commands.argument("count", StringArgumentType.word())
                                        .executes(context -> limit(context,
                                                StringArgumentType.getString(context, "player"),
                                                StringArgumentType.getString(context, "count")))))));
    }

    // ==================== 状态与实体管理 ====================

    private static int status(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        int[] counts = ServerVerityRegistry.countEntities();
        int online = server.getPlayerList().getPlayers().size();
        int owned = ServerVerityRegistry.snapshotOwners().size();

        reply(source, "§b【Verity 管理】服务端状态");
        reply(source, "§7· 适配开关：" + yesNo(ModConfig.SERVER_VERITY_ADAPTER_ENABLED.get())
                + " · 私聊：" + yesNo(ModConfig.SERVER_VERITY_PRIVATE_CHAT.get())
                + " · 必须用 /v：" + yesNo(ModConfig.SERVER_VERITY_REQUIRE_COMMAND.get()));
        reply(source, "§7· 在线玩家：" + online + " · 有 Verity 归属的玩家：" + owned);
        reply(source, "§7· Verity 实体：" + counts[0] + "（其中无主 " + counts[1] + "）· 恶魔实体：" + counts[2]);
        reply(source, "§7· 默认实体上限/人：" + ModConfig.SERVER_VERITY_MAX_ENTITIES_PER_PLAYER.get()
                + " · 恶魔血量上限：" + ModConfig.SERVER_VERITY_DEMON_MAX_HEALTH.get());
        int muted = ServerAdminState.mutedPlayers().size();
        int limited = ServerAdminState.limitOverrides().size();
        if (muted > 0 || limited > 0) {
            reply(source, "§7· 临时设置：禁言 " + muted + " 人 · 单独配额 " + limited + " 人");
        }
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        Map<UUID, Set<UUID>> owners = ServerVerityRegistry.snapshotOwners();
        if (owners.isEmpty()) {
            reply(source, "§7【Verity 管理】当前没有任何 Verity 有归属。");
            return 1;
        }
        reply(source, "§b【Verity 管理】归属列表（共 " + owners.size() + " 名玩家）");
        owners.forEach((owner, entities) -> reply(source, "§7· " + displayName(server, owner)
                + " §8→§7 " + entities.size() + " 个实体"
                + (ServerAdminState.isMuted(owner) ? " §c[已禁言]" : "")
                + (ServerAdminState.hasLimitOverride(owner)
                        ? " §e[配额 " + ServerAdminState.effectiveLimit(owner) + "]" : "")));
        return 1;
    }

    private static int cleanup(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        int removed = ServerVerityRegistry.purgeUnowned();
        reply(source, "§a【Verity 管理】已清理 " + removed + " 个无主 Verity 实体。");
        OllamaProxyMod.LOGGER.info("管理员清理无主 Verity 实体: {} 个，操作者={}",
                removed, source.getTextName());
        return 1;
    }

    /**
     * 只打印清除全部 Verity 的说明，不真删。这一步会连有归属的一起删，得再补 confirm 才执行。
     */
    private static int purgeHint(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        int[] counts = ServerVerityRegistry.countEntities();
        reply(source, "§c【Verity 管理】清除所有 Verity 实体");
        reply(source, "§7· 当前共有 §f" + counts[0] + "§7 个 Verity 实体（含 §f"
                + (counts[0] - counts[1]) + "§7 个有归属的）");
        reply(source, "§7· 影响：所有玩家的 Verity 都会被移除，归属与跟随状态一并清空。");
        reply(source, "§7· 实体本身带的记忆/好感度数据随实体一起消失，无法撤销。");
        reply(source, "§7· 想只清无主的，用 §f/verity-admin cleanup§7（安全得多）。");
        reply(source, "§7· 清完不会自动重新生成；需要时用原版指令 §f/recoververity§7 召回。");
        reply(source, "§e确认要清除的话，执行：§f/verity-admin purge confirm");
        return 1;
    }

    private static int purgeAll(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        int removed = ServerVerityRegistry.purge(true);
        reply(source, "§a【Verity 管理】已清除全部 Verity 实体，共 " + removed + " 个。");
        if (removed > 0) {
            reply(source, "§7· 原版「已生成」状态已一并复位，不会因为残留状态报错。");
            reply(source, "§7· 需要重新获得 Verity 时用原版指令 §f/recoververity§7。");
        }
        OllamaProxyMod.LOGGER.warn("管理员清除了全部 Verity 实体: {} 个，操作者={}",
                removed, source.getTextName());
        return 1;
    }

    private static int unclaim(CommandContext<CommandSourceStack> context, String playerName) {        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        UUID target = resolvePlayerId(server, playerName);
        if (target == null) {
            source.sendFailure(Component.literal("找不到玩家：" + playerName));
            return 0;
        }
        int count = ServerVerityRegistry.unclaimAll(target);
        if (count == 0) {
            reply(source, "§7【Verity 管理】" + playerName + " 名下没有可解除归属的实体。");
            return 1;
        }
        reply(source, "§a【Verity 管理】已解除 " + playerName + " 名下 " + count
                + " 个实体的归属（现在任何人都可以重新认领）。");
        OllamaProxyMod.LOGGER.info("管理员解除归属: 玩家={} 实体数={} 操作者={}",
                playerName, count, source.getTextName());
        return 1;
    }

    // ==================== 运行时改服务端配置 ====================

    private static int configList(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        reply(source, "§b【Verity 管理】服务端配置（§7/v-a config <键> <值>§b 可改，会写回配置文件）");
        reply(source, "§7· §fadapter §8= " + ModConfig.SERVER_VERITY_ADAPTER_ENABLED.get()
                + "§8（多人适配总开关）");
        reply(source, "§7· §fprivateChat §8= " + ModConfig.SERVER_VERITY_PRIVATE_CHAT.get()
                + "§8（回复只发给触发者）");
        reply(source, "§7· §frequireCommand §8= " + ModConfig.SERVER_VERITY_REQUIRE_COMMAND.get()
                + "§8（必须用 /v 触发，关闭后普通聊天也能触发）");
        reply(source, "§7· §fpublicChat §8= " + ModConfig.SERVER_VERITY_ALLOW_PUBLIC_CHAT.get()
                + "§8（允许 /v! 公开广播）");
        reply(source, "§7· §fautoClaim §8= " + ModConfig.SERVER_VERITY_AUTO_CLAIM_UNOWNED.get()
                + "§8（首次交互自动认领无主实体）");
        reply(source, "§7· §fmaxEntities §8= " + ModConfig.SERVER_VERITY_MAX_ENTITIES_PER_PLAYER.get()
                + "§8（每人实体上限 1-64）");
        reply(source, "§7· §fdemonHealth §8= " + ModConfig.SERVER_VERITY_DEMON_MAX_HEALTH.get()
                + "§8（恶魔血量上限 20-4000）");
        reply(source, "§7· §fglobalPurge §8= " + ModConfig.SERVER_VERITY_ALLOW_GLOBAL_ENTITY_PURGE.get()
                + "§8（允许原版每 40 tick 全世界实体扫描，卡顿风险）");
        return 1;
    }

    private static int configSet(CommandContext<CommandSourceStack> context, String key, String value) {
        CommandSourceStack source = context.getSource();
        String normalized = key.toLowerCase(java.util.Locale.ROOT);
        try {
            switch (normalized) {
                case "adapter" -> ModConfig.SERVER_VERITY_ADAPTER_ENABLED.set(parseBool(value));
                case "privatechat" -> ModConfig.SERVER_VERITY_PRIVATE_CHAT.set(parseBool(value));
                case "requirecommand" -> ModConfig.SERVER_VERITY_REQUIRE_COMMAND.set(parseBool(value));
                case "publicchat" -> ModConfig.SERVER_VERITY_ALLOW_PUBLIC_CHAT.set(parseBool(value));
                case "autoclaim" -> ModConfig.SERVER_VERITY_AUTO_CLAIM_UNOWNED.set(parseBool(value));
                case "maxentities" -> ModConfig.SERVER_VERITY_MAX_ENTITIES_PER_PLAYER.set(
                        clamp(parseInt(value), 1, 64));
                case "demonhealth" -> ModConfig.SERVER_VERITY_DEMON_MAX_HEALTH.set(
                        clamp(parseInt(value), 20, 4000));
                case "globalpurge" -> ModConfig.SERVER_VERITY_ALLOW_GLOBAL_ENTITY_PURGE.set(parseBool(value));
                default -> {
                    source.sendFailure(Component.literal("未知配置键：" + key + "（用 /verity-admin config 查看）"));
                    return 0;
                }
            }
        } catch (NumberFormatException error) {
            source.sendFailure(Component.literal("数值格式不对：" + value));
            return 0;
        } catch (IllegalArgumentException error) {
            source.sendFailure(Component.literal("布尔值只能是 true/false（或 on/off）：" + value));
            return 0;
        }
        ConfigManager.saveCommonConfig();
        reply(source, "§a【Verity 管理】已设置 " + normalized + " = " + value + "，并写回配置文件。");
        OllamaProxyMod.LOGGER.info("管理员修改服务端配置: {} = {}，操作者={}",
                normalized, value, source.getTextName());
        return 1;
    }

    // ==================== 玩家级管控 ====================

    private static int player(CommandContext<CommandSourceStack> context, String playerName) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        UUID target = resolvePlayerId(server, playerName);
        if (target == null) {
            source.sendFailure(Component.literal("找不到玩家：" + playerName));
            return 0;
        }
        int owned = ServerVerityRegistry.ownedCount(target);
        int limit = ServerAdminState.effectiveLimit(target);
        reply(source, "§b【Verity 管理】" + displayName(server, target));
        reply(source, "§7· 持有实体：" + owned + " / 上限 " + limit
                + (ServerAdminState.hasLimitOverride(target) ? "§e（管理员单独设置）" : "§8（服务器默认）"));
        reply(source, "§7· 对话禁言：" + (ServerAdminState.isMuted(target) ? "§c是" : "§a否"));
        ServerPlayer online = server.getPlayerList().getPlayer(target);
        reply(source, "§7· 在线：" + (online != null ? "是" : "否"));
        return 1;
    }

    private static int mute(CommandContext<CommandSourceStack> context, String playerName, String state) {
        CommandSourceStack source = context.getSource();
        UUID target = resolvePlayerId(source.getServer(), playerName);
        if (target == null) {
            source.sendFailure(Component.literal("找不到玩家：" + playerName));
            return 0;
        }
        boolean muted = parseBool(state);
        ServerAdminState.setMuted(target, muted);
        reply(source, muted
                ? "§a【Verity 管理】已禁止 " + playerName + " 触发 Verity 对话（/v 与普通聊天都会被拦下）。"
                : "§a【Verity 管理】已恢复 " + playerName + " 的 Verity 对话。");
        reply(source, "§8注意：这是运行时设置，服务器重启后失效。");
        OllamaProxyMod.LOGGER.info("管理员{}禁言: 玩家={} 操作者={}",
                muted ? "" : "解除", playerName, source.getTextName());
        return 1;
    }

    private static int limit(CommandContext<CommandSourceStack> context, String playerName, String count) {
        CommandSourceStack source = context.getSource();
        UUID target = resolvePlayerId(source.getServer(), playerName);
        if (target == null) {
            source.sendFailure(Component.literal("找不到玩家：" + playerName));
            return 0;
        }
        String normalized = count.toLowerCase(java.util.Locale.ROOT);
        if ("default".equals(normalized) || "默认".equals(normalized)) {
            ServerAdminState.setLimit(target, null);
            reply(source, "§a【Verity 管理】" + playerName + " 的实体上限已恢复为服务器默认值（"
                    + ModConfig.SERVER_VERITY_MAX_ENTITIES_PER_PLAYER.get() + "）。");
            return 1;
        }
        int value;
        try {
            value = clamp(parseInt(count), 1, 64);
        } catch (NumberFormatException error) {
            source.sendFailure(Component.literal("数量格式不对：" + count));
            return 0;
        }
        ServerAdminState.setLimit(target, value);
        reply(source, "§a【Verity 管理】已把 " + playerName + " 的实体上限设为 " + value + "。");
        reply(source, "§8注意：这是运行时设置，服务器重启后失效。");
        OllamaProxyMod.LOGGER.info("管理员设置实体上限: 玩家={} 上限={} 操作者={}",
                playerName, value, source.getTextName());
        return 1;
    }

    // ==================== 工具 ====================

    private static void reply(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal(text), false);
    }

    private static boolean parseBool(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "true", "on", "yes", "1", "开", "启用" -> true;
            case "false", "off", "no", "0", "关", "禁用" -> false;
            default -> throw new IllegalArgumentException("不是布尔值: " + value);
        };
    }

    private static int parseInt(String value) {
        return Integer.parseInt(value.trim());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String yesNo(boolean value) {
        return value ? "开" : "关";
    }

    /** 玩家名转 UUID：先找在线玩家，再查档案缓存（离线的也能查到）。 */
    private static UUID resolvePlayerId(MinecraftServer server, String name) {
        if (server == null || name == null || name.isBlank()) return null;
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) return online.getUUID();
        if (server.getProfileCache() == null) return null;
        return server.getProfileCache().get(name).map(GameProfile::getId).orElse(null);
    }

    /** UUID 转显示名：在线优先，其次档案缓存，都没有就退回 UUID 字符串。 */
    private static String displayName(MinecraftServer server, UUID id) {
        if (id == null) return "未知";
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) return online.getGameProfile().getName();
        if (server.getProfileCache() != null) {
            String name = server.getProfileCache().get(id).map(GameProfile::getName).orElse(null);
            if (name != null && !name.isBlank()) return name;
        }
        return id.toString();
    }
}
