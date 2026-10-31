package com.hanchen.veritycustomapi.network;

import net.minecraft.network.FriendlyByteBuf;

/** 服务端 → 客户端：标记/取消「这个实体是战斗系统召唤的替身」。 */
public final class GuardianSyncPayload {

    private final int entityId;
    private final boolean guardian;

    public GuardianSyncPayload(int entityId, boolean guardian) {
        this.entityId = entityId;
        this.guardian = guardian;
    }

    public int entityId() {
        return entityId;
    }

    public boolean guardian() {
        return guardian;
    }

    public static void write(GuardianSyncPayload payload, FriendlyByteBuf buffer) {
        buffer.writeVarInt(payload.entityId);
        buffer.writeBoolean(payload.guardian);
    }

    public static GuardianSyncPayload read(FriendlyByteBuf buffer) {
        return new GuardianSyncPayload(buffer.readVarInt(), buffer.readBoolean());
    }
}
