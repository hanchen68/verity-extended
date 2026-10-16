package com.hanchen.veritycustomapi.compat;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.config.ModConfig;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import varmite.verity.entity.verity.VerityEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class VerityGrowthState {
    private static final String FEED_COUNT_KEY = "verity_extended_growth_feeds";
    private static final String PROTOCOL = "1";
    private static final Map<UUID, Integer> CLIENT_FEED_COUNTS = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> PENDING_PICKUPS = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> PENDING_BLOCK_PLACEMENTS = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> PENDING_ITEM_PLACEMENTS = new ConcurrentHashMap<>();
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(OllamaProxyMod.MOD_ID, "growth"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals
    );

    private VerityGrowthState() {
    }

    public static void register() {
        CHANNEL.registerMessage(
                0,
                GrowthPacket.class,
                GrowthPacket::encode,
                GrowthPacket::decode,
                GrowthPacket::handle,
                java.util.Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
    }

    public static void onSuccessfulFeed(VerityEntity entity) {
        int currentFeedCount = getFeedCount(entity);
        int feedCount = currentFeedCount == Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : currentFeedCount + 1;
        entity.getPersistentData().putInt(FEED_COUNT_KEY, feedCount);
        sync(entity, feedCount);
    }

    public static float getClientScale(VerityEntity entity) {
        if (!ModConfig.ENABLE_VERITY_GROWTH.get()) return 1.0F;
        return VerityGrowthMath.scaleForFeedCount(CLIENT_FEED_COUNTS.getOrDefault(entity.getUUID(), 0));
    }

    public static double getGroundedYOffset(VerityEntity entity) {
        return VerityGrowthMath.groundedYOffset(getClientScale(entity));
    }

    public static void clearClient() {
        CLIENT_FEED_COUNTS.clear();
    }

    public static void clearServer() {
        PENDING_PICKUPS.clear();
        PENDING_BLOCK_PLACEMENTS.clear();
        PENDING_ITEM_PLACEMENTS.clear();
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID playerId = event.getEntity().getUUID();
        PENDING_PICKUPS.remove(playerId);
        PENDING_BLOCK_PLACEMENTS.remove(playerId);
        PENDING_ITEM_PLACEMENTS.remove(playerId);
    }

    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!(event.getTarget() instanceof VerityEntity entity)) return;
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new GrowthPacket(entity.getUUID(), getFeedCount(entity)));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof ItemEntity itemEntity && isVerityItem(itemEntity.getItem())) {
            return;
        }
        if (!(event.getEntity() instanceof VerityEntity entity)) return;
        if (!event.getLevel().isClientSide() && getFeedCount(entity) == 0) {
            int feedCount = consumePendingPlacement(entity);
            if (feedCount > 0) {
                applyFeedCount(entity, feedCount);
            } else {
                AABB area = entity.getBoundingBox().inflate(1.5D);
                for (ItemEntity itemEntity : event.getLevel().getEntitiesOfClass(ItemEntity.class, area)) {
                    feedCount = readItemFeedCount(itemEntity.getItem());
                    if (feedCount > 0) {
                        applyFeedCount(entity, feedCount);
                        break;
                    }
                }
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getTarget() instanceof VerityEntity entity)) return;
        Integer feedCount = PENDING_PICKUPS.remove(event.getEntity().getUUID());
        // 事件被取消表示交互未真正发生，清理待处理状态并放弃转移成长数据
        if (event.isCanceled() || feedCount == null) return;
        ItemStack heldStack = event.getEntity().getItemInHand(InteractionHand.MAIN_HAND);
        if (isVerityItem(heldStack)) {
            heldStack.getOrCreateTag().putInt(FEED_COUNT_KEY, feedCount);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void captureEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getTarget() instanceof VerityEntity entity)) return;
        if (!event.getItemStack().isEmpty()) return;
        PENDING_PICKUPS.put(event.getEntity().getUUID(), getFeedCount(entity));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        Integer pending = PENDING_BLOCK_PLACEMENTS.remove(event.getEntity().getUUID());
        // 事件被取消表示放置未真正发生，放弃转移成长数据
        if (event.isCanceled()) return;
        int feedCount = pending == null ? 0 : pending;
        if (feedCount <= 0) return;
        net.minecraft.core.BlockPos spawnPos = event.getPos().relative(event.getFace());
        AABB area = new AABB(spawnPos).inflate(1.0D);
        for (VerityEntity entity : event.getLevel().getEntitiesOfClass(VerityEntity.class, area)) {
            if (getFeedCount(entity) == 0) {
                applyFeedCount(entity, feedCount);
                break;
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void captureRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide()) return;
        int feedCount = readItemFeedCount(event.getItemStack());
        if (feedCount > 0) {
            PENDING_BLOCK_PLACEMENTS.put(event.getEntity().getUUID(), feedCount);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getLevel().isClientSide()) return;
        Integer pending = PENDING_ITEM_PLACEMENTS.remove(event.getEntity().getUUID());
        // 事件被取消表示放置未真正发生，放弃转移成长数据
        if (event.isCanceled()) return;
        int feedCount = pending == null ? 0 : pending;
        if (feedCount <= 0) return;
        AABB area = event.getEntity().getBoundingBox().inflate(3.0D);
        for (VerityEntity entity : event.getLevel().getEntitiesOfClass(VerityEntity.class, area)) {
            if (getFeedCount(entity) == 0) {
                applyFeedCount(entity, feedCount);
                break;
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void captureRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getLevel().isClientSide()) return;
        int feedCount = readItemFeedCount(event.getItemStack());
        if (feedCount > 0) {
            PENDING_ITEM_PLACEMENTS.put(event.getEntity().getUUID(), feedCount);
        }
    }

    private static int getFeedCount(VerityEntity entity) {
        return VerityGrowthMath.boundFeedCount(entity.getPersistentData().getInt(FEED_COUNT_KEY));
    }

    private static int readItemFeedCount(ItemStack stack) {
        if (!isVerityItem(stack) || stack.getTag() == null) return 0;
        return VerityGrowthMath.boundFeedCount(stack.getTag().getInt(FEED_COUNT_KEY));
    }

    private static boolean isVerityItem(ItemStack stack) {
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return itemId != null
                && "verity".equals(itemId.getNamespace())
                && "verity_item".equals(itemId.getPath());
    }

    private static int consumePendingPlacement(VerityEntity entity) {
        for (net.minecraft.world.entity.player.Player player : entity.level().players()) {
            if (player.distanceToSqr(entity) > 16.0D) continue;
            Integer feedCount = PENDING_BLOCK_PLACEMENTS.remove(player.getUUID());
            if (feedCount == null) {
                feedCount = PENDING_ITEM_PLACEMENTS.remove(player.getUUID());
            }
            if (feedCount != null && feedCount > 0) {
                return feedCount;
            }
        }
        return 0;
    }

    private static void applyFeedCount(VerityEntity entity, int feedCount) {
        int boundedCount = VerityGrowthMath.boundFeedCount(feedCount);
        entity.getPersistentData().putInt(FEED_COUNT_KEY, boundedCount);
        sync(entity, boundedCount);
    }

    private static void sync(VerityEntity entity, int feedCount) {
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> entity),
                new GrowthPacket(entity.getUUID(), feedCount));
    }

    private record GrowthPacket(UUID entityId, int feedCount) {
        private static void encode(GrowthPacket packet, FriendlyByteBuf buffer) {
            buffer.writeUUID(packet.entityId());
            buffer.writeVarInt(packet.feedCount());
        }

        private static GrowthPacket decode(FriendlyByteBuf buffer) {
            return new GrowthPacket(buffer.readUUID(), buffer.readVarInt());
        }

        private static void handle(GrowthPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> CLIENT_FEED_COUNTS.put(
                    packet.entityId(),
                    VerityGrowthMath.boundFeedCount(packet.feedCount())
            ));
            context.setPacketHandled(true);
        }
    }
}
