package com.hanchen.veritycustomapi.body;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 玩家最近在干什么。
 *
 * <p><b>为什么需要</b>：之前 Verity 只知道玩家在哪、血量多少，不知道你在挖矿还是在盖房，
 * 所以只能自己做自己的事——那就成了「各玩各的」。要像伙伴，先得看见你在做什么。
 *
 * <p>只记最近一次动作，几十秒后自动过期；不做行为分析，也不上传。
 */
@Mod.EventBusSubscriber(modid = OllamaProxyMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CompanionActivity {

    /** 多久算「刚才」：40 秒。 */
    private static final long FRESH_MS = 40_000L;

    public record Activity(String kind, String blockId, BlockPos pos, long at) {
    }

    private static final Map<ServerPlayer, Activity> LAST = Collections.synchronizedMap(new WeakHashMap<>());

    private CompanionActivity() {
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        record(player, "挖方块", event.getState(), event.getPos());
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        record(player, "放方块", event.getPlacedBlock(), event.getPos());
    }

    private static void record(ServerPlayer player, String kind, BlockState state, BlockPos pos) {
        if (state == null || pos == null) return;
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        LAST.put(player, new Activity(kind, id, pos.immutable(), System.currentTimeMillis()));
    }

    /** 给提示词用的一句话；没有近期活动返回空串。 */
    public static String describe(ServerPlayer player) {
        Activity activity = LAST.get(player);
        if (activity == null) return "";
        if (System.currentTimeMillis() - activity.at() > FRESH_MS) return "";
        return activity.kind() + " " + activity.blockId()
                + " @ " + activity.pos().toShortString()
                + "（" + (System.currentTimeMillis() - activity.at()) / 1000 + " 秒前）";
    }

    /** 最近在挖的方块 ID，用于「一起挖同一种」。 */
    public static String recentBlock(ServerPlayer player, String kind) {
        Activity activity = LAST.get(player);
        if (activity == null) return "";
        if (!activity.kind().equals(kind)) return "";
        if (System.currentTimeMillis() - activity.at() > FRESH_MS) return "";
        return activity.blockId();
    }

    public static void clear() {
        LAST.clear();
    }
}
