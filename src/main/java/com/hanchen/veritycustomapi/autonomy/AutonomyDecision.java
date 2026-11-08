package com.hanchen.veritycustomapi.autonomy;

import com.google.gson.*;
import java.util.Set;

/** Strict structured plans; arbitrary commands and coordinates are not executable. */
public record AutonomyDecision(String action, String candidate, String reason, String tool, JsonObject args,
                               String goal, String completion, JsonArray steps, String speech, String goalState,
                               String useSkill, String skillName, String primitive) {
    private static final Set<String> ACTIONS = Set.of("KEEP", "COMPANION", "HOLD", "SCOUT", "REST", "TOOL", "PRIMITIVE");
    /** 身体原语：模型组合这些，而不是只能挑语义工具。 */
    public static final Set<String> PRIMITIVES = Set.of("goto", "look_at", "break_block", "place_block", "pickup", "craft", "fell_tree", "build", "give", "attack", "shelter", "equip", "smelt", "blueprint", "eat");
    private static final Set<String> TOOLS = Set.of("scan_area", "find_block", "inspect_container",
            "check_inventory", "read_sign", "harvest_area", "plant_crop", "tidy_containers");
    public AutonomyDecision {
        args = args.deepCopy(); steps = steps.deepCopy();
    }
    @Override public JsonArray steps() { return steps.deepCopy(); }
    @Override public JsonObject args() { return args.deepCopy(); }
    /** 最近一次解析是否发生过字段纠正（供日志用，避免静默吞掉模型错误）。 */
    private static volatile String lastCorrection = "";

    public static String lastCorrection() {
        return lastCorrection;
    }

    public static AutonomyDecision parse(String text) {
        if (text == null || text.length() > 16000) throw new IllegalArgumentException("decision size");
        String raw = text.strip();
        if (raw.startsWith("```")) {
            int line = raw.indexOf('\n');
            if (line < 0 || !raw.endsWith("```")) throw new IllegalArgumentException("invalid fence");
            raw = raw.substring(line + 1, raw.length() - 3).strip();
        }
        JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
        for (String key : root.keySet()) if (!Set.of("action","candidate","reason","tool","args",
                "goal","completion","steps","speech","goalState","useSkill","skillName","primitive").contains(key))
            throw new IllegalArgumentException("unknown field");
        String action = root.has("action") ? string(root,"action",16) : "KEEP";
        if (!ACTIONS.contains(action)) throw new IllegalArgumentException("unknown action");
        String candidate = string(root,"candidate",16);
        if (action.equals("SCOUT") && !candidate.matches("p[0-9]{1,2}")) throw new IllegalArgumentException("candidate required");
        String primitive = string(root,"primitive",24);
        String tool = string(root,"tool",32);
        JsonObject args = root.has("args") && !root.get("args").isJsonNull()
                ? root.getAsJsonObject("args").deepCopy() : new JsonObject();

        if (action.equals("PRIMITIVE") && primitive.isBlank()) {
            String mapped = alias(tool);
            if (mapped != null) { primitive = mapped; tool = ""; }
        }
        // TOOL 却没写名字：这是实测最常见的失败（"unknown tool:" 后面是空的）。
        // 有坐标就当作挖方块，没有就退化成 KEEP——绝不因为一个空字段浪费整次请求。
        if (action.equals("TOOL") && tool.isBlank()) {
            if (args.has("x") && args.has("y") && args.has("z")) {
                primitive = "break_block";
                action = "PRIMITIVE";
                lastCorrection = "TOOL 缺名字，按坐标当作 break_block";
            } else {
                action = "KEEP";
                lastCorrection = "TOOL 缺名字且无坐标，降级为 KEEP";
            }
        }
        if (action.equals("PRIMITIVE") && !PRIMITIVES.contains(primitive))
            throw new IllegalArgumentException("unknown primitive: " + clip(primitive));
        if (action.equals("TOOL") && !TOOLS.contains(tool)) {
            // 模型常把原语写成 tool（"mine"/"break_block"），纠正比拒绝有用
            String mapped = alias(tool);
            if (mapped == null) throw new IllegalArgumentException("unknown tool: " + clip(tool));
            primitive = mapped;
            action = "PRIMITIVE";
            tool = "";
        }
        if (args.toString().length()>2048) throw new IllegalArgumentException("args size");
        JsonArray steps = root.has("steps") ? root.getAsJsonArray("steps").deepCopy() : new JsonArray();
        if (steps.size()>6) throw new IllegalArgumentException("too many steps");
        for (JsonElement entry: steps) step(entry.getAsJsonObject());
        String state = root.has("goalState") ? string(root,"goalState",16) : "ACTIVE";
        if (!Set.of("ACTIVE","REPLAN","DONE","CANCEL").contains(state)) throw new IllegalArgumentException("goal state");
        String useSkill = string(root,"useSkill",32);
        if (!useSkill.isBlank() && !useSkill.matches("[a-z][a-z0-9_]{2,31}")) throw new IllegalArgumentException("skill name");
        String skillName = string(root,"skillName",32);
        if (!skillName.isBlank() && !skillName.matches("[a-z][a-z0-9_]{2,31}")) throw new IllegalArgumentException("skill name");
        return new AutonomyDecision(action,candidate,string(root,"reason",240),tool,args,string(root,"goal",240),
                string(root,"completion",300),steps,string(root,"speech",180),state,useSkill,skillName,primitive);
    }
    public static AutonomyDecision step(JsonObject object) {
        for(String key:object.keySet()) if(!Set.of("action","candidate","reason","tool","args","primitive").contains(key))
            throw new IllegalArgumentException("step field");
        if (!object.has("action") || "KEEP".equals(string(object,"action",16))) throw new IllegalArgumentException("empty step");
        return parse(object.toString());
    }
    /** 常见写法映射到我们的原语；不认识的返回 null。 */
    private static String alias(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String key = raw.toLowerCase(java.util.Locale.ROOT).replace('-', '_');
        return switch (key) {
            case "mine", "dig", "break", "break_block", "mine_block" -> "break_block";
            case "place", "place_block", "build_block" -> "place_block";
            case "pickup", "pick_up", "collect", "grab", "take" -> "pickup";
            case "goto", "move", "walk", "navigate", "move_to" -> "goto";
            case "look", "look_at", "face" -> "look_at";
            case "craft", "crafting", "make" -> "craft";
            case "attack", "hit", "fight" -> "attack";
            case "shelter", "house", "build_shelter" -> "shelter";
            case "fell_tree", "chop", "chop_tree", "lumber" -> "fell_tree";
            case "give", "hand_over", "deliver" -> "give";
            case "build", "place_blocks" -> "build";
            case "smelt", "smelting", "cook" -> "smelt";
            case "equip", "wear", "hold_item" -> "equip";
            case "blueprint", "schematic", "build_structure" -> "blueprint";
            case "eat", "food", "feed" -> "eat";
            default -> null;
        };
    }

    private static String clip(String text) {
        if (text == null) return "";
        return text.length() <= 40 ? text : text.substring(0, 40);
    }

    private static String string(JsonObject root,String key,int max) {
        if(!root.has(key) || root.get(key).isJsonNull())return "";
        JsonElement value=root.get(key);
        if(!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())throw new IllegalArgumentException("string required");
        String text=value.getAsString();
        if(text.length()>max)throw new IllegalArgumentException("field size");
        return text.replaceAll("[\\p{Cntrl}§]"," ");
    }
}
