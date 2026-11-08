package com.hanchen.veritycustomapi.combat;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.Vec3;
import varmite.verity.entity.verity.VerityEntity;

import java.util.EnumSet;

/**
 * 主动战斗：选目标 → 靠近 → 挥击 → 打不过就退。
 *
 * <p><b>为什么要自己写</b>：原版 {@code VerityEntity} 只注册了跟随/看玩家/四处张望三个 Goal，
 * 没有任何战斗 Goal；{@code MeleeAttackGoal} 又依赖 {@code getTarget()} 被外部设置。
 * 所以这里自己找目标、自己维护冷却和撤退。
 *
 * <p>三条边界：
 * <ul>
 *   <li>只打 {@link Monster}，不打玩家、动物、村民；</li>
 *   <li>血量低于 35% 就后撤，不做自杀式冲锋；</li>
 *   <li>主人明确叫停（{@code suspended}）时不主动开战。</li>
 * </ul>
 */
public class VerityCombatGoal extends Goal {

    private static final double SEARCH_RADIUS = 12.0D;
    private static final double CHASE_RADIUS = 20.0D;
    private static final double ATTACK_REACH = 3.2D;
    private static final int ATTACK_COOLDOWN = 20;
    private static final float DAMAGE = 4.0F;

    private final VerityEntity verity;
    private LivingEntity target;
    private int cooldown;
    private int retreatTicks;

    public VerityCombatGoal(VerityEntity verity) {
        this.verity = verity;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        if (verity.level().isClientSide || !verity.isAlive()) return false;
        if (verity.getHealth() < verity.getMaxHealth() * 0.35F) return false;
        if (com.hanchen.veritycustomapi.autonomy.VerityAutonomy.state(verity).suspended) return false;
        target = findTarget();
        return target != null;
    }

    @Override
    public boolean canContinueToUse() {
        if (target == null || !target.isAlive() || target.isRemoved()) return false;
        if (verity.getHealth() < verity.getMaxHealth() * 0.25F) return false;
        if (verity.distanceToSqr(target) > CHASE_RADIUS * CHASE_RADIUS) return false;
        if (com.hanchen.veritycustomapi.autonomy.VerityAutonomy.state(verity).suspended) return false;
        return true;
    }

    @Override
    public void start() {
        cooldown = 0;
        retreatTicks = 0;
    }

    @Override
    public void stop() {
        target = null;
        if (!verity.level().isClientSide) verity.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (target == null) return;
        verity.getLookControl().setLookAt(target, 30.0F, 30.0F);

        // 血少就退：朝反方向走 20 tick，别硬顶
        if (verity.getHealth() < verity.getMaxHealth() * 0.35F) {
            retreatTicks = 20;
        }
        if (retreatTicks > 0) {
            retreatTicks--;
            Vec3 away = verity.position().subtract(target.position());
            if (away.lengthSqr() < 1.0E-4D) away = new Vec3(1.0D, 0.0D, 0.0D);
            Vec3 spot = verity.position().add(away.normalize().scale(4.0D));
            verity.getNavigation().moveTo(spot.x, spot.y, spot.z, 1.2D);
            return;
        }

        double distance = verity.distanceToSqr(target);
        if (distance > ATTACK_REACH * ATTACK_REACH) {
            verity.getNavigation().moveTo(target, 1.25D);
            return;
        }
        verity.getNavigation().stop();
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        cooldown = ATTACK_COOLDOWN;
        target.hurt(verity.damageSources().mobAttack(verity), DAMAGE);
        verity.doHurtTarget(target);
    }

    private LivingEntity findTarget() {
        LivingEntity best = null;
        double bestDistance = SEARCH_RADIUS * SEARCH_RADIUS;
        for (Monster monster : verity.level().getEntitiesOfClass(Monster.class,
                verity.getBoundingBox().inflate(SEARCH_RADIUS))) {
            if (monster == null || !monster.isAlive() || monster.isRemoved()) continue;
            if (com.hanchen.veritycustomapi.combat.MonstrousManager.isGuardian(monster)) continue;
            double distance = verity.distanceToSqr(monster);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = monster;
            }
        }
        return best;
    }
}
