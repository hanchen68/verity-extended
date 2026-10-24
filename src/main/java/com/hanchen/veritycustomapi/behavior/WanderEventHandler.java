package com.hanchen.veritycustomapi.behavior;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import varmite.verity.entity.verity.VerityEntity;

/**
 * 给 Verity 挂上「闲置溜达」goal。
 *
 * <p>和 {@code CombatEventHandler} 一样走 {@link EntityJoinLevelEvent}：
 * 这个事件会随区块加载反复触发，所以**必须去重**，否则同一个 Verity 身上会叠十几个
 * 漫游 goal，每 tick 被调用十几次。
 *
 * <p>优先级 3（比战斗 0、跟随 1、看向玩家 2 都低）——有正经事的时候轮不到它。
 * 是否启用由 {@code verity_wander.enabled} 控制，判断写在 goal 的 {@code canUse()} 里，
 * 这样开关切换能立即生效，不需要重挂 goal。
 */
@Mod.EventBusSubscriber(modid = OllamaProxyMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class WanderEventHandler {
    private WanderEventHandler() {}

    /** 比跟随(1)和看向玩家(2)都低，只在它们都不跑的时候才有机会。 */
    private static final int WANDER_GOAL_PRIORITY = 3;

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof VerityEntity verity)) return;
        if (hasWanderGoal(verity)) return;
        verity.goalSelector.addGoal(WANDER_GOAL_PRIORITY, new VerityWanderGoal(verity));
    }

    private static boolean hasWanderGoal(Mob mob) {
        for (WrappedGoal wrapped : mob.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof VerityWanderGoal) return true;
        }
        return false;
    }
}
