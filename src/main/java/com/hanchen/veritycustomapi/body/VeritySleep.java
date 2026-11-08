package com.hanchen.veritycustomapi.body;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import varmite.verity.entity.verity.VerityEntity;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 她的作息：天黑了找床睡，天亮了起床。
 *
 * <p>生物不会真的「睡床」，所以这里是行为上的模拟：走到床边、换成睡觉姿势、
 * 停止自主活动，天亮（或被打断）再站起来。目的不是复刻原版睡眠机制，
 * 而是让「一起过夜」这件事成立。
 */
public final class VeritySleep {

    /** 夜晚区间（游戏刻）。 */
    private static final long NIGHT_START = 12500L;
    private static final long NIGHT_END = 23000L;
    private static final double SEARCH_RADIUS = 8.0D;
    private static final double BED_REACH = 2.5D;

    private static final Map<VerityEntity, BlockPos> SLEEPING =
            Collections.synchronizedMap(new WeakHashMap<>());

    private VeritySleep() {
    }

    public static boolean sleeping(VerityEntity verity) {
        return SLEEPING.containsKey(verity);
    }

    public static boolean isNight(VerityEntity verity) {
        long time = verity.level().getDayTime() % 24000L;
        return time >= NIGHT_START && time < NIGHT_END;
    }

    /** 每 40 tick 调一次。 */
    public static void tick(VerityEntity verity) {
        if (!(verity.level() instanceof ServerLevel level)) return;
        if (sleeping(verity)) {
            if (!isNight(verity) || verity.isRemoved() || !verity.isAlive()
                    || verity.getHealth() < verity.getMaxHealth() * 0.3F) {
                wake(verity);
            }
            return;
        }
        if (!isNight(verity) || !verity.isAlive()) return;
        if (verity.getHealth() < verity.getMaxHealth() * 0.5F) return; // 受伤先保命
        if (com.hanchen.veritycustomapi.autonomy.VerityAutonomy.busy(verity)) return;

        BlockPos bed = findBed(level, verity);
        if (bed == null) return;
        double distance = verity.distanceToSqr(bed.getX() + 0.5D, bed.getY() + 0.5D, bed.getZ() + 0.5D);
        if (distance > BED_REACH * BED_REACH) {
            verity.getNavigation().moveTo(bed.getX() + 0.5D, bed.getY(), bed.getZ() + 0.5D, 1.0D);
            return;
        }
        verity.getNavigation().stop();
        verity.setPose(Pose.SLEEPING);
        SLEEPING.put(verity, bed);
        verity.getPersistentData().putBoolean("verity_ext_sleeping", true);
    }

    public static void wake(VerityEntity verity) {
        if (SLEEPING.remove(verity) == null) return;
        if (verity.getPose() == Pose.SLEEPING) verity.setPose(Pose.STANDING);
        verity.getPersistentData().remove("verity_ext_sleeping");
    }

    public static String describe(VerityEntity verity) {
        if (sleeping(verity)) return "正在睡觉";
        return isNight(verity) ? "夜里" : "白天";
    }

    private static BlockPos findBed(ServerLevel level, VerityEntity verity) {
        BlockPos origin = verity.blockPosition();
        int radius = (int) SEARCH_RADIUS;
        for (BlockPos pos : BlockPos.betweenClosed(
                origin.offset(-radius, -3, -radius), origin.offset(radius, 3, radius))) {
            if (!level.hasChunkAt(pos)) continue;
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof BedBlock)) continue;
            if (state.getValue(BedBlock.PART) != BedPart.HEAD) continue;
            return pos.immutable();
        }
        return null;
    }

    /** 重载后清姿势，避免一直躺着。 */
    public static void clear() {
        for (VerityEntity verity : SLEEPING.keySet()) {
            if (verity != null && verity.getPose() == Pose.SLEEPING) verity.setPose(Pose.STANDING);
        }
        SLEEPING.clear();
    }
}
