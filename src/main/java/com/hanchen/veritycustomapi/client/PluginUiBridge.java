package com.hanchen.veritycustomapi.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hanchen.veritycustomapi.network.PluginPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

/**
 * 把服务端推来的插件数据交给界面，顺便把提示显示给玩家。
 *
 * 客户端这边只做缓存，任何修改都回服务端执行——界面绝不自己改数据，
 * 否则界面和实际状态就对不上了。
 */
@OnlyIn(Dist.CLIENT)
public final class PluginUiBridge {

    /** 界面展示用的一条插件摘要。 */
    public record Entry(String id, String name, String description, boolean enabled,
                        String trigger, int conditions, int actions, boolean mine) {
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();

    private PluginUiBridge() {
    }

    public static void accept(byte action, String text) {
        Minecraft minecraft = Minecraft.getInstance();
        if (action == PluginPayload.ACTION_LIST_RESPONSE) {
            parse(text);
            if (minecraft.screen instanceof PluginManagerScreen screen) {
                screen.onListUpdated();
            }
            return;
        }
        if (action == PluginPayload.ACTION_OPEN_UI) {
            minecraft.setScreen(new PluginManagerScreen());
            return;
        }
        if (action == PluginPayload.ACTION_MESSAGE) {
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(Component.literal(text), false);
            }
            if (minecraft.screen instanceof PluginManagerScreen screen) {
                screen.onStatus(text);
            }
        }
    }

    public static List<Entry> entries() {
        return List.copyOf(ENTRIES);
    }

    private static void parse(String json) {
        ENTRIES.clear();
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            JsonArray array = JsonParser.parseString(json).getAsJsonArray();
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject object = element.getAsJsonObject();
                ENTRIES.add(new Entry(
                        string(object, "id"),
                        string(object, "name"),
                        string(object, "description"),
                        object.has("enabled") && object.get("enabled").getAsBoolean(),
                        string(object, "trigger"),
                        integer(object, "conditions"),
                        integer(object, "actions"),
                        !object.has("mine") || object.get("mine").getAsBoolean()));
            }
        } catch (RuntimeException ignored) {
            // 数据坏了就当空列表，界面自己会显示「还没有插件」
        }
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : "";
    }

    private static int integer(JsonObject object, String key) {
        try {
            return object.has(key) ? object.get(key).getAsInt() : 0;
        } catch (RuntimeException ignored) {
            return 0;
        }
    }
}
