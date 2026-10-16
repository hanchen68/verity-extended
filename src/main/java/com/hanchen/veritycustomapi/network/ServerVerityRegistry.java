package com.hanchen.veritycustomapi.network;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.compat.OriginalVerityStateAccess;
import com.hanchen.veritycustomapi.config.ModConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;
import varmite.verity.entity.verity.VerityEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(modid = "verity_extended", bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ServerVerityRegistry {
    private static final Map<UUID, UUID> OWNER_BY_ENTITY = new ConcurrentHashMap<>();
    private static final Map<UUID, Set<UUID>> ENTITIES_BY_OWNER = new ConcurrentHashMap<>();
    private static final Map<UUID, UUID> SELECTED_BY_PLAYER = new ConcurrentHashMap<>();

    private ServerVerityRegistry() {
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof VerityEntity verity)) return;
        register(verity);
    }

    @SubscribeEvent
    public static void onEntityLeave(EntityLeaveLevelEvent event) {
        if (!(event.getEntity() instanceof VerityEntity)) return;
        unregister(event.getEntity().getUUID());
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onVerityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide() || !(event.getTarget() instanceof VerityEntity verity)
                || !(event.getEntity() instanceof ServerPlayer player)) return;
        Optional<UUID> owner = verity.getOwnerUUID();
        if (owner.isEmpty() && ModConfig.SERVER_VERITY_ADAPTER_ENABLED.get()
                && ModConfig.SERVER_VERITY_AUTO_CLAIM_UNOWNED.get()
                && ownedCount(player.getUUID()) < ServerAdminState.effectiveLimit(player.getUUID())) {
            verity.setOwnerUUID(player.getUUID());
            register(verity);
            owner = Optional.of(player.getUUID());
        }
        if (owner.isPresent() && owner.get().equals(player.getUUID())) {
            SELECTED_BY_PLAYER.put(player.getUUID(), verity.getUUID());
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        SELECTED_BY_PLAYER.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        clear();
        // 世界状态标记是 static 的，跨存档会残留：不清的话，下个存档里恶魔一死，
        // 就会把这个存档的 doDaylightCycle 强行打开。详见 VerityWorldStateGuard#reset。
        com.hanchen.veritycustomapi.compat.VerityWorldStateGuard.reset();
        // 恐怖事件的每玩家冷却/类型历史同样是 static，顺带清掉（防跨存档残留，也防表无限增长）。
        ServerHorrorEventService.clear();
    }

    public static void register(VerityEntity verity) {
        UUID entityId = verity.getUUID();
        unregister(entityId);
        verity.getOwnerUUID().ifPresent(owner -> {
            OWNER_BY_ENTITY.put(entityId, owner);
            ENTITIES_BY_OWNER.computeIfAbsent(owner, ignored -> ConcurrentHashMap.newKeySet()).add(entityId);
        });
    }

    public static void unregister(UUID entityId) {
        UUID owner = OWNER_BY_ENTITY.remove(entityId);
        if (owner != null) {
            Set<UUID> entities = ENTITIES_BY_OWNER.get(owner);
            if (entities != null) {
                entities.remove(entityId);
                if (entities.isEmpty()) ENTITIES_BY_OWNER.remove(owner, entities);
            }
        }
        SELECTED_BY_PLAYER.entrySet().removeIf(entry -> entityId.equals(entry.getValue()));
    }

    public static VerityEntity findForPlayer(ServerPlayer player) {
        if (player == null) return null;
        UUID playerId = player.getUUID();
        UUID selectedId = SELECTED_BY_PLAYER.get(playerId);
        VerityEntity selected = resolveOwned(player, selectedId);
        if (selected != null) return selected;
        Set<UUID> entityIds = ENTITIES_BY_OWNER.get(playerId);
        if (entityIds != null && !entityIds.isEmpty()) {
            VerityEntity nearest = new ArrayList<>(entityIds).stream()
                    .map(id -> resolveOwned(player, id))
                    .filter(java.util.Objects::nonNull)
                    .min(Comparator.comparingDouble(entity -> player.distanceToSqr(entity)))
                    .orElse(null);
            if (nearest != null) return nearest;
        }
        // 单人世界特例：单人（含局域网单人）里的 Verity 不一定有 owner——它可能是
        // 世界自带的、或玩家从未右键认领过。没有 owner 就永远不进 ENTITIES_BY_OWNER，
        // /v 会一直回「你目前没有可用的 Verity。」。
        // 单人世界里没有必要区分归属，直接取同维度一只最近的活 Verity。
        return findAnyNearby(player);
    }

    /**
     * 单人世界兜底：找玩家同维度里最近的一只活 Verity，不限 owner。
     *
     * <p>只在 {@link PrivateChatReplySupport#isSoloPlay} 为真时调用——
     * 纯单人世界里没有「别人的 Verity」这种区分，取最近的一只是唯一合理答案。
     */
    private static VerityEntity findAnyNearby(ServerPlayer player) {
        if (!com.hanchen.veritycustomapi.compat.PrivateChatReplySupport
                .isSoloPlay(player.getServer())) {
            return null;
        }
        if (!(player.level() instanceof ServerLevel level)) return null;
        AABB area = player.getBoundingBox().inflate(96.0D);
        List<VerityEntity> nearby = level.getEntitiesOfClass(VerityEntity.class, area,
                entity -> entity.isAlive());
        return nearby.stream()
                .min(Comparator.comparingDouble(player::distanceToSqr))
                .orElse(null);
    }

    public static int ownedCount(UUID owner) {
        Set<UUID> entities = ENTITIES_BY_OWNER.get(owner);
        return entities == null ? 0 : entities.size();
    }

    public static boolean owns(ServerPlayer player, VerityEntity verity) {
        return player != null && verity != null && verity.getOwnerUUID().isPresent()
                && player.getUUID().equals(verity.getOwnerUUID().get());
    }

    public static ServerPlayer resolveOwner(VerityEntity verity) {
        if (verity == null || verity.getOwnerUUID().isEmpty() || !(verity.level() instanceof ServerLevel level)) {
            return null;
        }
        Entity owner = level.getServer().getPlayerList().getPlayer(verity.getOwnerUUID().get());
        return owner instanceof ServerPlayer player && player.serverLevel() == level ? player : null;
    }

    private static VerityEntity resolveOwned(ServerPlayer player, UUID entityId) {
        if (entityId == null || !(player.level() instanceof ServerLevel level)) return null;
        Entity entity = level.getEntity(entityId);
        return entity instanceof VerityEntity verity && verity.isAlive() && owns(player, verity) ? verity : null;
    }

    public static void clear() {
        OWNER_BY_ENTITY.clear();
        ENTITIES_BY_OWNER.clear();
        SELECTED_BY_PLAYER.clear();
        ServerAdminState.clear();
    }

    /** 归属快照：拥有者 UUID 到其实体 UUID 集合的只读拷贝，给管理员指令看。 */
    public static Map<UUID, Set<UUID>> snapshotOwners() {
        Map<UUID, Set<UUID>> copy = new LinkedHashMap<>();
        ENTITIES_BY_OWNER.forEach((owner, entities) -> copy.put(owner, Set.copyOf(entities)));
        return copy;
    }

    /**
     * 解除某玩家名下所有实体的归属，管理员回收时用。返回实际解除的数量。
     */
    public static int unclaimAll(UUID owner) {
        if (owner == null) return 0;
        Set<UUID> entities = ENTITIES_BY_OWNER.get(owner);
        if (entities == null || entities.isEmpty()) return 0;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        int count = 0;
        for (UUID entityId : List.copyOf(entities)) {
            boolean located = false;
            if (server != null) {
                for (ServerLevel level : server.getAllLevels()) {
                    if (level.getEntity(entityId) instanceof VerityEntity verity) {
                        verity.setOwnerUUID(null);
                        located = true;
                        count++;
                        break;
                    }
                }
            }
            if (!located) {
                // 实体在未加载的区块里改不了归属，但索引里还得摘掉，否则玩家配额放不出来。
                // 记条日志方便排查「归属解除了、实体还挂着主人」这种残留。
                OllamaProxyMod.LOGGER.warn("解除归属时未定位到实体（可能在未加载区块）：entity={}", entityId);
            }
            unregister(entityId);
        }
        return count;
    }

    /** 数所有维度里的实体：{Verity 总数, 无主 Verity, 恶魔总数}。 */
    public static int[] countEntities() {
        int verityTotal = 0;
        int verityUnowned = 0;
        int demons = 0;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return new int[]{0, 0, 0};
        ResourceLocation demonId = ResourceLocation.fromNamespaceAndPath("verity", "verity_demon");
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof VerityEntity verity) {
                    verityTotal++;
                    if (verity.getOwnerUUID().isEmpty()) verityUnowned++;
                } else if (demonId.equals(ForgeRegistries.ENTITY_TYPES.getKey(entity.getType()))) {
                    demons++;
                }
            }
        }
        return new int[]{verityTotal, verityUnowned, demons};
    }

    /** 清理无主 Verity，管理员回收时用。返回清理数量。 */
    public static int purgeUnowned() {
        return purge(false);
    }

    /**
     * 清除 Verity 实体。includeOwned 传 false 只清无主的，true 连有归属的一起清；返回实际清除数量。
     */
    public static int purge(boolean includeOwned) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return 0;
        int removed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            // 先做快照：discard 会改实体表，不能边遍历边改
            List<Entity> snapshot = new ArrayList<>();
            level.getAllEntities().forEach(snapshot::add);
            for (Entity entity : snapshot) {
                if (!(entity instanceof VerityEntity verity)) continue;
                if (!includeOwned && verity.getOwnerUUID().isPresent()) continue;
                unregister(verity.getUUID());
                verity.discard();
                removed++;
            }
        }
        // 清完一个 Verity 都不剩时必须复位原版状态：原版 onPlayerChat 只看 hasSpawned，
        // 标志是 true 但实体没了就会拿 null 去查 AI（NPE）。只清无主也可能清掉最后一个。
        if (removed > 0 && !hasAnyVerity(server)) {
            // 跟原版「把 Verity 收进物品」时的处理一样：discard + hasSpawned=false。
            OriginalVerityStateAccess.setVerityEntity(null);
            OriginalVerityStateAccess.setHasSpawned(false);
            OllamaProxyMod.LOGGER.info("已清除全部 Verity 实体，并复位原版生成状态");
        }
        return removed;
    }

    /** 世界里还有没有 Verity 实体，用来判断要不要复位原版状态。 */
    private static boolean hasAnyVerity(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof VerityEntity) return true;
            }
        }
        return false;
    }
}
