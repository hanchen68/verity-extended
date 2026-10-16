package com.hanchen.veritycustomapi.plugin;

import java.util.List;
import java.util.Map;

/**
 * 一个插件的完整定义：触发器 + 条件 + 动作。
 *
 * 刻意做成纯数据，不引用任何游戏类：这样解析与校验能在没有游戏进程的情况下单测，
 * 也保证插件本身不可能包含可执行代码——它只能表达「什么时候、满足什么、做什么」。
 */
public final class PluginDefinition {

    /** 标识符，同时是文件名；只允许小写字母、数字、下划线。 */
    public final String id;
    public final String name;
    public final String description;
    /** 归属玩家的 UUID 字符串；为空表示服务器级插件（由管理员放置，对所有玩家生效）。 */
    public final String owner;
    public boolean enabled;
    public final Trigger trigger;
    public final List<Condition> conditions;
    public final List<Action> actions;

    public PluginDefinition(String id, String name, String description, boolean enabled,
                            Trigger trigger, List<Condition> conditions, List<Action> actions) {
        this(id, name, description, null, enabled, trigger, conditions, actions);
    }

    public PluginDefinition(String id, String name, String description, String owner, boolean enabled,
                            Trigger trigger, List<Condition> conditions, List<Action> actions) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.owner = owner;
        this.enabled = enabled;
        this.trigger = trigger;
        this.conditions = conditions == null ? List.of() : List.copyOf(conditions);
        this.actions = actions == null ? List.of() : List.copyOf(actions);
    }

    /** 这个插件是否属于该玩家；服务器级插件（owner 为空）对所有人成立。 */
    public boolean belongsTo(java.util.UUID playerId) {
        if (owner == null || owner.isBlank()) {
            return true;
        }
        return playerId != null && owner.equalsIgnoreCase(playerId.toString());
    }

    /** 触发时机。 */
    public static final class Trigger {
        public final String type;
        public final Map<String, Object> params;

        public Trigger(String type, Map<String, Object> params) {
            this.type = type;
            this.params = params == null ? Map.of() : Map.copyOf(params);
        }
    }

    /** 触发后要满足的条件；全部满足才会执行动作。 */
    public static final class Condition {
        public final String type;
        public final Map<String, Object> params;

        public Condition(String type, Map<String, Object> params) {
            this.type = type;
            this.params = params == null ? Map.of() : Map.copyOf(params);
        }
    }

    /** 条件满足后执行的一个动作。 */
    public static final class Action {
        public final String type;
        public final Map<String, Object> params;

        public Action(String type, Map<String, Object> params) {
            this.type = type;
            this.params = params == null ? Map.of() : Map.copyOf(params);
        }
    }
}
