package com.hanchen.veritycustomapi.behavior;

import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.network.ServerVerityRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import varmite.verity.entity.verity.VerityEntity;

/**
 * 「护主」：玩家血量掉到一半以下时，身边的 Verity 主动给一个短时间治疗。
 *
 * <p>玩家反馈的原话是「血条降至一半上 buff 什么的」——要的是**Verity 会关心你的状态**，
 * 而不是一个随时生效的自动回血。所以这里的边界都很保守：
 * <ul>
 *   <li>只在 <b>50% 以下</b> 触发，满血/轻伤不打扰；</li>
 *   <li><b>30 秒最多一次</b>（时间戳写在玩家的 persistentData 里，
 *       不用 static 缓存——换维度、重进存档都跟着玩家走，也不需要额外清理）；</li>
 *   <li>只在 Verity <b>活着、不是恶魔形态、且在 16 格内</b> 时触发——
 *       不然玩家在矿洞底下 Verity 在地表也能隔空奶，太假；</li>
 *   <li>不消耗物品、不消耗额度，就是一段 8 秒的再生 I。</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = OllamaProxyMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class VeritySupportHandler {
    private VeritySupportHandler() {}

    /** 血量比例阈值：掉到这个比例以下才帮忙。 */
    private static final float HEALTH_THRESHOLD = 0.5F;
    /** 两次帮忙之间的冷却（毫秒）。 */
    private static final long COOLDOWN_MS = 30_000L;
    /** Verity 离玩家超过这个距离就不管了（平方）。 */
    private static final double MAX_DISTANCE_SQR = 16.0D * 16.0D;
    /** 再生时长（tick）：8 秒，够撑过一次战斗但不至于变成自动回血。 */
    private static final int REGEN_TICKS = 160;
    /** persistentData 里记录上次帮忙时间的键。 */
    private static final String LAST_HELP_TAG = "verity_extended_help_at";

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof ServerPlayer player)) return;
        // 每秒检查一次就够，没必要每 tick 算
        if (player.tickCount % 20 != 0) return;
        if (!ConfigManager.isVerityHelpEnabled()) return;

        float maxHealth = player.getMaxHealth();
        if (maxHealth <= 0.0F) return;
        if (player.getHealth() <= 0.0F) return;
        if (player.getHealth() > maxHealth * HEALTH_THRESHOLD) return;

        long now = System.currentTimeMillis();
        long last = player.getPersistentData().getLong(LAST_HELP_TAG);
        if (last > 0L && now - last < COOLDOWN_MS) return;

        VerityEntity verity = ServerVerityRegistry.findForPlayer(player);
        if (verity == null || !verity.isAlive()) return;
        if (verity.isMonstrous()) return;
        if (verity.level() != player.level()) return;
        if (verity.distanceToSqr(player) > MAX_DISTANCE_SQR) return;

        player.getPersistentData().putLong(LAST_HELP_TAG, now);
        player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, REGEN_TICKS, 0));
        // 当作 Verity 自己说的一句话发到聊天栏：格式和「狠话」一致（`<Verity> …`），
        // 不加颜色——上色会让 Verity 看起来像系统提示，而不是在跟你说话。
        player.sendSystemMessage(Component.literal("<Verity> 撑住，我在。"));
        OllamaProxyMod.LOGGER.info("Verity 给低血量玩家上了治疗: {}（{}/{}）",
                player.getGameProfile().getName(),
                String.format("%.1f", player.getHealth()),
                String.format("%.1f", maxHealth));
    }
}
