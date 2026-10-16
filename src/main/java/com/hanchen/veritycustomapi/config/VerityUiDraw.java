package com.hanchen.veritycustomapi.config;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 界面绘制原语：圆角矩形、圆角描边、药丸、开关。
 *
 * <p>{@link GuiGraphics} 只有 {@code fill}（实心矩形）和 {@code blit}（贴图），
 * 没有圆角、没有描边。整套新视觉靠「用若干行 1px 高的矩形拼出圆角」实现，
 * 半径 5 时每个圆角只要 5 行、整个矩形约 10 次 fill，开销可以忽略。
 *
 * <p>为什么值得这么做：这套界面的背景是半透明的世界，方角实心块会显得又硬又脏；
 * 圆角 + 细描边才能让卡片「浮」在世界上。这是原版控件（浅色描边方块）做不到的。
 */
@OnlyIn(Dist.CLIENT)
public final class VerityUiDraw {

    private VerityUiDraw() {
    }

    /**
     * 圆角实心矩形。
     *
     * <p>上下各 {@code radius} 行按圆弧收缩，中间一次性填满。
     */
    public static void fillRounded(GuiGraphics g, int left, int top, int right, int bottom,
                                   int radius, int color) {
        if (right <= left || bottom <= top) return;
        int r = Math.max(0, Math.min(radius, Math.min((right - left) / 2, (bottom - top) / 2)));
        if (r == 0) {
            g.fill(left, top, right, bottom, color);
            return;
        }
        // 上下两段圆弧：每一行算出左右各缩进多少
        for (int i = 0; i < r; i++) {
            int inset = insetAt(r, i);
            g.fill(left + inset, top + i, right - inset, top + i + 1, color);
            g.fill(left + inset, bottom - i - 1, right - inset, bottom - i, color);
        }
        g.fill(left, top + r, right, bottom - r, color);
    }

    /**
     * 圆角描边（只画 1px 轮廓，内部不填）。
     *
     * <p>描边是半透明背景上划分层次最有效的手段——没有它，卡片会和背后的世界糊在一起。
     */
    public static void strokeRounded(GuiGraphics g, int left, int top, int right, int bottom,
                                     int radius, int color) {
        if (right <= left || bottom <= top) return;
        int r = Math.max(0, Math.min(radius, Math.min((right - left) / 2, (bottom - top) / 2)));
        if (r == 0) {
            g.fill(left, top, right, top + 1, color);
            g.fill(left, bottom - 1, right, bottom, color);
            g.fill(left, top, left + 1, bottom, color);
            g.fill(right - 1, top, right, bottom, color);
            return;
        }
        for (int i = 0; i < r; i++) {
            int inset = insetAt(r, i);
            g.fill(left + inset, top + i, right - inset, top + i + 1, color);
            g.fill(left + inset, bottom - i - 1, right - inset, bottom - i, color);
        }
        g.fill(left, top + r, left + 1, bottom - r, color);
        g.fill(right - 1, top + r, right, bottom - r, color);
    }

    /** 卡片 = 投影 + 圆角底 + 圆角描边。 */
    public static void card(GuiGraphics g, int left, int top, int right, int bottom, int radius) {
        // 投影：往下偏移 2px、左右各内缩 2px，露出一点就够，太多会显脏
        fillRounded(g, left + 2, bottom, right + 2, bottom + 2, radius, VerityUiTheme.CARD_SHADOW);
        fillRounded(g, left, top, right, bottom, radius, VerityUiTheme.CARD);
        strokeRounded(g, left, top, right, bottom, radius, VerityUiTheme.CARD_BORDER);
    }

    /** 抬升层（标题栏、分组头）：比卡片实一档，用于区分层次。 */
    public static void raised(GuiGraphics g, int left, int top, int right, int bottom, int radius) {
        fillRounded(g, left, top, right, bottom, radius, VerityUiTheme.CARD_RAISED);
    }

    /**
     * 药丸形开关：轨道 + 滑块。
     *
     * <p>替换原版 {@code Checkbox}——那个是浅色描边的方块，在深色卡片上像贴了张便利贴。
     */
    public static void switchPill(GuiGraphics g, int left, int top, int width, int height,
                                  boolean on, boolean hovered) {
        int radius = height / 2;
        int track = on
                ? (hovered ? brighten(VerityUiTheme.CONTROL_ON) : VerityUiTheme.CONTROL_ON)
                : (hovered ? 0x73FFFFFF : VerityUiTheme.CONTROL_TRACK);
        fillRounded(g, left, top, left + width, top + height, radius, track);
        if (!on) {
            strokeRounded(g, left, top, left + width, top + height, radius,
                    VerityUiTheme.CONTROL_BORDER);
        }
        int knobSize = height - 4;
        int knobX = on ? left + width - knobSize - 2 : left + 2;
        fillRounded(g, knobX, top + 2, knobX + knobSize, top + 2 + knobSize,
                knobSize / 2, VerityUiTheme.CONTROL_KNOB);
    }

    /**
     * 提高不透明度一档，用于**半透明**按钮的悬停反馈。
     *
     * <p>为什么不能只用 {@link #brighten}：它只在 RGB 上加值，而
     * 淡底按钮的 RGB 本来就是接近白色的中性色（`0x1FFFFFFF`），
     * 加到 255 就封顶了——悬停完全看不出变化。改 alpha 才有效。
     */
    public static int raiseAlpha(int argb, int delta) {
        int a = Math.min(255, ((argb >>> 24) & 0xFF) + delta);
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    /** 把颜色提亮一档，用于实色按钮的悬停反馈。 */
    public static int brighten(int argb) {
        int a = (argb >>> 24) & 0xFF;
        int r = Math.min(255, ((argb >> 16) & 0xFF) + 26);
        int g = Math.min(255, ((argb >> 8) & 0xFF) + 26);
        int b = Math.min(255, (argb & 0xFF) + 26);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * 第 {@code row} 行（自上而下，0 起）在半径 {@code r} 的圆角里需要左右各缩进多少。
     *
     * <p>取该行中心处的圆弧位置，四舍五入到整像素，避免圆角出现锯齿断层。
     */
    private static int insetAt(int r, int row) {
        double dy = r - row - 0.5;
        double dx = r - Math.sqrt(Math.max(0.0, r * r - dy * dy));
        return (int) Math.round(dx);
    }
}
