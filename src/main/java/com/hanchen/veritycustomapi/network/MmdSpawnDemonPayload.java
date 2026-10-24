package com.hanchen.veritycustomapi.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * 双向 payload：同一个结构，C2S 请求和 S2C 回执共用。
 * 请求时 entityKey 是 verity:verity_demon、position 为玩家位置；回执时 entityKey 为 null、position 是实际生成位置。
 */
public final class MmdSpawnDemonPayload {
    private final int entityId;
    private final Vec3 position;
    private final ResourceLocation entityKey;

    public MmdSpawnDemonPayload(int entityId, Vec3 position) {
        this(entityId, position, null);
    }

    public MmdSpawnDemonPayload(int entityId, Vec3 position, ResourceLocation entityKey) {
        this.entityId = entityId;
        this.position = position;
        this.entityKey = entityKey;
    }

    public int entityId() { return entityId; }
    public Vec3 position() { return position; }
    public ResourceLocation entityKey() {
        return entityKey == null ? new ResourceLocation("verity", "verity_demon") : entityKey;
    }

    public static void write(MmdSpawnDemonPayload payload, FriendlyByteBuf buf) {
        buf.writeInt(payload.entityId);
        buf.writeDouble(payload.position.x);
        buf.writeDouble(payload.position.y);
        buf.writeDouble(payload.position.z);
        // entityKey 是可选的，先用一个 bool 标记在不在
        buf.writeBoolean(payload.entityKey != null);
        if (payload.entityKey != null) {
            buf.writeResourceLocation(payload.entityKey);
        }
    }

    public static MmdSpawnDemonPayload read(FriendlyByteBuf buf) {
        int id = buf.readInt();
        Vec3 pos = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
        boolean hasKey = buf.readBoolean();
        ResourceLocation key = hasKey ? buf.readResourceLocation() : null;
        return new MmdSpawnDemonPayload(id, pos, key);
    }
}