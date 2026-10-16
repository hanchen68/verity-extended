package com.hanchen.veritycustomapi.action;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.debug.RuntimeDiagnostics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.server.ServerLifecycleHooks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

public class GameActionHandler {
    private static final class Holder {
        static final GameActionHandler INSTANCE = new GameActionHandler();
    }
    
    private final Map<String, ActionExecutor> actionExecutors = new HashMap<>();
    private final ConcurrentLinkedQueue<QueuedAction> actionQueue = new ConcurrentLinkedQueue<>();
    private boolean registered = false;

    private GameActionHandler() {
        registerActions();
    }

    public static GameActionHandler getInstance() {
        return Holder.INSTANCE;
    }

    public void registerEvents() {
        if (!registered) {
            MinecraftForge.EVENT_BUS.addListener(this::onServerTick);
            registered = true;
            OllamaProxyMod.LOGGER.info("GameActionHandler 事件已注册 (使用 addListener)");
        }
    }

    private void registerActions() {
        actionExecutors.put("none", (server, player, params) -> {});
        actionExecutors.put("answer", (server, player, params) -> {});

        actionExecutors.put("build_structure", (server, player, params) ->
                AiBuildingManager.getInstance().start(server, player, params));
        actionExecutors.put("build_blueprint", (server, player, params) ->
                AiBuildingManager.getInstance().startBlueprint(server, player, params));
        actionExecutors.put("build_plan", (server, player, params) ->
                AiBuildingManager.getInstance().startPlan(server, player, params));
        actionExecutors.put("pause_build", (server, player, params) ->
                AiBuildingManager.getInstance().pause(player));
        actionExecutors.put("resume_build", (server, player, params) ->
                AiBuildingManager.getInstance().resume(player));
        actionExecutors.put("cancel_build", (server, player, params) ->
                AiBuildingManager.getInstance().cancel(player));
        actionExecutors.put("build_status", (server, player, params) ->
                AiBuildingManager.getInstance().status(player));
        actionExecutors.put("save_blueprint", (server, player, params) -> {
            if (params.length < 1) {
                AiBuildingManager.getInstance().listBlueprints(player);
                return;
            }
            AiBuildingManager.getInstance().saveCurrentBlueprint(player, params[0]);
        });
        actionExecutors.put("load_blueprint", (server, player, params) -> {
            if (params.length < 1) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[AI 建造] 用法：save_blueprint <name>"));
                return;
            }
            AiBuildingManager.getInstance().loadBlueprint(player, params[0]);
        });
        actionExecutors.put("list_blueprint", (server, player, params) ->
                AiBuildingManager.getInstance().listBlueprints(player));
        actionExecutors.put("undo_build", (server, player, params) ->
                AiBuildingManager.getInstance().undoLastBuild(player));
    }

    public void queueAction(String actionStr) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        ServerPlayer player = getCurrentPlayer(server);
        if (player == null) return;
        queueAction(actionStr, player);
    }

    public void queueAction(String actionStr, ServerPlayer player) {
        if (!isBuildingAction(actionStr) || player == null) return;
        actionQueue.add(new QueuedAction(actionStr, player.getServer(), player.getUUID(),
                player.serverLevel().dimension().location()));
        OllamaProxyMod.LOGGER.info("【动作队列】已添加动作: {} player={} dimension={}",
                actionStr, player.getUUID(), player.serverLevel().dimension().location());
    }

    private boolean isBuildingAction(String actionStr) {
        if (actionStr == null || actionStr.isBlank()) return false;
        String normalized = actionStr.trim().toLowerCase();
        return normalized.startsWith("build_structure ") || normalized.startsWith("build_blueprint ")
                || normalized.startsWith("build_plan ")
                || normalized.equals("pause_build")
                || normalized.equals("resume_build") || normalized.equals("cancel_build")
                || normalized.equals("build_status")
                || normalized.startsWith("save_blueprint")
                || normalized.startsWith("load_blueprint")
                || normalized.equals("list_blueprint")
                || normalized.equals("undo_build");
    }

    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        
        RuntimeDiagnostics.onServerTick(event.getServer(), actionQueue.size());
        
        QueuedAction action;
        while ((action = actionQueue.poll()) != null) {
            executeAction(action, event.getServer());
        }
        AiBuildingManager.getInstance().tick(event.getServer());
    }

    public void clear() {
        actionQueue.clear();
    }

    private void executeAction(QueuedAction action, MinecraftServer server) {
        if (server == null) {
            OllamaProxyMod.LOGGER.warn("【动作执行】服务器为空");
            return;
        }
        if (server != action.server()) {
            OllamaProxyMod.LOGGER.warn("【动作执行】服务器实例已变化，丢弃动作: {} player={}",
                    action.action(), action.playerId());
            return;
        }

        ServerPlayer player = server.getPlayerList().getPlayer(action.playerId());
        if (player == null) {
            OllamaProxyMod.LOGGER.warn("【动作执行】入队玩家已离线: {}", action.playerId());
            return;
        }
        if (!player.serverLevel().dimension().location().equals(action.dimension())) {
            OllamaProxyMod.LOGGER.warn("【动作执行】玩家维度已变化，丢弃动作: {} player={} queuedDimension={} currentDimension={}",
                    action.action(), action.playerId(), action.dimension(), player.serverLevel().dimension().location());
            return;
        }

        String actionStr = action.action().trim();
        
        String actionName;
        String[] params = new String[0];
        
        int spaceIndex = actionStr.indexOf(' ');
        if (spaceIndex > 0) {
            actionName = actionStr.substring(0, spaceIndex).toLowerCase();
            String paramStr = actionStr.substring(spaceIndex + 1).trim();
            params = paramStr.split("\\s+");
        } else {
            actionName = actionStr.toLowerCase();
        }

        ActionExecutor executor = actionExecutors.get(actionName);
        if (executor != null) {
            try {
                executor.execute(server, player, params);
            } catch (Throwable e) {
                OllamaProxyMod.LOGGER.error("【动作执行】动作 {} 执行失败", actionName, e);
            }
        } else {
            OllamaProxyMod.LOGGER.warn("【动作执行】未知动作: {}", actionName);
        }
    }

    private ServerPlayer getCurrentPlayer(MinecraftServer server) {
        if (server == null || server.getPlayerList().getPlayers().isEmpty()) {
            return null;
        }
        var players = server.getPlayerList().getPlayers();
        if (players.size() > 1) {
            OllamaProxyMod.LOGGER.warn("【动作队列】多人游戏环境且缺少明确玩家，拒绝入队（共{}人）", players.size());
            return null;
        }
        return players.get(0);
    }

    @FunctionalInterface
    private interface ActionExecutor {
        void execute(MinecraftServer server, ServerPlayer player, String[] params);
    }

    private record QueuedAction(String action, MinecraftServer server, UUID playerId, ResourceLocation dimension) {
    }
}
