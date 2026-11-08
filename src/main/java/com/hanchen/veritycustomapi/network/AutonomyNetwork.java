package com.hanchen.veritycustomapi.network;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.autonomy.ClientAutonomyState;
import com.hanchen.veritycustomapi.autonomy.VerityAutonomy;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import varmite.verity.entity.verity.VerityEntity;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 自主状态的请求/同步通道。
 *
 * <p>0 = 客户端请求当前状态（打开设置页或切换开关后调用）；
 * 1 = 服务端下发快照。
 *
 * <p>为什么不复用原有命令：命令的输出只进聊天栏，界面拿不到；
 * 而界面必须知道「现在到底是开还是关」才能给出正确的按钮，否则就是让玩家盲点。
 */
public final class AutonomyNetwork {
    private static final String PROTOCOL = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(OllamaProxyMod.MOD_ID, "autonomy_state"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);
    private static boolean registered;

    private AutonomyNetwork() {
    }

    public static synchronized void register() {
        if (registered) return;
        CHANNEL.registerMessage(0, AutonomyStateRequest.class,
                AutonomyStateRequest::write, AutonomyStateRequest::read,
                AutonomyNetwork::handleRequest,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1, AutonomyStatePayload.class,
                AutonomyStatePayload::write, AutonomyStatePayload::read,
                AutonomyNetwork::handleState,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        registered = true;
    }

    /** 客户端请求：没有实体 id 时由服务端按「属于该玩家的那只」解析。 */
    public static void request(int entityId) {
        try {
            if (!registered) return;
            CHANNEL.send(PacketDistributor.SERVER.noArg(), new AutonomyStateRequest(entityId));
        } catch (Throwable error) {
            // 同步只是让界面准确；失败不该影响操作本身
            OllamaProxyMod.LOGGER.debug("请求自主状态失败: {}", error.toString());
        }
    }

    /** 服务端主动推送给单个玩家。 */
    public static void sendTo(ServerPlayer player, VerityEntity verity) {
        if (player == null || verity == null) return;
        try {
            if (!registered) return;
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), snapshot(verity));
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.debug("下发自主状态失败: {}", error.toString());
        }
    }

    private static AutonomyStatePayload snapshot(VerityEntity verity) {
        VerityAutonomy.State state = VerityAutonomy.state(verity);
        String goal = state.plan == null ? "" : state.plan.describe();
        return new AutonomyStatePayload(verity.getId(), state.enabled, state.suspended,
                state.intent == null ? "" : state.intent.name(), goal, state.modelStatus);
    }

    private static void handleRequest(AutonomyStateRequest payload, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        ServerPlayer player = context.getSender();
        context.setPacketHandled(true);
        if (player == null) return;
        context.enqueueWork(() -> {
            int entityId = payload == null ? -1 : payload.entityId();
            VerityEntity verity = entityId >= 0 && player.serverLevel().getEntity(entityId) instanceof VerityEntity found
                    ? found : com.hanchen.veritycustomapi.network.ServerVerityRegistry.findForPlayer(player);
            if (verity == null) return;
            // 只下发自己的同伴状态，避免看到别人的目标
            if (!com.hanchen.veritycustomapi.network.ServerVerityRegistry.owns(player, verity)) return;
            sendTo(player, verity);
        });
    }

    private static void handleState(AutonomyStatePayload payload, Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        context.enqueueWork(() -> ClientAutonomyState.accept(payload));
        context.setPacketHandled(true);
    }

    /** 请求体：只带实体 id，-1 表示「帮我找属于我的那只」。 */
    public record AutonomyStateRequest(int entityId) {
        public static void write(AutonomyStateRequest payload, FriendlyByteBuf buffer) {
            buffer.writeVarInt(payload.entityId + 1);
        }

        public static AutonomyStateRequest read(FriendlyByteBuf buffer) {
            return new AutonomyStateRequest(buffer.readVarInt() - 1);
        }
    }
}
