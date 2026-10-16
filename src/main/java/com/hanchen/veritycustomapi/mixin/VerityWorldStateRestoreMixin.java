package com.hanchen.veritycustomapi.mixin;

import com.hanchen.veritycustomapi.compat.VerityWorldStateGuard;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import varmite.verity.entity.demon.VerityDemonEntity;

/**
 * 修复恶魔被击败后世界仍停在死寂状态的问题：原版 ModEvents.onDeath 只恢复了 Verity 状态，
 * 没恢复变身关掉的 doDaylightCycle 和第 2 天事件关掉的 doMobSpawning。
 * onDeath（正常击杀）与 onDemonLeaveLevel（discard 等非死亡移除）各挂 TAIL 恢复；require = 0，方法属 mod 自身不混淆故 remap = false 直接写名。
 */
@Mixin(targets = "varmite.verity.event.ModEvents", remap = false)
public abstract class VerityWorldStateRestoreMixin {
    @Inject(method = "onDeath", at = @At("TAIL"), require = 0, remap = false)
    private static void verityExtended$restoreWorldAfterDemonDeath(LivingDeathEvent event, CallbackInfo callback) {
        if (event.getEntity() instanceof VerityDemonEntity demon) {
            VerityWorldStateGuard.restore(demon.level());
        }
    }

    @Inject(method = "onDemonLeaveLevel", at = @At("TAIL"), require = 0, remap = false)
    private static void verityExtended$restoreWorldAfterDemonLeave(EntityLeaveLevelEvent event, CallbackInfo callback) {
        if (event.getEntity() instanceof VerityDemonEntity) {
            VerityWorldStateGuard.restore(event.getLevel());
        }
    }
}
