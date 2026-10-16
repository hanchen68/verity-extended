package com.hanchen.veritycustomapi.network;

import com.hanchen.veritycustomapi.config.ModConfig;
import varmite.verity.entity.verity.VerityEntity;
import net.minecraft.server.level.ServerPlayer;

public final class ServerVerityMemoryContext {
    private static final ThreadLocal<String> MEMORY_ID = new ThreadLocal<>();

    private ServerVerityMemoryContext() {
    }

    public static void begin(VerityEntity verity, ServerPlayer player) {
        if (ModConfig.SERVER_VERITY_ADAPTER_ENABLED.get() && ModConfig.SERVER_VERITY_PRIVATE_CHAT.get()
                && verity != null && player != null) {
            MEMORY_ID.set("verity:" + verity.getUUID() + ":player:" + player.getUUID());
        } else {
            MEMORY_ID.remove();
        }
    }

    public static String current(String fallback) {
        String value = MEMORY_ID.get();
        return value == null || value.isBlank() ? fallback : value;
    }

    public static void clear() {
        MEMORY_ID.remove();
    }
}
