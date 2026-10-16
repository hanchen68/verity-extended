package com.hanchen.veritycustomapi.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 插件管理界面的往返载荷。
 *
 * 所有动作共用一个结构，省得每加一个界面操作就新增一个包：靠 {@code action} 区分语义，
 * 其余字段按需用。列表和提示都用字符串承载（列表是 JSON），服务端只产出、客户端只显示。
 */
public final class PluginPayload {
    /** 客户端 -> 服务端：请求列表。 */
    public static final byte ACTION_LIST_REQUEST = 0;
    /** 服务端 -> 客户端：列表数据（{@code text} 是 JSON 数组）。 */
    public static final byte ACTION_LIST_RESPONSE = 1;
    /** 客户端 -> 服务端：切换启用状态（{@code id} + {@code flag}）。 */
    public static final byte ACTION_TOGGLE = 2;
    /** 客户端 -> 服务端：删除插件（{@code id}）。 */
    public static final byte ACTION_DELETE = 3;
    /** 客户端 -> 服务端：新建插件（{@code text} 是玩家描述）。 */
    public static final byte ACTION_CREATE = 4;
    /** 服务端 -> 客户端：一行提示（{@code text}）。 */
    public static final byte ACTION_MESSAGE = 5;
    /** 服务端 -> 客户端：打开插件管理界面。 */
    public static final byte ACTION_OPEN_UI = 6;

    private final byte action;
    private final String id;
    private final String text;
    private final boolean flag;

    public PluginPayload(byte action) {
        this(action, "", "", false);
    }

    public PluginPayload(byte action, String id, String text, boolean flag) {
        this.action = action;
        this.id = id == null ? "" : id;
        this.text = text == null ? "" : text;
        this.flag = flag;
    }

    public static PluginPayload listRequest() {
        return new PluginPayload(ACTION_LIST_REQUEST);
    }

    public static PluginPayload listResponse(String json) {
        return new PluginPayload(ACTION_LIST_RESPONSE, "", json, false);
    }

    public static PluginPayload toggle(String id, boolean enabled) {
        return new PluginPayload(ACTION_TOGGLE, id, "", enabled);
    }

    public static PluginPayload delete(String id) {
        return new PluginPayload(ACTION_DELETE, id, "", false);
    }

    public static PluginPayload create(String description) {
        return new PluginPayload(ACTION_CREATE, "", description, false);
    }

    public static PluginPayload message(String text) {
        return new PluginPayload(ACTION_MESSAGE, "", text, false);
    }

    public static PluginPayload openUi() {
        return new PluginPayload(ACTION_OPEN_UI);
    }

    public byte action() {
        return action;
    }

    public String id() {
        return id;
    }

    public String text() {
        return text;
    }

    public boolean flag() {
        return flag;
    }

    public static void write(PluginPayload payload, FriendlyByteBuf buffer) {
        buffer.writeByte(payload.action);
        buffer.writeUtf(payload.id, 64);
        // 列表 JSON 和生成描述都可能很长，放宽到 8K，仍留个上限
        buffer.writeUtf(payload.text, 8192);
        buffer.writeBoolean(payload.flag);
    }

    public static PluginPayload read(FriendlyByteBuf buffer) {
        return new PluginPayload(buffer.readByte(), buffer.readUtf(64), buffer.readUtf(8192),
                buffer.readBoolean());
    }
}
