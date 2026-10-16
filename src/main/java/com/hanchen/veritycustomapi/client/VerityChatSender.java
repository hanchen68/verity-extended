package com.hanchen.veritycustomapi.client;

import net.minecraft.client.Minecraft;

/**
 * 把一句话投递给 Verity 的统一入口。
 *
 * 多人游戏里服务端适配默认要求用 /v 指令触发（{@code requireCommand}），
 * 普通聊天会被服务端拦掉——既没有回复也没有提示，玩家看到的就像功能失灵。
 * 所以这里先看服务器的命令表里有没有 {@code /v}（有就说明服务端装了本 mod），
 * 有就走 {@code /v 内容}，没有才退回普通聊天（保持服务器没装本 mod 时的原有行为）。
 *
 * 使用方：语音输入（{@code SpeechRecognitionManager}、{@code OriginalVeritySttInjector}）
 * 与 K 键快捷菜单的对话类条目。
 */
public final class VerityChatSender {
    private VerityChatSender() {
    }

    /**
     * 发送一句给 Verity 的话。
     *
     * @return {@code true} 表示走了 /v 指令（服务端装了本 mod）；{@code false} 表示退化成普通聊天
     */
    public static boolean sendToVerity(String text) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null || text == null || text.isBlank()) return false;
        if (hasServerVerityCommand(minecraft)) {
            minecraft.player.connection.sendCommand("v " + text);
            return true;
        }
        minecraft.player.connection.sendChat(text);
        return false;
    }

    /** 服务器命令表里是否存在 /v（由本 mod 的服务端适配提供）。 */
    public static boolean hasServerVerityCommand(Minecraft minecraft) {
        try {
            if (minecraft == null || minecraft.player == null) return false;
            var commands = minecraft.player.connection.getCommands();
            return commands != null && commands.getRoot().getChild("v") != null;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
