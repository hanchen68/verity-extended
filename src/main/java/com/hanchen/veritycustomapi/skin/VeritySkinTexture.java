package com.hanchen.veritycustomapi.skin;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;


public class VeritySkinTexture {
    private static final ResourceLocation TEX_ID = ResourceLocation.tryParse("verity_extended:dynamic/skin");

    private DynamicTexture dynamicTexture;
    private volatile boolean ready;

    /**
     * 创建初始动态纹理并注册到 Minecraft TextureManager
     */
    public void init() {
        if (ready) return;
        try {
            // 创建 1x1 初始纹理
            NativeImage image = new NativeImage(1, 1, false);
            image.setPixelRGBA(0, 0, 0xFFFFFFFF);
            dynamicTexture = new DynamicTexture(image);
            Minecraft.getInstance().getTextureManager().register(TEX_ID, dynamicTexture);
            ready = true;
        } catch (Exception e) {
            // 静默失败，保持 ready = false
        }
    }

    /**
     * 从输入流加载 PNG 纹理并覆盖到动态纹理上。
     * GL 操作必须在渲染线程执行，非渲染线程调用时会自动调度。
     */
    public void applyTexture(InputStream pngStream) throws IOException {
        NativeImage newImage = NativeImage.read(pngStream);
        if (newImage == null) {
            com.hanchen.veritycustomapi.OllamaProxyMod.LOGGER.warn("[SkinTexture] PNG 读取失败");
            return;
        }
        com.hanchen.veritycustomapi.OllamaProxyMod.LOGGER.info("[SkinTexture] 应用纹理: {}x{}", newImage.getWidth(), newImage.getHeight());

        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            com.hanchen.veritycustomapi.OllamaProxyMod.LOGGER.warn("[SkinTexture] Minecraft 实例为空，无法应用纹理");
            return;
        }

        if (com.mojang.blaze3d.systems.RenderSystem.isOnRenderThread()) {
            doApplyTexture(newImage);
        } else {
            final NativeImage finalImage = newImage;
            mc.execute(() -> doApplyTexture(finalImage));
        }
        com.hanchen.veritycustomapi.OllamaProxyMod.LOGGER.info("[SkinTexture] 纹理调度完成");
    }

    private void doApplyTexture(NativeImage newImage) {
        DynamicTexture oldTex = dynamicTexture;
        DynamicTexture newTex = new DynamicTexture(newImage);
        Minecraft.getInstance().getTextureManager().register(TEX_ID, newTex);
        dynamicTexture = newTex;
        ready = true;
        // 延迟一帧关闭旧纹理，确保 GPU 不再引用
        if (oldTex != null && oldTex != newTex) {
            com.mojang.blaze3d.systems.RenderSystem.recordRenderCall(() -> {
                try { oldTex.close(); } catch (Exception ignored) {}
            });
        }
    }

    /**
     * 返回纹理的 ResourceLocation，用于渲染时绑定
     */
    public ResourceLocation getTextureId() {
        return TEX_ID;
    }

    public boolean isReady() {
        return ready;
    }

    /** 返回当前动态纹理的 NativeImage 像素（用于色调系统等） */
    public NativeImage getPixels() {
        return dynamicTexture != null ? dynamicTexture.getPixels() : null;
    }

    /**
     * 释放 GPU 资源
     */
    public void close() {
        if (dynamicTexture != null) {
            dynamicTexture.close();
            dynamicTexture = null;
        }
        ready = false;
    }
}
