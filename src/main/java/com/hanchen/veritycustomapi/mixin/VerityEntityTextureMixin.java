package com.hanchen.veritycustomapi.mixin;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.skin.SkinManager;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import varmite.verity.entity.verity.rendering.VerityEntityTexture;

/**
 * 注入原版 VerityEntityTexture，让自定义皮肤也走原版色调（Hue）。
 * getTextureRL() 被替换后 setBaseTexture 无法加载资源，这里在 setBaseTexture 入口拦截，直接用皮肤像素填充 baseImage。
 */
@Mixin(value = VerityEntityTexture.class, remap = false)
public class VerityEntityTextureMixin {

    /** 有自定义皮肤时跳过资源加载，直接填充 baseImage。 */
    @Inject(method = "setBaseTexture", at = @At("HEAD"), cancellable = true)
    private static void onSetBaseTexture(ResourceLocation textureLocation, CallbackInfo ci) {
        SkinManager skinMgr = SkinManager.getInstance();
        if (!skinMgr.hasActiveSkin()) return;

        NativeImage ourSkin = skinMgr.getActiveSkinPixels();
        if (ourSkin == null) return;

        boolean replaced = false;
        try {
            Class<?> vtClass = VerityEntityTexture.class;

            // 获取/替换 baseImage（不 close 旧图像，避免 GPU 引用已释放纹理导致崩溃）
            java.lang.reflect.Field baseImageField = vtClass.getDeclaredField("baseImage");
            baseImageField.setAccessible(true);
            NativeImage newBase = new NativeImage(ourSkin.getWidth(), ourSkin.getHeight(), true);
            for (int y = 0; y < ourSkin.getHeight(); y++) {
                for (int x = 0; x < ourSkin.getWidth(); x++) {
                    newBase.setPixelRGBA(x, y, ourSkin.getPixelRGBA(x, y));
                }
            }
            baseImageField.set(null, newBase);

            // 确保 DynamicTexture 存在且尺寸匹配，避免 applyHue 下标越界或 NPE
            java.lang.reflect.Field texField = vtClass.getDeclaredField("texture");
            texField.setAccessible(true);
            DynamicTexture tex = (DynamicTexture) texField.get(null);
            boolean needNewTex = tex == null;
            if (!needNewTex) {
                NativeImage texPixels = tex.getPixels();
                if (texPixels != null && (texPixels.getWidth() != ourSkin.getWidth()
                        || texPixels.getHeight() != ourSkin.getHeight())) {
                    needNewTex = true;
                    // 不 close 旧 tex，防止 GPU 引用已释放纹理崩溃
                }
            }
            if (needNewTex) {
                DynamicTexture newTex = new DynamicTexture(
                        new NativeImage(ourSkin.getWidth(), ourSkin.getHeight(), true));
                Minecraft.getInstance().getTextureManager().register(
                        (ResourceLocation) vtClass.getMethod("id").invoke(null), newTex);
                texField.set(null, newTex);
            }

            // 记录当前纹理位置，避免下次重复处理
            java.lang.reflect.Field curTexField = vtClass.getDeclaredField("currentTexture");
            curTexField.setAccessible(true);
            curTexField.set(null, textureLocation);

            // 强制下次 applyHue 一定执行（lastHue 置为 -1）
            java.lang.reflect.Field lastHueField = vtClass.getDeclaredField("lastHue");
            lastHueField.setAccessible(true);
            lastHueField.set(null, -1);
            replaced = true;
        } catch (Exception e) {
            OllamaProxyMod.LOGGER.warn("[VerityEntityTextureMixin] 纹理替换失败: {}", e.toString());
        }
        if (replaced) {
            ci.cancel();
        }
    }
}
