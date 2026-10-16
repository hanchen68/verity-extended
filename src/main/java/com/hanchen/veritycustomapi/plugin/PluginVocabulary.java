package com.hanchen.veritycustomapi.plugin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 插件的能力清单：触发器 / 条件 / 动作三张表。
 *
 * 这张表是整套系统的单一事实来源：校验 AI 产出的插件时按它拒绝表里没有的写法，
 * 生成给模型的说明也直接由它拼出来，界面和文档的能力说明同样出自这里。
 *
 * 新增一个动作 = 在这里加一行 + 在 {@link PluginActions} 里实现一次，
 * 校验、提示词、报错都会自动跟上。
 */
public final class PluginVocabulary {

    /** 参数类型。只用于校验与提示，不代表任何可执行能力。 */
    public enum Kind {
        INT("整数"),
        TEXT("文本"),
        ITEM("物品ID"),
        BLOCK("方块ID"),
        ENTITY("实体ID"),
        DIMENSION("维度ID"),
        EFFECT("效果ID"),
        SOUND("音效ID"),
        PARTICLE("粒子ID");

        private final String hint;

        Kind(String hint) {
            this.hint = hint;
        }

        public String hint() {
            return hint;
        }
    }

    /**
     * 一个参数的规格。
     *
     * @param min 仅对 {@link Kind#INT} 有意义；{@code Double.NaN} 表示不限
     * @param max 同上
     */
    public record ParamSpec(String name, Kind kind, boolean required, double min, double max) {
        public static ParamSpec intRange(String name, boolean required, double min, double max) {
            return new ParamSpec(name, Kind.INT, required, min, max);
        }

        public static ParamSpec of(Kind kind, String name, boolean required) {
            return new ParamSpec(name, kind, required, Double.NaN, Double.NaN);
        }

        public static ParamSpec text(String name, boolean required) {
            return of(Kind.TEXT, name, required);
        }

        public static ParamSpec item(String name, boolean required) {
            return of(Kind.ITEM, name, required);
        }

        public String describe() {
            StringBuilder sb = new StringBuilder(name);
            sb.append(": ").append(kind.hint());
            if (kind == Kind.INT && !Double.isNaN(min) && !Double.isNaN(max)) {
                sb.append(' ').append((long) min).append('-').append((long) max);
            }
            sb.append(required ? " 必填" : " 可选");
            return sb.toString();
        }
    }

    /** 一个能力（触发器/条件/动作）的规格。 */
    public record Spec(String type, String summary, List<ParamSpec> params) {
    }

    /** 单条文本参数的长度上限，防止插件里塞进整本书。 */
    public static final int MAX_TEXT_LENGTH = 200;
    /** 一个插件最多多少条动作。 */
    public static final int MAX_ACTIONS = 20;
    /** 一个插件最多多少条条件。 */
    public static final int MAX_CONDITIONS = 10;
    /** 一个插件的 id 长度上限。 */
    public static final int MAX_ID_LENGTH = 32;

    /** 变量的作用域取值：只给自己，或全服共享。 */
    public static final String SCOPE_SELF = "self";
    public static final String SCOPE_SHARED = "shared";

    public static final Map<String, Spec> TRIGGERS = new LinkedHashMap<>();
    public static final Map<String, Spec> CONDITIONS = new LinkedHashMap<>();
    public static final Map<String, Spec> ACTIONS = new LinkedHashMap<>();

    static {
        // ---------------- 触发器 ----------------
        TRIGGERS.put("interval", new Spec("interval", "每隔一段时间检查一次", List.of(
                ParamSpec.intRange("seconds", true, 1, 3600))));
        TRIGGERS.put("hurt", new Spec("hurt", "玩家受到伤害时", List.of()));
        TRIGGERS.put("chat", new Spec("chat", "玩家发送聊天时", List.of(
                ParamSpec.text("contains", false))));
        TRIGGERS.put("join", new Spec("join", "玩家进入世界时", List.of()));
        TRIGGERS.put("block_broken", new Spec("block_broken", "玩家挖掉方块时", List.of(
                ParamSpec.of(Kind.BLOCK, "block", false))));
        TRIGGERS.put("death", new Spec("death", "玩家死亡时", List.of()));
        TRIGGERS.put("respawn", new Spec("respawn", "玩家复活时", List.of()));
        TRIGGERS.put("dimension_change", new Spec("dimension_change", "玩家切换维度时", List.of(
                ParamSpec.of(Kind.DIMENSION, "to", false))));

        // ---------------- 条件 ----------------
        CONDITIONS.put("health_below", new Spec("health_below", "血量低于指定值（20 = 满血）", List.of(
                ParamSpec.intRange("value", true, 1, 20))));
        CONDITIONS.put("health_above", new Spec("health_above", "血量高于指定值", List.of(
                ParamSpec.intRange("value", true, 0, 20))));
        CONDITIONS.put("hunger_below", new Spec("hunger_below", "饥饿值低于指定值", List.of(
                ParamSpec.intRange("value", true, 1, 20))));
        CONDITIONS.put("has_item", new Spec("has_item", "背包里有指定物品", List.of(
                ParamSpec.item("item", true),
                ParamSpec.intRange("count", false, 1, 2304))));
        CONDITIONS.put("missing_item", new Spec("missing_item", "背包里没有指定物品", List.of(
                ParamSpec.item("item", true),
                ParamSpec.intRange("count", false, 1, 2304))));
        CONDITIONS.put("inventory_full", new Spec("inventory_full", "背包已满", List.of()));
        CONDITIONS.put("dimension", new Spec("dimension", "身处指定维度", List.of(
                ParamSpec.of(Kind.DIMENSION, "id", true))));
        CONDITIONS.put("time_of_day", new Spec("time_of_day", "游戏时间在区间内（0-24000）", List.of(
                ParamSpec.intRange("from", true, 0, 24000),
                ParamSpec.intRange("to", true, 0, 24000))));
        CONDITIONS.put("cooldown_ready", new Spec("cooldown_ready", "距离上次生效已超过指定秒数", List.of(
                ParamSpec.intRange("seconds", true, 1, 86400))));
        CONDITIONS.put("random_chance", new Spec("random_chance", "按百分比概率命中", List.of(
                ParamSpec.intRange("percent", true, 1, 100))));
        CONDITIONS.put("var_at_least", new Spec("var_at_least", "变量达到指定值", List.of(
                ParamSpec.text("name", true),
                ParamSpec.intRange("value", true, -1000000, 1000000),
                ParamSpec.text("scope", false))));
        CONDITIONS.put("nearby_entity", new Spec("nearby_entity", "附近有指定实体", List.of(
                ParamSpec.of(Kind.ENTITY, "entity", true),
                ParamSpec.intRange("radius", true, 1, 64),
                ParamSpec.intRange("count", false, 1, 64))));
        CONDITIONS.put("level_at_least", new Spec("level_at_least", "经验等级达到指定值", List.of(
                ParamSpec.intRange("value", true, 0, 1000))));
        CONDITIONS.put("exp_at_least", new Spec("exp_at_least", "累计经验达到指定值", List.of(
                ParamSpec.intRange("value", true, 0, 1000000))));
        CONDITIONS.put("sneaking", new Spec("sneaking", "玩家正在潜行", List.of()));
        CONDITIONS.put("on_ground", new Spec("on_ground", "玩家站在地面上", List.of()));
        CONDITIONS.put("weather", new Spec("weather", "天气状况：clear / rain / thunder", List.of(
                ParamSpec.text("kind", true))));

        // ---------------- 动作 ----------------
        ACTIONS.put("message", new Spec("message", "给玩家发一条提示", List.of(
                ParamSpec.text("text", true))));
        ACTIONS.put("title", new Spec("title", "在屏幕中央显示标题", List.of(
                ParamSpec.text("text", true),
                ParamSpec.text("subtitle", false))));
        ACTIONS.put("broadcast", new Spec("broadcast", "在服务器公屏发一条消息（所有人可见）", List.of(
                ParamSpec.text("text", true))));
        ACTIONS.put("sound", new Spec("sound", "播放一个音效", List.of(
                ParamSpec.of(Kind.SOUND, "id", true))));
        ACTIONS.put("speak", new Spec("speak", "让 Verity 用语音念出来", List.of(
                ParamSpec.text("text", true))));
        ACTIONS.put("give_item", new Spec("give_item", "给玩家物品", List.of(
                ParamSpec.item("item", true),
                ParamSpec.intRange("count", false, 1, 64))));
        ACTIONS.put("take_item", new Spec("take_item", "从玩家背包扣除物品", List.of(
                ParamSpec.item("item", true),
                ParamSpec.intRange("count", false, 1, 64))));
        ACTIONS.put("sort_inventory", new Spec("sort_inventory", "整理玩家背包", List.of()));
        ACTIONS.put("heal", new Spec("heal", "治疗玩家（按点数，2 点 = 1 颗心）", List.of(
                ParamSpec.intRange("amount", true, 1, 20))));
        ACTIONS.put("feed", new Spec("feed", "恢复玩家饱食度", List.of(
                ParamSpec.intRange("amount", true, 1, 20))));
        ACTIONS.put("effect", new Spec("effect", "给玩家一个状态效果", List.of(
                ParamSpec.of(Kind.EFFECT, "id", true),
                ParamSpec.intRange("seconds", true, 1, 3600),
                ParamSpec.intRange("amplifier", false, 0, 9))));
        ACTIONS.put("set_block", new Spec("set_block", "在玩家附近放置方块（需要服务端开启）", List.of(
                ParamSpec.of(Kind.BLOCK, "block", true),
                ParamSpec.intRange("x", false, -8, 8),
                ParamSpec.intRange("y", false, -8, 8),
                ParamSpec.intRange("z", false, -8, 8))));
        ACTIONS.put("particle", new Spec("particle", "在玩家身上播放粒子", List.of(
                ParamSpec.of(Kind.PARTICLE, "id", true),
                ParamSpec.intRange("count", false, 1, 200))));
        ACTIONS.put("talk_to_verity", new Spec("talk_to_verity", "让 Verity 接一句话", List.of(
                ParamSpec.text("text", true))));
        ACTIONS.put("set_var", new Spec("set_var", "把变量设为指定值（scope 默认 self，填 shared 则全服共享）", List.of(
                ParamSpec.text("name", true),
                ParamSpec.intRange("value", true, -1000000, 1000000),
                ParamSpec.text("scope", false))));
        ACTIONS.put("add_var", new Spec("add_var", "把变量加上一个增量（scope 默认 self，填 shared 则全服共享）", List.of(
                ParamSpec.text("name", true),
                ParamSpec.intRange("delta", true, -1000000, 1000000),
                ParamSpec.text("scope", false))));
    }

    private PluginVocabulary() {
    }

    /** 生成给模型看的完整能力清单。模型只能在这张表里挑，因此不需要它写代码。 */
    public static String describeForPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("触发器（trigger.type 只能选一个）：\n");
        appendSpecs(sb, TRIGGERS);
        sb.append("\n条件（conditions，可 0 到 ").append(MAX_CONDITIONS).append(" 条，全部满足才执行）：\n");
        appendSpecs(sb, CONDITIONS);
        sb.append("\n动作（actions，1 到 ").append(MAX_ACTIONS).append(" 条，按顺序执行）：\n");
        appendSpecs(sb, ACTIONS);
        return sb.toString();
    }

    private static void appendSpecs(StringBuilder sb, Map<String, Spec> specs) {
        for (Spec spec : specs.values()) {
            sb.append("- ").append(spec.type()).append("（").append(spec.summary()).append('）');
            if (spec.params().isEmpty()) {
                sb.append(" 无参数");
            } else {
                sb.append(" 参数：");
                for (int i = 0; i < spec.params().size(); i++) {
                    if (i > 0) sb.append('，');
                    sb.append(spec.params().get(i).describe());
                }
            }
            sb.append('\n');
        }
    }

    /** 供界面/文档列出能力总数，方便做「漏接自检」。 */
    public static int totalCapabilities() {
        return TRIGGERS.size() + CONDITIONS.size() + ACTIONS.size();
    }

    /** 某个 scope 参数是否表示全服共享。 */
    public static boolean isSharedScope(Object scope) {
        return scope instanceof String text && SCOPE_SHARED.equalsIgnoreCase(text.trim());
    }
}
