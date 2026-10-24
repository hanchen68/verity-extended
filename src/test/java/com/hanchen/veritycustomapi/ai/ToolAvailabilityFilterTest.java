package com.hanchen.veritycustomapi.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 工具可用性过滤的自检。
 *
 * <p>这条规则对应的现场事故：{@code use_stellar_remote} 无条件出现在工具清单里，
 * 模型把「工具可用」读成「玩家手里拿着遥控器」，于是主动开口
 * 「你手里拿着星之遥控器，要我帮你打那座山吗」——而玩家既没装那个模组，
 * 手里也没有那个物品。玩家反驳之后她才去查背包，才发现是空的。
 *
 * <p>所以这里的断言不是「工具能用」，而是「不该出现时**一定不出现**」。
 */
public final class ToolAvailabilityFilterTest {

    private ToolAvailabilityFilterTest() {
    }

    public static void main(String[] args) {
        removesStellarToolWhenUnavailable();
        keepsOtherToolsWhenRemoving();
        toleratesFlatToolShape();
        toleratesMissingToolsField();
        toleratesMalformedTools();
        noChangeReturnsFalse();

        System.out.println("ToolAvailabilityFilter checks passed");
    }

    /**
     * 星之遥控器工具在「模组缺失 / 开关关闭」时必须被摘掉。
     *
     * <p>自检环境里《The Shooting Star》一定不存在，所以 {@code available()} 为 false，
     * 这个工具必须消失。**这条断言挂了就意味着误报回来了。**
     */
    private static void removesStellarToolWhenUnavailable() {
        JsonObject body = bodyWith(tool("use_stellar_remote"), tool("change_expression"));
        boolean changed = ToolAvailabilityFilter.applyInPlace(body);
        require(changed, "模组缺席时应判定为需要改动");
        JsonArray tools = body.getAsJsonArray("tools");
        require(!hasTool(tools, "use_stellar_remote"),
                "《The Shooting Star》不在时必须摘掉 use_stellar_remote");
        require(hasTool(tools, "change_expression"), "其它工具不能被顺手删掉");
    }

    /** 摘除只针对目标工具，其余工具顺序与内容都不能变。 */
    private static void keepsOtherToolsWhenRemoving() {
        JsonObject body = bodyWith(tool("a_memory"), tool("use_stellar_remote"), tool("z_follow"));
        ToolAvailabilityFilter.applyInPlace(body);
        JsonArray tools = body.getAsJsonArray("tools");
        require(tools.size() == 2, "应只剩 2 个工具，实际=" + tools.size());
        require("a_memory".equals(nameOf(tools.get(0))), "顺序被打乱");
        require("z_follow".equals(nameOf(tools.get(1))), "顺序被打乱");
    }

    /** 部分上游只吃扁平写法 `{"name":...}`，也要能识别。 */
    private static void toleratesFlatToolShape() {
        JsonObject flat = new JsonObject();
        flat.addProperty("name", "use_stellar_remote");
        JsonObject body = new JsonObject();
        JsonArray tools = new JsonArray();
        tools.add(flat);
        body.add("tools", tools);
        ToolAvailabilityFilter.applyInPlace(body);
        require(body.getAsJsonArray("tools").isEmpty(), "扁平写法的工具也要能被摘掉");
    }

    /** 没有 tools 字段时不能抛，也不该误报改动。 */
    private static void toleratesMissingToolsField() {
        JsonObject body = new JsonObject();
        body.addProperty("model", "test");
        require(!ToolAvailabilityFilter.applyInPlace(body), "没有 tools 时不应报告改动");
        require(!ToolAvailabilityFilter.applyInPlace(null), "null 请求体不应崩");
    }

    /** tools 不是数组（上游写错类型）时保持原样。 */
    private static void toleratesMalformedTools() {
        JsonObject body = new JsonObject();
        body.addProperty("tools", "not-an-array");
        require(!ToolAvailabilityFilter.applyInPlace(body), "非数组 tools 不应报告改动");
        require("not-an-array".equals(body.get("tools").getAsString()), "非数组 tools 应保持原样");
    }

    /** 没有需要摘的工具时必须返回 false，否则每轮都会白白重新序列化一次。 */
    private static void noChangeReturnsFalse() {
        JsonObject body = bodyWith(tool("change_expression"), tool("get_from_memory"));
        require(!ToolAvailabilityFilter.applyInPlace(body), "无可摘工具时应返回 false");
        require(body.getAsJsonArray("tools").size() == 2, "无可摘工具时不应删任何东西");
    }

    private static JsonObject bodyWith(JsonObject... tools) {
        JsonObject body = new JsonObject();
        JsonArray array = new JsonArray();
        for (JsonObject tool : tools) array.add(tool);
        body.add("tools", array);
        return body;
    }

    private static JsonObject tool(String name) {
        JsonObject function = new JsonObject();
        function.addProperty("name", name);
        function.addProperty("description", "test tool");
        JsonObject tool = new JsonObject();
        tool.addProperty("type", "function");
        tool.add("function", function);
        return tool;
    }

    private static boolean hasTool(JsonArray tools, String name) {
        for (int i = 0; i < tools.size(); i++) {
            if (name.equals(nameOf(tools.get(i)))) return true;
        }
        return false;
    }

    private static String nameOf(com.google.gson.JsonElement element) {
        if (element == null || !element.isJsonObject()) return "";
        JsonObject tool = element.getAsJsonObject();
        if (tool.has("function") && tool.get("function").isJsonObject()) {
            return tool.getAsJsonObject("function").get("name").getAsString();
        }
        return tool.has("name") ? tool.get("name").getAsString() : "";
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
