package com.hanchen.veritycustomapi.mixin;

import com.hanchen.veritycustomapi.compat.CrossSavePursuit;
import com.hanchen.veritycustomapi.compat.VerityWorldStateGuard;
import com.hanchen.veritycustomapi.config.ModConfig;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import varmite.verity.entity.verity.VerityEntity;

@Mixin(value = VerityEntity.class, remap = false)
public abstract class VerityDemonTransformationMixin {
    @Inject(method = "transformIntoDemon", at = @At("HEAD"), cancellable = true, remap = false)
    private void verityExtended$controlDemonTransformation(Player player, boolean provoked,
                                                            CallbackInfoReturnable<Boolean> cir) {
        if (ModConfig.FRIENDLY_MODE.get()
                || !ModConfig.ALLOW_VERITY_DEMON_TRANSFORMATION.get()) {
            // 原版在调用本方法之前就已经把 doDaylightCycle 关掉了（恐怖永夜）。
            // 变身被我们取消后不会出现恶魔，也就永远等不到「恶魔被击败」的恢复时机，
            // 所以必须当场把时间恢复，否则世界会永久冻结。
            if (player != null) {
                VerityWorldStateGuard.restoreIfJustDisabled(player.level());
            }
            cir.setReturnValue(false);
        }
    }

    /** 变身成功后写全局标记；即使玩家删档/换世界，记录仍在 config 目录。 */
    @Inject(method = "transformIntoDemon", at = @At("RETURN"), require = 0, remap = false)
    private void verityExtended$markCrossSavePursuit(Player player, boolean provoked,
                                                     CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            CrossSavePursuit.markTriggered(player);
        }
    }
}
