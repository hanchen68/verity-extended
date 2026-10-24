package com.hanchen.veritycustomapi.network;

import com.hanchen.veritycustomapi.config.ModConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import varmite.verity.entity.verity.VerityEntity;

import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = "verity_extended", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ServerHorrorEventService {
    private static final long STARTUP_SILENCE = 1200L;
    private static final long SCAN_INTERVAL = 100L;
    private static final double MAX_DISTANCE_SQ = 1024.0D;
    private static final long TYPE_COOLDOWN = 30000L;
    private static final ResourceLocation VERITY_ID = ResourceLocation.fromNamespaceAndPath("verity", "verity");
    // 用并发容器：这两张表虽然主要在服务端线程读写，但 clear() 由 ServerStopping 事件触发，
    // 事件线程与服务端 tick 未必是同一个，普通 HashMap 在清空时并发读会 CME。
    private static final Map<UUID, Long> NEXT_EVENT = new ConcurrentHashMap<>();
    private static final Map<UUID, long[]> TYPE_HISTORY = new ConcurrentHashMap<>();
    private static long serverTick;

    private ServerHorrorEventService() {
    }

    /**
     * 服务器停止时清掉每玩家的冷却和类型历史。static 表按 UUID 记，不清就会跨存档残留，而且只增不减。
     */
    public static void clear() {
        NEXT_EVENT.clear();
        TYPE_HISTORY.clear();
        serverTick = 0L;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !ModConfig.HORROR_SERVER_ENABLED.get()
                || !ModConfig.HORROR_SERVER_AMBIENT_EVENTS.get()
                || ModConfig.HORROR_SERVER_EVENT_FREQUENCY.get() <= 0.0D) return;
        serverTick++;
        if (serverTick < STARTUP_SILENCE || serverTick % SCAN_INTERVAL != 0L) return;
        for (ServerLevel level : event.getServer().getAllLevels()) {
            for (ServerPlayer player : level.players()) tickPlayer(level, player);
        }
    }

    private static void tickPlayer(ServerLevel level, ServerPlayer player) {
        if (player.isDeadOrDying() || player.isSleeping() || player.isSpectator()
                || level.getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL) return;
        UUID playerId = player.getUUID();
        if (serverTick < NEXT_EVENT.getOrDefault(playerId, STARTUP_SILENCE)) return;
        VerityEntity verity = findVerity(level, player);
        if (verity == null || verity.isTalking() || hasDemon(level)) return;
        int weight = contextWeight(level, player);
        double frequency = Math.min(3.0D, Math.max(0.0D, ModConfig.HORROR_SERVER_EVENT_FREQUENCY.get()));
        double chance = Math.min(0.018D, (0.0015D + Math.min(11, weight) * 0.00115D) * frequency);
        if (level.random.nextDouble() >= chance) return;
        long[] history = TYPE_HISTORY.computeIfAbsent(playerId, ignored -> new long[9]);
        int type = chooseType(history, serverTick, level.random.nextInt(9));
        history[type] = serverTick;
        HorrorEventNetwork.sendToPlayer(player,
                new HorrorEventPayload(type, verity.getId(), type >= 4 ? 36 : 24,
                        Math.min(1.0F, 0.45F + weight * 0.05F), level.random.nextLong()));
        long cooldown = Math.max(200L, Math.round(20.0D * ModConfig.HORROR_SERVER_COOLDOWN_SECONDS.get()
                / Math.sqrt(frequency)));
        NEXT_EVENT.put(playerId, serverTick + cooldown);
    }

    private static VerityEntity findVerity(ServerLevel level, ServerPlayer player) {
        VerityEntity nearest = null;
        double nearestDistance = MAX_DISTANCE_SQ;
        for (Entity entity : level.getEntities(player, player.getBoundingBox().inflate(32.0D),
                candidate -> VERITY_ID.equals(ForgeRegistries.ENTITY_TYPES.getKey(candidate.getType())))) {
            if (!(entity instanceof VerityEntity verity) || !verity.isAlive()) continue;
            double distance = player.distanceToSqr(verity);
            if (distance >= 16.0D && distance < nearestDistance) {
                nearest = verity;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private static boolean hasDemon(ServerLevel level) {
        for (Entity entity : level.getEntities().getAll()) {
            ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
            if (entity.isAlive() && id != null && "verity".equals(id.getNamespace())
                    && id.getPath().contains("demon")) return true;
        }
        return false;
    }

    private static int contextWeight(ServerLevel level, ServerPlayer player) {
        long time = level.getDayTime() % 24000L;
        boolean night = level.dimensionType().natural() && time >= 13000L && time < 23000L;
        boolean dark = level.getMaxLocalRawBrightness(player.blockPosition()) <= 5;
        boolean alone = level.players().stream().filter(other -> other != player && other.isAlive()
                && !other.isSpectator()).noneMatch(other -> other.distanceToSqr(player) <= 4096.0D);
        return (night ? 2 : 0) + (dark ? 2 : 0) + (alone ? 1 : 0);
    }

    static int chooseType(long[] history, long now, int start) {
        for (int offset = 0; offset < 9; offset++) {
            int type = (start + offset) % 9;
            if (history[type] == 0L || now - history[type] >= TYPE_COOLDOWN) return type;
        }
        return start;
    }
}
