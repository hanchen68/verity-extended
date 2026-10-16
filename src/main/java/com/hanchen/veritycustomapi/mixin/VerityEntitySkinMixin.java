package com.hanchen.veritycustomapi.mixin;

import com.hanchen.veritycustomapi.skin.SkinManager;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import varmite.verity.entity.verity.VerityEntity;

/** 注入原版 VerityEntity.getTextureRL()，拦截纹理请求以应用自定义皮肤。 */
@Mixin(value = VerityEntity.class, remap = false)
public class VerityEntitySkinMixin {

    /** 返回前拦截：记录当前表情变体，有自定义皮肤则替换为动态纹理。 */
    @Inject(method = "getTextureRL", at = @At("RETURN"), cancellable = true)
    private void onGetTextureRL(CallbackInfoReturnable<ResourceLocation> cir) {
        ResourceLocation original = cir.getReturnValue();
        if (original == null) return;

        SkinManager skinManager = SkinManager.getInstance();
        if (!skinManager.hasActiveSkin()) return;

        String texturePath = original.toString();
        skinManager.setCurrentVariant(texturePath);

        cir.setReturnValue(skinManager.getSkinTexture().getTextureId());
    }
}
