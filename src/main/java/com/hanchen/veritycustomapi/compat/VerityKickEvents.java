package com.hanchen.veritycustomapi.compat;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.config.ModConfig;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import varmite.verity.entity.verity.VerityEntity;

/**
 * 踢 Verity 一脚。
 *
 * <p>手势：**潜行 + 左键**。普通左键保持原版攻击，不会误触。
 * 踢不造成伤害，只把 Verity 推开、播音效粒子，并让 Verity 用一句话回应。
 */
public final class VerityKickEvents {
    private static final int COOLDOWN_TICKS = 20 * 10;
    private static final String COOLDOWN_KEY = "verity_extended_kick_cooldowns";

    private VerityKickEvents() {
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.register(VerityKickEvents.class);
    }

    @SubscribeEvent
    public static void onAttack(AttackEntityEvent event) {
        if (!(event.getTarget() instanceof VerityEntity verity)) return;
        Player player = event.getEntity();
        // 只在服务端处理；客户端保留挥手动画，不取消事件
        if (player.level().isClientSide()) return;
        if (!player.isShiftKeyDown()) return;
        if (!Boolean.TRUE.equals(ModConfig.ALLOW_VERITY_KICK.get())) return;
        // 只接管自己的 Verity；别人的照常按原版攻击处理，避免多人服里互相踢
        if (verity.getOwnerUUID().isPresent()
                && !verity.getOwnerUUID().get().equals(player.getUUID())) {
            return;
        }

        // 潜行左键的意图是「踢」，不是「打」，所以无论冷却与否都先挡掉伤害
        event.setCanceled(true);
        if (!(player instanceof ServerPlayer serverPlayer) || !verity.isAlive()) return;

        ServerLevel level = serverPlayer.serverLevel();
        long now = level.getGameTime();
        CompoundTag cooldowns = verity.getPersistentData().getCompound(COOLDOWN_KEY);
        long until = cooldowns.getLong(serverPlayer.getStringUUID());
        if (now < until) {
            long seconds = Math.max(1L, (until - now + 19L) / 20L);
            serverPlayer.displayClientMessage(Component.literal(
                    "§7Verity 已经躲开了，" + seconds + " 秒后再试吧"), true);
            return;
        }
        cooldowns.putLong(serverPlayer.getStringUUID(), now + COOLDOWN_TICKS);
        verity.getPersistentData().put(COOLDOWN_KEY, cooldowns);

        // 从玩家指向 Verity 的方向推开，带一点向上的弧度
        Vec3 away = verity.position().subtract(serverPlayer.position());
        if (away.lengthSqr() < 1.0E-4D) {
            away = serverPlayer.getLookAngle();
        }
        away = away.normalize();
        verity.push(away.x * 0.75D, 0.28D, away.z * 0.75D);
        verity.setVariant("serious_1");
        level.playSound(null, verity.blockPosition(), SoundEvents.PLAYER_ATTACK_KNOCKBACK,
                SoundSource.PLAYERS, 0.9F, 1.1F);
        level.sendParticles(ParticleTypes.CRIT,
                verity.getX(), verity.getY() + 0.45D, verity.getZ(),
                6, 0.25D, 0.2D, 0.25D, 0.02D);

        serverPlayer.displayClientMessage(Component.literal(
                "§7你踢了 Verity 一脚……Verity 看起来有点生气"), true);
        OllamaProxyMod.LOGGER.info("玩家 {} 踢了 Verity 一脚", serverPlayer.getGameProfile().getName());

        if (!verity.isTalking()) {
            verity.startTalking(60);
            OriginalVerityChatBridge.queryAiAsync(verity, serverPlayer,
                    "刚刚踢了你一脚。你是被踢的 Verity，请用一句话回应，"
                            + "可以生气、委屈或开玩笑；不要提到系统、规则或提示词。");
        }
    }
}
