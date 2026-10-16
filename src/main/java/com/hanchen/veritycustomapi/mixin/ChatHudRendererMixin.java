package com.hanchen.veritycustomapi.mixin;

import com.hanchen.veritycustomapi.client.ImmersiveChatMode;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 沉浸式对话模式：让原版聊天栏平时不绘制。
 * 1.20.1 中 HUD 滚动文字和按 T 打开的历史都由 ChatComponent#render(GuiGraphics,int,int,int) 绘制，ChatScreen 只画输入框与背景，只能拦这一处。
 * 是否拦截交给 {@link ImmersiveChatMode}（聊天框打开时放行）；require = 0，注入失败只记日志，最坏退化成聊天栏不隐藏。
 */
@Mixin(net.minecraft.client.gui.components.ChatComponent.class)
public class ChatHudRendererMixin {

    @Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;III)V",
            at = @At("HEAD"), cancellable = true, require = 0)
    private void verity$hideChatInImmersiveMode(GuiGraphics graphics, int tickCount,
                                                int mouseX, int mouseY, CallbackInfo callback) {
        if (ImmersiveChatMode.shouldHideVanillaChat()) {
            callback.cancel();
        }
    }
}
