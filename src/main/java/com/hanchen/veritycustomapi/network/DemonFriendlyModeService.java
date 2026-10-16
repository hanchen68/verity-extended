package com.hanchen.veritycustomapi.network;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.config.ModConfig;

/**
 * 友好模式开着的时候拦住 VerityDemonEntity 打玩家。
 *
 * 事件层只把玩家目标临时清空，不动剧情恶魔身上的目标 Goal，所以关掉友好模式就立刻恢复追逐。
 * 需要永久摘掉玩家目标的只有 MMD 展示恶魔，那种情况走 disablePlayerTargeting(Mob)。
 */
@Mod.EventBusSubscriber(modid = OllamaProxyMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class DemonFriendlyModeService {
    private static final Logger LOGGER = LogManager.getLogger("VerityExt-DemonFriendly");

    private DemonFriendlyModeService() {}

    /** 按类名认恶魔，两个版本的包路径都要兼容。 */
    private static boolean isDemonEntity(Object entity) {
        if (entity == null) return false;
        String cls = entity.getClass().getName();
        String low = cls.toLowerCase();
        if (low.contains("veritydemon") || low.contains("demonentity")) return true;
        return cls.equals("varmite.verity.entity.demon.VerityDemonEntity")
                || cls.equals("varmite.verity.entity.custom.VerityDemonEntity");
    }

    /**
     * 字段名各版本不一样，只能反射去找类型是 {@link GoalSelector} 的那个。
     */
    private static Field findGoalSelectorField(Mob mob) {
        for (Field f : mob.getClass().getFields()) {
            if (GoalSelector.class.isAssignableFrom(f.getType())) {
                return f;
            }
        }
        // 公开字段没有，就翻 declared 的（private/protected/包级）
        Class<?> c = mob.getClass();
        while (c != null && c != Object.class) {
            try {
                for (Field f : c.getDeclaredFields()) {
                    if (GoalSelector.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        return f;
                    }
                }
            } catch (Throwable ignored) {}
            c = c.getSuperclass();
        }
        return null;
    }

    private static GoalSelector readSelector(Mob mob, Field field) {
        try {
            field.setAccessible(true);
            return (GoalSelector) field.get(mob);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 把 {@code targetSelector} 里针对玩家的 {@link NearestAttackableTargetGoal}&lt;Player&gt; 全部摘掉。
     * 目标列表是反射读的 availableGoals，摘除走 {@code selector.removeGoal}。
     */
    public static void disablePlayerTargeting(Mob mob) {
        Field f = findGoalSelectorField(mob);
        if (f == null) {
            LOGGER.warn("未在 {} 上找到目标 GoalSelector 字段，跳过禁用玩家目标", mob.getClass().getName());
            return;
        }
        GoalSelector selector = readSelector(mob, f);
        if (selector == null) return;

        List<?> available = null;
        try {
            Field avail = GoalSelector.class.getDeclaredField("availableGoals");
            avail.setAccessible(true);
            available = (List<?>) avail.get(selector);
        } catch (Throwable ignored) {}
        if (available == null) return;

        int removed = 0;
        for (Object goalObj : new java.util.ArrayList<>(available)) {
            if (!(goalObj instanceof NearestAttackableTargetGoal)) continue;
            Object goal = goalObj;
            // 先看 goal 自己的 targetClass 是不是 Player
            try {
                Field tc = goal.getClass().getDeclaredField("targetClass");
                tc.setAccessible(true);
                Object targetClass = tc.get(goal);
                if (targetClass == Player.class) {
                    // 命中了就直接摘掉
                    selector.removeGoal((Goal) goal);
                    removed++;
                }
            } catch (NoSuchFieldException ignored) {
                // 拿不到 targetClass 就退而求其次，类名里带 Player 就算针对玩家
                String cls = goal.getClass().getName();
                if (cls.contains("Player") || cls.contains("player")) {
                    selector.removeGoal((Goal) goal);
                    removed++;
                }
            } catch (Throwable ignored) {}
        }
        if (removed > 0) {
            LOGGER.info("恶魔实体 {} 已移除 {} 个玩家目标选择器", mob.getClass().getSimpleName(), removed);
        }
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !ModConfig.FRIENDLY_MODE.get()) return;
        if (!isDemonEntity(event.getEntity())) return;
        if (event.getEntity() instanceof Mob mob && mob.getTarget() instanceof Player) {
            mob.setTarget(null);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        if (!ModConfig.FRIENDLY_MODE.get()) return;
        if (!(event.getEntity() instanceof PathfinderMob mob)) return;
        if (!isDemonEntity(mob)) return;
        if (mob.getTarget() instanceof Player) {
            mob.setTarget(null);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onChangeTarget(LivingChangeTargetEvent event) {
        if (!ModConfig.FRIENDLY_MODE.get() || !isDemonEntity(event.getEntity())) return;
        if (event.getNewTarget() instanceof Player) {
            event.setNewTarget(null);
        }
    }
}