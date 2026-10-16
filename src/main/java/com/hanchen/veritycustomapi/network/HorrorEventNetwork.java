package com.hanchen.veritycustomapi.network;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.function.Supplier;

public final class HorrorEventNetwork {
    private static final String PROTOCOL = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(OllamaProxyMod.MOD_ID, "horror_event"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    private HorrorEventNetwork() {
    }

    public static void register() {
        CHANNEL.registerMessage(0, HorrorEventPayload.class,
                HorrorEventPayload::write, HorrorEventPayload::read,
                HorrorEventNetwork::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static void sendToPlayer(net.minecraft.server.level.ServerPlayer player,
                                    HorrorEventPayload payload) {
        if (player != null) CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), payload);
    }

    private static void handle(HorrorEventPayload payload, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(
                Dist.CLIENT,
                () -> () -> com.hanchen.veritycustomapi.client.ClientHorrorEventHandler.handle(payload)));
        context.setPacketHandled(true);
    }
}
