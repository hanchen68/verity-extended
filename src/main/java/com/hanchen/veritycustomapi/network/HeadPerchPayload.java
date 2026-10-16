package com.hanchen.veritycustomapi.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 玩家对 Verity 的骑乘类请求。
 *
 * action：0 站头上/下来切换，1 让 Verity 下来（也用于脱离骑乘），2 骑乘请求（潜行+空手右键）。
 *
 * 骑乘请求必须带目标实体 id：服务端靠它定位被右键的那只，不能去猜「最近的一只」，
 * 玩家点的是哪只就得骑哪只。
 */
public final class HeadPerchPayload {
    public static final byte ACTION_TOGGLE_PERCH = 0;
    public static final byte ACTION_DISMOUNT = 1;
    public static final byte ACTION_RIDE_REQUEST = 2;

    private final byte action;
    private final int entityId;

    public HeadPerchPayload(byte action) {
        this(action, -1);
    }

    public HeadPerchPayload(byte action, int entityId) {
        this.action = action;
        this.entityId = entityId;
    }

    public byte action() {
        return action;
    }

    /** 骑乘请求的目标实体 id，其它动作恒为 -1。 */
    public int entityId() {
        return entityId;
    }

    public static void write(HeadPerchPayload payload, FriendlyByteBuf buffer) {
        buffer.writeByte(payload.action);
        buffer.writeVarInt(payload.entityId);
    }

    public static HeadPerchPayload read(FriendlyByteBuf buffer) {
        return new HeadPerchPayload(buffer.readByte(), buffer.readVarInt());
    }
}
