package com.hanchen.veritycustomapi.autonomy;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.behavior.VerityMiningJob;
import com.hanchen.veritycustomapi.network.ServerVerityRegistry;
import com.hanchen.veritycustomapi.ride.HeadPerchState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import varmite.verity.entity.verity.VerityEntity;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/** 服务端有限行动执行层；模型给计划，规则只负责安全让位与确定性兜底。 */
public final class VerityAutonomy {
    public enum Intent {
        IDLE, WAIT, FOLLOW, EXPLORE, GUARD, MOVE_TO
    }

    private static final int DECIDE_INTERVAL = 20;
    private static final int MAX_FAILURES = 3;
    private static final Map<VerityEntity, State> STATES =
            Collections.synchronizedMap(new WeakHashMap<>());

    public static final class State {
        public Intent intent = Intent.IDLE;
        public AutonomyPlan plan;
        public long waitUntil;
        public int failures;
        public long changedAt;
        public double exploreX;
        public double exploreY;
        public double exploreZ;
        public boolean hasExploreTarget;
        public boolean suspended;
        public boolean restored;
        public long exploreCooldownUntil;
        public long revision;
        public long nextThinkAt;
        public boolean inFlight;
        public String modelStatus = "未启用";
        public String lastInstruction = "";
        /** 默认关闭，避免未经同意产生背景请求费用。 */
        public boolean enabled;
        public long retryAt;
        public String waitDimension = "";

        void set(Intent next, long now) {
            if (intent != next) {
                intent = next;
                changedAt = now;
                revision++;
                hasExploreTarget = false;
            }
        }
    }

    private VerityAutonomy() {
    }

    /** 首次访问即恢复，命令先于首个 tick 时也不会被旧 NBT 覆盖。 */
    public static State state(VerityEntity verity) {
        synchronized (STATES) {
            State state = STATES.get(verity);
            if (state == null) {
                state = new State();
                STATES.put(verity, state);
                restore(verity, state, verity.level().getGameTime());
                state.restored = true;
            }
            return state;
        }
    }

    public static ServerPlayer owner(VerityEntity verity) {
        if (verity == null || verity.level().isClientSide) return null;
        ServerPlayer player = ServerVerityRegistry.resolveOwner(verity);
        return player != null && ServerVerityRegistry.owns(player, verity)
                && player.level() == verity.level() && player.isAlive()
                && !player.isSpectator() ? player : null;
    }

    /** 采集、站头、骑乘与战斗拥有更高控制权。 */
    public static boolean busy(VerityEntity verity) {
        if (verity == null || verity.level().isClientSide || !verity.isAlive()
                || verity.isRemoved() || verity.isPassenger() || verity.isVehicle() || verity.isMonstrous()) return true;
        LivingEntity target = verity.getTarget();
        ServerPlayer owner = owner(verity);
        return VerityMiningJob.isBusyFor(verity)
                || owner != null && AutonomyActions.isBusy(owner)
                || com.hanchen.veritycustomapi.body.VeritySleep.sleeping(verity)
                || HeadPerchState.isPerched(verity.getUUID())
                || target != null && target.isAlive() && !target.isRemoved();
    }

    public static void tick(VerityEntity verity) {
        if (!(verity.level() instanceof ServerLevel level)) return;
        // Cadence is owned by the server ticker, not this entity's independently offset tickCount.
        AutonomyGoal.install(verity);
        AutonomyActions.poll(verity);
        State state = state(verity);
        long now = level.getGameTime();
        expireWait(verity, state, now);
        if (state.plan != null && !validHere(verity, state.plan)) {
            state.plan = null;
            state.revision++;
            savePlan(verity, null);
        }
        if (verity.isNoAi()) {
            if (state.enabled) state.modelStatus = "NoAI=true：原版Goal与导航被暂停，等待解除表演或其他控制";
            return;
        }
        if (busy(verity)) {
            if (state.enabled) state.modelStatus = verity.isMonstrous() ? "恶魔形态，暂停自主"
                    : verity.isPassenger() || verity.isVehicle() ? "骑乘中，暂停自主"
                    : HeadPerchState.isPerched(verity.getUUID()) ? "站头中，暂停自主" : "战斗或作业中，等待结束";
            state.set(Intent.IDLE, now);
            return;
        }
        if (state.suspended) {
            state.set(Intent.WAIT, now);
            return;
        }
        if (state.plan != null && state.plan.expired(now)) {
            if (state.plan.type == AutonomyPlan.Type.SCOUT) reportFailure(verity);
            state.plan = null;
            state.revision++;
            savePlan(verity, null);
        }
        ServerPlayer owner = owner(verity);
        if (owner == null) {
            if (state.enabled) state.modelStatus = "未找到同维度在线拥有者，暂停自主";
            state.set(state.waitUntil > now || stationaryPlan(state) ? Intent.WAIT : Intent.IDLE, now);
            return;
        }
        boolean playerPlan = state.plan != null && state.plan.source == AutonomyPlan.Source.PLAYER;
        if (!state.enabled && !playerPlan && state.waitUntil <= now) {
            state.set(Intent.IDLE, now);
            return;
        }
        state.set(AutonomyBrain.decide(verity, state, owner, now), now);
        AutonomyAgenda.tick(verity, owner);
        if (state.enabled && state.waitUntil <= now
                && (!playerPlan || !state.lastInstruction.isBlank())) {
            AutonomyDecisionService.tick(verity, owner, state);
        }
    }

    public static LivingEntity nearestThreat(VerityEntity verity) {
        List<Monster> monsters = verity.level().getEntitiesOfClass(Monster.class,
                verity.getBoundingBox().inflate(AutonomyBrain.THREAT_RADIUS));
        LivingEntity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Monster monster : monsters) {
            if (!monster.isAlive() || monster.isRemoved()
                    || com.hanchen.veritycustomapi.combat.MonstrousManager.isGuardian(monster)) continue;
            double distance = verity.distanceToSqr(monster);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = monster;
            }
        }
        return best;
    }

    public static void waitFor(VerityEntity verity, int seconds) {
        if (verity.level().isClientSide) return;
        State state = state(verity);
        state.suspended = false;
        state.waitUntil = verity.level().getGameTime() + Math.max(1, seconds) * 20L;
        state.waitDimension = dimension(verity);
        invalidate(state);
        state.set(Intent.WAIT, verity.level().getGameTime());
        if (!busy(verity)) verity.getNavigation().stop();
        persist(verity, state);
        AutonomyGoal.install(verity);
    }

    /** 暂停仍占 MOVE，但保留 enabled，resume 可恢复已授权的模型决策。 */
    public static void stop(VerityEntity verity) {
        if (verity.level().isClientSide) return;
        State state = state(verity);
        state.suspended = true;
        state.waitUntil = 0L;
        invalidate(state);
        state.set(Intent.WAIT, verity.level().getGameTime());
        if (!busy(verity)) verity.getNavigation().stop();
        persist(verity, state);
        AutonomyGoal.install(verity);
    }

    public static void resume(VerityEntity verity) {
        if (verity.level().isClientSide) return;
        State state = state(verity);
        state.suspended = false;
        state.waitUntil = 0L;
        invalidate(state);
        AutonomyBrain.backToCompanion(verity);
        persist(verity, state);
    }

    public static void enable(VerityEntity verity, boolean enabled) {
        if (verity.level().isClientSide) return;
        State state = state(verity);
        // Explicit on/off replaces a previous hold/rest plan; otherwise on keeps braking forever.
        state.plan = null;
        savePlan(verity, null);
        state.retryAt = 0L;
        state.failures = 0;
        state.enabled = enabled;
        state.suspended = false;
        state.waitUntil = 0L;
        state.nextThinkAt = 0L;
        state.modelStatus = enabled ? "等待模型决策" : "未启用";
        if (enabled && verity.isNoAi()) state.modelStatus = "实体NoAI=true，AI被其他系统暂停；自主已开但导航不会运行";
        invalidate(state);
        state.set(Intent.IDLE, verity.level().getGameTime());
        if (!busy(verity)) verity.getNavigation().stop();
        persist(verity, state);
        AutonomyGoal.install(verity);
    }

    /** 模型返回值必须在服务端主线程、通过 revision 校验后应用。 */
    public static void applyModelPlan(VerityEntity verity, AutonomyPlan plan) {
        if (verity.level().isClientSide || plan == null) return;
        State state = state(verity);
        long now = verity.level().getGameTime();
        if (!state.enabled || state.suspended || state.waitUntil > now
                || owner(verity) == null || busy(verity)) return;
        plan.source = AutonomyPlan.Source.MODEL;
        plan.createdAt = now;
        if (plan.dimension == null || plan.dimension.isBlank()) plan.dimension = dimension(verity);
        if (!validHere(verity, plan) || plan.expired(now)) return;
        setPlan(verity, plan, false);
    }

    static void setPlan(VerityEntity verity, AutonomyPlan plan, boolean playerCommand) {
        State state = state(verity);
        state.plan = plan;
        if (playerCommand) {
            state.suspended = false;
            state.waitUntil = 0L;
        }
        invalidate(state);
        ServerPlayer owner = owner(verity);
        state.set(owner == null || busy(verity) ? Intent.IDLE
                : AutonomyBrain.decide(verity, state, owner, verity.level().getGameTime()),
                verity.level().getGameTime());
        if (!busy(verity)) verity.getNavigation().stop();
        savePlan(verity, plan);
        persist(verity, state);
        AutonomyGoal.install(verity);
    }

    public static boolean wantsMove(VerityEntity verity) {
        if (busy(verity)) return false;
        State state = state(verity);
        long now = verity.level().getGameTime();
        expireWait(verity, state, now);
        if (state.suspended || state.waitUntil > now) return true;
        boolean playerPlan = state.plan != null && state.plan.source == AutonomyPlan.Source.PLAYER;
        if (!state.enabled && !playerPlan) return false;
        if (state.plan != null && (!validHere(verity, state.plan) || state.plan.expired(now))) return false;
        if (owner(verity) == null) return stationaryPlan(state);
        return state.intent != Intent.IDLE;
    }

    private static boolean stationaryPlan(State state) {
        return state.plan != null && (state.enabled || state.plan.source == AutonomyPlan.Source.PLAYER)
                && (state.plan.type == AutonomyPlan.Type.HOLD || state.plan.type == AutonomyPlan.Type.REST);
    }

    public static void finishExplore(VerityEntity verity) {
        State state = state(verity);
        state.hasExploreTarget = false;
        state.set(Intent.WAIT, verity.level().getGameTime());
    }

    /** 真正失败才累加；跨意图保留，退避最高一分钟。 */
    public static void reportFailure(VerityEntity verity) {
        State state = state(verity);
        AutonomyAgenda.movementFeedback(verity, false, "导航未到达目标，执行失败，需要更换路线或重规划");
        if (state.failures < Integer.MAX_VALUE) state.failures++;
        long now = verity.level().getGameTime();
        state.retryAt = now + Math.min(1200L, 20L << Math.min(6, state.failures - 1));
        if (state.plan != null && state.plan.hasPos) {
            AutonomyMemory.record(verity, state.plan.type.name().toLowerCase(Locale.ROOT),
                    state.plan.x, state.plan.y, state.plan.z, false);
            if (state.plan.attempts < Integer.MAX_VALUE) state.plan.attempts++;
        }
        if (state.failures >= MAX_FAILURES) {
            OllamaProxyMod.LOGGER.debug("[自主] 累计 {} 次导航失败，放弃当前目标", state.failures);
            state.plan = null;
            savePlan(verity, null);
        } else {
            savePlan(verity, state.plan);
        }
        state.revision++;
        state.set(Intent.WAIT, now);
        if (!busy(verity)) verity.getNavigation().stop();
        persist(verity, state);
    }

    public static void reportArrived(VerityEntity verity) {
        State state = state(verity);
        AutonomyPlan plan = state.plan;
        if (plan != null && plan.hasPos) {
            AutonomyMemory.record(verity, plan.type.name().toLowerCase(Locale.ROOT),
                    plan.x, plan.y, plan.z, true);
        }
        state.failures = 0;
        state.retryAt = 0L;
        persist(verity, state);
        if (plan != null && plan.type == AutonomyPlan.Type.SCOUT) {
            AutonomyAgenda.movementFeedback(verity, true, "已实际到达侦察位置 " + plan.pos().toShortString());
            state.plan = null;
            savePlan(verity, null);
            state.set(Intent.WAIT, verity.level().getGameTime());
        }
    }

    public static void savePlan(VerityEntity verity, AutonomyPlan plan) {
        if (plan == null) verity.getPersistentData().remove("verity_ext_plan");
        else verity.getPersistentData().put("verity_ext_plan", plan.save());
    }

    static String dimension(VerityEntity verity) {
        return verity.level().dimension().location().toString();
    }

    private static boolean validHere(VerityEntity verity, AutonomyPlan plan) {
        return plan.valid() && dimension(verity).equals(plan.dimension);
    }

    private static void invalidate(State state) {
        state.revision++;
        state.lastInstruction = "";
        // 只让结果失效；请求返回前保留单飞标记，避免重复后台请求与计费。
        state.hasExploreTarget = false;
    }

    private static void expireWait(VerityEntity verity, State state, long now) {
        if (state.waitUntil != 0L && (state.waitUntil <= now
                || !dimension(verity).equals(state.waitDimension))) {
            state.waitUntil = 0L;
            state.waitDimension = "";
            state.revision++;
            if (state.intent == Intent.WAIT && !state.suspended) state.set(Intent.IDLE, now);
            persist(verity, state);
        }
    }

    private static void persist(VerityEntity verity, State state) {
        CompoundTag data = verity.getPersistentData();
        data.putBoolean("verity_ext_autonomy_enabled", state.enabled);
        data.putBoolean("verity_ext_autonomy_suspended", state.suspended);
        data.putLong("verity_ext_autonomy_wait_until", state.waitUntil);
        data.putString("verity_ext_autonomy_wait_dimension", state.waitDimension);
        data.putInt("verity_ext_autonomy_failures", state.failures);
        data.putLong("verity_ext_autonomy_retry_at", state.retryAt);
        data.putString("verity_ext_autonomy_retry_dimension", dimension(verity));
    }

    private static long nonnegativeLong(CompoundTag data, String key) {
        return Double.isFinite(data.getDouble(key)) ? Math.max(0L, data.getLong(key)) : 0L;
    }

    private static void restore(VerityEntity verity, State state, long now) {
        CompoundTag data = verity.getPersistentData();
        state.enabled = data.getBoolean("verity_ext_autonomy_enabled");
        state.suspended = data.getBoolean("verity_ext_autonomy_suspended");
        state.modelStatus = state.enabled ? "等待模型决策" : "未启用";
        state.waitUntil = nonnegativeLong(data, "verity_ext_autonomy_wait_until");
        state.waitDimension = data.getString("verity_ext_autonomy_wait_dimension");
        state.failures = Double.isFinite(data.getDouble("verity_ext_autonomy_failures"))
                ? Math.max(0, data.getInt("verity_ext_autonomy_failures")) : 0;
        if (dimension(verity).equals(data.getString("verity_ext_autonomy_retry_dimension"))) {
            state.retryAt = Math.min(now + 1200L, nonnegativeLong(data, "verity_ext_autonomy_retry_at"));
        }
        if (data.contains("verity_ext_plan")) {
            AutonomyPlan plan = AutonomyPlan.load(data.getCompound("verity_ext_plan"));
            if (plan != null && validHere(verity, plan) && !plan.expired(now)) state.plan = plan;
            else data.remove("verity_ext_plan");
        }
        expireWait(verity, state, now);
        if (state.suspended || state.waitUntil > now) state.intent = Intent.WAIT;
        else if (state.plan != null && (state.enabled || state.plan.source == AutonomyPlan.Source.PLAYER)) {
            ServerPlayer owner = owner(verity);
            if (owner != null) state.intent = AutonomyBrain.decide(verity, state, owner, now);
        }
    }

    public static void clear() {
        AutonomyDecisionService.reset();
        AutonomyAgenda.reset();
        com.hanchen.veritycustomapi.body.VeritySleep.clear();
        STATES.clear();
        AutonomyGoal.reset();
    }

    public static String describe(VerityEntity verity) {
        State state = state(verity);
        if (state.suspended) return "已叫停（/verity-autonomy resume 恢复）；模型：" + state.modelStatus;
        long remaining = Math.max(0L, state.waitUntil - verity.level().getGameTime()) / 20L;
        String intent = switch (state.intent) {
            case IDLE -> "空闲";
            case WAIT -> state.waitUntil > 0L ? "等待中（还剩 " + remaining + " 秒）" : "原地等待";
            case FOLLOW -> "正在跟随主人";
            case EXPLORE -> "活动中";
            case GUARD -> "警戒中";
            case MOVE_TO -> "正在走向计划位置";
        };
        return intent + "；自主模型：" + (state.enabled ? "已开启" : "已关闭")
                + "；" + state.modelStatus + "；当前目标："
                + (state.plan == null ? "安全陪伴兜底" : state.plan.describe())
                + "；" + com.hanchen.veritycustomapi.body.VerityVitals.describe(verity)
                + "；" + com.hanchen.veritycustomapi.body.VeritySleep.describe(verity)
                + "；执行诊断：NoAI=" + verity.isNoAi() + ", navigationDone=" + verity.getNavigation().isDone()
                + ", intent=" + state.intent + ", revision=" + state.revision
                + "；记忆：" + AutonomyMemory.describe(verity) + "；" + AutonomyAgenda.describe(verity);
    }

    public static void halt(Mob mob) {
        mob.getNavigation().stop();
    }
}
