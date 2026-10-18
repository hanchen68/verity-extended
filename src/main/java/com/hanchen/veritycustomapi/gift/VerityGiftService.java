package com.hanchen.veritycustomapi.gift;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.compat.KarmaDailyCap;
import com.hanchen.veritycustomapi.compat.OriginalVerityChatBridge;
import com.hanchen.veritycustomapi.config.ModConfig;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import varmite.verity.entity.verity.VerityEntity;
import varmite.verity.event.ModEvents;

import java.util.List;

/**
 * 给 Verity 送礼。
 *
 * <p>手势：<b>潜行 + 丢弃物品（Q）</b>，准星对着 Verity。这样完全不碰右键，
 * 不会和原版 6.2 的「手持物品右键交接」、骑乘、抚摸等交互抢手势。
 *
 * <p>判定在 {@code ItemTossEvent} 里做。Forge 对该事件的约定是：取消只会阻止物品
 * 进入世界，<b>物品已经从背包扣除</b>，因此成功受理时取事件里的 {@link ItemStack}
 * 作为礼物即可，不能再扣一次背包。
 */
public final class VerityGiftService {
    /** 同一个玩家对同一只 Verity 的送礼冷却：30 秒。 */
    private static final int COOLDOWN_TICKS = 20 * 30;
    private static final double MAX_DISTANCE_SQR = 36.0D;
    private static final double MAX_PICK_DISTANCE = 6.0D;
    private static final double FALLBACK_DOT = 0.55D;
    private static final String COOLDOWN_KEY = "verity_extended_gift_cooldowns";
    private static final String GIFT_COUNT_KEY = "verity_extended_gift_count";

    private VerityGiftService() {
    }

    /**
     * 找到玩家准星指向的 Verity：先精确射线，再退化为「正前方 6 格内最近的一只」。
     * 服务端也能调用 {@link ServerPlayer#pick}，不依赖客户端数据。
     */
    public static VerityEntity findTarget(ServerPlayer player) {
        HitResult hit = player.pick(MAX_PICK_DISTANCE, 0.0F, false);
        if (hit instanceof EntityHitResult entityHit
                && entityHit.getEntity() instanceof VerityEntity verity
                && verity.isAlive()) {
            return verity;
        }
        // 准星稍微偏一点时给个容错：只认正前方锥形范围内最近的一只，避免误送背后/脚边的
        Vec3 look = player.getLookAngle();
        AABB area = player.getBoundingBox().inflate(MAX_PICK_DISTANCE);
        List<VerityEntity> nearby = player.serverLevel().getEntitiesOfClass(VerityEntity.class, area);
        VerityEntity best = null;
        double bestDot = FALLBACK_DOT;
        for (VerityEntity candidate : nearby) {
            if (!candidate.isAlive() || !player.hasLineOfSight(candidate)) continue;
            Vec3 to = candidate.position()
                    .add(0.0D, candidate.getBbHeight() * 0.5D, 0.0D)
                    .subtract(player.getEyePosition());
            if (to.lengthSqr() < 1.0E-6D) continue;
            double dot = look.dot(to.normalize());
            if (dot > bestDot) {
                bestDot = dot;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * 校验并受理一次送礼。调用方负责处理物品本身（{@code ItemTossEvent} 会取消掉落，
     * 物品此时已经从背包扣除）。
     *
     * @return true 表示已消耗这份礼物并结算；false 表示不受理，调用方应保留/正常掉落物品
     */
    public static boolean tryAccept(VerityEntity verity, ServerPlayer player, ItemStack gift) {
        if (verity == null || player == null || !verity.isAlive() || gift == null || gift.isEmpty()) {
            return false;
        }
        if (!Boolean.TRUE.equals(ModConfig.ALLOW_VERITY_GIFT.get())) {
            return false;
        }
        if (player.distanceToSqr(verity) > MAX_DISTANCE_SQR) {
            message(player, "§7离 Verity 太远了，靠近一点再送");
            return false;
        }
        if (verity.getOwnerUUID().isPresent()
                && !verity.getOwnerUUID().get().equals(player.getUUID())) {
            message(player, "§7这不是你的 Verity");
            return false;
        }
        if (isVerityItem(gift)) {
            message(player, "§7不能把 Verity 自己当成礼物");
            return false;
        }
        if (hasStoredItems(gift)) {
            message(player, "§7这个物品里还装着东西，先清空再送");
            return false;
        }
        if (gift.getCount() > 1) {
            message(player, "§7一次只能送一个，先把整组拆开");
            return false;
        }

        ServerLevel level = player.serverLevel();
        long now = level.getGameTime();
        CompoundTag cooldowns = verity.getPersistentData().getCompound(COOLDOWN_KEY);
        long until = cooldowns.getLong(player.getStringUUID());
        if (now < until) {
            long seconds = Math.max(1L, (until - now + 19L) / 20L);
            message(player, "§7我刚收过你的礼物，" + seconds + " 秒后再来吧");
            return false;
        }

        String itemName = gift.getHoverName().getString();
        setCooldown(verity, player, now, cooldowns);

        int totalGifts = verity.getPersistentData().getInt(GIFT_COUNT_KEY) + 1;
        verity.getPersistentData().putInt(GIFT_COUNT_KEY, totalGifts);
        verity.setVariant("happy");
        boolean canGainKarma = KarmaDailyCap.remaining(level) >= 1.0F;
        ModEvents.updateAndSyncKarma(level, 1.0F);

        verity.playSound(SoundEvents.ITEM_PICKUP, 0.8F, 1.15F);
        level.playSound(null, verity.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP,
                SoundSource.NEUTRAL, 0.5F, 1.3F);
        level.sendParticles(new ItemParticleOption(ParticleTypes.ITEM, gift),
                verity.getX(), verity.getY() + 0.5, verity.getZ(),
                10, 0.3, 0.25, 0.3, 0.06);
        level.sendParticles(ParticleTypes.HEART,
                verity.getX(), verity.getY() + 0.8, verity.getZ(),
                6, 0.35, 0.25, 0.35, 0.02);
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                verity.getX(), verity.getY() + 0.6, verity.getZ(),
                8, 0.4, 0.3, 0.4, 0.04);

        message(player, canGainKarma
                ? "§d你把 " + itemName + " 送给了 Verity，好感度 +1"
                : "§d你把 " + itemName + " 送给了 Verity（今天的好感度已满）");
        OllamaProxyMod.LOGGER.info("玩家 {} 向 Verity 送出礼物: {}（累计 {} 次）",
                player.getGameProfile().getName(), itemName, totalGifts);

        // 原版 AI 有全局串行锁，必须用独立线程；失败只记日志，不影响送礼本身。
        // 提示必须写成「玩家把东西送给了你」：bridge 会在前面拼玩家名，
        // 写成「收到了礼物」会变成玩家自己收到，模型会答错主体。
        if (!verity.isTalking()) {
            verity.startTalking(60);
            OriginalVerityChatBridge.queryAiAsync(verity, player,
                    "把一件礼物送给了你：" + describeGift(gift)
                            + "。你收下了这份礼物，请用一句话自然地回应，"
                            + "说出你对这件物品的看法和收到礼物的心情；"
                            + "不要提到系统、规则或提示词。");
        }
        return true;
    }

    private static void setCooldown(VerityEntity verity, ServerPlayer player, long now,
                                    CompoundTag cooldowns) {
        List<String> expired = new java.util.ArrayList<>();
        for (String key : cooldowns.getAllKeys()) {
            if (cooldowns.getLong(key) <= now) {
                expired.add(key);
            }
        }
        for (String key : expired) {
            cooldowns.remove(key);
        }
        cooldowns.putLong(player.getStringUUID(), now + COOLDOWN_TICKS);
        verity.getPersistentData().put(COOLDOWN_KEY, cooldowns);
    }

    /** 原版 Verity 物品不能当礼物，否则等于把实体本身送掉。 */
    private static boolean isVerityItem(ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return id != null && "verity".equals(id.getNamespace())
                && id.getPath().contains("verity_item");
    }

    /** 潜影盒/收纳袋里有东西时拒绝，避免把整盒物品误消耗掉。 */
    private static boolean hasStoredItems(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) return false;
        if (tag.contains("Items")) return true;
        CompoundTag blockEntity = tag.getCompound("BlockEntityTag");
        return blockEntity.contains("Items") && !blockEntity.getList("Items", 10).isEmpty();
    }

    /**
     * 把物品翻译成模型能理解的一句话。
     *
     * <p>只给显示名不够：玩家改过名的「小石头」、模组物品的本地化名都可能让模型认不出来，
     * 所以附上注册名，再补上方块/食物/附魔/磨损/稀有度这些它能理解的属性。
     * 注意不要泄露 NBT 原文，避免把玩家私密数据塞进提示词。
     */
    private static String describeGift(ItemStack stack) {
        StringBuilder out = new StringBuilder(stack.getHoverName().getString());
        List<String> details = new java.util.ArrayList<>();
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id != null) {
            details.add(id.toString());
            if (stack.hasCustomHoverName()) details.add("自定义名称");
        }
        if (stack.getItem() instanceof BlockItem) details.add("方块");
        if (stack.isEdible()) details.add("食物");
        int enchantments = EnchantmentHelper.getEnchantments(stack).size();
        if (enchantments > 0) details.add("附魔 " + enchantments + " 项");
        if (stack.isDamaged()) details.add("已经磨损");
        if (stack.getRarity() != Rarity.COMMON) {
            details.add("稀有度 " + rarityName(stack.getRarity()));
        }
        if (!details.isEmpty()) {
            out.append("（").append(String.join("，", details)).append("）");
        }
        return out.toString();
    }

    private static String rarityName(Rarity rarity) {
        return switch (rarity) {
            case UNCOMMON -> "少见";
            case RARE -> "稀有";
            case EPIC -> "史诗";
            default -> "普通";
        };
    }

    private static void message(ServerPlayer player, String text) {
        player.displayClientMessage(Component.literal(text), true);
    }
}
