package com.hanchen.veritycustomapi.combat;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.compat.OriginalVerityChatBridge;
import com.hanchen.veritycustomapi.compat.VeritySay;
import com.hanchen.veritycustomapi.config.ModConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.registries.ForgeRegistries;
import varmite.verity.entity.demon.VerityDemonEntity;
import varmite.verity.entity.verity.VerityEntity;

import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「残血深渊觉醒」：主人被精英/Boss 打到快死时，Verity 召唤**原版那只恶魔**上场。
 *
 * <p>召唤的是 {@code verity:verity_demon}（外观就是原版那只大恶魔），属性拉到
 * 500 血 / 20 攻 / 0.35 移速。但原版这只恶魔自带一整套「追玩家 / 追猎音效 / 死寂 / 跨存档追击」
 * 逻辑，所以召唤后要做四件事把它变成「听指挥的打手」：
 * <ol>
 *   <li><b>清空 {@code targetSelector}</b>：它「追着玩家打」靠的是里面的
 *       {@code NearestAttackableTargetGoal<Player>}（反编译 {@code VerityDemonEntity.m_8099_} 可见），
 *       清空后它不会自己挑目标，打谁完全由 {@link #forceTargets} 指定；</li>
 *   <li><b>保持 {@code demonState = 1}</b>：原版 tick 里 {@code demonState != 1} 的分支会
 *       {@code getNavigation().stop()} + 速度清零——按回 0 它就不动了；</li>
 *   <li><b>服务端 tick END 锁目标</b>：生物的 target goal 在 {@code serverAiStep} 里跑，
 *       实体 tick 注入在它之前，改完会被覆盖；</li>
 *   <li><b>打持久标记 + 网络同步</b>：{@code verity_ext_guardian} 让「跨存档追击」跳过它，
 *       同时广播给客户端，追击音效（{@code GuardianDemonSoundMixin}）和恐怖系统
 *       （{@code VerityHorrorDirector}）据此跳过。</li>
 * </ol>
 */
public final class MonstrousManager {

    /** 召唤的实体：原版那只恶魔。 */
    private static final ResourceLocation DEMON_ID = new ResourceLocation("verity", "verity_demon");

    /** 实体上的持久标记，给「跨存档追击」等系统做白名单。 */
    public static final String GUARDIAN_TAG = "verity_ext_guardian";
    /** 头顶显示名（客户端认不出持久数据时靠它兜底）。 */
    public static final String GUARDIAN_NAME = "深渊替身";

    private static final Map<UUID, Long> TRANSFORM_TIMES = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> COOLDOWN_END = new ConcurrentHashMap<>();
    /** 玩家 → 召唤出来的恶魔。 */
    private static final Map<UUID, Mob> GUARDIANS = new ConcurrentHashMap<>();
    /** 玩家 → 需要被「按头」的目标 UUID。 */
    private static final Map<UUID, UUID> SUMMON_TARGETS = new ConcurrentHashMap<>();
    /** Verity 实体 → 主人玩家 UUID。 */
    private static final Map<UUID, UUID> VERITY_OWNER = new ConcurrentHashMap<>();

    private static final Random RANDOM = new Random();
    private static final String[] EVIL_FACES = {"evil", "crazy"};
    private static final long COOLDOWN_TICKS = 200L;

    private static final double DEMON_HEALTH = 500.0D;
    private static final double DEMON_ATTACK = 20.0D;
    private static final double DEMON_SPEED = 0.35D;
    /** 恶魔单次受伤上限。 */
    public static final float DEMON_DAMAGE_CAP = 10.0F;
    /** 觉醒瞬间给主人的保护：抗性提升 5 级 + 瞬间治疗，3 秒。 */
    private static final int LOCK_TICKS = 60;
    private static final int LOCK_AMPLIFIER = 4;
    /** 按头距离：超过 16 格就瞬移回目标身边。 */
    private static final double PRESS_DISTANCE = 16.0D;

    private MonstrousManager() {
    }

    /**
     * 这只实体是不是我们召唤的那只恶魔。
     *
     * <p>客户端判定顺序：网络同步的登记表 → 持久标记（服务端）→ 自定义名兜底。
     */
    public static boolean isGuardian(Entity entity) {
        if (entity == null) return false;
        if (GuardianRegistry.contains(entity.getId())) return true;
        try {
            if (entity.getPersistentData().getBoolean(GUARDIAN_TAG)) return true;
        } catch (Throwable ignored) {
            // 客户端 ForgeData 为空，继续看名字
        }
        try {
            return entity.hasCustomName() && entity.getCustomName() != null
                    && GUARDIAN_NAME.equals(entity.getCustomName().getString());
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isAwakened(UUID playerId) {
        if (playerId == null) return false;
        Mob guardian = GUARDIANS.get(playerId);
        if (guardian == null) return false;
        if (!guardian.isAlive() || guardian.isRemoved()) {
            GUARDIANS.remove(playerId);
            return false;
        }
        return true;
    }

    /**
     * 召唤恶魔。
     *
     * @param verity 可能为 null（她不在加载范围时也能召唤）
     * @return true 表示真的召唤出来了
     */
    public static boolean transform(ServerPlayer owner, VerityEntity verity, LivingEntity target) {
        if (owner == null || target == null || !target.isAlive()) return false;
        if (!ModConfig.VERITY_DEMON_AWAKENING.get()) return false;

        UUID playerId = owner.getUUID();
        if (isAwakened(playerId)) return false;
        ServerLevel level = owner.serverLevel();

        long now = level.getGameTime();
        Long cooldownEnd = COOLDOWN_END.get(playerId);
        if (cooldownEnd != null && now < cooldownEnd) return false;

        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(DEMON_ID);
        if (type == null) {
            OllamaProxyMod.LOGGER.warn("[觉醒] 找不到实体 {}，跳过召唤", DEMON_ID);
            return false;
        }
        Entity created = type.create(level);
        if (!(created instanceof Mob demon)) {
            if (created != null) created.discard();
            OllamaProxyMod.LOGGER.warn("[觉醒] 生成的实体不是 Mob，跳过");
            return false;
        }

        demon.setPersistenceRequired();
        demon.getPersistentData().putBoolean(GUARDIAN_TAG, true);
        demon.setCustomName(Component.literal(GUARDIAN_NAME));
        demon.setCustomNameVisible(true);
        // 🔴 清空索敌表：原版「追着玩家打」就是这里的 NearestAttackableTargetGoal<Player>。
        // 清掉之后它不会自己挑目标，打谁由 forceTargets 决定。
        demon.targetSelector.removeAllGoals(goal -> true);
        if (demon.getAttribute(Attributes.MAX_HEALTH) != null) {
            demon.getAttribute(Attributes.MAX_HEALTH).setBaseValue(DEMON_HEALTH);
            demon.setHealth((float) DEMON_HEALTH);
        }
        if (demon.getAttribute(Attributes.ATTACK_DAMAGE) != null) {
            demon.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(DEMON_ATTACK);
        }
        if (demon.getAttribute(Attributes.MOVEMENT_SPEED) != null) {
            demon.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(DEMON_SPEED);
        }
        demon.moveTo(target.getX(), target.getY(), target.getZ(), target.getYRot(), 0.0F);
        demon.setTarget(target);
        // 🔴 必须真的放进世界，否则不渲染、不进实体表，下一 tick 就找不到它
        level.addFreshEntity(demon);
        // 同步给客户端：音效屏蔽与恐怖系统在客户端判定
        com.hanchen.veritycustomapi.network.HeadPerchNetwork
                .broadcastGuardian(demon.getId(), true);

        TRANSFORM_TIMES.put(playerId, now);
        GUARDIANS.put(playerId, demon);
        SUMMON_TARGETS.put(playerId, target.getUUID());

        if (verity != null && verity.isAlive()) {
            verity.setVariant(EVIL_FACES[RANDOM.nextInt(EVIL_FACES.length)]);
            verity.setTarget(null);
            VERITY_OWNER.put(verity.getUUID(), playerId);
        }

        // 救场：抗性提升 5 级 3 秒 + 瞬间治疗
        owner.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, LOCK_TICKS, LOCK_AMPLIFIER));
        owner.addEffect(new MobEffectInstance(MobEffects.HEAL, 1, 1));
        speakAwakeningLine(verity, owner);

        BlockPos spawnPos = target.blockPosition();
        level.sendParticles(ParticleTypes.LARGE_SMOKE,
                spawnPos.getX() + 0.5D, spawnPos.getY() + 1.0D, spawnPos.getZ() + 0.5D,
                64, 0.8D, 1.2D, 0.8D, 0.12D);
        level.playSound(null, demon.blockPosition(), SoundEvents.ENDER_DRAGON_GROWL,
                SoundSource.HOSTILE, 1.0F, 0.75F);

        // 平时不刷屏；排查时把日志级别调到 DEBUG 才看得到
        OllamaProxyMod.LOGGER.debug("[觉醒] 已召唤恶魔：玩家={} 目标={} 实体={}",
                owner.getScoreboardName(), target.getName().getString(), demon.getUUID());
        return true;
    }

    /** 觉醒台词：优先让 AI 说，失败/超时用固定兜底句。 */
    private static void speakAwakeningLine(VerityEntity verity, ServerPlayer owner) {
        String prompt = "你为了保护主人觉醒了恶魔形态，请对主人说一句霸道、深情或极度护短的台词，不超过20字。"
                + "只说这一句，不要解释，不要提到 AI、代码、游戏机制。";
        try {
            if (verity != null) {
                OriginalVerityChatBridge.queryAiAsync(verity, owner, prompt);
                return;
            }
        } catch (Throwable ignored) {
            // 落到固定句
        }
        VeritySay.to(owner, "你是我的，这世上没有任何人能伤害你。");
    }

    /** 撤掉恶魔，恢复日常。 */
    public static void revert(VerityEntity verity) {
        if (verity == null) return;
        UUID playerId = VERITY_OWNER.remove(verity.getUUID());
        if (playerId == null) return;
        Mob demon = GUARDIANS.remove(playerId);
        TRANSFORM_TIMES.remove(playerId);
        SUMMON_TARGETS.remove(playerId);
        if (!(verity.level() instanceof ServerLevel level)) return;

        if (demon != null) {
            com.hanchen.veritycustomapi.network.HeadPerchNetwork
                    .broadcastGuardian(demon.getId(), false);
            if (demon.isAlive()) {
                level.sendParticles(ParticleTypes.LARGE_SMOKE,
                        demon.getX(), demon.getY() + 1.0D, demon.getZ(),
                        48, 0.6D, 1.0D, 0.6D, 0.15D);
                demon.discard();
            }
        }
        verity.setVariant("happy");
        COOLDOWN_END.put(playerId, level.getGameTime() + COOLDOWN_TICKS);
    }

    /** 强制复位（换维度、死亡、停服时用）。 */
    public static void forceReset(VerityEntity verity) {
        revert(verity);
    }

    /**
     * 服务端 tick 的 END 阶段：锁目标 + 保状态 + 按头 + 收场。
     *
     * <p>必须放 END —— 生物的 target goal 在 {@code serverAiStep} 里跑，
     * 实体 tick 注入在它之前，改完会被 AI 覆盖。
     */
    public static void forceTargets(MinecraftServer server) {
        if (GUARDIANS.isEmpty()) return;
        for (Map.Entry<UUID, Mob> entry : GUARDIANS.entrySet()) {
            Mob demon = entry.getValue();
            if (demon == null || !demon.isAlive() || demon.isRemoved()) {
                GUARDIANS.remove(entry.getKey());
                continue;
            }
            UUID targetId = SUMMON_TARGETS.get(entry.getKey());
            if (targetId == null) continue;
            ServerLevel level = demon.level() instanceof ServerLevel l ? l : null;
            Entity targetEntity = level == null ? null : level.getEntity(targetId);
            if (!(targetEntity instanceof LivingEntity boss) || !boss.isAlive()) {
                // Boss 没了就收场
                GUARDIANS.remove(entry.getKey());
                SUMMON_TARGETS.remove(entry.getKey());
                COOLDOWN_END.put(entry.getKey(), level == null ? 0L : level.getGameTime() + COOLDOWN_TICKS);
                com.hanchen.veritycustomapi.network.HeadPerchNetwork
                        .broadcastGuardian(demon.getId(), false);
                if (demon.isAlive()) demon.discard();
                continue;
            }
            if (demon.getTarget() != boss) {
                demon.setTarget(boss);
            }
            // 🔴 demonState 必须保持 1：原版 tick 里 `!= 1` 会停寻路 + 速度清零，它就不动了。
            // 追猎的副作用分开处理：音效由 GuardianDemonSoundMixin 屏蔽，恐怖等级由恐怖导演跳过。
            if (demon instanceof VerityDemonEntity vd) {
                if (vd.getDemonState() != 1) vd.setDemonState(1);
                if (vd.getHuntPhase() != 1) vd.setHuntPhase(1);
            }
            if (level.getGameTime() % 20L == 0L
                    && demon.distanceToSqr(boss) > PRESS_DISTANCE * PRESS_DISTANCE) {
                demon.moveTo(boss.getX(), boss.getY(), boss.getZ(), boss.getYRot(), 0.0F);
            }
        }
    }

    /** 每 tick 维护小球表情（由 {@code VerityEntityCombatMixin} 调用）。 */
    public static void tick(VerityEntity verity) {
        if (verity == null || verity.level().isClientSide) return;
        UUID playerId = VERITY_OWNER.get(verity.getUUID());
        if (playerId == null) return;
        if (!GUARDIANS.containsKey(playerId)) {
            VERITY_OWNER.remove(verity.getUUID());
            verity.setVariant("happy");
            return;
        }
        verity.setVariant(EVIL_FACES[(verity.tickCount / 40) % EVIL_FACES.length]);
        if (verity.getTarget() != null) verity.setTarget(null);
    }

    /** 服务器停止时清空。 */
    public static void clear() {
        TRANSFORM_TIMES.clear();
        COOLDOWN_END.clear();
        GUARDIANS.clear();
        SUMMON_TARGETS.clear();
        VERITY_OWNER.clear();
    }
}
