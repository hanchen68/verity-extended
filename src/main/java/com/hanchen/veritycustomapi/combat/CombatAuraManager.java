package com.hanchen.veritycustomapi.combat;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.player.Player;
import varmite.verity.entity.verity.VerityEntity;

import java.util.Comparator;

/**
 * 护主 / 帮忙打怪的共享判定与入口。
 *
 * <p>核心约束：{@code VerityEntity.createAttributes()} 只注册了
 * MAX_HEALTH / MOVEMENT_SPEED / FOLLOW_RANGE，**没有 ATTACK_DAMAGE，也没有 ATTACK_KNOCKBACK**。
 * 原版 {@code Mob#doHurtTarget} 会无条件读这两个属性，所以任何「直接让它打人」的调用
 * 都会抛 {@code IllegalArgumentException: Can't find attribute minecraft:generic.attack_damage}。
 * 属性由 {@link VerityCombatAttributes} 在注册阶段补上；这里只负责判定和设置目标。
 */
public final class CombatAuraManager {
    private CombatAuraManager() {}

    /**
     * 总开关：是否自动护主 / 帮忙打怪。
     *
     * <p>由 {@code /verity-combat on|off} 切换，默认开启。
     * 这是**玩家偏好**而不是存档状态，所以不需要在
     * {@code ServerVerityRegistry.onServerStopping} 里清——换个存档还该保持。
     */
    public static volatile boolean combatAssistEnabled = true;

    /** 主人超过 32 格就不再护主（平方距离）。避免 Verity 被拽着满世界跑。 */
    public static final double MAX_DISTANCE_SQR = 1024.0D;

    /** 补属性时用的攻击力；属性补不上时的兜底路径也用它。 */
    public static final double ATTACK_DAMAGE = 3.0D;

    /**
     * 目标是否合法：排除玩家、主人自己、已驯服宠物、另一只 Verity。
     *
     * @param owner 可为 {@code null}（Verity 自卫时没有「主人」上下文）
     */
    public static boolean isValidTarget(LivingEntity owner, LivingEntity target) {
        if (target == null || !target.isAlive()) return false;
        if (target instanceof Player) return false;
        if (target == owner) return false;
        if (target instanceof TamableAnimal tameable && tameable.isTame()) return false;
        // 别让两只 Verity 互相打起来
        if (target instanceof VerityEntity) return false;
        return true;
    }

    /** 被动护主：主人挨打。 */
    public static void onOwnerHurt(Mob verity, LivingEntity owner, LivingEntity attacker) {
        if (!combatAssistEnabled) return;
        if (!(verity instanceof VerityEntity self) || self.isMonstrous()) return;
        if (owner == null) return;
        if (self.distanceToSqr(owner) > MAX_DISTANCE_SQR) return;
        if (!isValidTarget(owner, attacker)) return;
        self.setTarget(attacker);
    }

    /** 自卫：Verity 自己被怪物打。 */
    public static void onVerityHurt(Mob verity, LivingEntity attacker) {
        if (!combatAssistEnabled) return;
        if (!(verity instanceof VerityEntity self) || self.isMonstrous()) return;
        if (!isValidTarget(null, attacker)) return;
        self.setTarget(attacker);
    }

    /** 主动指令：让 Verity 打附近名字匹配的目标。 */
    public static boolean commandAttack(Mob verity, LivingEntity owner, String targetName) {
        if (!combatAssistEnabled) return false;
        if (!(verity instanceof VerityEntity self) || self.isMonstrous()) return false;

        LivingEntity found = self.level().getEntitiesOfClass(LivingEntity.class,
                        self.getBoundingBox().inflate(16.0D),
                        e -> isValidTarget(owner, e)
                                && (targetName == null || targetName.isBlank()
                                    || e.getName().getString().contains(targetName)))
                .stream()
                .min(Comparator.comparingDouble(self::distanceToSqr))
                .orElse(null);

        if (found == null) return false;
        self.setTarget(found);
        return true;
    }
}
