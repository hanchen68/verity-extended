package com.hanchen.veritycustomapi.client;

import com.hanchen.veritycustomapi.network.HorrorEventPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

public final class ClientHorrorEventHandler {
    private ClientHorrorEventHandler() {
    }

    public static void handle(HorrorEventPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) return;
        Entity target = payload.entityId() < 0 ? null : minecraft.level.getEntity(payload.entityId());
        VerityHorrorDirector.acceptServerEvent(payload.type(), payload.durationTicks(),
                payload.intensity(), payload.seed(), target);
    }
}
