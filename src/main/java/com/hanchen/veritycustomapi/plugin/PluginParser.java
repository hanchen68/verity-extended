package com.hanchen.veritycustomapi.plugin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 把模型产出的 JSON 解析成 {@link PluginDefinition}，并做严格校验。
 *
 * 模型一定会编出不存在的动作、拼错的字段、越界的数值，这些必须当场变成一条条可读错误：
 * 既能回喂给模型重试，也能直接告诉玩家「你想要的这个功能缺哪个动作」。
 * 宽容解析（悄悄忽略不认识的东西）会让插件静默半失效，那是最难排查的失败模式。
 */
public final class PluginParser {

    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9_]{1," + PluginVocabulary.MAX_ID_LENGTH + "}");
    private static final Pattern RESOURCE_PATTERN = Pattern.compile("[a-z0-9_.-]+(:[a-z0-9_./-]+)?");
    private static final Set<String> TOP_LEVEL_KEYS =
            Set.of("id", "name", "description", "owner", "enabled", "trigger", "conditions", "actions");

    /** 解析结果：{@code plugin} 非空且 {@code errors} 为空才算成功。 */
    public record Result(PluginDefinition plugin, List<String> errors) {
        public boolean ok() {
            return plugin != null && errors.isEmpty();
        }

        public String errorText() {
            return String.join("；", errors);
        }
    }

    private PluginParser() {
    }

    public static Result parse(String json) {
        List<String> errors = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return new Result(null, List.of("插件内容为空"));
        }
        JsonObject root;
        try {
            JsonElement element = JsonParser.parseString(stripCodeFence(json));
            if (!element.isJsonObject()) {
                return new Result(null, List.of("插件必须是一个 JSON 对象"));
            }
            root = element.getAsJsonObject();
        } catch (RuntimeException error) {
            return new Result(null, List.of("JSON 格式不合法：" + error.getMessage()));
        }

        for (String key : root.keySet()) {
            if (!TOP_LEVEL_KEYS.contains(key)) {
                errors.add("不认识的字段 " + key + "，只能用 " + TOP_LEVEL_KEYS);
            }
        }

        String name = string(root, "name", errors, true, 40);
        if (name == null) name = "未命名插件";

        String rawId = root.has("id") ? string(root, "id", errors, false, PluginVocabulary.MAX_ID_LENGTH) : null;
        String id = rawId == null || rawId.isBlank() ? slug(name) : rawId.trim().toLowerCase(Locale.ROOT);
        if (!ID_PATTERN.matcher(id).matches()) {
            errors.add("id 只能用小写字母、数字、下划线，且不超过 " + PluginVocabulary.MAX_ID_LENGTH + " 个字符");
        }

        String description = string(root, "description", errors, false, 200);
        String owner = readOwner(root, errors);
        boolean enabled = !root.has("enabled") || booleanValue(root.get("enabled"), "enabled", errors);

        PluginDefinition.Trigger trigger = null;
        if (!root.has("trigger") || !root.get("trigger").isJsonObject()) {
            errors.add("缺少 trigger（触发器）");
        } else {
            trigger = readTrigger(root.getAsJsonObject("trigger"), errors);
        }

        List<PluginDefinition.Condition> conditions = readList(root, "conditions",
                PluginVocabulary.CONDITIONS, PluginVocabulary.MAX_CONDITIONS, errors, true);
        List<PluginDefinition.Action> actions = readList(root, "actions",
                PluginVocabulary.ACTIONS, PluginVocabulary.MAX_ACTIONS, errors, false);

        if (actions.isEmpty() && !root.has("actions")) {
            errors.add("缺少 actions（至少要有一个动作）");
        }

        if (!errors.isEmpty()) {
            return new Result(null, List.copyOf(errors));
        }
        return new Result(new PluginDefinition(id, name, description, owner, enabled, trigger, conditions, actions),
                List.of());
    }

    /**
     * 归属玩家。不由模型填写：模型产出的插件在保存时才由模组盖上归属者。
     * 这里只负责读回自己写出去的文件，格式不对就整条拒绝，避免出现「谁都管不了」的插件。
     */
    private static String readOwner(JsonObject root, List<String> errors) {
        if (!root.has("owner") || root.get("owner").isJsonNull()) {
            return null;
        }
        JsonElement element = root.get("owner");
        if (!element.isJsonPrimitive()) {
            errors.add("owner 必须是文本");
            return null;
        }
        String text = element.getAsString().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            java.util.UUID.fromString(text);
        } catch (IllegalArgumentException error) {
            errors.add("owner 不是合法的玩家标识");
            return null;
        }
        return text;
    }

    private static PluginDefinition.Trigger readTrigger(JsonObject object, List<String> errors) {
        String type = string(object, "type", errors, true, 32);
        if (type == null) {
            return null;
        }
        PluginVocabulary.Spec spec = PluginVocabulary.TRIGGERS.get(type);
        if (spec == null) {
            errors.add("不认识的触发器 " + type + "，可用：" + PluginVocabulary.TRIGGERS.keySet());
            return null;
        }
        Map<String, Object> params = readParams(object, spec, "触发器 " + type, errors);
        return new PluginDefinition.Trigger(type, params);
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> readList(JsonObject root, String key, Map<String, PluginVocabulary.Spec> specs,
                                        int maxSize, List<String> errors, boolean condition) {
        List<T> result = new ArrayList<>();
        if (!root.has(key)) {
            return result;
        }
        if (!root.get(key).isJsonArray()) {
            errors.add(key + " 必须是数组");
            return result;
        }
        JsonArray array = root.getAsJsonArray(key);
        if (array.size() > maxSize) {
            errors.add(key + " 最多 " + maxSize + " 条，当前 " + array.size() + " 条");
        }
        int index = 0;
        for (JsonElement element : array) {
            String where = key + "[" + index + "]";
            index++;
            if (!element.isJsonObject()) {
                errors.add(where + " 必须是对象");
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            String type = string(object, "type", errors, true, 32);
            if (type == null) {
                continue;
            }
            PluginVocabulary.Spec spec = specs.get(type);
            if (spec == null) {
                errors.add(where + " 不认识的类型 " + type + "，可用：" + specs.keySet());
                continue;
            }
            Map<String, Object> params = readParams(object, spec, where, errors);
            if (condition) {
                result.add((T) new PluginDefinition.Condition(type, params));
            } else {
                result.add((T) new PluginDefinition.Action(type, params));
            }
        }
        return result;
    }

    private static Map<String, Object> readParams(JsonObject object, PluginVocabulary.Spec spec,
                                                  String where, List<String> errors) {
        Map<String, Object> params = new LinkedHashMap<>();
        Set<String> allowed = new java.util.LinkedHashSet<>();
        for (PluginVocabulary.ParamSpec paramSpec : spec.params()) {
            allowed.add(paramSpec.name());
            if (!object.has(paramSpec.name()) || object.get(paramSpec.name()).isJsonNull()) {
                if (paramSpec.required()) {
                    errors.add(where + " 缺少必填参数 " + paramSpec.name());
                }
                continue;
            }
            Object value = coerce(object.get(paramSpec.name()), paramSpec, where, errors);
            if (value != null) {
                params.put(paramSpec.name(), value);
            }
        }
        for (String key : object.keySet()) {
            if (!"type".equals(key) && !allowed.contains(key)) {
                errors.add(where + " 不认识的参数 " + key + "，可用：" + allowed);
            }
        }
        return params;
    }

    private static Object coerce(JsonElement element, PluginVocabulary.ParamSpec spec,
                                 String where, List<String> errors) {
        if (spec.kind() == PluginVocabulary.Kind.INT) {
            Double number = asNumber(element);
            if (number == null) {
                errors.add(where + " 的 " + spec.name() + " 必须是整数");
                return null;
            }
            long rounded = Math.round(number);
            if (!Double.isNaN(spec.min()) && (rounded < spec.min() || rounded > spec.max())) {
                errors.add(where + " 的 " + spec.name() + " 超出范围，只能是 "
                        + (long) spec.min() + " 到 " + (long) spec.max() + "，当前 " + rounded);
                return null;
            }
            return rounded;
        }
        if (!element.isJsonPrimitive()) {
            errors.add(where + " 的 " + spec.name() + " 必须是文本");
            return null;
        }
        String text = element.getAsString().trim();
        if (text.isEmpty()) {
            errors.add(where + " 的 " + spec.name() + " 不能为空");
            return null;
        }
        if (spec.kind() == PluginVocabulary.Kind.TEXT) {
            if (text.length() > PluginVocabulary.MAX_TEXT_LENGTH) {
                errors.add(where + " 的 " + spec.name() + " 太长（上限 "
                        + PluginVocabulary.MAX_TEXT_LENGTH + " 字）");
                return null;
            }
            return text;
        }
        String id = text.toLowerCase(Locale.ROOT);
        if (!RESOURCE_PATTERN.matcher(id).matches()) {
            errors.add(where + " 的 " + spec.name() + " 不是合法的资源名：" + text);
            return null;
        }
        return id.contains(":") ? id : "minecraft:" + id;
    }

    private static Double asNumber(JsonElement element) {
        if (!element.isJsonPrimitive()) {
            return null;
        }
        try {
            if (element.getAsJsonPrimitive().isNumber()) {
                return element.getAsDouble();
            }
            if (element.getAsJsonPrimitive().isString()) {
                return Double.parseDouble(element.getAsString().trim());
            }
        } catch (RuntimeException ignored) {
        }
        return null;
    }

    private static String string(JsonObject object, String key, List<String> errors,
                                 boolean required, int maxLength) {
        if (!object.has(key) || object.get(key).isJsonNull()) {
            if (required) {
                errors.add("缺少必填字段 " + key);
            }
            return null;
        }
        JsonElement element = object.get(key);
        if (!element.isJsonPrimitive()) {
            errors.add(key + " 必须是文本");
            return null;
        }
        String text = element.getAsString().trim();
        if (text.isEmpty()) {
            if (required) {
                errors.add(key + " 不能为空");
            }
            return null;
        }
        if (text.length() > maxLength) {
            errors.add(key + " 太长（上限 " + maxLength + " 字）");
            return null;
        }
        return text;
    }

    private static boolean booleanValue(JsonElement element, String key, List<String> errors) {
        if (element.isJsonPrimitive()) {
            if (element.getAsJsonPrimitive().isBoolean()) {
                return element.getAsBoolean();
            }
            String text = element.getAsString().trim().toLowerCase(Locale.ROOT);
            if ("true".equals(text) || "1".equals(text) || "yes".equals(text)) return true;
            if ("false".equals(text) || "0".equals(text) || "no".equals(text)) return false;
        }
        errors.add(key + " 必须是 true 或 false");
        return false;
    }

    /** 去掉模型习惯性包上的 ```json 围栏。 */
    static String stripCodeFence(String raw) {
        String value = raw.trim();
        if (value.startsWith("```")) {
            value = value.replaceFirst("^```(?:json)?\\s*", "");
            int end = value.lastIndexOf("```");
            if (end >= 0) {
                value = value.substring(0, end);
            }
        }
        // 模型偶尔在 JSON 前后加解释文字，截取最外层对象
        int start = value.indexOf('{');
        int last = value.lastIndexOf('}');
        if (start >= 0 && last > start) {
            value = value.substring(start, last + 1);
        }
        return value.trim();
    }

    /** 由名字生成合法 id。 */
    static String slug(String name) {
        StringBuilder sb = new StringBuilder();
        for (char c : name.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c >= 'a' && c <= 'z' || c >= '0' && c <= '9') {
                sb.append(c);
            } else if (c == '_' || c == '-' || c == ' ' || c > 127) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '_') {
                    sb.append('_');
                }
            }
            if (sb.length() >= PluginVocabulary.MAX_ID_LENGTH) {
                break;
            }
        }
        String result = sb.toString();
        while (result.endsWith("_")) {
            result = result.substring(0, result.length() - 1);
        }
        return result.isEmpty() ? "plugin" : result;
    }
}
