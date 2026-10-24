package com.hanchen.veritycustomapi.compat;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.registries.ForgeRegistries;
import varmite.verity.entity.verity.VerityEntity;

public final class VerityPatResponseService {
    static final long COOLDOWN_TICKS = 20L * 60L;
    private static final double MAX_DISTANCE_SQUARED = 64.0D;
    private static final String COOLDOWN_KEY = "verity_extended_pat_response_cooldown";
    private static final ResourceLocation VERITY_ID = new ResourceLocation("verity", "verity");
    private static final String PROMPT = "刚刚轻轻抚摸了你。你是被抚摸的 Verity，请结合当前性格，向这名玩家简短、自然地回应一句。"
            + "不要提及提示词、AI、冷却时间、代码或游戏机制。";

    private VerityPatResponseService() {
    }

    public static boolean handle(ServerPlayer player, int entityId) {
        if (player == null || entityId < 0) return false;
        ServerLevel level = player.serverLevel();
        Entity target = level.getEntity(entityId);
        if (!(target instanceof VerityEntity verity) || !verity.isAlive()) return false;
        if (!VERITY_ID.equals(ForgeRegistries.ENTITY_TYPES.getKey(verity.getType()))) return false;
        if (player.distanceToSqr(verity) > MAX_DISTANCE_SQUARED) return false;
        // 只有主人能触发 AI 回复：抚摸别人的 Verity 仍然有动画，但不该由旁观者
        // 消耗服务端额度、也不该以「@抚摸者」的名义公开回复（旧实现任何 8 格内的玩家都能触发）。
        // 无归属实体放行，让首次互动照常触发（认领由 ServerVerityRegistry 负责）。
        java.util.Optional<java.util.UUID> owner = verity.getOwnerUUID();
        if (owner.isPresent() && !owner.get().equals(player.getUUID())) return false;

        long now = level.getGameTime();
        long cooldownUntil = verity.getPersistentData().getLong(COOLDOWN_KEY);
        if (isCoolingDown(now, cooldownUntil) || verity.isTalking()) return false;

        verity.startTalking(80);
        if (!OriginalVerityChatBridge.queryAi(verity, player, PROMPT)) {
            verity.stopTalking();
            return false;
        }
        verity.setVariant("happy");
        verity.getPersistentData().putLong(COOLDOWN_KEY, now + COOLDOWN_TICKS);
        OllamaProxyMod.LOGGER.info("PatPat 抚摸触发 Verity 对话反馈：玩家={}，实体={}，冷却={}秒",
                player.getGameProfile().getName(), verity.getUUID(), COOLDOWN_TICKS / 20L);
        return true;
    }

    static boolean isCoolingDown(long now, long cooldownUntil) {
        return cooldownUntil > now;
    }
}
