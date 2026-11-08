package com.hanchen.veritycustomapi.autonomy;

import net.minecraft.server.level.ServerPlayer;
import varmite.verity.entity.verity.VerityEntity;

/** 将模型或玩家计划映射成意图；无模型计划时只做确定性的安全跟随/等待。 */
public final class AutonomyBrain {
    static final double FOLLOW_DISTANCE = 10.0D;
    static final double THREAT_RADIUS = 12.0D;
    static final double ARRIVE_SQR = 9.0D;

    private AutonomyBrain() {
    }

    public static VerityAutonomy.Intent decide(VerityEntity verity, VerityAutonomy.State state,
                                               ServerPlayer owner, long now) {
        if (state.suspended || state.waitUntil > now || state.retryAt > now) {
            return VerityAutonomy.Intent.WAIT;
        }
        AutonomyPlan plan = state.plan;
        if (plan == null || plan.expired(now)) {
            // No implicit follow while pursuing an independent agenda: choose the next action yourself.
            if (state.enabled && AutonomyAgenda.executing(verity)) return VerityAutonomy.Intent.WAIT;
            return companionIntent(verity, owner);
        }
        return switch (plan.type) {
            case REST -> VerityAutonomy.Intent.WAIT;
            case HOLD -> plan.distanceSqrTo(verity.getX(), verity.getY(), verity.getZ()) <= ARRIVE_SQR
                    ? VerityAutonomy.Intent.WAIT : VerityAutonomy.Intent.MOVE_TO;
            // 即使已经在目标点也交给 Goal 确认完成，不能跳过成功记忆。
            case SCOUT -> VerityAutonomy.Intent.MOVE_TO;
            case COMPANION -> companionIntent(verity, owner);
        };
    }

    private static VerityAutonomy.Intent companionIntent(VerityEntity verity, ServerPlayer owner) {
        return owner != null && verity.distanceToSqr(owner) > FOLLOW_DISTANCE * FOLLOW_DISTANCE
                ? VerityAutonomy.Intent.FOLLOW : VerityAutonomy.Intent.WAIT;
    }

    public static void holdHere(VerityEntity verity) {
        if (verity.level().isClientSide) return;
        AutonomyPlan plan = new AutonomyPlan();
        plan.type = AutonomyPlan.Type.HOLD;
        plan.source = AutonomyPlan.Source.PLAYER;
        plan.x = verity.getX();
        plan.y = verity.getY();
        plan.z = verity.getZ();
        plan.hasPos = true;
        plan.dimension = VerityAutonomy.dimension(verity);
        plan.createdAt = verity.level().getGameTime();
        plan.reason = "主人要求守点";
        VerityAutonomy.setPlan(verity, plan, true);
    }

    /** 玩家明确要求侦察时，沿主人的水平朝向选择一个确定的近点。 */
    public static void scoutAround(VerityEntity verity) {
        if (verity.level().isClientSide) return;
        ServerPlayer owner = VerityAutonomy.owner(verity);
        if (owner == null || VerityAutonomy.busy(verity)) return;
        var direction = owner.getLookAngle();
        double length = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        double dx = length > 1.0E-6D ? direction.x / length : 1.0D;
        double dz = length > 1.0E-6D ? direction.z / length : 0.0D;
        double x = owner.getX() + dx * 8.0D;
        double y = owner.getY();
        double z = owner.getZ() + dz * 8.0D;
        if (AutonomyMemory.isBlocked(verity, x, y, z)) return;
        AutonomyPlan plan = new AutonomyPlan();
        plan.type = AutonomyPlan.Type.SCOUT;
        plan.source = AutonomyPlan.Source.PLAYER;
        plan.x = x;
        plan.y = y;
        plan.z = z;
        plan.hasPos = true;
        plan.dimension = VerityAutonomy.dimension(verity);
        plan.createdAt = verity.level().getGameTime();
        plan.expiresAt = plan.createdAt + 20L * 45L;
        plan.reason = "主人明确要求附近侦察";
        if (!verity.level().hasChunkAt(plan.pos())) return;
        var path = verity.getNavigation().createPath(plan.pos(), 0);
        if (path == null || !path.canReach()) return;
        VerityAutonomy.setPlan(verity, plan, true);
    }

    public static void backToCompanion(VerityEntity verity) {
        if (verity.level().isClientSide) return;
        AutonomyPlan plan = new AutonomyPlan();
        plan.type = AutonomyPlan.Type.COMPANION;
        plan.source = AutonomyPlan.Source.SELF;
        plan.dimension = VerityAutonomy.dimension(verity);
        plan.createdAt = verity.level().getGameTime();
        plan.reason = "确定性安全陪伴";
        VerityAutonomy.setPlan(verity, plan, false);
    }
}
