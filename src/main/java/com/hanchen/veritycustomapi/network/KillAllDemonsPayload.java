package com.hanchen.veritycustomapi.network;

import net.minecraft.network.FriendlyByteBuf;

public final class KillAllDemonsPayload {
    public static void write(KillAllDemonsPayload payload, FriendlyByteBuf buffer) {
    }

    public static KillAllDemonsPayload read(FriendlyByteBuf buffer) {
        return new KillAllDemonsPayload();
    }
}
