package com.hanchen.veritycustomapi.gift;

import com.hanchen.veritycustomapi.config.ModConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import varmite.verity.entity.verity.VerityEntity;

/**
 * 「潜行 + 丢弃物品」送礼。
 *
 * <p>为什么不用右键：原版 6.2 自带「手持物品右键」的物品交接，扩展再抢右键就会互相顶掉；
 * 丢弃是原版没有占用的手势，也不会影响骑乘（潜行 + 空手右键）和普通右键。
 *
 * <p>时序很关键：{@link ItemTossEvent} 触发时物品<b>已经从背包扣除</b>，
 * 取消只阻止它掉到地上。因此必须先让 {@link VerityGiftService#tryAccept} 判定成功，
 * 再取消事件；判定失败就不取消，物品会正常掉落，玩家还能捡回来。
 */
public final class VerityGiftEvents {
    private VerityGiftEvents() {
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.register(VerityGiftEvents.class);
    }

    @SubscribeEvent
    public static void onItemToss(ItemTossEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        if (player.level().isClientSide()) return;
        if (!player.isShiftKeyDown()) return;
        if (!Boolean.TRUE.equals(ModConfig.ALLOW_VERITY_GIFT.get())) return;

        VerityEntity verity = VerityGiftService.findTarget(player);
        if (verity == null) return;

        ItemStack gift = event.getEntity().getItem();
        if (!VerityGiftService.tryAccept(verity, player, gift)) {
            // 不受理就不取消：物品正常掉落，避免「冷却中丢一下就没了」
            return;
        }
        event.setCanceled(true);
    }
}
