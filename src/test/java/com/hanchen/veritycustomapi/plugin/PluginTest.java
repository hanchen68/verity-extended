package com.hanchen.veritycustomapi.plugin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 插件系统自检。
 *
 * 重点是两条。解析得严格，模型编的东西要当场拦下，别让它静默半失效；
 * 能力清单和动作实现得一一对应，清单里写了却没实现的话，玩家生成的插件跑起来什么也不做，
 * 这种失败最难排查，所以用自检钉死。
 */
public final class PluginTest {

    private PluginTest() {
    }

    public static void main(String[] args) throws Exception {
        validPluginParses();
        codeFenceIsStripped();
        unknownActionIsRejected();
        unknownFieldIsRejected();
        outOfRangeIsRejected();
        missingRequiredParamIsRejected();
        tooManyActionsRejected();
        idIsGeneratedFromName();
        itemIdGetsDefaultNamespace();
        roundTripThroughJson();
        ownerIsolation();
        timeRangeWrapsMidnight();
        everyActionHasImplementation();
        noParamNamedType();
        expandedCapabilitiesParse();
        screensGuardAgainstNoConnection();
        payloadRoundTrip();
        System.out.println("Plugin checks passed");
    }

    /**
     * 插件界面在没有连接服务器时不能发包。
     *
     * 设置界面在主菜单也能打开，那时 {@code Minecraft.getConnection()} 是 null，
     * 无条件发会直接空指针把客户端弄崩——实际崩过一次，所以从源码层面钉住这个守卫。
     */
    private static void screensGuardAgainstNoConnection() throws Exception {
        Path clientDir = Path.of("src", "main", "java", "com", "hanchen", "veritycustomapi",
                "client");
        for (String name : List.of("PluginManagerScreen.java", "PluginCreateScreen.java")) {
            Path file = clientDir.resolve(name);
            require(Files.exists(file), "找不到 " + name);
            String text = Files.readString(file, StandardCharsets.UTF_8);
            require(text.contains("getConnection()"),
                    name + " 缺少「未连接服务器」的守卫，主菜单下打开会崩客户端");
        }
        Path network = Path.of("src", "main", "java", "com", "hanchen", "veritycustomapi",
                "network", "PluginNetwork.java");
        String text = Files.readString(network, StandardCharsets.UTF_8);
        require(text.contains("sendToServer"), "PluginNetwork 缺少统一的发送出口");
        require(text.contains("catch (Throwable"), "PluginNetwork 的发包没有兜异常，未连接时会崩客户端");
    }

    /**
     * 参数名不能与「类型字段」重名。
     *
     * 解析器用 {@code type} 区分是哪个触发器/条件/动作。参数一旦也叫 {@code type}，
     * 就会被当成类型本身读走——插件永远匹配不上，而且不会报错，是最难发现的一类错误。
     */
    private static void noParamNamedType() {
        List<String> offenders = new ArrayList<>();
        for (PluginVocabulary.Spec spec : PluginVocabulary.TRIGGERS.values()) {
            collectTypeParam(spec, offenders);
        }
        for (PluginVocabulary.Spec spec : PluginVocabulary.CONDITIONS.values()) {
            collectTypeParam(spec, offenders);
        }
        for (PluginVocabulary.Spec spec : PluginVocabulary.ACTIONS.values()) {
            collectTypeParam(spec, offenders);
        }
        require(offenders.isEmpty(), "这些能力有名叫 type 的参数，会与类型字段冲突：" + offenders);
    }

    private static void collectTypeParam(PluginVocabulary.Spec spec, List<String> offenders) {
        for (PluginVocabulary.ParamSpec param : spec.params()) {
            if ("type".equals(param.name())) {
                offenders.add(spec.type());
            }
        }
    }

    /** 新增的能力（方块破坏、附近实体、天气、广播、治疗、共享变量）要能正常解析。 */
    private static void expandedCapabilitiesParse() {
        PluginParser.Result result = PluginParser.parse("""
                { "id": "diamond_counter", "name": "钻石计数",
                  "trigger": { "type": "block_broken", "block": "minecraft:diamond_ore" },
                  "conditions": [
                    { "type": "nearby_entity", "entity": "minecraft:zombie", "radius": 8 },
                    { "type": "weather", "kind": "clear" },
                    { "type": "level_at_least", "value": 10 }
                  ],
                  "actions": [
                    { "type": "add_var", "name": "diamonds", "delta": 1, "scope": "shared" },
                    { "type": "broadcast", "text": "挖到钻石了" },
                    { "type": "heal", "amount": 2 },
                    { "type": "effect", "id": "minecraft:night_vision", "seconds": 30 }
                  ] }
                """);
        require(result.ok(), "新增能力应当能正常解析：" + result.errorText());
        Object entity = result.plugin().conditions.get(0).params.get("entity");
        require("minecraft:zombie".equals(entity), "nearby_entity 的 entity 参数解析错误：" + entity);
        Object block = result.plugin().trigger.params.get("block");
        require("minecraft:diamond_ore".equals(block), "block_broken 的 block 参数解析错误：" + block);
        Object scope = result.plugin().actions.get(0).params.get("scope");
        require(PluginVocabulary.isSharedScope(scope), "scope=shared 应当被识别为全服共享");
        require(!PluginVocabulary.isSharedScope("self"), "scope=self 不应当被识别为共享");
    }

    // 解析

    private static void validPluginParses() {
        PluginParser.Result result = PluginParser.parse("""
                {
                  "id": "low_health_potion",
                  "name": "低血量提醒",
                  "description": "血量低于3颗心时提醒",
                  "enabled": true,
                  "trigger": { "type": "interval", "seconds": 5 },
                  "conditions": [
                    { "type": "health_below", "value": 6 },
                    { "type": "cooldown_ready", "seconds": 30 }
                  ],
                  "actions": [
                    { "type": "message", "text": "你血量太低了" },
                    { "type": "give_item", "item": "minecraft:potion", "count": 1 }
                  ]
                }
                """);
        require(result.ok(), "合法插件应当解析成功：" + result.errorText());
        PluginDefinition plugin = result.plugin();
        require("low_health_potion".equals(plugin.id), "id 解析错误");
        require("interval".equals(plugin.trigger.type), "触发器解析错误");
        require(plugin.conditions.size() == 2, "条件数量错误");
        require(plugin.actions.size() == 2, "动作数量错误");
        require(plugin.enabled, "enabled 默认应为 true");
    }

    private static void codeFenceIsStripped() {
        PluginParser.Result result = PluginParser.parse("""
                这是插件：
                ```json
                { "id": "demo", "name": "演示", "trigger": { "type": "hurt" },
                  "actions": [ { "type": "message", "text": "疼" } ] }
                ```
                希望有帮助！
                """);
        require(result.ok(), "带围栏与前后说明文字时也应当能解析：" + result.errorText());
        require("demo".equals(result.plugin().id), "围栏剥离后 id 解析错误");
    }

    private static void unknownActionIsRejected() {
        PluginResult result = parse("""
                { "id": "bad", "name": "坏插件", "trigger": { "type": "hurt" },
                  "actions": [ { "type": "explode_world", "power": 9 } ] }
                """);
        require(!result.ok(), "不存在的动作必须被拒绝，否则插件会静默失效");
        require(result.contains("explode_world"), "错误信息里应当指出不认识的动作名");
    }

    private static void unknownFieldIsRejected() {
        PluginResult result = parse("""
                { "id": "bad2", "name": "坏插件", "schedule": "daily",
                  "trigger": { "type": "hurt" },
                  "actions": [ { "type": "message", "text": "hi" } ] }
                """);
        require(!result.ok(), "顶层未知字段必须被拒绝");
        require(result.contains("schedule"), "错误信息里应当指出未知字段");
    }

    private static void outOfRangeIsRejected() {
        PluginResult result = parse("""
                { "id": "bad3", "name": "坏插件", "trigger": { "type": "hurt" },
                  "conditions": [ { "type": "health_below", "value": 999 } ],
                  "actions": [ { "type": "message", "text": "hi" } ] }
                """);
        require(!result.ok(), "越界数值必须被拒绝");
        require(result.contains("value"), "错误信息里应当指出是哪个参数越界");
    }

    private static void missingRequiredParamIsRejected() {
        PluginResult result = parse("""
                { "id": "bad4", "name": "坏插件", "trigger": { "type": "hurt" },
                  "actions": [ { "type": "give_item", "count": 2 } ] }
                """);
        require(!result.ok(), "缺少必填参数必须被拒绝");
        require(result.contains("item"), "错误信息里应当指出缺哪个参数");
    }

    private static void tooManyActionsRejected() {
        StringBuilder actions = new StringBuilder();
        for (int i = 0; i <= PluginVocabulary.MAX_ACTIONS; i++) {
            if (i > 0) actions.append(',');
            actions.append("{\"type\":\"message\",\"text\":\"x\"}");
        }
        PluginResult result = parse("{ \"id\": \"many\", \"name\": \"多动作\", \"trigger\": {\"type\":\"hurt\"},"
                + " \"actions\": [" + actions + "] }");
        require(!result.ok(), "动作数超过上限必须被拒绝");
    }

    private static void idIsGeneratedFromName() {
        PluginParser.Result result = PluginParser.parse("""
                { "name": "Auto Sort Bag", "trigger": { "type": "chat" },
                  "actions": [ { "type": "sort_inventory" } ] }
                """);
        require(result.ok(), "缺 id 时应当由名字生成：" + result.errorText());
        require(PluginParser.slug("Auto Sort Bag").equals(result.plugin().id),
                "生成的 id 应当来自名字：" + result.plugin().id);
    }

    private static void itemIdGetsDefaultNamespace() {
        PluginParser.Result result = PluginParser.parse("""
                { "id": "ns", "name": "命名空间", "trigger": { "type": "hurt" },
                  "actions": [ { "type": "give_item", "item": "diamond", "count": 1 } ] }
                """);
        require(result.ok(), "省略命名空间应当被接受：" + result.errorText());
        Object item = result.plugin().actions.get(0).params.get("item");
        require("minecraft:diamond".equals(item), "应当补上默认命名空间，实际 " + item);
    }

    private static void roundTripThroughJson() {
        PluginParser.Result first = PluginParser.parse("""
                { "id": "round", "name": "往返", "description": "测试",
                  "trigger": { "type": "interval", "seconds": 10 },
                  "conditions": [ { "type": "has_item", "item": "minecraft:apple", "count": 3 } ],
                  "actions": [ { "type": "add_var", "name": "eaten", "delta": 1 } ] }
                """);
        require(first.ok(), "往返测试的源插件应当合法：" + first.errorText());
        String json = PluginStore.toJson(first.plugin()).toString();
        PluginParser.Result second = PluginParser.parse(json);
        require(second.ok(), "写出去的插件应当能被自己读回来：" + second.errorText());
        require(first.plugin().id.equals(second.plugin().id), "往返后 id 变了");
        require(first.plugin().trigger.type.equals(second.plugin().trigger.type), "往返后触发器变了");
        require(first.plugin().conditions.size() == second.plugin().conditions.size(), "往返后条件数量变了");
        require(first.plugin().actions.size() == second.plugin().actions.size(), "往返后动作数量变了");
    }

    private static void ownerIsolation() {
        java.util.UUID mine = java.util.UUID.randomUUID();
        java.util.UUID other = java.util.UUID.randomUUID();
        PluginDefinition owned = new PluginDefinition("x", "x", null, mine.toString(), true,
                new PluginDefinition.Trigger("hurt", java.util.Map.of()), List.of(), List.of());
        require(owned.belongsTo(mine), "自己的插件应当对自己生效");
        require(!owned.belongsTo(other), "别人的插件不应当对自己生效");
        PluginDefinition shared = new PluginDefinition("y", "y", null, null, true,
                new PluginDefinition.Trigger("hurt", java.util.Map.of()), List.of(), List.of());
        require(shared.belongsTo(other), "没有归属者的插件对所有人生效");
    }

    private static void timeRangeWrapsMidnight() {
        require(PluginConditions.inTimeRange(23000, 22000, 2000), "跨零点区间应当命中");
        require(!PluginConditions.inTimeRange(12000, 22000, 2000), "跨零点区间外不应当命中");
        require(PluginConditions.inTimeRange(6000, 5000, 7000), "普通区间应当命中");
        require(!PluginConditions.inTimeRange(9000, 5000, 7000), "普通区间外不应当命中");
        require(PluginConditions.inTimeRange(1234, 500, 500), "from == to 视为全天");
    }

    // 漏接自检：清单里声明的东西都得有实现

    /**
     * 能力清单里声明的每个动作都必须真的被实现。
     *
     * 清单里写了、实现里没有 = 玩家用 AI 生成的插件看起来合法、运行时却什么都不做，
     * 是最难排查的一类失败。这里直接从源码扫描，避免依赖运行期类加载。
     */
    private static void everyActionHasImplementation() throws Exception {
        Path source = Path.of("src", "main", "java", "com", "hanchen", "veritycustomapi",
                "plugin", "PluginActions.java");
        require(Files.exists(source), "找不到 PluginActions.java：" + source);
        String text = Files.readString(source, StandardCharsets.UTF_8);

        List<String> missing = new ArrayList<>();
        for (String type : PluginVocabulary.ACTIONS.keySet()) {
            if (!text.contains("register(\"" + type + "\"")) {
                missing.add(type);
            }
        }
        require(missing.isEmpty(), "这些动作在能力清单里声明了但没有实现：" + missing);

        require(!PluginVocabulary.TRIGGERS.isEmpty(), "触发器清单不能为空");
        require(!PluginVocabulary.CONDITIONS.isEmpty(), "条件清单不能为空");
        require(PluginVocabulary.totalCapabilities() > 0, "能力清单不能为空");
    }

    /**
     * 管理界面的载荷必须能原样往返。
     *
     * 列表 JSON 与生成描述都可能比较长，长度上限写错就会在运行时静默截断
     * （表现为界面里插件名缺一半），所以这里用接近上限的内容实测一遍。
     */
    private static void payloadRoundTrip() {
        String longText = "x".repeat(8000);
        io.netty.buffer.ByteBuf buffer = io.netty.buffer.Unpooled.buffer();
        com.hanchen.veritycustomapi.network.PluginPayload list =
                com.hanchen.veritycustomapi.network.PluginPayload.listResponse(longText);
        com.hanchen.veritycustomapi.network.PluginPayload.write(list,
                new net.minecraft.network.FriendlyByteBuf(buffer));
        com.hanchen.veritycustomapi.network.PluginPayload read =
                com.hanchen.veritycustomapi.network.PluginPayload.read(
                        new net.minecraft.network.FriendlyByteBuf(buffer));
        require(read.action() == com.hanchen.veritycustomapi.network.PluginPayload.ACTION_LIST_RESPONSE,
                "载荷动作在往返后变了");
        require(longText.equals(read.text()), "长文本在往返后被截断了，实际长度 " + read.text().length());

        io.netty.buffer.ByteBuf second = io.netty.buffer.Unpooled.buffer();
        com.hanchen.veritycustomapi.network.PluginPayload toggle =
                com.hanchen.veritycustomapi.network.PluginPayload.toggle("low_health_potion", true);
        com.hanchen.veritycustomapi.network.PluginPayload.write(toggle,
                new net.minecraft.network.FriendlyByteBuf(second));
        com.hanchen.veritycustomapi.network.PluginPayload readToggle =
                com.hanchen.veritycustomapi.network.PluginPayload.read(
                        new net.minecraft.network.FriendlyByteBuf(second));
        require("low_health_potion".equals(readToggle.id()), "载荷 id 在往返后变了");
        require(readToggle.flag(), "载荷开关状态在往返后丢了");
    }

    // 工具

    private record PluginResult(boolean ok, String errors) {
        boolean contains(String needle) {
            return errors != null && errors.contains(needle);
        }
    }

    private static PluginResult parse(String json) {
        PluginParser.Result result = PluginParser.parse(json);
        return new PluginResult(result.ok(), result.errorText());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
