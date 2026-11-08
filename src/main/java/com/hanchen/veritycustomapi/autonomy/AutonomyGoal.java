package com.hanchen.veritycustomapi.autonomy;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import varmite.verity.entity.verity.VerityEntity;

import java.util.ArrayList;
import java.util.EnumSet;

/** 占用 MOVE 的服务端计划控制器；战斗优先于控制器，原版跟随低于控制器。 */
public class AutonomyGoal extends Goal {
    private static final double SPEED = 0.9D;
    private static final long TIMEOUT_TICKS = 200L;
    private final VerityEntity verity;
    private long startedAt;
    private long revision;
    private boolean stationary;

    public AutonomyGoal(VerityEntity verity) {
        this.verity = verity;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    /** 检查实体自身的 Goal 集合去重，区块重载的新实例不会被旧 UUID 拦住。 */
    public static void install(VerityEntity verity) {
        if (verity.level().isClientSide || verity.isRemoved()) return;
        boolean installed = false;
        for (WrappedGoal wrapped : verity.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof AutonomyGoal) installed = true;
            if (wrapped.getGoal() instanceof com.hanchen.veritycustomapi.combat.VerityCombatGoal)
                return; // 两个都装过
        }
        if (!installed) {
            try { verity.goalSelector.addGoal(0, new com.hanchen.veritycustomapi.combat.VerityCombatGoal(verity)); }
            catch (RuntimeException ignored) { }
        } else return;
        try {
            // 战斗为 0、原版跟随原为 1。将跟随移到 2，给暂停/等待控制留出真实空档。
            for (WrappedGoal wrapped : new ArrayList<>(verity.goalSelector.getAvailableGoals())) {
                Goal goal = wrapped.getGoal();
                if (wrapped.getPriority() == 1 && goal.getFlags().contains(Flag.MOVE)
                        && goal.getClass().getSimpleName().contains("Follow")) {
                    verity.goalSelector.removeGoal(goal);
                    verity.goalSelector.addGoal(2, new FollowGate(verity, goal));
                }
            }
            verity.goalSelector.addGoal(1, new AutonomyGoal(verity));
        } catch (RuntimeException error) {
            OllamaProxyMod.LOGGER.debug("[自主] 安装控制 Goal 失败: {}", error.toString());
        }
    }

    /** GoalSelector may evaluate canUse before priority arbitration; suppress side effects first. */
    private static final class FollowGate extends Goal {
        private final VerityEntity entity;
        private final Goal delegate;
        FollowGate(VerityEntity entity, Goal delegate) {
            this.entity = entity; this.delegate = delegate; setFlags(delegate.getFlags());
        }
        @Override public boolean canUse() {
            return !VerityAutonomy.busy(entity) && !VerityAutonomy.wantsMove(entity) && delegate.canUse();
        }
        @Override public boolean canContinueToUse() {
            return !VerityAutonomy.busy(entity) && !VerityAutonomy.wantsMove(entity) && delegate.canContinueToUse();
        }
        @Override public void start() { delegate.start(); }
        @Override public void stop() { if (!VerityAutonomy.busy(entity)) delegate.stop(); }
        @Override public void tick() { delegate.tick(); }
        @Override public boolean isInterruptable() { return delegate.isInterruptable(); }
        @Override public boolean requiresUpdateEveryTick() { return delegate.requiresUpdateEveryTick(); }
    }

    /** 安装记录在实体自身，无跨存档静态 UUID 集合需要清理。 */
    public static void reset() {
    }

    @Override
    public boolean canUse() {
        return VerityAutonomy.wantsMove(verity);
    }

    @Override
    public boolean canContinueToUse() {
        return VerityAutonomy.wantsMove(verity) && revision == VerityAutonomy.state(verity).revision;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        VerityAutonomy.State state = VerityAutonomy.state(verity);
        revision = state.revision;
        startedAt = verity.level().getGameTime();
        stationary = shouldStay(state, startedAt);
        verity.getNavigation().stop();
        if (!stationary) beginPath(state);
    }

    private boolean shouldStay(VerityAutonomy.State state, long now) {
        return state.suspended || state.waitUntil > now || state.retryAt > now
                || state.intent == VerityAutonomy.Intent.WAIT;
    }

    private void beginPath(VerityAutonomy.State state) {
        boolean moving;
        switch (state.intent) {
            case FOLLOW, GUARD -> {
                ServerPlayer owner = VerityAutonomy.owner(verity);
                if (owner == null) return;
                if (verity.distanceToSqr(owner) <= 36.0D) {
                    complete(state);
                    return;
                }
                moving = verity.getNavigation().moveTo(owner, SPEED);
            }
            case MOVE_TO -> {
                AutonomyPlan plan = state.plan;
                if (plan == null || !plan.valid() || !plan.hasPos
                        || !VerityAutonomy.dimension(verity).equals(plan.dimension)) return;
                if (plan.distanceSqrTo(verity.getX(), verity.getY(), verity.getZ()) <= AutonomyBrain.ARRIVE_SQR) {
                    complete(state);
                    return;
                }
                moving = verity.getNavigation().moveTo(plan.x, plan.y, plan.z, SPEED);
            }
            default -> {
                stationary = true;
                return;
            }
        }
        if (!moving) fail();
    }

    @Override
    public void tick() {
        if (VerityAutonomy.busy(verity)) return;
        VerityAutonomy.State state = VerityAutonomy.state(verity);
        long now = verity.level().getGameTime();
        if (revision != state.revision) return;
        if (stationary || shouldStay(state, now)) {
            verity.getNavigation().stop();
            return;
        }
        ServerPlayer owner = VerityAutonomy.owner(verity);
        if (owner == null) return;
        if (now % 40L == 0L) verity.getLookControl().setLookAt(owner, 20.0F, 20.0F);
        boolean arrived = switch (state.intent) {
            case FOLLOW, GUARD -> verity.distanceToSqr(owner) <= 36.0D;
            case MOVE_TO -> state.plan != null && state.plan.hasPos
                    && state.plan.distanceSqrTo(verity.getX(), verity.getY(), verity.getZ()) <= AutonomyBrain.ARRIVE_SQR;
            default -> false;
        };
        if (arrived) {
            complete(state);
            return;
        }
        if (now - startedAt >= TIMEOUT_TICKS || verity.getNavigation().isDone()) {
            fail();
            return;
        }
        if ((state.intent == VerityAutonomy.Intent.FOLLOW || state.intent == VerityAutonomy.Intent.GUARD)
                && now % 20L == 0L && !verity.getNavigation().moveTo(owner, SPEED)) fail();
    }

    private void complete(VerityAutonomy.State state) {
        stationary = true;
        verity.getNavigation().stop();
        VerityAutonomy.reportArrived(verity);
        // HOLD 到达后仍保持 MOVE；SCOUT 的成功记录由 reportArrived 写入并切回陪伴。
        if (state.plan != null && state.plan.type == AutonomyPlan.Type.HOLD) {
            state.set(VerityAutonomy.Intent.WAIT, verity.level().getGameTime());
        } else if (state.intent == VerityAutonomy.Intent.FOLLOW || state.intent == VerityAutonomy.Intent.GUARD) {
            state.set(VerityAutonomy.Intent.WAIT, verity.level().getGameTime());
        }
    }

    private void fail() {
        stationary = true;
        VerityAutonomy.reportFailure(verity);
    }

    @Override
    public void stop() {
        // 被采集、骑乘、战斗抢占时不得清掉它们刚接手的路径。
        if (!VerityAutonomy.busy(verity)) verity.getNavigation().stop();
        stationary = false;
    }
}
