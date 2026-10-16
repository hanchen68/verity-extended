package com.hanchen.veritycustomapi.mixin;

import com.hanchen.veritycustomapi.skin.SkinManager;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import varmite.verity.environment.items.VerityVariants;
import varmite.verity.environment.items.client.VerityItemRenderer;

@Mixin(value = VerityItemRenderer.class, remap = false)
public class VerityItemRendererSkinMixin {
    @Redirect(
            method = "m_108829_",
            at = @At(
                    value = "INVOKE",
                    target = "Lvarmite/verity/environment/items/VerityVariants;entityTexture(Ljava/lang/String;)Lnet/minecraft/resources/ResourceLocation;"
            )
    )
    private ResourceLocation verityExtended$resolveItemTexture(String variant) {
        SkinManager skinManager = SkinManager.getInstance();
        if (!skinManager.hasActiveSkin()) {
            return VerityVariants.entityTexture(variant);
        }
        skinManager.setCurrentVariant(variant);
        return skinManager.getSkinTexture().getTextureId();
    }
}
