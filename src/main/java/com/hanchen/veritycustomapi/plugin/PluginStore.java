package com.hanchen.veritycustomapi.plugin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.config.ModConfig;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 插件的落盘与读取。
 *
 * 一个插件一个文件（{@code <id>.json}），玩家可以直接看、直接改、直接删；
 * 变量单独存 {@code _runtime.json}，避免每次计数都重写插件本体。
 *
 * 任何一个文件坏掉都只跳过它并记日志，绝不影响模组启动。
 */
public final class PluginStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String STATE_FILE = "_runtime.json";

    private static final Map<String, PluginDefinition> LOADED = new LinkedHashMap<>();
    /** pluginId -> playerId -> 变量表。 */
    private static final Map<String, Map<String, Map<String, Long>>> VARS = new LinkedHashMap<>();
    /** pluginId -> 全服共享变量（同插件的所有玩家共用一份）。 */
    private static final Map<String, Map<String, Long>> SHARED = new LinkedHashMap<>();

    private PluginStore() {
    }

    public static Path directory() {
        return FMLPaths.CONFIGDIR.get().resolve("verity_extended").resolve("plugins");
    }

    /** 从磁盘加载全部插件；返回成功加载的数量。 */
    public static synchronized int loadAll() {
        LOADED.clear();
        VARS.clear();
        SHARED.clear();
        Path dir = directory();
        try {
            Files.createDirectories(dir);
        } catch (IOException error) {
            OllamaProxyMod.LOGGER.warn("插件目录创建失败: {}", error.toString());
            return 0;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.json")) {
            int limit = maxLoaded();
            for (Path file : stream) {
                String fileName = file.getFileName().toString();
                if (STATE_FILE.equals(fileName)) {
                    continue;
                }
                if (limit >= 0 && LOADED.size() >= limit) {
                    OllamaProxyMod.LOGGER.warn("插件数量已达上限 {}，{} 未加载（可在配置里调大 plugin.maxLoaded）",
                            limit, fileName);
                    continue;
                }
                try {
                    PluginParser.Result result = PluginParser.parse(Files.readString(file, StandardCharsets.UTF_8));
                    if (!result.ok()) {
                        OllamaProxyMod.LOGGER.warn("插件 {} 解析失败，已跳过：{}", fileName, result.errorText());
                        continue;
                    }
                    LOADED.put(result.plugin().id, result.plugin());
                } catch (Throwable error) {
                    OllamaProxyMod.LOGGER.warn("插件 {} 读取失败，已跳过: {}", fileName, error.toString());
                }
            }
        } catch (IOException error) {
            OllamaProxyMod.LOGGER.warn("插件目录扫描失败: {}", error.toString());
        }
        loadVars();
        return LOADED.size();
    }

    /** 上限读配置；配置不可用（例如纯 JVM 自检环境）时不设限。 */
    private static int maxLoaded() {
        try {
            return ModConfig.PLUGIN_MAX_LOADED.get();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** 覆盖保存一个插件（新插件 = 直接写文件）。 */
    public static synchronized boolean save(PluginDefinition plugin) {
        if (plugin == null) {
            return false;
        }
        LOADED.put(plugin.id, plugin);
        Path file = directory().resolve(plugin.id + ".json");
        try {
            Files.createDirectories(directory());
            Files.writeString(file, GSON.toJson(toJson(plugin)), StandardCharsets.UTF_8);
            return true;
        } catch (IOException error) {
            OllamaProxyMod.LOGGER.warn("插件 {} 保存失败: {}", plugin.id, error.toString());
            return false;
        }
    }

    public static synchronized boolean delete(String id) {
        if (id == null) {
            return false;
        }
        LOADED.remove(id);
        VARS.remove(id);
        SHARED.remove(id);
        saveVars();
        try {
            return Files.deleteIfExists(directory().resolve(id + ".json"));
        } catch (IOException error) {
            OllamaProxyMod.LOGGER.warn("插件 {} 删除失败: {}", id, error.toString());
            return false;
        }
    }

    public static synchronized List<PluginDefinition> all() {
        return new ArrayList<>(LOADED.values());
    }

    public static synchronized PluginDefinition get(String id) {
        return id == null ? null : LOADED.get(id);
    }

    public static synchronized boolean setEnabled(String id, boolean enabled) {
        PluginDefinition plugin = LOADED.get(id);
        if (plugin == null) {
            return false;
        }
        plugin.enabled = enabled;
        return save(plugin);
    }

    /** 取某玩家在某插件下的变量表（不存在时创建空表）。 */
    public static synchronized Map<String, Long> varsFor(String pluginId, UUID playerId) {
        return VARS.computeIfAbsent(pluginId, key -> new LinkedHashMap<>())
                .computeIfAbsent(playerId.toString(), key -> new LinkedHashMap<>());
    }

    /** 取某插件的全服共享变量表（不存在时创建空表）。 */
    public static synchronized Map<String, Long> sharedVars(String pluginId) {
        return SHARED.computeIfAbsent(pluginId, key -> new LinkedHashMap<>());
    }

    /** 变量有变化时落盘；由引擎在动作执行后按需调用。 */
    public static synchronized void saveVars() {
        JsonObject root = new JsonObject();
        JsonObject vars = new JsonObject();
        for (Map.Entry<String, Map<String, Map<String, Long>>> pluginEntry : VARS.entrySet()) {
            JsonObject players = new JsonObject();
            for (Map.Entry<String, Map<String, Long>> playerEntry : pluginEntry.getValue().entrySet()) {
                JsonObject values = new JsonObject();
                for (Map.Entry<String, Long> var : playerEntry.getValue().entrySet()) {
                    values.addProperty(var.getKey(), var.getValue());
                }
                players.add(playerEntry.getKey(), values);
            }
            vars.add(pluginEntry.getKey(), players);
        }
        root.add("vars", vars);
        JsonObject shared = new JsonObject();
        for (Map.Entry<String, Map<String, Long>> entry : SHARED.entrySet()) {
            JsonObject values = new JsonObject();
            for (Map.Entry<String, Long> var : entry.getValue().entrySet()) {
                values.addProperty(var.getKey(), var.getValue());
            }
            shared.add(entry.getKey(), values);
        }
        root.add("shared", shared);
        try {
            Files.createDirectories(directory());
            Files.writeString(directory().resolve(STATE_FILE), GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException error) {
            OllamaProxyMod.LOGGER.warn("插件变量保存失败: {}", error.toString());
        }
    }

    private static void loadVars() {
        Path file = directory().resolve(STATE_FILE);
        if (!Files.exists(file)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            if (!root.has("vars") || !root.get("vars").isJsonObject()) {
                return;
            }
            for (Map.Entry<String, JsonElement> pluginEntry : root.getAsJsonObject("vars").entrySet()) {
                if (!pluginEntry.getValue().isJsonObject()) {
                    continue;
                }
                Map<String, Map<String, Long>> players = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> playerEntry : pluginEntry.getValue().getAsJsonObject().entrySet()) {
                    if (!playerEntry.getValue().isJsonObject()) {
                        continue;
                    }
                    Map<String, Long> values = new LinkedHashMap<>();
                    for (Map.Entry<String, JsonElement> var : playerEntry.getValue().getAsJsonObject().entrySet()) {
                        try {
                            values.put(var.getKey(), var.getValue().getAsLong());
                        } catch (RuntimeException ignored) {
                        }
                    }
                    players.put(playerEntry.getKey(), values);
                }
                VARS.put(pluginEntry.getKey(), players);
            }
            if (root.has("shared") && root.get("shared").isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("shared").entrySet()) {
                    if (!entry.getValue().isJsonObject()) {
                        continue;
                    }
                    Map<String, Long> values = new LinkedHashMap<>();
                    for (Map.Entry<String, JsonElement> var : entry.getValue().getAsJsonObject().entrySet()) {
                        try {
                            values.put(var.getKey(), var.getValue().getAsLong());
                        } catch (RuntimeException ignored) {
                        }
                    }
                    SHARED.put(entry.getKey(), values);
                }
            }
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.warn("插件变量读取失败，按空处理: {}", error.toString());
        }
    }

    /** 插件定义 -> JSON（与解析器接受的格式一致，可往返）。 */
    public static JsonObject toJson(PluginDefinition plugin) {
        JsonObject root = new JsonObject();
        root.addProperty("id", plugin.id);
        root.addProperty("name", plugin.name);
        if (plugin.description != null && !plugin.description.isBlank()) {
            root.addProperty("description", plugin.description);
        }
        root.addProperty("enabled", plugin.enabled);
        if (plugin.owner != null && !plugin.owner.isBlank()) {
            root.addProperty("owner", plugin.owner);
        }
        JsonObject trigger = new JsonObject();
        trigger.addProperty("type", plugin.trigger.type);
        addParams(trigger, plugin.trigger.params);
        root.add("trigger", trigger);
        com.google.gson.JsonArray conditions = new com.google.gson.JsonArray();
        for (PluginDefinition.Condition condition : plugin.conditions) {
            JsonObject object = new JsonObject();
            object.addProperty("type", condition.type);
            addParams(object, condition.params);
            conditions.add(object);
        }
        root.add("conditions", conditions);
        com.google.gson.JsonArray actions = new com.google.gson.JsonArray();
        for (PluginDefinition.Action action : plugin.actions) {
            JsonObject object = new JsonObject();
            object.addProperty("type", action.type);
            addParams(object, action.params);
            actions.add(object);
        }
        root.add("actions", actions);
        return root;
    }

    private static void addParams(JsonObject target, Map<String, Object> params) {
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (entry.getValue() instanceof Number number) {
                target.addProperty(entry.getKey(), number);
            } else if (entry.getValue() instanceof String text) {
                target.addProperty(entry.getKey(), text);
            }
        }
    }
}
