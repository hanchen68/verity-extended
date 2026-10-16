package com.hanchen.veritycustomapi.network;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.compat.VerityPatResponseService;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.function.Supplier;

public final class VerityPatNetwork {
    private static final String PROTOCOL = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(OllamaProxyMod.MOD_ID, "verity_pat"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    private VerityPatNetwork() {
    }

    public static void register() {
        CHANNEL.registerMessage(
                0,
                VerityPatRequestPayload.class,
                VerityPatRequestPayload::write,
                VerityPatRequestPayload::read,
                VerityPatNetwork::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
    }

    public static void sendRequest(int entityId) {
        CHANNEL.send(PacketDistributor.SERVER.noArg(), new VerityPatRequestPayload(entityId));
    }

    private static void handle(VerityPatRequestPayload payload, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        net.minecraft.server.level.ServerPlayer player = context.getSender();
        context.setPacketHandled(true);
        if (player == null) return;
        context.enqueueWork(() -> VerityPatResponseService.handle(player, payload.entityId()));
    }
}
