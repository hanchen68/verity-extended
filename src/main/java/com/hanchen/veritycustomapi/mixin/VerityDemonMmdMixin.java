package com.hanchen.veritycustomapi.mixin;

import com.hanchen.veritycustomapi.mmd.MmdPlaybackManager;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import software.bernie.geckolib.core.animation.AnimationState;
import varmite.verity.entity.demon.VerityDemonEntity;
import varmite.verity.entity.demon.rendering.VerityDemonModel;

/**
 * 在 VerityDemonModel.setCustomAnimations 末尾追加：若该恶魔有活跃的 MMD
 * 播放，则按 vmd 当前帧叠加骨骼旋转。该 mixin 只针对恶魔模型（GeoEntityRenderer
 * 渲染 VerityDemonEntity 时使用 VerityDemonModel），原版 Verity 不会进入此处。
 */
@Mixin(value = VerityDemonModel.class, remap = false)
public abstract class VerityDemonMmdMixin {
    @Inject(method = "setCustomAnimations", at = @At("TAIL"), remap = false)
    private void verityExtended$applyMmdToDemon(VerityDemonEntity animatable, long instanceId,
                                                AnimationState<VerityDemonEntity> animationState, CallbackInfo ci) {
        if (!(animatable instanceof Entity)) return;
        // mixin 里 this 就是被注入的模型实例
        VerityDemonModel self = (VerityDemonModel) (Object) this;
        MmdPlaybackManager.get().applyToDemonModel((Entity) animatable, self);
    }
}
