package com.hanchen.veritycustomapi.mixin;

import com.hanchen.veritycustomapi.compat.KarmaDailyCap;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 好感度每日上限。
 *
 * <p>原版 AI 的 {@code increase_karma} 工具、扩展的喂食/送礼最终都会调用
 * {@code ModEvents.updateAndSyncKarma}；在这里统一按当天额度拦截正增量。
 * 原版剧情里直接把 karma 设成 20 的路径不走这个方法，因此不会被误伤。
 *
 * <p>{@code require = 0}：万一以后原版改签名，只是上限失效，不能因为注入失败让游戏起不来。
 */
@Mixin(targets = "varmite.verity.event.ModEvents", remap = false)
public abstract class VerityKarmaCapMixin {
    @Inject(method = "updateAndSyncKarma", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void verityExt$capDailyKarma(ServerLevel level, float amount, CallbackInfo callback) {
        if (amount > 0.0F && !KarmaDailyCap.tryConsume(level, amount)) {
            callback.cancel();
        }
    }
}
