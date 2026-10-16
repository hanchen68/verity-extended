package com.hanchen.veritycustomapi.network;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.plugin.PluginDefinition;
import com.hanchen.veritycustomapi.plugin.PluginEngine;
import com.hanchen.veritycustomapi.plugin.PluginGenerator;
import com.hanchen.veritycustomapi.plugin.PluginStore;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 插件管理界面的服务端权威处理。
 *
 * 界面上每次操作（列清单、开关、删除、新建）都走这里，不让客户端直接改本地文件——
 * 插件是服务端资源，归属和权限只能有一个地方说了算。
 */
public final class PluginNetwork {

    private static final String PROTOCOL = "1";
    private static final int ADMIN_PERMISSION = 2;

    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(OllamaProxyMod.MOD_ID, "plugin_manager"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);
    private static boolean registered;

    private PluginNetwork() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        CHANNEL.registerMessage(0, PluginPayload.class,
                PluginPayload::write, PluginPayload::read,
                PluginNetwork::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1, PluginPayload.class,
                PluginPayload::write, PluginPayload::read,
                PluginNetwork::handleClient,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        registered = true;
    }

    // ------------------------------------------------------------------ 客户端 -> 服务端

    public static void requestList() {
        sendToServer(PluginPayload.listRequest());
    }

    public static void sendToggle(String id, boolean enabled) {
        sendToServer(PluginPayload.toggle(id, enabled));
    }

    public static void sendDelete(String id) {
        sendToServer(PluginPayload.delete(id));
    }

    public static void sendCreate(String description) {
        sendToServer(PluginPayload.create(description));
    }

    /**
     * 客户端->服务端统一的发送出口。
     *
     * 设置界面在主菜单也能打开，那时还没连接，直接发会在这里抛空指针把客户端弄崩。
     * 界面层已经判过连接了，这里再兜一层——界面不该有能力把游戏弄崩。
     */
    private static void sendToServer(PluginPayload payload) {
        try {
            CHANNEL.send(PacketDistributor.SERVER.noArg(), payload);
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.debug("插件请求未发出（可能尚未连接服务器）: {}", error.toString());
        }
    }

    private static void handle(PluginPayload payload, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        ServerPlayer player = context.getSender();
        context.setPacketHandled(true);
        if (player == null || payload == null) {
            return;
        }
        byte action = payload.action();
        context.enqueueWork(() -> {
            switch (action) {
                case PluginPayload.ACTION_LIST_REQUEST -> pushList(player);
                case PluginPayload.ACTION_TOGGLE -> {
                    if (manage(player, payload.id())) {
                        PluginStore.setEnabled(payload.id(), payload.flag());
                        pushMessage(player, "插件 " + payload.id() + (payload.flag() ? " 已启用" : " 已停用"));
                    } else {
                        pushMessage(player, "找不到这个插件（或它不是你的）");
                    }
                    pushList(player);
                }
                case PluginPayload.ACTION_DELETE -> {
                    if (manage(player, payload.id())) {
                        PluginStore.delete(payload.id());
                        pushMessage(player, "插件 " + payload.id() + " 已删除");
                    } else {
                        pushMessage(player, "找不到这个插件（或它不是你的）");
                    }
                    pushList(player);
                }
                case PluginPayload.ACTION_CREATE -> createAsync(player, payload.text());
                default -> {
                }
            }
        });
    }

    /** 生成要联网，不能卡主线程；跑完再回主线程把结果推给这名玩家。 */
    private static void createAsync(ServerPlayer player, String description) {
        if (!PluginEngine.enabled()) {
            pushMessage(player, "插件功能没开启（配置里 plugin.enabled = false）");
            return;
        }
        if (description == null || description.isBlank()) {
            pushMessage(player, "请先写清楚你想要的功能");
            return;
        }
        pushMessage(player, "正在把你的想法变成插件，请稍等…");
        Thread worker = new Thread(() -> {
            PluginGenerator.Outcome outcome = PluginGenerator.generate(description);
            if (player.getServer() == null) {
                return;
            }
            player.getServer().execute(() -> {
                if (player.hasDisconnected()) {
                    return;
                }
                if (!outcome.ok()) {
                    pushMessage(player, "插件生成失败：" + String.join("；", outcome.errors()));
                    pushMessage(player, "如果提示缺少某个动作，说明当前版本还做不到这个效果。");
                } else {
                    PluginDefinition generated = outcome.plugin();
                    String id = uniqueId(generated.id, player.getUUID().toString());
                    PluginDefinition stamped = new PluginDefinition(id, generated.name,
                            generated.description, player.getUUID().toString(), true,
                            generated.trigger, generated.conditions, generated.actions);
                    if (PluginStore.save(stamped)) {
                        pushMessage(player, "插件已生成：" + id + " —— " + stamped.name);
                    } else {
                        pushMessage(player, "插件保存失败，请查看日志");
                    }
                }
                pushList(player);
            });
        }, "Verity-Plugin-Generate");
        worker.setDaemon(true);
        worker.start();
    }

    // ------------------------------------------------------------------ 服务端 -> 客户端

    private static void handleClient(PluginPayload payload, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.setPacketHandled(true);
        if (payload == null) {
            return;
        }
        byte action = payload.action();
        String text = payload.text();
        // 界面是客户端专属类，得走 DistExecutor，专用服务器上不会去加载它
        context.enqueueWork(() -> net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                net.minecraftforge.api.distmarker.Dist.CLIENT,
                () -> () -> com.hanchen.veritycustomapi.client.PluginUiBridge.accept(action, text)));
    }

    // ------------------------------------------------------------------ 工具

    public static void pushList(ServerPlayer player) {
        if (player == null) {
            return;
        }
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), PluginPayload.listResponse(listJson(player)));
    }

    /** 让客户端弹出插件管理界面。界面是客户端专属的，服务端只负责发信号。 */
    public static void pushOpenUi(ServerPlayer player) {
        if (player == null) {
            return;
        }
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), PluginPayload.openUi());
    }

    public static void pushMessage(ServerPlayer player, String text) {
        if (player == null) {
            return;
        }
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), PluginPayload.message(text));
    }

    /** 这名玩家能看到的插件清单（JSON 数组）：管理员看全部，普通玩家只看自己的。 */
    public static String listJson(ServerPlayer player) {
        boolean admin = player.hasPermissions(ADMIN_PERMISSION);
        JsonArray array = new JsonArray();
        for (PluginDefinition plugin : PluginStore.all()) {
            boolean mine = plugin.belongsTo(player.getUUID());
            if (!admin && !mine) {
                continue;
            }
            JsonObject object = new JsonObject();
            object.addProperty("id", plugin.id);
            object.addProperty("name", plugin.name);
            object.addProperty("description", plugin.description == null ? "" : plugin.description);
            object.addProperty("enabled", plugin.enabled);
            object.addProperty("trigger", plugin.trigger.type);
            object.addProperty("conditions", plugin.conditions.size());
            object.addProperty("actions", plugin.actions.size());
            object.addProperty("mine", mine);
            array.add(object);
        }
        return array.toString();
    }

    private static boolean manage(ServerPlayer player, String id) {
        PluginDefinition plugin = PluginStore.get(id);
        if (plugin == null) {
            return false;
        }
        if (player.hasPermissions(ADMIN_PERMISSION)) {
            return true;
        }
        return plugin.belongsTo(player.getUUID());
    }

    private static String uniqueId(String base, String owner) {
        String candidate = base;
        int suffix = 2;
        while (true) {
            PluginDefinition existing = PluginStore.get(candidate);
            if (existing == null || owner.equals(existing.owner)) {
                return candidate;
            }
            candidate = base + "_" + suffix;
            suffix++;
            if (suffix > 99) {
                return base + "_" + System.currentTimeMillis() % 100000;
            }
        }
    }
}
