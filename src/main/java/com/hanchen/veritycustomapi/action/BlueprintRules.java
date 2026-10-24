package com.hanchen.veritycustomapi.action;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 蓝图的纯判定与字段校验：方块分类、致命错误识别、坐标解析。
 *
 * <p>抽自 {@code AiBuildingManager}。这些方法**不碰世界、不碰网络**，
 * 只做字符串判断与 JSON 校验，所以能独立出来单独验证——
 * 建造是「会改世界」的功能，判定逻辑越容易被单独测，出事故的概率越低。
 *
 * <p>约定：校验失败一律抛 {@link IllegalArgumentException}，**消息前缀固定**
 * （如「坐标超出安全范围」），因为 {@link #isFatalBlueprintError} 靠前缀判断
 * 「这是模型给了坏数据，重试也没用」还是「偶发失败可以重试」。
 */
public final class BlueprintRules {

    private BlueprintRules() {
    }

    /** 是否光源方块：建造时优先补光，避免成品一片黑。 */
    public static boolean isLightBlock(String path) {
        return path.contains("lantern") || path.contains("glowstone") || path.contains("shroomlight")
                || path.contains("froglight") || path.contains("sea_lantern") || path.contains("redstone_lamp");
    }

    /**
     * 是否是「重试也没用」的致命错误。
     *
     * <p>这几类都是模型给的蓝图本身有问题，再调一次还是同样结果，
     * 所以要立刻放弃并回话；其余异常（世界未加载、区块未就绪）可以重试。
     */
    public static boolean isFatalBlueprintError(IllegalArgumentException error) {
        String message = error.getMessage();
        return message != null && (message.startsWith("蓝图包含禁止方块")
                || message.startsWith("坐标超出安全范围")
                || message.startsWith("单个操作范围过大")
                || message.startsWith("坐标必须为整数"));
    }

    /**
     * 是否「非满块」方块（门、台阶、栅栏等）。
     *
     * <p>这类方块不能按整格占位来算碰撞与朝向，摆放时需要额外处理。
     */
    public static boolean isNonFullPlacementBlock(String path) {
        return path.endsWith("_door") || path.endsWith("_trapdoor") || path.endsWith("_stairs")
                || path.endsWith("_slab") || path.endsWith("_pane") || path.endsWith("_fence")
                || path.endsWith("_wall") || path.endsWith("_button") || path.endsWith("_pressure_plate");
    }

    /** 两点是否在同一平面（用于判定窗户/门洞这类平面结构）。 */
    public static boolean isPlanarWindow(int[] from, int[] to) {
        return from[0] == to[0] || from[2] == to[2];
    }

    /** 取必填字符串字段：缺失、非字符串、空白、超过 128 字符都算无效。 */
    public static String requiredString(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) {
            throw new IllegalArgumentException("缺少字段 " + key);
        }
        String value = object.get(key).getAsString().trim();
        if (value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException("字段无效 " + key);
        }
        return value;
    }

    /**
     * 取必填坐标 {@code [x,y,z]}。
     *
     * <p>兼容两种写法：直接的 {@code [1,2,3]} 和嵌套一层的 {@code [[1,2,3]]}
     * （模型经常多包一层数组）。y 不允许为负——建造不会挖到基岩以下。
     */
    public static int[] requiredPosition(JsonObject object, String key, int maxSize) {
        if (!object.has(key) || !object.get(key).isJsonArray()) {
            throw new IllegalArgumentException("缺少坐标 " + key);
        }
        JsonArray array = object.getAsJsonArray(key);
        if (array.size() == 1 && array.get(0).isJsonArray()) array = array.get(0).getAsJsonArray();
        if (array.size() != 3) throw new IllegalArgumentException("坐标必须包含 x,y,z");
        int x = strictInt(array.get(0));
        int y = strictInt(array.get(1));
        int z = strictInt(array.get(2));
        if (Math.abs(x) > maxSize || y < 0 || y > maxSize || Math.abs(z) > maxSize) {
            throw new IllegalArgumentException("坐标超出安全范围");
        }
        return new int[]{x, y, z};
    }

    /**
     * 严格整数解析：{@code 3.0} 可以，{@code 3.5} 不行。
     *
     * <p>不放过小数是刻意的——坐标带小数说明模型理解错了结构，
     * 四舍五入会让整栋楼错位一格。
     */
    public static int strictInt(JsonElement element) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("坐标必须为整数");
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value) || value != Math.rint(value)
                || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("坐标必须为整数");
        }
        return (int) value;
    }
}
