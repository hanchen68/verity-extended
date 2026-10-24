package com.hanchen.veritycustomapi.behavior;

import com.hanchen.veritycustomapi.ConfigManager;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import varmite.verity.entity.verity.VerityEntity;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/**
 * 闲置时在主人附近自己溜达。
 *
 * <p><b>为什么要单独一个 goal</b>：Verity 原来是「跟着走」或者「站着不动」两种状态，
 * 玩家站着不动的时候它就是一尊雕像。这个 goal 补上第三种状态——闲着就自己在旁边慢慢滚，
 * 看起来是活的。
 *
 * <p><b>优先级 3</b>，排在所有「有正经事」的 goal 后面：
 * <ul>
 *   <li>0 = 战斗（{@code FightTargetGoal}）；</li>
 *   <li>1 = 原版跟随（{@code FollowPlacerGoal}）；</li>
 *   <li>2 = 原版看向玩家（{@code LookAtPlayerGoal}）。</li>
 * </ul>
 * Minecraft 的 goal 是按「占用的 flag」抢占的：跟随在跑时 MOVE 被占住，
 * 这个 goal 自然选不上，所以**不需要**在这里再判断「是否跟随中」。
 *
 * <p>另外三条自我保护：
 * <ol>
 *   <li>有攻击目标时不溜达（打架优先，别打着打着跑去散步）；</li>
 *   <li>正在说话时不溜达（说台词时乱跑很出戏）；</li>
 *   <li>主人离太远就停下（否则会自己走丢）。</li>
 * </ol>
 */
public class VerityWanderGoal extends Goal {

    /** 主人超过这个距离就不溜达了，先跟上去再说。 */
    private static final double OWNER_RANGE = 24.0D;
    /** 在主人周围这个半径内挑落点。 */
    private static final double WANDER_RADIUS = 8.0D;
    /** 目标点离当前位置不到这个距离（平方）就懒得动，避免原地抽搐。 */
    private static final double MIN_MOVE_SQR = 4.0D;
    /** 两次溜达之间的停顿，随机取区间内的值，看起来不像机器人。 */
    private static final int MIN_IDLE_TICKS = 80;
    private static final int MAX_IDLE_TICKS = 200;
    private static final double SPEED = 0.9D;

    private final PathfinderMob verity;
    private final Random random = new Random();
    private int cooldown;
    private double targetX;
    private double targetY;
    private double targetZ;

    public VerityWanderGoal(PathfinderMob verity) {
        this.verity = verity;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (!ConfigManager.isVerityWanderEnabled()) return false;
        // 有人骑着的时候绝对不能溜达：骑乘的移动由玩家输入驱动
        // （见 LivingEntityPerchRideMixin 对 travel 的接管），
        // 这时候再让 goal 去 moveTo，两边会抢同一个实体的位移。
        if (verity.isVehicle() || verity.getControllingPassenger() != null) return false;
        // 有活着的目标 = 在打架，别溜达
        if (verity.getTarget() != null && verity.getTarget().isAlive()) return false;
        if (verity instanceof VerityEntity self) {
            // 恶魔形态本来就该盯着玩家，不该悠闲散步
            if (self.isMonstrous()) return false;
            // 说话时站定，跑着说台词很出戏
            if (self.isTalking()) return false;
        }
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        Player owner = resolveOwner();
        if (owner == null || !owner.isAlive()) return false;
        if (verity.level() != owner.level()) return false;
        if (verity.distanceToSqr(owner) > OWNER_RANGE * OWNER_RANGE) return false;
        return pickTarget(owner);
    }

    @Override
    public boolean canContinueToUse() {
        if (!ConfigManager.isVerityWanderEnabled()) return false;
        if (verity.isVehicle() || verity.getControllingPassenger() != null) return false;
        if (verity.getTarget() != null && verity.getTarget().isAlive()) return false;
        if (verity instanceof VerityEntity self && (self.isMonstrous() || self.isTalking())) return false;
        // 主人走远了就放弃这次溜达
        Player owner = resolveOwner();
        if (owner == null) return false;
        if (verity.distanceToSqr(owner) > OWNER_RANGE * OWNER_RANGE) return false;
        return !verity.getNavigation().isDone();
    }

    @Override
    public void start() {
        verity.getNavigation().moveTo(targetX, targetY, targetZ, SPEED);
    }

    @Override
    public void tick() {
        // 走到附近就收工，不必精确踩到点上
        if (verity.getNavigation().isDone()) {
            stop();
        }
    }

    @Override
    public void stop() {
        verity.getNavigation().stop();
        cooldown = MIN_IDLE_TICKS + random.nextInt(Math.max(1, MAX_IDLE_TICKS - MIN_IDLE_TICKS));
    }

    /**
     * 在主人周围随机挑一个落点。
     *
     * @return 挑到了返回 {@code true}；点太近（懒得动）返回 {@code false}
     */
    private boolean pickTarget(Player owner) {
        double angle = random.nextDouble() * Math.PI * 2.0D;
        double distance = 2.0D + random.nextDouble() * (WANDER_RADIUS - 2.0D);
        double x = owner.getX() + Math.cos(angle) * distance;
        double z = owner.getZ() + Math.sin(angle) * distance;
        double y = owner.getY();
        if (verity.distanceToSqr(x, y, z) < MIN_MOVE_SQR) return false;
        targetX = x;
        targetY = y;
        targetZ = z;
        return true;
    }

    /** 优先用原版记录的 owner；没有就退化成最近的玩家（单人世界足够用）。 */
    private Player resolveOwner() {
        if (verity instanceof VerityEntity self) {
            Optional<UUID> ownerId = self.getOwnerUUID();
            if (ownerId.isPresent()) {
                Player player = verity.level().getPlayerByUUID(ownerId.get());
                if (player != null) return player;
            }
        }
        return verity.level().getNearestPlayer(verity, OWNER_RANGE);
    }
}
