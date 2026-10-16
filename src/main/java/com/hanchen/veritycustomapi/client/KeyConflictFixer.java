package com.hanchen.veritycustomapi.client;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.settings.IKeyConflictContext;
import net.minecraftforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 启动时扫一遍本模组按键跟原版/别的模组的冲突。只在用户没改过默认键时才自动改绑到
 * 候选列表里第一个空闲键。这些键都能在「选项 -> 控制 -> Verity 扩展增强」里自己改。
 */
public final class KeyConflictFixer {
    private static final List<String> NOTICES = new ArrayList<>();
    private static boolean shown;
    private static boolean ran;

    /** 每个按键的候选键位，第一个是默认键，后面是备选。 */
    private static final int[][] CANDIDATES = {
            {GLFW.GLFW_KEY_V, GLFW.GLFW_KEY_CAPS_LOCK,
                    GLFW.GLFW_KEY_RIGHT_BRACKET, GLFW.GLFW_KEY_GRAVE_ACCENT,
                    GLFW.GLFW_KEY_SEMICOLON, GLFW.GLFW_KEY_APOSTROPHE,
                    GLFW.GLFW_KEY_LEFT_BRACKET, GLFW.GLFW_KEY_BACKSLASH,
                    GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_EQUAL,
                    GLFW.GLFW_KEY_COMMA, GLFW.GLFW_KEY_PERIOD,
                    GLFW.GLFW_KEY_F4, GLFW.GLFW_KEY_F6, GLFW.GLFW_KEY_F7,
                    GLFW.GLFW_KEY_F8, GLFW.GLFW_KEY_F9,
                    GLFW.GLFW_KEY_INSERT, GLFW.GLFW_KEY_HOME, GLFW.GLFW_KEY_END,
                    GLFW.GLFW_KEY_PAGE_UP, GLFW.GLFW_KEY_PAGE_DOWN},
            {GLFW.GLFW_KEY_N, GLFW.GLFW_KEY_M,
                    GLFW.GLFW_KEY_LEFT_BRACKET, GLFW.GLFW_KEY_SEMICOLON,
                    GLFW.GLFW_KEY_APOSTROPHE, GLFW.GLFW_KEY_GRAVE_ACCENT,
                    GLFW.GLFW_KEY_RIGHT_BRACKET, GLFW.GLFW_KEY_BACKSLASH,
                    GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_EQUAL,
                    GLFW.GLFW_KEY_F4, GLFW.GLFW_KEY_F6, GLFW.GLFW_KEY_F7,
                    GLFW.GLFW_KEY_F8, GLFW.GLFW_KEY_INSERT, GLFW.GLFW_KEY_HOME,
                    GLFW.GLFW_KEY_PAGE_UP, GLFW.GLFW_KEY_PAGE_DOWN},
            {GLFW.GLFW_KEY_O, GLFW.GLFW_KEY_G,
                    GLFW.GLFW_KEY_RIGHT_BRACKET, GLFW.GLFW_KEY_SEMICOLON,
                    GLFW.GLFW_KEY_APOSTROPHE, GLFW.GLFW_KEY_GRAVE_ACCENT,
                    GLFW.GLFW_KEY_LEFT_BRACKET, GLFW.GLFW_KEY_BACKSLASH,
                    GLFW.GLFW_KEY_F6, GLFW.GLFW_KEY_F7, GLFW.GLFW_KEY_F8,
                    GLFW.GLFW_KEY_F9, GLFW.GLFW_KEY_INSERT, GLFW.GLFW_KEY_HOME,
                    GLFW.GLFW_KEY_END, GLFW.GLFW_KEY_PAGE_UP, GLFW.GLFW_KEY_PAGE_DOWN},
            {GLFW.GLFW_KEY_K, GLFW.GLFW_KEY_B,
                    GLFW.GLFW_KEY_RIGHT_BRACKET, GLFW.GLFW_KEY_SEMICOLON,
                    GLFW.GLFW_KEY_APOSTROPHE, GLFW.GLFW_KEY_GRAVE_ACCENT,
                    GLFW.GLFW_KEY_LEFT_BRACKET, GLFW.GLFW_KEY_BACKSLASH,
                    GLFW.GLFW_KEY_F6, GLFW.GLFW_KEY_F7, GLFW.GLFW_KEY_F8,
                    GLFW.GLFW_KEY_F9, GLFW.GLFW_KEY_INSERT, GLFW.GLFW_KEY_HOME,
                    GLFW.GLFW_KEY_END, GLFW.GLFW_KEY_PAGE_UP, GLFW.GLFW_KEY_PAGE_DOWN},
            {GLFW.GLFW_KEY_H,
                    GLFW.GLFW_KEY_RIGHT_BRACKET, GLFW.GLFW_KEY_SEMICOLON,
                    GLFW.GLFW_KEY_APOSTROPHE, GLFW.GLFW_KEY_GRAVE_ACCENT,
                    GLFW.GLFW_KEY_LEFT_BRACKET, GLFW.GLFW_KEY_BACKSLASH,
                    GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_EQUAL,
                    GLFW.GLFW_KEY_F6, GLFW.GLFW_KEY_F7, GLFW.GLFW_KEY_F8,
                    GLFW.GLFW_KEY_F9, GLFW.GLFW_KEY_INSERT, GLFW.GLFW_KEY_HOME,
                    GLFW.GLFW_KEY_END, GLFW.GLFW_KEY_PAGE_UP, GLFW.GLFW_KEY_PAGE_DOWN},
            };

    private static final KeyMapping[] OUR_KEYS = {
            KeyBindings.SPEECH_KEY,
            KeyBindings.MMD_CONTROL_KEY,
            KeyBindings.CAMERA_LOOK_KEY,
            KeyBindings.QUICK_COMMAND_KEY,
            KeyBindings.HEAD_PERCH_KEY
    };

    private KeyConflictFixer() {
    }

    /** 首个客户端 tick 执行一次：此时所有模组按键已注册、用户键位设置已加载。 */
    public static void runOnce() {
        if (ran) {
            showNoticesOnce();
            return;
        }
        ran = true;
        run();
    }

    public static void run() {
        NOTICES.clear();
        shown = false;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options == null) return;
        KeyMapping[] all = minecraft.options.keyMappings;

        boolean changed = false;
        for (int i = 0; i < OUR_KEYS.length; i++) {
            KeyMapping mapping = OUR_KEYS[i];
            int[] candidates = CANDIDATES[i];
            int currentCode = keyCode(mapping);
            // 用户自定义过的键位不碰（当前键不等于出厂默认）
            if (currentCode != candidates[0]) continue;

            for (int code : candidates) {
                if (!isOccupied(all, mapping, code)) {
                    if (code != currentCode) {
                        mapping.setKey(InputConstants.Type.KEYSYM.getOrCreate(code));
                        NOTICES.add(Component.translatable(mapping.getName()).getString()
                                + ": " + keyName(currentCode) + " → " + keyName(code));
                        changed = true;
                    }
                    break;
                }
            }
        }

        if (changed) {
            KeyMapping.resetMapping();
            for (String notice : NOTICES) {
                OllamaProxyMod.LOGGER.info("[按键避让] {}", notice);
            }
        }
    }

    /** 进世界后把改绑结果提示一次。 */
    public static void showNoticesOnce() {
        if (shown || NOTICES.isEmpty()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) return;
        shown = true;
        minecraft.player.sendSystemMessage(Component.literal(
                "检测到按键冲突，已自动改绑（可在 选项→控制→Verity 扩展增强 里自行调整）：")
                .withStyle(ChatFormatting.YELLOW));
        for (String notice : NOTICES) {
            minecraft.player.sendSystemMessage(Component.literal("• " + notice)
                    .withStyle(ChatFormatting.GOLD));
        }
    }

    private static int keyCode(KeyMapping mapping) {
        InputConstants.Key key = mapping.getKey();
        return key.getType() == InputConstants.Type.KEYSYM ? key.getValue() : -999;
    }

    private static boolean isOccupied(KeyMapping[] all, KeyMapping self, int code) {
        for (KeyMapping other : all) {
            if (other == self) continue;
            InputConstants.Key key = other.getKey();
            if (key.getType() != InputConstants.Type.KEYSYM || key.getValue() != code) continue;
            if (contextsOverlap(self.getKeyConflictContext(), other.getKeyConflictContext())) {
                return true;
            }
        }
        return false;
    }

    private static boolean contextsOverlap(IKeyConflictContext a, IKeyConflictContext b) {
        if (a == b) return true;
        return a == KeyConflictContext.UNIVERSAL || b == KeyConflictContext.UNIVERSAL;
    }

    private static String keyName(int code) {
        Component name = InputConstants.Type.KEYSYM.getOrCreate(code).getDisplayName();
        return name.getString();
    }
}