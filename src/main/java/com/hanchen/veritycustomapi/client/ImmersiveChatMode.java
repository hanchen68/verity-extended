package com.hanchen.veritycustomapi.client;

import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;

/**
 * 沉浸式对话模式判定：四个条件都满足时隐藏原版聊天栏（按 T 打开聊天框时放行）。
 * 挂在每帧的聊天栏渲染入口上，只做很轻的检查，状态变了就打条日志。
 */
public final class ImmersiveChatMode {
    /** 上一次的判定结果，仅在跨线程首帧为 null。 */
    private static volatile Boolean lastState;

    private ImmersiveChatMode() {
    }

    public static boolean shouldHideVanillaChat() {
        boolean hide = compute();
        Boolean previous = lastState;
        if (previous == null || previous != hide) {
            lastState = hide;
            OllamaProxyMod.LOGGER.info("沉浸式对话：原版聊天栏{}", hide ? "已隐藏" : "已恢复显示");
        }
        return hide;
    }

    private static boolean compute() {
        try {
            if (!ConfigManager.isImmersiveChatEnabled() || !ConfigManager.isDialogueHudEnabled()) {
                // 气泡没开就不隐藏，否则玩家没有任何地方能看到消息
                return false;
            }
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null || minecraft.player == null || minecraft.level == null) {
                return false;
            }
            // 打开聊天框（按 T）时必须正常显示历史与输入框
            return !(minecraft.screen instanceof ChatScreen);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
