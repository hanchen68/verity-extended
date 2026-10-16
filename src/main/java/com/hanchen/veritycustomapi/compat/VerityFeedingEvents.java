package com.hanchen.veritycustomapi.compat;

import com.mojang.datafixers.util.Pair;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import varmite.verity.entity.verity.VerityEntity;
import varmite.verity.event.ModEvents;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class VerityFeedingEvents {
    /** 冷却时长：60 秒对应的服务端游戏刻数 */
    private static final long KARMA_COOLDOWN_TICKS = 20L * 60L;
    /** 冷却时间戳按玩家 UUID 保存在实体 NBT 中，服务端重启后仍然生效 */
    private static final String FEED_COOLDOWN_KEY = "verity_extended_feed_cooldowns";
    private static final Map<FeedKey, FeedState> FEED_STATES = new HashMap<>();

    private VerityFeedingEvents() {
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.register(VerityFeedingEvents.class);
    }

    public static void clear() {
        FEED_STATES.clear();
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!(event.getTarget() instanceof VerityEntity verity)) return;
        ItemStack stack = event.getItemStack();
        FoodProperties food = stack.getFoodProperties(event.getEntity());
        if (stack.isEmpty() || !hasUsefulEffect(food) || !verity.isAlive()) return;
        // 原版 6.2 起自带的实体交互处理器扩展了很多分支，含「玩家把物品递给 Verity」的交接逻辑；
        // 这里无条件 setCanceled(true) 并吃掉物品会顶掉原版逻辑，故检测到该版本时让位，不取消、不消耗。
        if (VerityStructureDetector.hasBuiltinPatSupport()) return;
        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide()));
        if (event.getLevel().isClientSide()) return;

        ServerLevel level = (ServerLevel) event.getLevel();
        boolean growthEnabled = com.hanchen.veritycustomapi.config.ModConfig.ENABLE_VERITY_GROWTH.get();
        // 冷却使用服务端游戏刻而非墙钟时间：服务器暂停或系统时间调整不会影响判定
        long now = level.getGameTime();
        UUID playerId = event.getEntity().getUUID();
        FeedKey feedKey = new FeedKey(verity.getUUID(), playerId);

        if (!growthEnabled && now < getCooldownUntil(verity, playerId)) {
            int attempts = FEED_STATES.getOrDefault(feedKey, new FeedState(0)).attempts() + 1;
            FEED_STATES.put(feedKey, new FeedState(attempts));
            showFullFeedback(event, verity, level, attempts);
            return;
        }

        // 冷却按玩家分别记录，并写入实体 NBT 以便服务端重启后仍然生效
        FEED_STATES.remove(feedKey);
        setCooldownUntil(verity, playerId, now + KARMA_COOLDOWN_TICKS, now);
        ItemStack fedStack = stack.copyWithCount(1);
        if (food.getNutrition() > 0) {
            verity.heal(Math.min(4.0F, food.getNutrition()));
        }
        applyFoodEffects(verity, food);
        verity.setVariant("happy");
        triggerFeedConversation(verity, event.getEntity());
        if (growthEnabled) {
            VerityGrowthState.onSuccessfulFeed(verity);
        }
        if (!event.getEntity().getAbilities().instabuild) stack.shrink(1);
        verity.playSound(SoundEvents.GENERIC_EAT, 1.0F, 0.9F + verity.getRandom().nextFloat() * 0.2F);
        ModEvents.updateAndSyncKarma(level, 1.0F);
        event.getEntity().displayClientMessage(Component.literal("Verity 很喜欢！好感度 +1"), true);
        level.playSound(null, verity.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP,
                SoundSource.NEUTRAL, 0.55F, 1.25F);
        level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, fedStack),
                verity.getX(), verity.getY() + 0.45, verity.getZ(),
                8, 0.28, 0.2, 0.28, 0.08);
        level.sendParticles(ParticleTypes.HEART, verity.getX(), verity.getY() + 0.7, verity.getZ(),
                6, 0.35, 0.25, 0.35, 0.02);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER, verity.getX(), verity.getY() + 0.55, verity.getZ(),
                7, 0.4, 0.3, 0.4, 0.04);
    }

    private static void showFullFeedback(PlayerInteractEvent.EntityInteract event, VerityEntity verity,
                                         ServerLevel level, int attempts) {
        String response;
        if (attempts == 1) {
            response = "Verity：我已经吃饱啦，等一会儿再喂我吧。";
        } else if (attempts == 2) {
            response = "Verity：好了好了，我已经吃饱了。";
        } else {
            response = "Verity：你够了哦……我真的吃不下了。";
            verity.setVariant("happy_sleep");
        }
        verity.startTalking(20);
        event.getEntity().displayClientMessage(Component.literal(response), true);
        level.playSound(null, verity.blockPosition(), SoundEvents.PLAYER_BURP,
                SoundSource.NEUTRAL, 0.6F, 1.1F);
        level.sendParticles(ParticleTypes.CLOUD, verity.getX(), verity.getY() + 0.65, verity.getZ(),
                Math.min(5, attempts + 1), 0.25, 0.15, 0.25, 0.01);
    }

    private static boolean hasUsefulEffect(FoodProperties food) {
        if (food == null) {
            return false;
        }
        if (food.getNutrition() > 0) {
            return true;
        }
        return food.getEffects().stream().anyMatch(pair -> pair != null && pair.getFirst() != null);
    }

    private static void applyFoodEffects(VerityEntity verity, FoodProperties food) {
        for (Pair<MobEffectInstance, Float> pair : food.getEffects()) {
            if (pair == null || pair.getFirst() == null) {
                continue;
            }
            if (pair.getSecond() >= 1.0F || verity.getRandom().nextFloat() < pair.getSecond()) {
                verity.addEffect(new MobEffectInstance(pair.getFirst()));
            }
        }
    }

    private static void triggerFeedConversation(VerityEntity verity, net.minecraft.world.entity.player.Player player) {
        if (!(player instanceof ServerPlayer serverPlayer) || verity.isTalking()) {
            return;
        }
        verity.startTalking(80);
        if (!OriginalVerityChatBridge.queryAi(
                verity, serverPlayer, "刚刚把手中的食物喂给了你。你是吃下食物的 Verity，请向这名玩家简短回应。")) {
            verity.stopTalking();
        }
    }

    private static long getCooldownUntil(VerityEntity verity, UUID playerId) {
        CompoundTag cooldowns = verity.getPersistentData().getCompound(FEED_COOLDOWN_KEY);
        return cooldowns.getLong(playerId.toString());
    }

    private static void setCooldownUntil(VerityEntity verity, UUID playerId, long untilTick, long now) {
        CompoundTag root = verity.getPersistentData();
        CompoundTag cooldowns = root.getCompound(FEED_COOLDOWN_KEY);
        List<String> expired = new ArrayList<>();
        for (String key : cooldowns.getAllKeys()) {
            if (cooldowns.getLong(key) <= now) {
                expired.add(key);
            }
        }
        for (String key : expired) {
            cooldowns.remove(key);
        }
        cooldowns.putLong(playerId.toString(), untilTick);
        root.put(FEED_COOLDOWN_KEY, cooldowns);
    }

    private record FeedState(int attempts) {
    }

    private record FeedKey(UUID entityId, UUID playerId) {
    }
}
