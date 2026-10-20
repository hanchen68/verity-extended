package com.hanchen.veritycustomapi.compat;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.config.ModConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import varmite.verity.entity.demon.VerityDemonEntity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 跨存档追击：Verity 恶魔化后，把“她记住了你”写进世界之外的全局文件。
 *
 * <p>删除旧存档、新建世界后，客户端仍会读取这个文件；延迟几秒在玩家附近生成
 * 一只追击恶魔，并让她说一句“你换了存档也没用”。击败这只恶魔后标记解除。
 *
 * <p>刻意不做视频里的“吃掉退出按钮”“强制删档”“无限不可击杀”：
 * 这些会把玩家锁死在存档里，属于破坏性设计。这里是可关闭、可解除的追击。
 */
public final class CrossSavePursuit {
    private static final Gson GSON = new Gson();
    private static final ResourceLocation DEMON_ID =
            ResourceLocation.fromNamespaceAndPath("verity", "verity_demon");
    private static final String TAG = "verity_extended_cross_save_pursuit";
    private static final long TRIGGER_DELAY_MS = 8000L;

    /** 全局记录：玩家 UUID → 追击状态。文件放在 config，不在任何存档目录内。 */
    private static final Map<UUID, Entry> ENTRIES = new LinkedHashMap<>();
    private static final Map<UUID, Long> PENDING = new HashMap<>();
    private static final Map<UUID, Integer> ATTEMPTS = new HashMap<>();
    private static int tickCounter;
    private static boolean loaded;

    private CrossSavePursuit() {
    }

    public static void register() {
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(CrossSavePursuit.class);
    }

    public static synchronized void resetSession() {
        PENDING.clear();
        ATTEMPTS.clear();
        tickCounter = 0;
    }

    /** 由变身 Mixin 在 transformIntoDemon 成功后调用。 */
    public static synchronized void markTriggered(Player player) {
        if (player == null || player.level().isClientSide()) return;
        if (!ModConfig.CROSS_SAVE_PURSUIT.get()) return;
        ensureLoaded();
        UUID id = player.getUUID();
        Entry entry = ENTRIES.computeIfAbsent(id, ignored -> new Entry());
        entry.active = true;
        entry.since = System.currentTimeMillis();
        entry.reason = "demon";
        entry.lastWorld = worldKey(player);
        entry.demon = "";
        tagNearbyDemon(player, entry);
        save();
        OllamaProxyMod.LOGGER.info("[CrossSave] 已记录跨存档追击标记: player={}", id);
    }

    private static void tagNearbyDemon(Player player, Entry entry) {
        if (!(player.level() instanceof ServerLevel level)) return;
        for (VerityDemonEntity demon : level.getEntitiesOfClass(VerityDemonEntity.class,
                player.getBoundingBox().inflate(64.0D))) {
            demon.getPersistentData().putString(TAG, player.getUUID().toString());
            entry.demon = demon.getUUID().toString();
            return;
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!ModConfig.CROSS_SAVE_PURSUIT.get()) return;
        ensureLoaded();
        Entry entry = ENTRIES.get(player.getUUID());
        if ((entry == null || !entry.active) && !ModConfig.FRIENDLY_MODE.get()
                && hasHostileStory(player)) {
            // 兼容升级前就已恶魔化的旧存档：第一次进游戏时补记，之后删档重开仍会追。
            markTriggered(player);
            entry = ENTRIES.get(player.getUUID());
        }
        if (entry == null || !entry.active) return;
        String key = worldKey(player);
        if (key.equals(entry.lastWorld)) return;
        PENDING.put(player.getUUID(), System.currentTimeMillis() + TRIGGER_DELAY_MS);
        OllamaProxyMod.LOGGER.info("[CrossSave] 新世界检测到未解除的追击，将在 {}ms 后触发: player={} world={}",
                TRIGGER_DELAY_MS, player.getUUID(), key);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            PENDING.remove(player.getUUID());
            ATTEMPTS.remove(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ++tickCounter < 20) return;
        tickCounter = 0;
        if (PENDING.isEmpty()) return;
        MinecraftServer server = event.getServer();
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Long>> iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Long> pending = iterator.next();
            if (now < pending.getValue()) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(pending.getKey());
            if (player == null) {
                iterator.remove();
                ATTEMPTS.remove(pending.getKey());
                continue;
            }
            if (trigger(player)) {
                iterator.remove();
                ATTEMPTS.remove(pending.getKey());
            } else {
                int attempts = ATTEMPTS.merge(pending.getKey(), 1, Integer::sum);
                if (attempts >= 3) {
                    iterator.remove();
                    ATTEMPTS.remove(pending.getKey());
                    OllamaProxyMod.LOGGER.warn("[CrossSave] 追击触发失败，已放弃: player={}", pending.getKey());
                } else {
                    pending.setValue(now + 5000L);
                }
            }
        }
    }

    private static boolean trigger(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) return false;
        if (!ModConfig.CROSS_SAVE_PURSUIT.get() || ModConfig.FRIENDLY_MODE.get()) return false;
        ensureLoaded();
        Entry entry = ENTRIES.get(player.getUUID());
        if (entry == null || !entry.active) return false;
        String key = worldKey(player);
        if (key.equals(entry.lastWorld)) return false;

        VerityDemonEntity demon = findNearbyDemon(level, player);
        if (demon == null) {
            demon = spawnDemon(level, player);
        }
        if (demon == null) return false;

        demon.setDemonState(1);
        demon.setPersistenceRequired();
        demon.setTarget(player);
        demon.getPersistentData().putString(TAG, player.getUUID().toString());
        entry.demon = demon.getUUID().toString();
        entry.lastWorld = key;
        save();

        level.sendParticles(ParticleTypes.PORTAL, demon.getX(), demon.getY() + 1.0D, demon.getZ(),
                64, 0.8D, 1.2D, 0.8D, 0.12D);
        level.playSound(null, player.blockPosition(), SoundEvents.ENDERMAN_SCREAM,
                SoundSource.HOSTILE, 1.0F, 0.75F);
        player.sendSystemMessage(Component.literal("§4<Verity> §r你换了存档也没用。"));
        OllamaProxyMod.LOGGER.info("[CrossSave] 已在世界 {} 触发跨存档追击: player={} demon={}",
                key, player.getUUID(), demon.getUUID());
        return true;
    }

    private static VerityDemonEntity findNearbyDemon(ServerLevel level, ServerPlayer player) {
        return level.getEntitiesOfClass(VerityDemonEntity.class,
                        player.getBoundingBox().inflate(64.0D)).stream()
                .findFirst().orElse(null);
    }

    private static VerityDemonEntity spawnDemon(ServerLevel level, ServerPlayer player) {
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(DEMON_ID);
        if (type == null) {
            OllamaProxyMod.LOGGER.warn("[CrossSave] 找不到 verity:verity_demon，无法触发追击");
            return null;
        }
        BlockPos base = player.blockPosition();
        int dx = player.getRandom().nextInt(17) - 8;
        int dz = player.getRandom().nextInt(17) - 8;
        if (Math.abs(dx) < 6 && Math.abs(dz) < 6) dx = dx >= 0 ? 7 : -7;
        BlockPos target = base.offset(dx, 0, dz);
        BlockPos safe = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, target);
        Entity created = type.spawn(level, safe, MobSpawnType.EVENT);
        if (!(created instanceof VerityDemonEntity demon)) {
            if (created != null) created.discard();
            return null;
        }
        demon.setPos(safe.getX() + 0.5D, safe.getY(), safe.getZ() + 0.5D);
        demon.setPersistenceRequired();
        return demon;
    }

    @SubscribeEvent
    public static void onDemonDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof VerityDemonEntity demon)) return;
        String tagged = demon.getPersistentData().getString(TAG);
        if (tagged.isBlank()) return;
        try {
            resolve(UUID.fromString(tagged));
        } catch (IllegalArgumentException ignored) {
        }
    }

    public static synchronized void resolve(UUID playerId) {
        ensureLoaded();
        Entry entry = ENTRIES.get(playerId);
        if (entry == null || !entry.active) return;
        entry.active = false;
        entry.demon = "";
        save();
        OllamaProxyMod.LOGGER.info("[CrossSave] 追击恶魔已被击败，标记解除: player={}", playerId);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        resetSession();
    }

    private static boolean hasHostileStory(ServerPlayer player) {
        try {
            varmite.verity.event.WorldSpawnData data =
                    varmite.verity.event.WorldSpawnData.get(player.serverLevel());
            return data != null && (data.hasLiveDemon()
                    || data.hasHostileDaySpawn || data.hasProvokedSpawn);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String worldKey(Player player) {
        MinecraftServer server = player.getServer();
        if (server == null) return "";
        return server.getWorldData().getLevelName() + "@" + server.overworld().getSeed();
    }

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        Path file = file();
        if (!Files.isRegularFile(file)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            for (Map.Entry<String, JsonElement> item : root.entrySet()) {
                if (!item.getValue().isJsonObject()) continue;
                try {
                    UUID id = UUID.fromString(item.getKey());
                    JsonObject value = item.getValue().getAsJsonObject();
                    Entry entry = new Entry();
                    entry.active = value.has("active") && value.get("active").getAsBoolean();
                    entry.since = value.has("since") ? value.get("since").getAsLong() : 0L;
                    entry.reason = value.has("reason") ? value.get("reason").getAsString() : "";
                    entry.lastWorld = value.has("lastWorld") ? value.get("lastWorld").getAsString() : "";
                    entry.demon = value.has("demon") ? value.get("demon").getAsString() : "";
                    ENTRIES.put(id, entry);
                } catch (IllegalArgumentException ignored) {
                }
            }
        } catch (Exception error) {
            OllamaProxyMod.LOGGER.warn("[CrossSave] 读取跨存档追击记录失败: {}", error.toString());
        }
    }

    private static synchronized void save() {
        Path file = file();
        JsonObject root = new JsonObject();
        for (Map.Entry<UUID, Entry> item : ENTRIES.entrySet()) {
            Entry entry = item.getValue();
            if (!entry.active && entry.lastWorld.isBlank()) continue;
            JsonObject value = new JsonObject();
            value.addProperty("active", entry.active);
            value.addProperty("since", entry.since);
            value.addProperty("reason", entry.reason);
            value.addProperty("lastWorld", entry.lastWorld);
            value.addProperty("demon", entry.demon);
            root.add(item.getKey().toString(), value);
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException error) {
            OllamaProxyMod.LOGGER.warn("[CrossSave] 写入跨存档追击记录失败: {}", error.toString());
        }
    }

    private static Path file() {
        return ForgePaths.config("verity_extended", "cross_save_pursuit.json");
    }

    private static final class Entry {
        boolean active;
        long since;
        String reason = "";
        String lastWorld = "";
        String demon = "";
    }
}
