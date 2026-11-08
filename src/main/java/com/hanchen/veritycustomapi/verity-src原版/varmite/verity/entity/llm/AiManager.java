/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.minecraft.network.chat.Component
 *  net.minecraft.server.level.ServerLevel
 *  net.minecraft.server.level.ServerPlayer
 *  net.minecraftforge.network.PacketDistributor
 */
package varmite.verity.entity.llm;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.PacketDistributor;
import varmite.verity.VerityConfig;
import varmite.verity.entity.llm.AiAPI;
import varmite.verity.entity.verity.VerityEntity;
import varmite.verity.event.WorldSpawnData;
import varmite.verity.network.ModNetwork;
import varmite.verity.network.PlayTtsPayload;

public class AiManager {
    public static void queryAI(VerityEntity verity, String query, ServerPlayer Player2) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        ServerLevel level = (ServerLevel)verity.m_9236_();
        long currentDay = level.m_46468_() / 24000L;
        float currentKarma = WorldSpawnData.get((ServerLevel)level).verityKarma;
        ((CompletableFuture)CompletableFuture.supplyAsync(() -> {
            Thread.currentThread().setContextClassLoader(classLoader);
            return AiAPI.ask(verity, Player2, query, currentDay, currentKarma);
        }).whenComplete((answer, throwable) -> {
            if (throwable != null) {
                System.err.println("Error in AiAPI.ask: " + throwable.getMessage());
                throwable.printStackTrace();
            } else {
                System.out.println("AiAPI response: " + answer);
            }
        })).thenAccept(answer -> verity.m_20194_().execute(() -> {
            if (verity != null && !verity.m_213877_()) {
                ModNetwork.INSTANCE.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> verity), (Object)new PlayTtsPayload(verity.m_19879_(), (String)answer));
            }
            String name = Optional.ofNullable((String)VerityConfig.VERITY_CUSTOM_NAME.get()).filter(n -> !n.isBlank()).orElse("Verity");
            verity.m_20194_().m_6846_().m_240416_((Component)Component.m_237113_((String)("<" + name + "> " + answer)), false);
        }));
    }
}

