package com.hanchen.veritycustomapi.mixin;

import com.hanchen.veritycustomapi.combat.MonstrousManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import varmite.verity.entity.demon.VerityDemonEntity;

/**
 * 屏蔽「深渊替身」身上的追击音效。
 *
 * <p>原版 {@code VerityDemonEntity} 自己会调
 * {@code ClientSoundHandler.playDemonChaseSound(this)}——那是一条压迫感很强的循环音效，
 * 是「恶魔在追你」这个恐怖事件的一部分。我们召唤的替身只是打手，不该给玩家放这段。
 *
 * <p>客户端拿不到 ForgeData（不同步），所以判定走
 * {@link MonstrousManager#isGuardian}：持久标记 + 自定义名「深渊替身」两条。
 *
 * <p>{@code require = 0}：原版改签名时只是音效没屏蔽掉，不能因此让客户端类加载失败。
 */
@Mixin(targets = "varmite.verity.client.sound.ClientSoundHandler", remap = false)
public abstract class GuardianDemonSoundMixin {

    @Inject(method = "playDemonChaseSound", at = @At("HEAD"), cancellable = true,
            remap = false, require = 0)
    private static void verityExt$skipGuardianChaseSound(VerityDemonEntity demon, CallbackInfo ci) {
        if (MonstrousManager.isGuardian(demon)) {
            ci.cancel();
        }
    }
}
