package com.hanchen.veritycustomapi.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 各界面统一的背景处理。
 *
 * 世界里的界面用半透明淡幕透出世界；主菜单（{@code minecraft.level == null}）走原版不透明背景——
 * 主菜单背后是花哨的全景图，套半透明会糊成一团。设置界面从模组列表也能打开，不是边缘情况。
 *
 * 判据用 {@code minecraft.level == null} 而不是 {@code screen instanceof}：看的是「背后有没有世界」，
 * 谁打开的界面都无所谓。
 */
@OnlyIn(Dist.CLIENT)
public final class ScreenBackgrounds {

    /** 世界里的淡幕（约 30% 不透明）：透得出来，文字仍可读。 */
    public static final int SCRIM = 0x4D0B0D10;

    private ScreenBackgrounds() {
    }

    /** 按当前场合画背景。 */
    public static void draw(Screen screen, GuiGraphics graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
            // 主菜单：原版那层不透明暗幕 + 毛玻璃
            screen.renderBackground(graphics);
            return;
        }
        graphics.fill(0, 0, screen.width, screen.height, SCRIM);
    }
}
