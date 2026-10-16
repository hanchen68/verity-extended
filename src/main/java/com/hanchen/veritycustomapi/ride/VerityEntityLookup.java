package com.hanchen.veritycustomapi.ride;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.compat.OriginalVerityStateAccess;
import com.hanchen.veritycustomapi.network.ServerVerityRegistry;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import varmite.verity.entity.verity.VerityEntity;

import java.util.Comparator;
import java.util.List;

/** 服务端定位"属于这名玩家的 Verity"：扩展注册表 -> 原版状态引用 -> 附近搜索 -> 全维度兜底。 */
public final class VerityEntityLookup {
    private static final double SEARCH_RADIUS = 48.0D;

    private VerityEntityLookup() {
    }

    public static VerityEntity find(ServerPlayer player) {
        if (player == null) return null;

        // 注册表只是第一优先来源，不能无条件信任：跨存档、死亡、换维度后它可能还留着
        // 一只已经被移除、或不在当前维度的实体。直接返回它会让后续判定全部落在错的实体上，
        // 表现为「重进存档有几率不生效」。所以先校验，不合格就继续往后找。
        VerityEntity registered = ServerVerityRegistry.findForPlayer(player);
        if (registered != null && registered.isAlive()
                && registered.level().dimension().equals(player.level().dimension())) {
            return registered;
        }

        try {
            Object referenced = OriginalVerityStateAccess.getVerityEntity();
            if (referenced instanceof VerityEntity entity && entity.isAlive()
                    && entity.level().dimension().equals(player.level().dimension())
                    && claimableBy(player, entity)) {
                return entity;
            }
        } catch (Throwable ignored) {
        }

        VerityEntity nearby = nearest(player);
        if (nearby != null) return nearby;

        try {
            for (ServerLevel level : player.server.getAllLevels()) {
                if (!level.dimension().equals(player.level().dimension())) continue;
                for (Entity entity : level.getAllEntities()) {
                    if (entity instanceof VerityEntity verity && verity.isAlive()
                            && claimableBy(player, verity)) {
                        return verity;
                    }
                }
            }
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.debug("全维度扫描 Verity 失败", error);
        }
        return null;
    }

    private static boolean claimableBy(ServerPlayer player, VerityEntity verity) {
        java.util.Optional<java.util.UUID> owner = verity.getOwnerUUID();
        return owner.isEmpty() || player.getUUID().equals(owner.get());
    }

    private static VerityEntity nearest(ServerPlayer player) {
        try {
            List<VerityEntity> candidates = player.serverLevel().getEntitiesOfClass(
                    VerityEntity.class, player.getBoundingBox().inflate(SEARCH_RADIUS));
            return candidates.stream()
                    .filter(entity -> entity.isAlive() && claimableBy(player, entity))
                    .min(Comparator.comparingDouble(entity -> entity.distanceToSqr(player)))
                    .orElse(null);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
