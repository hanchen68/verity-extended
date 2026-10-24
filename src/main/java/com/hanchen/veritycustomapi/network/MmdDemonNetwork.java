package com.hanchen.veritycustomapi.network;

import com.hanchen.veritycustomapi.util.Silent;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

import com.hanchen.veritycustomapi.OllamaProxyMod;

/**
 * MMD 模块自己的一条 SimpleChannel，管客户端「按需生成恶魔」的请求和服务端回执。
 * 协议独立于原版 Verity 的网络层，本 mod 自己维护。
 */
public final class MmdDemonNetwork {
    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(OllamaProxyMod.MOD_ID, "mmd_demon"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    public static final int MSG_SPAWN_DEMON_REQ = 1;
    public static final int MSG_SPAWN_DEMON_ACK = 2;
    public static final int MSG_REMOVE_DEMON_REQ = 3;
    public static final int MSG_KILL_ALL_DEMONS_REQ = 4;

    /** 恶魔实体 ID，生成和删除都拿它比对。 */
    private static final ResourceLocation DEMON_ID = ResourceLocation.fromNamespaceAndPath("verity", "verity_demon");
    /** 单维度展示用恶魔的数量上限，防客户端刷包无限生成实体。 */
    private static final int MAX_DISPLAY_DEMONS_PER_LEVEL = 32;
    /** 同一玩家两次召唤的冷却（毫秒），防连点。 */
    private static final long SPAWN_COOLDOWN_MS = 3000L;
    /** 单玩家同时持有的展示恶魔上限。 */
    private static final int MAX_DEMONS_PER_PLAYER = 6;
    /** 玩家 -> 上次召唤时间戳。 */
    private static final java.util.concurrent.ConcurrentHashMap<java.util.UUID, Long> LAST_SPAWN_AT =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 玩家 -> 名下展示恶魔实体 id。 */
    private static final java.util.concurrent.ConcurrentHashMap<java.util.UUID, java.util.Set<Integer>> OWNED_DEMONS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private MmdDemonNetwork() {}

    /** 数某维度里有多少恶魔实体。 */
    private static int countDemonEntities(net.minecraft.server.level.ServerLevel level) {
        int count = 0;
        for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
            if (DEMON_ID.equals(net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(entity.getType()))) {
                count++;
            }
        }
        return count;
    }

    public static void register() {
        CHANNEL.registerMessage(MSG_SPAWN_DEMON_REQ,
                MmdSpawnDemonPayload.class,
                MmdSpawnDemonPayload::write,
                MmdSpawnDemonPayload::read,
                MmdDemonNetwork::handleSpawnRequest);
        CHANNEL.registerMessage(MSG_SPAWN_DEMON_ACK,
                MmdSpawnDemonPayload.class,
                MmdSpawnDemonPayload::write,
                MmdSpawnDemonPayload::read,
                MmdDemonNetwork::handleSpawnAck);
        CHANNEL.registerMessage(MSG_REMOVE_DEMON_REQ,
                MmdRemoveDemonPayload.class,
                MmdRemoveDemonPayload::write,
                MmdRemoveDemonPayload::read,
                MmdDemonNetwork::handleRemoveRequest);
        CHANNEL.registerMessage(MSG_KILL_ALL_DEMONS_REQ,
                KillAllDemonsPayload.class,
                KillAllDemonsPayload::write,
                KillAllDemonsPayload::read,
                MmdDemonNetwork::handleKillAllDemonsRequest);
    }

    private static void handleSpawnRequest(MmdSpawnDemonPayload payload,
                                           java.util.function.Supplier<net.minecraftforge.network.NetworkEvent.Context> ctxSupplier) {
        net.minecraftforge.network.NetworkEvent.Context ctx = ctxSupplier.get();
        net.minecraft.server.level.ServerPlayer player = ctx.getSender();
        ctx.setPacketHandled(true);
        if (player == null) return;
        ctx.enqueueWork(() -> doSpawn(payload, player));
    }

    private static void doSpawn(MmdSpawnDemonPayload payload,
                                net.minecraft.server.level.ServerPlayer player) {
        net.minecraft.server.level.ServerLevel level = player.serverLevel();
        ResourceLocation entityId = payload.entityKey();
        // 权限校验，和 remove / kill_all 两个处理器口径一致。
        // MMD 在多人服上本就是禁用的，这个包只该由单机/局域网主机发出来；
        // 不拦的话任何客户端都能刷包，在别人服务器上凭空刷实体。
        if (!player.getServer().isSingleplayer() && !player.hasPermissions(2)) {
            OllamaProxyMod.LOGGER.warn("MMD 生成恶魔被拒绝：玩家 {} 没有权限（非单机且非 OP）",
                    player.getGameProfile().getName());
            return;
        }
        // 冷却限流，连点生成按钮时服务端只认第一次
        long nowMs = System.currentTimeMillis();
        Long lastSpawn = LAST_SPAWN_AT.get(player.getUUID());
        if (lastSpawn != null && nowMs - lastSpawn < SPAWN_COOLDOWN_MS) {
            try {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "§e[MMD] 手速太快啦，歇一下再召唤。"));
            } catch (Throwable ignored) { Silent.ignore("MmdDemonNetwork", ignored); }
            return;
        }
        LAST_SPAWN_AT.put(player.getUUID(), nowMs);
        // 单维度上限。这个包是 C2S 的，谁都能发，
        // 不设上限刷包就能无限生成实体把服务端拖垮。
        int existing = countDemonEntities(level);
        if (existing >= MAX_DISPLAY_DEMONS_PER_LEVEL) {
            OllamaProxyMod.LOGGER.warn("MMD 生成恶魔被拒绝：维度 {} 内恶魔实体已达上限 {}",
                    level.dimension().location(), MAX_DISPLAY_DEMONS_PER_LEVEL);
            try {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "§c[MMD] 本维度展示用恶魔已达上限（" + MAX_DISPLAY_DEMONS_PER_LEVEL + "），请先清理再生成。"));
            } catch (Throwable ignored) { Silent.ignore("MmdDemonNetwork", ignored); }
            return;
        }
        // 单玩家上限。先把已经消失的实体 id 清掉，免得计数虚高
        java.util.Set<Integer> owned = OWNED_DEMONS.computeIfAbsent(player.getUUID(),
                k -> java.util.concurrent.ConcurrentHashMap.newKeySet());
        owned.removeIf(id -> id == null || level.getEntity(id) == null);
        if (owned.size() >= MAX_DEMONS_PER_PLAYER) {
            try {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "§e[MMD] 你已经有 " + owned.size() + " 个恶魔了，先送走几个再召唤。"));
            } catch (Throwable ignored) { Silent.ignore("MmdDemonNetwork", ignored); }
            return;
        }
        // 多重 fallback：先按 ID 精确找，找不到就在 ENTITY_TYPES 里按 demon 模糊匹配
        java.util.Optional<net.minecraft.world.entity.EntityType<?>> resolved =
                net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getHolder(entityId)
                        .map(h -> (net.minecraft.world.entity.EntityType<?>) h.value());
        if (resolved.isEmpty()) {
            // 模糊匹配：挑一个 id 里带 demon 或 verity 的 EntityType
            for (net.minecraft.world.entity.EntityType<?> t :
                    net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getValues()) {
                String rl = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(t).toString();
                if (rl.contains("demon") || rl.contains("verity")) {
                    resolved = java.util.Optional.of(t);
                    break;
                }
            }
        }
        if (resolved.isEmpty()) {
            OllamaProxyMod.LOGGER.error("MMD 生成恶魔失败：未找到 verity:verity_demon 实体（可能原版 Verity 模组未加载或命名空间不一致）");
            try {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "§c[MMD] §r未找到恶魔实体类型。原版 Verity 模组未生效，请检查 mods 目录。"));
            } catch (Throwable ignored) { Silent.ignore("MmdDemonNetwork", ignored); }
            return;
        }
        final net.minecraft.world.entity.EntityType<?> type = resolved.get();
        OllamaProxyMod.LOGGER.info("MMD 服务器收到按需生成恶魔请求：entityKey={} 解析为 {}",
                entityId, net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(type));
        net.minecraft.world.entity.MobSpawnType spawnType = net.minecraft.world.entity.MobSpawnType.COMMAND;
        net.minecraft.world.phys.Vec3 spawnPos = payload.position();
        // 落在玩家正前方 3 格，别贴着玩家；高度再往下找个安全的落点
        net.minecraft.core.BlockPos bp = net.minecraft.core.BlockPos.containing(
                spawnPos.x + player.getLookAngle().x * 3.0,
                spawnPos.y,
                spawnPos.z + player.getLookAngle().z * 3.0);
        net.minecraft.core.BlockPos safe = level.getBlockState(bp).isAir()
                && level.getBlockState(bp.below()).isSolid() ? bp
                : level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bp);
        net.minecraft.world.phys.Vec3 finalPos = net.minecraft.world.phys.Vec3.atBottomCenterOf(safe);
        net.minecraft.world.entity.Entity created = type.spawn(level, safe, spawnType);
        if (created instanceof net.minecraft.world.entity.LivingEntity living) {
            living.setPos(finalPos.x, finalPos.y, finalPos.z);
            if (living instanceof net.minecraft.world.entity.Mob mob) {
                // MMD 展示实体一律冻结 AI，不吃客户端配置，也不参与剧情追逐和恐怖导演。
                mob.setNoAi(true);
                mob.setTarget(null);
            }
            CHANNEL.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                    new MmdSpawnDemonPayload(created.getId(), finalPos));
            OllamaProxyMod.LOGGER.info("MMD 按需生成恶魔实体 id={} at {}", created.getId(), finalPos);
            owned.add(created.getId());
        } else {
            OllamaProxyMod.LOGGER.error("MMD 按需生成恶魔失败：type.spawn 返回了 null 或非 LivingEntity：entityId={} class={}",
                    entityId, created == null ? "null" : created.getClass().getName());
            try {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "§c[MMD] §r恶魔生成失败。请把 latest.log 末段贴回开发者。"));
            } catch (Throwable ignored) { Silent.ignore("MmdDemonNetwork", ignored); }
        }
    }

    private static void handleSpawnAck(MmdSpawnDemonPayload payload,
                                       java.util.function.Supplier<net.minecraftforge.network.NetworkEvent.Context> ctxSupplier) {
        net.minecraftforge.network.NetworkEvent.Context ctx = ctxSupplier.get();
        ctx.enqueueWork(() -> ClientMmdAckQueue.accept(payload));
        ctx.setPacketHandled(true);
    }

    private static void handleRemoveRequest(MmdRemoveDemonPayload payload,
                                             java.util.function.Supplier<net.minecraftforge.network.NetworkEvent.Context> ctxSupplier) {
        net.minecraftforge.network.NetworkEvent.Context ctx = ctxSupplier.get();
        net.minecraft.server.level.ServerPlayer player = ctx.getSender();
        ctx.setPacketHandled(true);
        if (player == null) return;
        ctx.enqueueWork(() -> {
            // 这个包能跨维度 discard 实体，必须校验来源。旧实现既不读 getSender 也不判权限，
            // 任何客户端都能删服务器上的任意实体。单机是直调 serverSideRemoveDemons，不走这条。
            if (!player.getServer().isSingleplayer() && !player.hasPermissions(2)) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                        "§c只有服务器管理员可以删除恶魔实体"));
                return;
            }
            serverSideRemoveDemons(payload.entityIds());
        });
    }

    private static void handleKillAllDemonsRequest(KillAllDemonsPayload payload,
                                                    java.util.function.Supplier<net.minecraftforge.network.NetworkEvent.Context> ctxSupplier) {
        net.minecraftforge.network.NetworkEvent.Context ctx = ctxSupplier.get();
        net.minecraft.server.level.ServerPlayer player = ctx.getSender();
        ctx.setPacketHandled(true);
        if (player == null) return;
        ctx.enqueueWork(() -> {
            if (!player.getServer().isSingleplayer() && !player.hasPermissions(2)) {
                player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§c只有服务器管理员可以杀死所有恶魔"));
                return;
            }
            int killed = serverSideKillAllDemons(player.getServer(), player);
            OWNED_DEMONS.remove(player.getUUID());
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("§a已杀死 " + killed + " 个恶魔实体"));
        });
    }

    public static int serverSideKillAllDemons(net.minecraft.server.MinecraftServer server,
                                               net.minecraft.server.level.ServerPlayer source) {
        if (server == null) return 0;
        ResourceLocation demonId = DEMON_ID;
        java.util.List<net.minecraft.world.entity.LivingEntity> demons = new java.util.ArrayList<>();
        for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
            for (net.minecraft.world.entity.Entity entity : level.getAllEntities()) {
                ResourceLocation typeId = net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
                if (demonId.equals(typeId) && entity instanceof net.minecraft.world.entity.LivingEntity living && living.isAlive()) {
                    demons.add(living);
                }
            }
        }
        int killed = 0;
        for (net.minecraft.world.entity.LivingEntity demon : demons) {
            if (demon.hurt(demon.damageSources().playerAttack(source), Float.MAX_VALUE)) killed++;
        }
        OllamaProxyMod.LOGGER.info("快捷菜单杀死所有恶魔：请求者={} 找到={} 成功={}",
                source.getGameProfile().getName(), demons.size(), killed);
        return killed;
    }

    /**
     * 删掉指定 id 列表里的实体，整合服和 SimpleChannel 都走这里。
     * 只删还在世界里的恶魔。
     */
    public static void serverSideRemoveDemons(java.util.List<Integer> entityIds) {
        if (entityIds == null || entityIds.isEmpty()) return;
        int removed = 0;
        int skipped = 0;
        net.minecraft.server.MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        // 只准删恶魔。这个入口网络包会调（得防滥用），MMD 清理也会调；
        // 加了类型过滤后，就算别的包被伪造也删不掉非恶魔实体。
        ResourceLocation demonId = DEMON_ID;
        for (Integer id : entityIds) {
            if (id == null) continue;
            // 所有维度的 ServerLevel 都扫一遍，保证删干净
            for (net.minecraft.server.level.ServerLevel level : server.getAllLevels()) {
                net.minecraft.world.entity.Entity e = level.getEntity(id);
                if (e == null) continue;
                if (!demonId.equals(net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(e.getType()))) {
                    skipped++;
                    continue;
                }
                e.discard();
                removed++;
            }
        }
        OllamaProxyMod.LOGGER.info("MMD 服务器侧删除恶魔：请求={} 成功={} 非恶魔跳过={}",
                entityIds.size(), removed, skipped);
    }

    /**
     * 单机下的直连入口：客户端拿到 ServerPlayer 就直接调，不走 SimpleChannel，
     * 绕开 integrated server 已知的派发坑。联机时改由 {@link #handleSpawnRequest} 走 SimpleChannel。
     */
    public static void serverSideSpawnDemon(net.minecraft.server.level.ServerPlayer player,
                                             ResourceLocation entityKey,
                                             net.minecraft.world.phys.Vec3 position) {
        doSpawn(new MmdSpawnDemonPayload(-1, position, entityKey), player);
    }
}