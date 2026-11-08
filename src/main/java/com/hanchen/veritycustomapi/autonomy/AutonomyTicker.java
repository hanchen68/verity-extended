package com.hanchen.veritycustomapi.autonomy;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import varmite.verity.entity.verity.VerityEntity;

/** Independent of whether a Verity build calls Entity.tick/super.tick. Loaded entities only. */
@Mod.EventBusSubscriber(modid = OllamaProxyMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class AutonomyTicker {
    private AutonomyTicker() {}
    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        // 挖掘要按 tick 推进，不能等 20 tick 一次，否则挖一个方块像卡住
        com.hanchen.veritycustomapi.body.VerityMining.tickAll(event.getServer());
        if (event.getServer().getTickCount() % 20 != 0) return;
        for (var level : event.getServer().getAllLevels()) {
            for (var entity : level.getAllEntities()) {
                if (!(entity instanceof VerityEntity verity) || !verity.isAlive() || verity.isRemoved()) continue;
                try {
                    VerityAutonomy.tick(verity);
                    int tick = event.getServer().getTickCount();
                    if (tick % 10 == 0) {
                        com.hanchen.veritycustomapi.body.VerityShelter.tick(verity);
                        com.hanchen.veritycustomapi.body.VerityBlueprint.tick(verity);
                        if (com.hanchen.veritycustomapi.body.VeritySelfTest.running(verity)) {
                            var owner = VerityAutonomy.owner(verity);
                            if (owner != null) com.hanchen.veritycustomapi.body.VeritySelfTest.tick(verity, owner);
                        }
                    }
                    if (tick % 20 == 0) {
                        com.hanchen.veritycustomapi.body.VerityEquipment.sync(verity);
                        com.hanchen.veritycustomapi.body.VeritySmelting.tick(verity);
                        com.hanchen.veritycustomapi.body.VerityVitals.tick(verity);
                    }
                    if (tick % 40 == 0) {
                        com.hanchen.veritycustomapi.body.VeritySleep.tick(verity);
                    }
                    // 每 20 tick 回推一次状态：菜单靠它显示「开没开、在做什么」。
                    // 只发给同伴的主人，避免别人看到你的目标与诊断。
                    if (tick % 20 == 0) {
                        var owner = VerityAutonomy.owner(verity);
                        if (owner != null) {
                            com.hanchen.veritycustomapi.network.AutonomyNetwork.sendTo(owner, verity);
                        }
                    }
                    // 有身体之后，路过掉落物会自己捡——和玩家一样，不需要专门下指令
                    if (VerityAutonomy.state(verity).enabled && !VerityAutonomy.busy(verity)) {
                        com.hanchen.veritycustomapi.body.VerityBody.pickupNearby(verity);
                    }
                }
                catch (RuntimeException error) {
                    VerityAutonomy.State state = VerityAutonomy.state(verity);
                    String diagnostic = "自主更新失败：" + error.getClass().getSimpleName();
                    if (!diagnostic.equals(state.modelStatus)) OllamaProxyMod.LOGGER.warn("[Autonomy] entity={} {}", verity.getId(), diagnostic);
                    state.modelStatus = diagnostic;
                }
            }
        }
    }
}
