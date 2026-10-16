package com.hanchen.veritycustomapi.cloud;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * 云端数值兼容读取：额度字段可能是整数也可能是小数，
 * getAsInt() 遇到小数会抛异常、让整份配置解析失败，统一走这里。
 */
final class CloudNumber {
    private CloudNumber() {
    }

    /**
     * 读取非负数值；缺失、null、非数字或无法解析时返回 {@code null}。
     * 负数按 0 处理，保持与旧版 {@code Math.max(0, ...)} 一致的行为。
     */
    static Double nullable(JsonObject object, String name) {
        if (object == null || name == null || !object.has(name)) return null;
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()) return null;
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        double value;
        if (primitive.isNumber()) {
            value = primitive.getAsDouble();
        } else if (primitive.isString()) {
            try {
                value = Double.parseDouble(primitive.getAsString().trim());
            } catch (RuntimeException ignored) {
                return null;
            }
        } else {
            return null;
        }
        if (!Double.isFinite(value)) return null;
        return Math.max(0.0D, value);
    }

    /** 读取数值，缺失或非法时返回 {@code fallback}。 */
    static double value(JsonObject object, String name, double fallback) {
        Double parsed = nullable(object, name);
        return parsed != null ? parsed : fallback;
    }
}
