package com.hanchen.veritycustomapi.network;

import net.minecraft.network.FriendlyByteBuf;

public final class HorrorEventPayload {
    private final int type;
    private final int entityId;
    private final int durationTicks;
    private final float intensity;
    private final long seed;

    public HorrorEventPayload(int type, int entityId, int durationTicks, float intensity, long seed) {
        if (!ServerVerityEventTypeChecks.isValidType(type)) {
            throw new IllegalArgumentException("无效的服务端恐怖事件类型: " + type);
        }
        this.type = type;
        this.entityId = entityId;
        this.durationTicks = durationTicks;
        this.intensity = intensity;
        this.seed = seed;
    }

    public int type() {
        return type;
    }

    public int entityId() {
        return entityId;
    }

    public int durationTicks() {
        return durationTicks;
    }

    public float intensity() {
        return intensity;
    }

    public long seed() {
        return seed;
    }

    public static void write(HorrorEventPayload payload, FriendlyByteBuf buffer) {
        buffer.writeVarInt(payload.type);
        buffer.writeVarInt(payload.entityId);
        buffer.writeVarInt(payload.durationTicks);
        buffer.writeFloat(payload.intensity);
        buffer.writeLong(payload.seed);
    }

    public static HorrorEventPayload read(FriendlyByteBuf buffer) {
        int type = buffer.readVarInt();
        int entityId = buffer.readVarInt();
        int durationTicks = buffer.readVarInt();
        float intensity = buffer.readFloat();
        long seed = buffer.readLong();
        return new HorrorEventPayload(type, entityId, durationTicks, intensity, seed);
    }
}
