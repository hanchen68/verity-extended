package com.hanchen.veritycustomapi.plugin;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.config.ModConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 插件规则引擎：把游戏事件喂给插件，求值条件，执行动作。
 *
 * 三件事必须守住。每 tick 有动作预算，超了就直接跳过并记一次日志，插件再多也不会把主线程占满。
 * 单个插件、单个动作出错只记日志不往外抛，否则玩家写错一个插件就能让整个存档崩掉。
 * 一切都以「触发它的那名玩家」为主体，插件拿不到别人。
 */
public final class PluginEngine {

    /** 每 tick 允许执行的动作数上限，防止插件把主线程吃满。 */
    private static final int MAX_ACTIONS_PER_TICK = 16;
    /** 变量落盘的最小间隔（tick），避免高频插件反复写盘。 */
    private static final int VARS_FLUSH_INTERVAL_TICKS = 200;

    /** pluginId + "|" + playerUuid -> 上次生效时间（毫秒）。 */
    private static final Map<String, Long> LAST_FIRED = new ConcurrentHashMap<>();
    private static final AtomicInteger ACTIONS_THIS_TICK = new AtomicInteger();
    private static long serverTicks;
    private static boolean varsDirty;
    private static boolean budgetWarned;

    private PluginEngine() {
    }

    /** 服务端停止时清掉运行时状态，避免跨存档残留。 */
    public static void clear() {
        LAST_FIRED.clear();
        ACTIONS_THIS_TICK.set(0);
        serverTicks = 0L;
        varsDirty = false;
        budgetWarned = false;
    }

    public static boolean enabled() {
        return Boolean.TRUE.equals(ModConfig.PLUGIN_ENABLED.get());
    }

    // ------------------------------------------------------------------ 事件入口

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        if (server == null || !enabled()) {
            return;
        }
        serverTicks++;
        ACTIONS_THIS_TICK.set(0);
        budgetWarned = false;
        if (varsDirty && serverTicks % VARS_FLUSH_INTERVAL_TICKS == 0) {
            PluginStore.saveVars();
            varsDirty = false;
        }
        for (PluginDefinition plugin : PluginStore.all()) {
            if (!plugin.enabled || !"interval".equals(plugin.trigger.type)) {
                continue;
            }
            Object seconds = plugin.trigger.params.get("seconds");
            long period = (seconds instanceof Number number ? Math.max(1L, number.longValue()) : 1L) * 20L;
            if (serverTicks % period != 0) {
                continue;
            }
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                evaluate(server, plugin, player, "");
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerHurt(LivingHurtEvent event) {
        if (!enabled() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        dispatch(server, player, "hurt", "", null);
    }

    @SubscribeEvent
    public static void onChat(ServerChatEvent event) {
        if (!enabled()) {
            return;
        }
        ServerPlayer player = event.getPlayer();
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        dispatch(server, player, "chat", event.getRawText(), event.getRawText());
    }

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!enabled() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        // 登录事件早于玩家完全就位，延后一拍再跑，避免读到半初始化状态
        server.execute(() -> dispatch(server, player, "join", "", null));
    }

    /** 挖掉方块。事件里的方块 id 作为筛选主体，供 block_broken 的 block 参数比对。 */
    @SubscribeEvent
    public static void onBlockBroken(net.minecraftforge.event.level.BlockEvent.BreakEvent event) {
        if (!enabled() || !(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        String blockId = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(event.getState().getBlock()).toString();
        dispatch(server, player, "block_broken", "", blockId);
    }

    @SubscribeEvent
    public static void onPlayerDeath(net.minecraftforge.event.entity.living.LivingDeathEvent event) {
        if (!enabled() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        dispatch(server, player, "death", "", null);
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (!enabled() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        // 复活瞬间玩家数据还在重建，延后一拍更稳
        server.execute(() -> dispatch(server, player, "respawn", "", null));
    }

    @SubscribeEvent
    public static void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!enabled() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        dispatch(server, player, "dimension_change", "", event.getTo().location().toString());
    }

    // ------------------------------------------------------------------ 求值与执行

    /**
     * 按触发器类型把事件分发给匹配的插件。
     *
     * @param subject 事件的主体（聊天文本 / 方块 id / 维度 id），用于匹配触发器上的筛选参数；
     * 没有筛选参数时忽略
     */
    private static void dispatch(MinecraftServer server, ServerPlayer player, String triggerType,
                                 String eventText, String subject) {
        for (PluginDefinition plugin : PluginStore.all()) {
            if (!plugin.enabled || !triggerType.equals(plugin.trigger.type)) {
                continue;
            }
            if (!matchesTriggerFilter(plugin, subject)) {
                continue;
            }
            evaluate(server, plugin, player, eventText);
        }
    }

    /** 触发器上的筛选参数：chat 用 contains（包含即可），block_broken / dimension_change 用精确匹配。 */
    private static boolean matchesTriggerFilter(PluginDefinition plugin, String subject) {
        Object filter = switch (plugin.trigger.type) {
            case "chat" -> plugin.trigger.params.get("contains");
            case "block_broken" -> plugin.trigger.params.get("block");
            case "dimension_change" -> plugin.trigger.params.get("to");
            default -> null;
        };
        if (!(filter instanceof String keyword) || keyword.isBlank()) {
            return true;
        }
        if (subject == null) {
            return false;
        }
        return "chat".equals(plugin.trigger.type) ? subject.contains(keyword) : subject.equals(keyword);
    }

    private static void evaluate(MinecraftServer server, PluginDefinition plugin,
                                 ServerPlayer player, String eventText) {
        if (player == null || !player.isAlive()) {
            return;
        }
        // 归属隔离：玩家自制的插件只对本人求值，不会去动别人
        if (!plugin.belongsTo(player.getUUID())) {
            return;
        }
        try {
            String key = plugin.id + "|" + player.getUUID();
            long lastFired = LAST_FIRED.getOrDefault(key, 0L);
            PluginContext context = new PluginContext(server, player, plugin,
                    PluginStore.varsFor(plugin.id, player.getUUID()), lastFired, eventText);
            if (!PluginConditions.allPass(plugin, context)) {
                return;
            }
            runActions(context);
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.warn("插件 {} 求值失败: {}", plugin.id, error.toString());
        }
    }

    private static void runActions(PluginContext context) {
        boolean changed = false;
        for (PluginDefinition.Action action : context.plugin.actions) {
            if (ACTIONS_THIS_TICK.get() >= MAX_ACTIONS_PER_TICK) {
                if (!budgetWarned) {
                    budgetWarned = true;
                    OllamaProxyMod.LOGGER.warn("插件动作数超过每 tick 预算 {}，本 tick 剩余动作已跳过",
                            MAX_ACTIONS_PER_TICK);
                }
                break;
            }
            ACTIONS_THIS_TICK.incrementAndGet();
            PluginActions.run(context, action);
            if ("set_var".equals(action.type) || "add_var".equals(action.type)) {
                changed = true;
            }
        }
        LAST_FIRED.put(context.plugin.id + "|" + context.player.getUUID(), System.currentTimeMillis());
        if (changed) {
            varsDirty = true;
        }
    }

    /** 供自检与诊断：当前加载的插件数与最近一次生效时间。 */
    public static long lastFiredAt(String pluginId, UUID playerId) {
        return LAST_FIRED.getOrDefault(pluginId + "|" + playerId, 0L);
    }

    /** 供自检：把一次求值限制在可控范围内。 */
    static void resetBudgetForTesting() {
        ACTIONS_THIS_TICK.set(0);
        budgetWarned = false;
    }
}
