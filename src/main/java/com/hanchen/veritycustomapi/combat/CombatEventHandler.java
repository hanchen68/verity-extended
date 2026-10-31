package com.hanchen.veritycustomapi.combat;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.network.ServerVerityRegistry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import varmite.verity.entity.verity.VerityEntity;

/**
 * 护主 / 帮忙打怪的服务端接线。
 *
 * <p>三条设计约束，改之前先读完：
 * <ul>
 *   <li><b>找 Verity 必须用 {@link ServerVerityRegistry#findForPlayer}。</b>
 *       原版的 {@code VerityState.verityEntity} 是「最后一只进世界的 Verity」这种全局单例，
 *       多人或换维度时会找错实体，表现为「打了 A 玩家的 Verity 却是 B 的去还手」。</li>
 *   <li><b>挂 goal 必须去重。</b> {@code EntityJoinLevelEvent} 会随区块加载反复触发，
 *       不去重会越挂越多——同一个 Verity 身上叠了十几个战斗 goal，
 *       每 tick 被调用十几次，肉眼可见地卡。</li>
 *   <li><b>开关关掉时也照挂 goal</b>，只在 {@link FightTargetGoal#canUse()} 里拦。
 *       否则先 {@code /verity-combat off} 再 {@code on}，
 *       已经存在的 Verity 永远不会有战斗 AI（goal 是构造期挂的）。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = OllamaProxyMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CombatEventHandler {
    private CombatEventHandler() {}

    /**
     * 战斗 goal 的优先级。
     *
     * <p>必须是 0：Verity 自己的 FollowPlacerGoal 是 1、LookAtPlayerGoal 是 2。
     * 挂在 2 会跟 LookAtPlayerGoal 抢 LOOK，而且 FollowPlacerGoal 在跑时
     * MOVE 已被占住，战斗 goal 起不来（表现是站原地挨打不还手）。
     */
    private static final int COMBAT_GOAL_PRIORITY = 0;

    /**
     * 服务端 tick 的 END 阶段：把替身的目标按回召唤时锁定的那只。
     *
     * <p>必须放 END —— 生物的目标 goal 在 {@code serverAiStep} 里跑，
     * 而实体自己的 tick 注入在它之前，改完会被 AI 覆盖（实测替身会回头追玩家）。
     */
    @SubscribeEvent
    public static void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
        MonstrousManager.forceTargets(event.getServer());
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof VerityEntity verity)) return;
        if (hasFightGoal(verity)) return;
        verity.goalSelector.addGoal(COMBAT_GOAL_PRIORITY, new FightTargetGoal(verity));
    }

    private static boolean hasFightGoal(Mob mob) {
        for (WrappedGoal wrapped : mob.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof FightTargetGoal) return true;
        }
        return false;
    }

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide()) return;

        LivingEntity attacker = event.getSource().getEntity() instanceof LivingEntity living
                ? living : null;
        // 环境伤害（摔落、火焰、虚空）没有实体来源，或者自己打自己，都不该触发护主
        if (attacker == null || attacker == victim) return;

        if (victim instanceof ServerPlayer player) {
            VerityEntity verity = ServerVerityRegistry.findAnywhere(player);
            // 觉醒不要求 Verity 在场：她可能不在加载范围，但主人快死了就得派恶魔
            if (tryAwaken(player, verity, attacker, false)) return;
            if (verity != null && verity.isAlive()) {
                CombatAuraManager.onOwnerHurt(verity, player, attacker);
            }
            return;
        }

        if (victim instanceof VerityEntity verity) {
            CombatAuraManager.onVerityHurt(verity, attacker);
        }
    }

    /**
     * 残血深渊觉醒的判定。
     *
     * <p>两种触发：攻击者是 Boss 档（最大生命 ≥ 200，满血也觉醒），
     * 或者攻击者是精英档（≥ 100）且主人血量 ≤ 40%。
     */
    private static boolean tryAwaken(ServerPlayer player, VerityEntity verity,
                                     LivingEntity attacker, boolean lethal) {
        if (!com.hanchen.veritycustomapi.config.ModConfig.VERITY_DEMON_AWAKENING.get()) return false;
        if (MonstrousManager.isAwakened(player.getUUID())) return false;

        float attackerHealth = attacker.getMaxHealth();
        float ratio = player.getMaxHealth() <= 0.0F ? 1.0F
                : player.getHealth() / player.getMaxHealth();
        boolean boss = attackerHealth >= 200.0F;
        boolean eliteAndLow = attackerHealth >= 100.0F && ratio <= 0.4F;
        boolean desperate = ratio <= 0.2F && attacker instanceof Enemy;
        if (!boss && !eliteAndLow && !desperate && !lethal) return false;

        return MonstrousManager.transform(player, verity, attacker);
    }
    /**
     * 伤害口径统一在这里改，别散到各个攻击点：
     *
     * <ol>
     *   <li><b>Verity 打怪 = 目标最大生命 8%，保底 3 点</b>：普通怪两下就没，Boss 也不会刮痧；</li>
     *   <li><b>恶魔单次最多吃 10 点</b>：500 血也扛不住高倍率整合包的一刀；</li>
     *   <li><b>玩家打恶魔放行</b>：原版有「亲手杀掉恶魔 → 谢谢你，你解放了黑暗」的隐藏剧情，
     *       所以先把它的无敌关掉再让伤害落地。</li>
     * </ol>
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingDamage(LivingDamageEvent event) {
        if (event.getEntity().level().isClientSide()) return;

        // 致命档觉醒：这一击会把主人打死，就不管对面是僵尸还是 Boss，先把人保住
        if (event.getEntity() instanceof ServerPlayer player && event.getAmount() > 0.0F
                && player.getHealth() - event.getAmount() <= 0.0F) {
            LivingEntity attacker = event.getSource().getEntity() instanceof LivingEntity living
                    ? living : null;
            if (attacker != null && attacker != player) {
                VerityEntity verity = ServerVerityRegistry.findAnywhere(player);
                if (tryAwaken(player, verity, attacker, true)) {
                    // 削到剩 1 点血：随后觉醒给的「抗性提升 5 级 3 秒 + 瞬间治疗」会兜住后续伤害
                    event.setAmount(Math.max(0.0F, player.getHealth() - 1.0F));
                }
            }
        }

        if (event.getSource().getEntity() instanceof VerityEntity verity && !verity.isMonstrous()) {
            event.setAmount(Math.max(3.0F, event.getEntity().getMaxHealth() * 0.08F));
        }

        // 召唤的替身：玩家打它放行（留给玩家亲手解决），其它来源单次最多 10 点
        // 替身绝不能伤到玩家：就算索敌被抢、或者玩家站在刀口上，也直接把伤害抹掉
        if (event.getEntity() instanceof ServerPlayer
                && event.getSource().getEntity() instanceof Mob attacker
                && MonstrousManager.isGuardian(attacker)) {
            event.setAmount(0.0F);
            event.setCanceled(true);
            return;
        }

        if (event.getEntity() instanceof Mob mob && MonstrousManager.isGuardian(mob)) {
            if (event.getSource().getEntity() instanceof ServerPlayer) {
                return;
            }
            if (event.getAmount() > MonstrousManager.DEMON_DAMAGE_CAP) {
                event.setAmount(MonstrousManager.DEMON_DAMAGE_CAP);
            }
        }
    }
}
