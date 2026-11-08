package com.hanchen.veritycustomapi.client;

import com.hanchen.veritycustomapi.config.VerityUiDraw;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * Window backdrop shared by every mod screen.
 *
 * <p>Opaque panels sit inside an inset frame; the area around them only dims the world,
 * so the player keeps their bearings while a menu is open. Panels themselves stay fully
 * opaque — translucency is limited to the surround.
 */
@OnlyIn(Dist.CLIENT)
public final class ScreenBackgrounds {
    /** Dark scrim around windows: the world stays readable, text stays legible. */
    public static final int SCRIM = 0xC8101010;
    private ScreenBackgrounds() {}

    public static void draw(Screen screen, GuiGraphics graphics) {
        // No loaded level means the title screen: nothing behind the menu is worth showing,
        // so fill it completely instead of letting the panorama compete with the settings.
        if (com.hanchen.veritycustomapi.config.VerityMenuContext.fullScreenWindow(
                net.minecraft.client.Minecraft.getInstance())) {
            graphics.flush();
            VerityUiDraw.fillOpaqueNow(graphics, 0, 0, screen.width, screen.height,
                    com.hanchen.veritycustomapi.config.VerityUiTheme.BACKGROUND);
            return;
        }
        graphics.fill(0, 0, screen.width, screen.height, SCRIM);
    }
}
