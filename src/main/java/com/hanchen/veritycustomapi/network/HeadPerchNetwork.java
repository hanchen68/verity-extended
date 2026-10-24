package com.hanchen.veritycustomapi.network;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.ride.HeadPerchManager;
import com.hanchen.veritycustomapi.ride.RideManager;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import varmite.verity.entity.verity.VerityEntity;

import java.util.Optional;
import java.util.function.Supplier;

/** 骑乘类请求的通道，按键和右键都发这里，判定统一在服务端做。 */
public final class HeadPerchNetwork {
    private static final String PROTOCOL = "2";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(OllamaProxyMod.MOD_ID, "head_perch"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);
    private static boolean registered;

    private HeadPerchNetwork() {
    }

    public static synchronized void register() {
        if (registered) return;
        CHANNEL.registerMessage(0, HeadPerchPayload.class,
                HeadPerchPayload::write, HeadPerchPayload::read,
                HeadPerchNetwork::handlePayload,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        registered = true;
    }

    public static void sendToggle() {
        CHANNEL.send(PacketDistributor.SERVER.noArg(),
                new HeadPerchPayload(HeadPerchPayload.ACTION_TOGGLE_PERCH));
    }

    public static void sendDismount() {
        CHANNEL.send(PacketDistributor.SERVER.noArg(),
                new HeadPerchPayload(HeadPerchPayload.ACTION_DISMOUNT));
    }

    /** 潜行空手右键发来的骑乘请求，带上被点的那只 Verity 的 id。 */
    public static void sendRideRequest(int entityId) {
        CHANNEL.send(PacketDistributor.SERVER.noArg(),
                new HeadPerchPayload(HeadPerchPayload.ACTION_RIDE_REQUEST, entityId));
    }


    private static void handlePayload(HeadPerchPayload payload, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        ServerPlayer player = context.getSender();
        context.setPacketHandled(true);
        if (player == null) return;
        byte action = payload == null ? HeadPerchPayload.ACTION_TOGGLE_PERCH : payload.action();
        int entityId = payload == null ? -1 : payload.entityId();
        context.enqueueWork(() -> {
            switch (action) {
                case HeadPerchPayload.ACTION_DISMOUNT -> {
                    if (!RideManager.requestDismount(player)) {
                        HeadPerchManager.requestDismount(player);
                    }
                }
                case HeadPerchPayload.ACTION_RIDE_REQUEST -> handleRideRequest(player, entityId);
                case HeadPerchPayload.ACTION_TOGGLE_PERCH -> HeadPerchManager.toggle(player);
                default -> OllamaProxyMod.LOGGER.warn("忽略未知的骑乘动作: {}", action);
            }
        });
    }

    /** 服务端按实体 id 找到被点的那只；判定和上骑都交给 RideManager，别在这里另开一套。 */
    private static void handleRideRequest(ServerPlayer player, int entityId) {
        Entity target = entityId >= 0 ? player.serverLevel().getEntity(entityId) : null;
        if (!(target instanceof VerityEntity verity) || !verity.isAlive()) {
            player.displayClientMessage(Component.literal("✖ 没找到刚才右键的那只 Verity")
                    .withStyle(ChatFormatting.RED), true);
            return;
        }
        RideManager.requestMount(verity, player);
    }
}
