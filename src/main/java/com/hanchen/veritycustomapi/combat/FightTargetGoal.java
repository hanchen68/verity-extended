package com.hanchen.veritycustomapi.combat;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import varmite.verity.entity.verity.VerityEntity;

import java.util.EnumSet;
import java.util.List;
import java.util.Random;

/**
 * 让 Verity 追着 {@code Target} 打。
 *
 * <p>三个要点，改之前先读完：
 * <ol>
 *   <li><b>优先级必须是 0。</b> Verity 自己的 FollowPlacerGoal 是 1、LookAtPlayerGoal 是 2，
 *       数字越小优先级越高。挂在 2 会跟 LookAtPlayerGoal 抢 LOOK，
 *       而且 FollowPlacerGoal 一旦在跑就把 MOVE 占住了，战斗 goal 根本起不来
 *       ——表现就是「Verity 站原地挨打不还手」。</li>
 *   <li><b>距离判断方向别写反。</b> 大于攻击距离才 {@code moveTo} 追过去；
 *       写成「近了才追、远了停」会让它一直退。</li>
 *   <li><b>绝不直接调 {@code doHurtTarget()}。</b> 那会读 ATTACK_DAMAGE 属性，
 *       Verity 原本没有这个属性，属性没补上时直接抛异常。统一走 {@link #hurtTarget}。</li>
 * </ol>
 */
public class FightTargetGoal extends Goal {
    private static final double CHASE_SPEED = 1.2D;
    /** 2.5 格：原版近战判定大致就是这个量级，2.0 格会经常「贴脸打不到」。 */
    private static final double ATTACK_REACH_SQR = 6.25D;
    /** 超过 32 格就放弃，和护主距离保持一致。 */
    private static final double GIVE_UP_SQR = CombatAuraManager.MAX_DISTANCE_SQR;
    private static final int ATTACK_COOLDOWN_TICKS = 20;
    /** 狠话 5 秒最多一句，否则每打一下刷一行聊天栏。 */
    private static final int TALK_COOLDOWN_TICKS = 100;

    private final PathfinderMob verity;
    private int cooldown;
    private int talkCooldown;
    private static final Random RAND = new Random();
    private static final String[] QUIPS = {
        "别碰我主人！",
        "滚开！",
        "你敢打他？",
        "我要揍扁你！",
        "离他远点！",
        "找打是吧？"
    };

    public FightTargetGoal(PathfinderMob verity) {
        this.verity = verity;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity target = verity.getTarget();
        return isEngageable(target) && verity.distanceToSqr(target) <= GIVE_UP_SQR;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public void tick() {
        LivingEntity target = verity.getTarget();
        if (!isEngageable(target)) {
            verity.setTarget(null);
            return;
        }
        if (cooldown > 0) cooldown--;
        if (talkCooldown > 0) talkCooldown--;

        // 战斗中每帧强制切严肃表情：其他渲染/表情逻辑会把 variant 改回去，这里要持续按住
        if (verity instanceof VerityEntity self) {
            String variant = self.getVariant();
            if (variant == null || !variant.equals("serious_1")) {
                self.setVariant("serious_1");
            }
        }

        verity.getLookControl().setLookAt(target, 30.0F, 30.0F);

        double distSqr = verity.distanceToSqr(target);
        if (distSqr > ATTACK_REACH_SQR) {
            verity.getNavigation().moveTo(target, CHASE_SPEED);
        } else {
            verity.getNavigation().stop();
            if (cooldown <= 0) {
                hurtTarget(target);
                cooldown = ATTACK_COOLDOWN_TICKS;
                if (talkCooldown <= 0) {
                    sayQuip();
                    talkCooldown = TALK_COOLDOWN_TICKS;
                }
            }
        }
    }

    /** 给最近的玩家发一条固定狠话（AI 不可用时的兜底）。 */
    private void sayQuip() {
        Player nearest = nearestPlayer();
        if (nearest == null) return;
        nearest.sendSystemMessage(Component.literal("<Verity> " + QUIPS[RAND.nextInt(QUIPS.length)]));
    }

    /** 32 格内最近的玩家；没有就返回 {@code null}。 */
    private Player nearestPlayer() {
        List<Player> players = verity.level().getEntitiesOfClass(Player.class,
                verity.getBoundingBox().inflate(32.0D));
        Player nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (Player player : players) {
            double distance = verity.distanceToSqr(player);
            if (distance < nearestDist) {
                nearestDist = distance;
                nearest = player;
            }
        }
        return nearest;
    }

    /**
     * 按目标最大血量的 8% 算伤害，最低 3 点。
     *
     * <p>走 {@code target.hurt(mobAttack)} 而不是 {@code doHurtTarget()}：
     * 前者是「外部施加伤害」，不需要 Verity 具备 ATTACK_DAMAGE 属性；
     * 伤害源仍然归属 Verity，死亡消息也正常。
     */
    private void hurtTarget(LivingEntity target) {
        float damage = (float) Math.max(CombatAuraManager.ATTACK_DAMAGE, target.getMaxHealth() * 0.08D);
        target.hurt(verity.damageSources().mobAttack(verity), damage);
    }

    private boolean isEngageable(LivingEntity target) {
        if (!CombatAuraManager.combatAssistEnabled) return false;
        if (target == null || !target.isAlive() || target.isRemoved()) return false;
        if (target == verity) return false;
        if (target instanceof Player) return false;
        if (target instanceof VerityEntity) return false;
        if (target instanceof TamableAnimal tameable && tameable.isTame()) return false;
        // 恶魔形态不索敌：那时候它自己就是威胁，护主逻辑没有意义
        if (verity instanceof VerityEntity self && self.isMonstrous()) return false;
        return target.level() == verity.level();
    }

    @Override
    public void start() {
        super.start();
        if (verity instanceof VerityEntity self) {
            self.setVariant("serious_1");
            sayCombatQuip(self);
        }
    }

    /**
     * 调原版 AI 生成一句符合人设的狠话；失败就走固定句子。
     *
     * <p>🔴 <b>必须走 {@code queryAiAsync}</b>：{@code Goal.tick()/start()} 跑在服务端主线程上，
     * 而原版 {@code AiManager.queryAI} 内部是一次同步 HTTP 请求（最长几十秒）。
     * 直接调会把整个服务端卡住——表现是「怪物一打主人，全服卡死几秒」。
     * 这类事件报告一律用 {@code OriginalVerityChatBridge.queryAiAsync}（它自己起守护线程）。
     */
    private void sayCombatQuip(VerityEntity self) {
        Player nearest = nearestPlayer();
        if (!(nearest instanceof ServerPlayer serverPlayer)) return;
        String prompt = "<SYSTEM> 你正在保护主人，现在有怪物攻击了主人。"
            + "说一句简短的、符合你性格的狠话来吓退敌人，比如警告它离开主人。"
            + "只说一句，不要解释，不要提到 AI、代码、游戏机制。";
        try {
            com.hanchen.veritycustomapi.compat.OriginalVerityChatBridge
                    .queryAiAsync(self, serverPlayer, prompt);
        } catch (Throwable ignored) {
            // AI 不可用（没配 API、代理异常…）不能影响战斗本身，发固定句子即可
            sayQuip();
        }
    }

    @Override
    public void stop() {
        verity.getNavigation().stop();
        verity.setTarget(null);
        // 打完了切回开心表情
        if (verity instanceof VerityEntity self) {
            self.setVariant("happy");
        }
    }
}
