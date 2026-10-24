package com.hanchen.veritycustomapi.network;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.function.Supplier;

/** 单向 S2C 通道：告诉客户端某只 Verity 现在手上举着什么。 */
public final class VerityHoldNetwork {
    private static final String PROTOCOL = "1";
    /** 允许对端完全没有这个通道：老版本客户端/服务端不会因为少一个纯视觉包就被断开连接。 */
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(OllamaProxyMod.MOD_ID, "verity_hold"),
            () -> PROTOCOL,
            NetworkRegistry.acceptMissingOr(PROTOCOL::equals),
            NetworkRegistry.acceptMissingOr(PROTOCOL::equals));
    private static boolean registered;

    private VerityHoldNetwork() {
    }

    public static synchronized void register() {
        if (registered) return;
        CHANNEL.registerMessage(0, VerityHoldPayload.class,
                VerityHoldPayload::write, VerityHoldPayload::read,
                VerityHoldNetwork::handlePayload,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        registered = true;
    }

    /** 发给能看到这只 Verity 的所有客户端；施法成功后调用。 */
    public static void broadcast(Entity verity, ItemStack stack, int durationTicks) {
        if (verity == null || verity.level().isClientSide || stack == null || stack.isEmpty()) return;
        try {
            CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> verity),
                    new VerityHoldPayload(verity.getId(), stack, durationTicks));
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.warn("同步 Verity 手持物品失败", error);
        }
    }

    private static void handlePayload(VerityHoldPayload payload, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.setPacketHandled(true);
        if (payload == null) return;
        context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.hanchen.veritycustomapi.client.StellarRemoteHandClient.accept(payload)));
    }
}