package com.hanchen.veritycustomapi.network;

import net.minecraft.network.FriendlyByteBuf;

public final class VerityPatRequestPayload {
    private final int entityId;

    public VerityPatRequestPayload(int entityId) {
        this.entityId = entityId;
    }

    public int entityId() {
        return entityId;
    }

    public static void write(VerityPatRequestPayload payload, FriendlyByteBuf buffer) {
        buffer.writeVarInt(payload.entityId);
    }

    public static VerityPatRequestPayload read(FriendlyByteBuf buffer) {
        return new VerityPatRequestPayload(buffer.readVarInt());
    }
}
