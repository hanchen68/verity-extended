package com.hanchen.veritycustomapi.client;

import com.hanchen.veritycustomapi.util.Silent;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.compat.OriginalVerityStateAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.util.ITeleporter;
import varmite.verity.entity.verity.VerityEntity;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Verity 的移动控制：跟随玩家、去指定坐标、瞬移到身边。
 * 跟随复用原版 followPlayer 开关；去坐标靠自己寻路，卡太久就传送兜底。
 * 状态以服务端为准，只在能拿到集成服务器时生效。文案上，Verity 能做的动作让它自己说，
 * 做不到的才用灰色【导航】提示。
 */
public final class VerityNavigationService {
    /** 判定「到达」的距离。 */
    private static final double ARRIVE_DISTANCE = 3.0D;
    /** 拿不到原版实体引用时的兜底搜索半径。 */
    private static final double SEARCH_RADIUS = 48.0D;
    /** 原版 FollowPlacerGoal 的启动距离：站得比这更近时它本来就站着不动。 */
    private static final double FOLLOW_TRIGGER_DISTANCE = 8.0D;
    /** 无进展多久后传送兜底。 */
    private static final long STUCK_TELEPORT_MS = 8000L;
    /** 驱动寻路的间隔，避免每 tick 都重设路径。 */
    private static final long MOVE_INTERVAL_MS = 500L;
    private static final double MOVE_SPEED = 1.1D;
    /** 没说具体目标时的默认叫法。 */
    private static final String DEFAULT_LABEL = "目的地";

    /** 当前导航目标，维度固定为发出命令时玩家所在的维度。 */
    public record Target(double x, double y, double z, String label, long setAt,
                         ResourceKey<Level> dimension) {
    }

    private static final AtomicReference<Target> TARGET = new AtomicReference<>();
    private static volatile long lastMoveAt;
    private static volatile long lastProgressAt;
    private static volatile double lastDistance = Double.MAX_VALUE;

    private VerityNavigationService() {
    }

    public static Target current() {
        return TARGET.get();
    }

    /** 让 Verity 跟随玩家。 */
    public static void startFollow() {
        TARGET.set(null);
        runOnServer(server -> {
            ServerPlayer player = resolveServerPlayer(server);
            if (player == null) {
                return;
            }
            VerityEntity entity = resolveEntity(server, player);
            if (entity != null && !entity.level().dimension().equals(player.level().dimension())) {
                entity = teleportAcrossDimensions(entity, player.serverLevel(),
                        player.getX() + 1.5D, player.getY(), player.getZ() + 1.5D);
                if (entity == null) {
                    OriginalVerityStateAccess.setFollowingPlayer(false);
                    message(player, "§7【导航】我没能来到你所在的世界，请稍后再试");
                    return;
                }
            }
            if (!OriginalVerityStateAccess.setFollowingPlayer(true)) {
                message(player, "§7【导航】当前 Verity 版本没有跟随开关，这条命令不会生效");
                OllamaProxyMod.LOGGER.warn("Verity 跟随开关写入失败：原版字段不可用");
                return;
            }
            message(player, "§b好，我跟着你～" + followHint(player, entity));
            OllamaProxyMod.LOGGER.info("Verity 跟随已开启");
        });
    }

    /** 让 Verity 前往指定坐标。 */
    public static void navigateTo(double x, double y, double z, String label) {
        runOnServer(server -> {
            OriginalVerityStateAccess.setFollowingPlayer(false);
            ServerPlayer player = resolveServerPlayer(server);
            if (player == null) {
                return;
            }
            VerityEntity entity = resolveEntity(server, player);
            if (entity == null) {
                TARGET.set(null);
                message(player, "§7【导航】没有找到 Verity，这次导航没有开始");
                return;
            }
            TARGET.set(new Target(x, y, z,
                    label == null || label.isBlank() ? DEFAULT_LABEL : label,
                    System.currentTimeMillis(), player.level().dimension()));
            lastDistance = Double.MAX_VALUE;
            lastProgressAt = System.currentTimeMillis();
            message(player, "§b好，我这就去 " + String.format("%.0f, %.0f, %.0f", x, y, z) + " 那边～");
            OllamaProxyMod.LOGGER.info("Verity 导航目标已设置: {} {} {} dimension={}",
                    x, y, z, player.level().dimension().location());
        });
    }

    /** 把 Verity 直接叫到身边。 */
    public static void comeToPlayer() {
        TARGET.set(null);
        runOnServer(server -> {
            OriginalVerityStateAccess.setFollowingPlayer(false);
            ServerPlayer player = resolveServerPlayer(server);
            if (player == null) {
                return;
            }
            VerityEntity entity = resolveEntity(server, player);
            if (entity == null) {
                message(player, "§7【导航】没有找到 Verity，这次没有传送");
                return;
            }
            entity.getNavigation().stop();
            entity = teleportTo(entity, player.serverLevel(),
                    player.getX() + 1.5D, player.getY(), player.getZ() + 1.5D);
            if (entity == null) {
                message(player, "§7【导航】传送失败，请稍后再试");
                return;
            }
            message(player, "§b我过来啦～");
            OllamaProxyMod.LOGGER.info("Verity 已传送到玩家身边");
        });
    }

    /** 停止一切移动（跟随与导航）。 */
    public static void stopAll() {
        TARGET.set(null);
        runOnServer(server -> {
            OriginalVerityStateAccess.setFollowingPlayer(false);
            ServerPlayer player = resolveServerPlayer(server);
            if (player == null) {
                return;
            }
            VerityEntity entity = resolveEntity(server, player);
            if (entity != null) {
                entity.getNavigation().stop();
            }
            message(player, "§b好，那我在这儿等你～");
            OllamaProxyMod.LOGGER.info("Verity 移动已停止");
        });
    }

    public static boolean isFollowing() {
        return OriginalVerityStateAccess.isFollowingPlayer();
    }

    /** 客户端每 tick 驱动一次寻路；到达或卡住时自行结束。 */
    public static void onClientTick(Minecraft minecraft) {
        Target target = TARGET.get();
        if (target == null || minecraft.player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastMoveAt < MOVE_INTERVAL_MS) {
            return;
        }
        lastMoveAt = now;
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            return;
        }
        server.execute(() -> drive(server, target, now));
    }

    private static void drive(MinecraftServer server, Target target, long now) {
        ServerPlayer player = resolveServerPlayer(server);
        if (player == null) {
            return;
        }
        ServerLevel targetLevel = server.getLevel(target.dimension());
        if (targetLevel == null) {
            if (TARGET.compareAndSet(target, null)) {
                message(player, "§7【导航】目的地所在的世界没有加载，导航已停止");
            }
            return;
        }
        VerityEntity entity = resolveEntity(server, player);
        if (entity == null) {
            if (TARGET.compareAndSet(target, null)) {
                message(player, "§7【导航】没有找到 Verity，导航已停止");
            }
            return;
        }
        if (!entity.level().dimension().equals(target.dimension())) {
            entity = teleportAcrossDimensions(entity, targetLevel, target.x(), target.y(), target.z());
            if (entity == null) {
                if (TARGET.compareAndSet(target, null)) {
                    message(player, "§7【导航】跨世界传送失败，导航已停止");
                }
                return;
            }
            lastDistance = Double.MAX_VALUE;
            lastProgressAt = now;
        }
        double remaining = Math.sqrt(entity.distanceToSqr(target.x(), target.y(), target.z()));
        if (remaining <= ARRIVE_DISTANCE) {
            entity.getNavigation().stop();
            if (TARGET.compareAndSet(target, null)) {
                message(player, "§b到「" + target.label() + "」啦～");
            }
            return;
        }
        entity.getNavigation().moveTo(target.x(), target.y(), target.z(), MOVE_SPEED);
        if (remaining < lastDistance - 0.5D) {
            lastDistance = remaining;
            lastProgressAt = now;
            return;
        }
        if (now - lastProgressAt > STUCK_TELEPORT_MS) {
            VerityEntity moved = teleportTo(entity, targetLevel, target.x(), target.y(), target.z());
            if (moved != null) {
                moved.getNavigation().stop();
                lastProgressAt = now;
                OllamaProxyMod.LOGGER.info("Verity 寻路无进展，已传送到导航目标");
            } else if (TARGET.compareAndSet(target, null)) {
                message(player, "§7【导航】寻路和传送都失败了，导航已停止");
            }
        }
    }

    /**
     * 取要操控的 Verity 实体。
     *
     * 优先用原版自己维护的引用（{@code VerityState.verityEntity}），它不受距离限制；
     * 拿不到就先在玩家附近找，再扫所有已加载维度——免得实体稍远或在另一个世界时功能就废了。
     */
    private static VerityEntity resolveEntity(MinecraftServer server, ServerPlayer player) {
        try {
            Object referenced = OriginalVerityStateAccess.getVerityEntity();
            if (referenced instanceof VerityEntity entity && entity.isAlive()) {
                return entity;
            }
        } catch (Throwable ignored) { Silent.ignore("VerityNavigationService", ignored); }
        VerityEntity nearby = nearestVerity(player);
        if (nearby != null) {
            return nearby;
        }
        try {
            for (ServerLevel level : server.getAllLevels()) {
                for (Entity entity : level.getAllEntities()) {
                    if (entity instanceof VerityEntity verity && verity.isAlive()) {
                        OriginalVerityStateAccess.setVerityEntity(verity);
                        return verity;
                    }
                }
            }
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.debug("扫描 Verity 实体失败: {}", error.toString());
        }
        return null;
    }

    /** 跟随成功后的补充说明，解释「为什么看起来没动」。 */
    private static String followHint(ServerPlayer player, VerityEntity entity) {
        if (entity == null) {
            return "§7（我好像还没在附近，走近一点再看看）";
        }
        Optional<UUID> owner = entity.getOwnerUUID();
        if (owner.isPresent() && !owner.get().equals(player.getUUID())) {
            return "§7（你好像不是我的主人，可能跟不动）";
        }
        if (entity.distanceToSqr(player) < FOLLOW_TRIGGER_DISTANCE * FOLLOW_TRIGGER_DISTANCE) {
            return "§7（你先走远一点，我就跟上来了）";
        }
        return "";
    }

    private static VerityEntity teleportTo(VerityEntity entity, ServerLevel targetLevel,
                                            double x, double y, double z) {
        if (entity.level().dimension().equals(targetLevel.dimension())) {
            entity.teleportTo(x, y, z);
            return entity;
        }
        return teleportAcrossDimensions(entity, targetLevel, x, y, z);
    }

    private static VerityEntity teleportAcrossDimensions(VerityEntity entity, ServerLevel targetLevel,
                                                          double x, double y, double z) {
        try {
            Entity moved = entity.changeDimension(targetLevel, new ITeleporter() {
                @Override
                public Entity placeEntity(Entity source, ServerLevel currentWorld, ServerLevel destinationWorld,
                                          float yaw, java.util.function.Function<Boolean, Entity> repositionEntity) {
                    Entity result = repositionEntity.apply(false);
                    result.teleportTo(x, y, z);
                    return result;
                }
            });
            if (moved instanceof VerityEntity verity) {
                OriginalVerityStateAccess.setVerityEntity(verity);
                return verity;
            }
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.warn("Verity 跨维度传送失败: {}", error.toString());
        }
        return null;
    }

    private static void runOnServer(Consumer<MinecraftServer> action) {
        Minecraft minecraft = Minecraft.getInstance();
        MinecraftServer server = minecraft.getSingleplayerServer();
        if (server == null) {
            notifyClient("§7【导航】这个功能只在你自己的存档里能用（单人世界或自己开房）");
            return;
        }
        try {
            server.execute(() -> action.accept(server));
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.warn("导航请求执行失败: {}", error.toString());
        }
    }

    private static ServerPlayer resolveServerPlayer(MinecraftServer server) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return null;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            for (ServerPlayer serverPlayer : players) {
                if (serverPlayer.getUUID().equals(minecraft.player.getUUID())) {
                    return serverPlayer;
                }
            }
        }
        return players.get(0);
    }

    private static VerityEntity nearestVerity(ServerPlayer player) {
        try {
            List<VerityEntity> candidates = player.serverLevel().getEntitiesOfClass(
                    VerityEntity.class, player.getBoundingBox().inflate(SEARCH_RADIUS));
            return candidates.stream()
                    .min(Comparator.comparingDouble(entity -> entity.distanceToSqr(player)))
                    .orElse(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void message(ServerPlayer player, String text) {
        try {
            player.displayClientMessage(Component.literal(text), true);
        } catch (Throwable ignored) { Silent.ignore("VerityNavigationService", ignored); }
    }

    private static void notifyClient(String text) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(Component.literal(text), true);
            }
        });
    }
}
