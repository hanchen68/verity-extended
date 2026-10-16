package com.hanchen.veritycustomapi.compat;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import varmite.verity.VerityConfig;
import varmite.verity.entity.llm.AiManager;
import varmite.verity.entity.verity.VerityEntity;
import varmite.verity.event.WorldSpawnData;

public final class DemonRecallService {
    private static final float RECALL_KARMA = 15.0F;
    private static final ResourceLocation DEMON_ID = ResourceLocation.fromNamespaceAndPath("verity", "verity_demon");
    private static int tickCounter;

    private DemonRecallService() {
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.register(DemonRecallService.class);
    }

    public static void reset() {
        tickCounter = 0;
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || ++tickCounter < 20) return;
        tickCounter = 0;
        recallEligibleDemon(event.getServer());
    }

    private static void recallEligibleDemon(MinecraftServer server) {
        int recalled = 0;
        Entity firstDemon = null;
        for (ServerLevel level : server.getAllLevels()) {
            WorldSpawnData data = WorldSpawnData.get(level);
            if (data.verityKarma < RECALL_KARMA || data.activeDemon == null) continue;
            Entity demon = level.getEntity(data.activeDemon);
            if (demon == null || !DEMON_ID.equals(ForgeRegistries.ENTITY_TYPES.getKey(demon.getType()))) continue;

            if (firstDemon == null) firstDemon = demon;
            data.clearActiveDemon(demon.getUUID());
            level.sendParticles(ParticleTypes.PORTAL, demon.getX(), demon.getY() + 1.0, demon.getZ(),
                    48, 0.6, 1.0, 0.6, 0.15);
            level.playSound(null, demon.blockPosition(), SoundEvents.ENDERMAN_TELEPORT,
                    SoundSource.NEUTRAL, 1.0F, 1.1F);
            demon.discard();
            recalled++;
        }
        if (recalled == 0) return;

        VerityEntity verity = findVerity(server);
        OriginalVerityStateAccess.setMonstrous(false);
        if (verity != null) {
            verity.setMonstrous(false);
            verity.setVariant("happy");
            verity.startTalking(80);
            ServerPlayer player = nearestPlayer(server, verity, firstDemon);
            String prompt = "<SYSTEM> Your relationship with the player is now strong enough that you have voluntarily "
                    + "regained control and withdrawn your dangerous demon form. Reply with exactly one short, natural "
                    + "Chinese sentence in your current personality. Express relief, self-control, or appreciation. "
                    + "Do not mention karma values, thresholds, AI, prompts, code, or game mechanics.";
            try {
                AiManager.queryAI(verity, prompt, player);
            } catch (Throwable error) {
                broadcastFallback(server);
                OllamaProxyMod.LOGGER.warn("恶魔自动收回后的 AI 发言启动失败", error);
            }
        } else {
            broadcastFallback(server);
        }
        OllamaProxyMod.LOGGER.info("好感度达到 {}，Verity 主动收回 {} 个剧情恶魔", RECALL_KARMA, recalled);
    }

    private static VerityEntity findVerity(MinecraftServer server) {
        Object known = OriginalVerityStateAccess.getVerityEntity();
        if (known instanceof VerityEntity verity && verity.isAlive()) return verity;
        ResourceLocation verityId = ResourceLocation.fromNamespaceAndPath("verity", "verity");
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (verityId.equals(ForgeRegistries.ENTITY_TYPES.getKey(entity.getType()))
                        && entity instanceof VerityEntity verity && verity.isAlive()) {
                    OriginalVerityStateAccess.setVerityEntity(verity);
                    return verity;
                }
            }
        }
        return null;
    }

    private static ServerPlayer nearestPlayer(MinecraftServer server, VerityEntity verity, Entity demon) {
        if (verity.level() instanceof ServerLevel level) {
            net.minecraft.world.entity.player.Player nearest = level.getNearestPlayer(verity, 64.0D);
            if (nearest instanceof ServerPlayer player) return player;
        }
        if (demon != null && demon.level() instanceof ServerLevel level) {
            net.minecraft.world.entity.player.Player nearest = level.getNearestPlayer(demon, 64.0D);
            if (nearest instanceof ServerPlayer player) return player;
        }
        return server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
    }

    private static void broadcastFallback(MinecraftServer server) {
        String configured = String.valueOf(VerityConfig.VERITY_CUSTOM_NAME.get());
        String name = configured.isBlank() || "null".equals(configured) ? "Verity" : configured;
        server.getPlayerList().broadcastSystemMessage(
                Component.literal("<" + name + "> 我已经没事了，那个失控的我不会再伤害你了。"), false);
    }
}
