package com.hanchen.veritycustomapi.network;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.compat.OriginalVerityStateAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.common.util.ITeleporter;
import varmite.verity.entity.verity.VerityEntity;

/**
 * 服务端侧的导航动作：跟随、停止跟随、叫到身边，由 /verity-nav 指令调用，只用服务端类型。
 * 这些是服务端权威状态，客户端改不了，必须服务端执行。专用服务器上只动执行者自己名下的
 * Verity（归属由 ServerVerityRegistry 校验）；单机没有归属概念，才回退到原版全局实体。
 */
public final class ServerNavigationService {
    private static final double SEARCH_RADIUS = 48.0D;

    public static final String ACTION_FOLLOW = "follow";
    public static final String ACTION_STOP = "stop";
    public static final String ACTION_COME = "come";

    private ServerNavigationService() {
    }

    public static boolean isKnownAction(String action) {
        return ACTION_FOLLOW.equals(action) || ACTION_STOP.equals(action) || ACTION_COME.equals(action);
    }

    /**
     * 受理一次导航动作。
     *
     * @return 是否已受理；未知动作或玩家没有服务端时为 false
     */
    public static boolean apply(ServerPlayer player, String action) {
        if (player == null || !isKnownAction(action)) return false;
        MinecraftServer server = player.getServer();
        if (server == null) return false;
        server.execute(() -> applyOnServerThread(player, action));
        return true;
    }

    private static void applyOnServerThread(ServerPlayer player, String action) {
        switch (action) {
            case ACTION_FOLLOW -> {
                VerityEntity verity = resolveVerity(player);
                if (verity == null) {
                    message(player, "§7【导航】没有找到你的 Verity");
                    return;
                }
                if (!verity.level().dimension().equals(player.level().dimension())) {
                    verity = teleportTo(verity, player.serverLevel(),
                            player.getX() + 1.5D, player.getY(), player.getZ() + 1.5D);
                    if (verity == null) {
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
                message(player, "§b好，我跟着你～");
                OllamaProxyMod.LOGGER.info("Verity 跟随已开启（服务端）：玩家={}",
                        player.getGameProfile().getName());
            }
            case ACTION_STOP -> {
                // 跟随开关在原版是个全局静态字段（一个世界只有一个 Verity），
                // 所以先确认玩家确实有自己的 Verity，否则谁都能把别人的跟随关掉。
                if (resolveVerity(player) == null) {
                    message(player, "§7【导航】没有找到你的 Verity");
                    return;
                }
                OriginalVerityStateAccess.setFollowingPlayer(false);
                message(player, "§b好，我不跟着了");
                OllamaProxyMod.LOGGER.info("Verity 跟随已关闭（服务端）：玩家={}",
                        player.getGameProfile().getName());
            }
            case ACTION_COME -> {
                VerityEntity verity = resolveVerity(player);
                if (verity == null) {
                    message(player, "§7【导航】没有找到你的 Verity");
                    return;
                }
                verity.getNavigation().stop();
                if (teleportTo(verity, player.serverLevel(),
                        player.getX() + 1.5D, player.getY(), player.getZ() + 1.5D) == null) {
                    message(player, "§7【导航】传送失败，请稍后再试");
                    return;
                }
                message(player, "§b我过来啦～");
                OllamaProxyMod.LOGGER.info("Verity 已传送到玩家身边（服务端）：玩家={}",
                        player.getGameProfile().getName());
            }
            default -> {
            }
        }
    }

    /**
     * 找这名玩家能操作的 Verity：先按归属取，服务器上必须这样，否则等于允许指挥别人的实体。
     * 单机/局域网里原版就一个全局实体、没有归属概念，才回退到原版链路。
     */
    private static VerityEntity resolveVerity(ServerPlayer player) {
        VerityEntity owned = ServerVerityRegistry.findForPlayer(player);
        if (owned != null) return owned;
        MinecraftServer server = player.getServer();
        if (server == null || !server.isSingleplayer()) return null;
        try {
            Object referenced = OriginalVerityStateAccess.getVerityEntity();
            if (referenced instanceof VerityEntity entity && entity.isAlive()) {
                return entity;
            }
        } catch (Throwable ignored) {
        }
        try {
            return player.serverLevel()
                    .getEntitiesOfClass(VerityEntity.class, player.getBoundingBox().inflate(SEARCH_RADIUS))
                    .stream()
                    .filter(Entity::isAlive)
                    .min(java.util.Comparator.comparingDouble(entity -> entity.distanceToSqr(player)))
                    .orElse(null);
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.debug("扫描 Verity 实体失败: {}", error.toString());
            return null;
        }
    }

    private static VerityEntity teleportTo(VerityEntity entity, ServerLevel targetLevel,
                                           double x, double y, double z) {
        if (entity.level().dimension().equals(targetLevel.dimension())) {
            entity.teleportTo(x, y, z);
            return entity;
        }
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

    private static void message(ServerPlayer player, String text) {
        player.sendSystemMessage(Component.literal(text));
    }
}
