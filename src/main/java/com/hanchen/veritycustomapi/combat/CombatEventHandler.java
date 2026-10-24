package com.hanchen.veritycustomapi.combat;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.network.ServerVerityRegistry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
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
            VerityEntity verity = ServerVerityRegistry.findForPlayer(player);
            if (verity != null && verity.isAlive()) {
                CombatAuraManager.onOwnerHurt(verity, player, attacker);
            }
            return;
        }

        if (victim instanceof VerityEntity verity) {
            CombatAuraManager.onVerityHurt(verity, attacker);
        }
    }
}
