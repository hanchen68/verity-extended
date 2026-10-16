package com.hanchen.veritycustomapi.plugin;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次插件求值/执行过程中的上下文。
 *
 * 作用域刻意收窄到「触发它的那名玩家」：插件拿不到别人的实体、也拿不到全局世界写权限，
 * 这是多人服务器上允许玩家自制插件的前提。
 */
public final class PluginContext {

    public final MinecraftServer server;
    public final ServerPlayer player;
    public final PluginDefinition plugin;
    /** 该玩家在该插件下的变量（会随插件一起持久化），用于「挖了多少块」这类计数。 */
    public final Map<String, Long> vars;
    /** 上一次真正执行动作的时间（毫秒），供 cooldown_ready 判断。 */
    public long lastFiredAt;
    /** 触发本次求值的事件文本（聊天触发时是玩家说的话，其余为空）。 */
    public final String eventText;

    public PluginContext(MinecraftServer server, ServerPlayer player, PluginDefinition plugin,
                         Map<String, Long> vars, long lastFiredAt, String eventText) {
        this.server = server;
        this.player = player;
        this.plugin = plugin;
        this.vars = vars == null ? new LinkedHashMap<>() : vars;
        this.lastFiredAt = lastFiredAt;
        this.eventText = eventText == null ? "" : eventText;
    }

    public long var(String name) {
        Long value = vars.get(name);
        return value == null ? 0L : value;
    }

    /**
     * 读变量。
     *
     * @param shared true 表示全服共享变量（同插件的所有玩家看到同一份），false 表示只属于自己
     */
    public long var(String name, boolean shared) {
        if (!shared) {
            return var(name);
        }
        Long value = PluginStore.sharedVars(plugin.id).get(name);
        return value == null ? 0L : value;
    }

    public void setVar(String name, long value) {
        vars.put(name, value);
    }

    public void setVar(String name, long value, boolean shared) {
        if (shared) {
            PluginStore.sharedVars(plugin.id).put(name, value);
            return;
        }
        vars.put(name, value);
    }

    public void addVar(String name, long delta) {
        vars.put(name, var(name) + delta);
    }

    public void addVar(String name, long delta, boolean shared) {
        if (shared) {
            Map<String, Long> target = PluginStore.sharedVars(plugin.id);
            target.put(name, (target.getOrDefault(name, 0L)) + delta);
            return;
        }
        vars.put(name, var(name) + delta);
    }

    public int intParam(PluginDefinition.Action action, String key, int fallback) {
        return intParam(action.params, key, fallback);
    }

    public int intParam(PluginDefinition.Condition condition, String key, int fallback) {
        return intParam(condition.params, key, fallback);
    }

    private static int intParam(Map<String, Object> params, String key, int fallback) {
        Object value = params.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    public String textParam(PluginDefinition.Action action, String key) {
        Object value = action.params.get(key);
        return value instanceof String text ? text : "";
    }

    public String textParam(PluginDefinition.Condition condition, String key) {
        Object value = condition.params.get(key);
        return value instanceof String text ? text : "";
    }
}
