package com.hanchen.veritycustomapi.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/** 客户端专属：取本地玩家的 UUID / 名字。memory 侧只经带 dist 判断的反射调它，免得服务端去加载客户端类。 */
public final class MemoryPlayerContext {
    private static final String NONE = "";

    private MemoryPlayerContext() {
    }

    /** @return 本地玩家 UUID 字符串；不在世界内时返回空串。 */
    public static String localPlayerId() {
        try {
            LocalPlayer player = Minecraft.getInstance().player;
            return player == null ? NONE : player.getUUID().toString();
        } catch (Throwable ignored) {
            return NONE;
        }
    }

    /** @return 本地玩家显示名；不在世界内时返回空串。 */
    public static String localPlayerName() {
        try {
            LocalPlayer player = Minecraft.getInstance().player;
            return player == null ? NONE : player.getGameProfile().getName();
        } catch (Throwable ignored) {
            return NONE;
        }
    }
}
