package com.hanchen.veritycustomapi.config;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;

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
     * 立即提交一个**完全不透明**的矩形，绕过 {@link GuiGraphics} 的批量管线。
     *
     * <p>为什么需要这个：{@code GuiGraphics.fill} 只是把顶点塞进
     * {@code RenderType.gui()} 的缓冲，真正光栅化要等 {@code flush()}。
     * 而 {@code RenderType.gui()} 自带 {@code TRANSLUCENT_TRANSPARENCY}——
     * flush 时会把混合重新打开，于是「颜色 alpha = FF」只代表顶点 alpha 是 1，
     * 最终像素仍然由混合公式决定。想让一块底**无论什么状态都实心**，
     * 唯一可靠的办法是自己在关闭混合的状态下立即提交。
     *
     * <p>用途：说明浮层这类「必须盖住底下所有内容」的面板。
     *
     * @param argb 颜色；alpha 会被忽略（因为混合是关的），传 {@code 0xFF......} 即可
     */
    public static void fillOpaqueNow(GuiGraphics g, int left, int top, int right, int bottom, int argb) {
        if (right <= left || bottom <= top) return;
        Matrix4f matrix = g.pose().last().pose();
        int r = (argb >> 16) & 0xFF;
        int green = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        RenderSystem.disableDepthTest();
        RenderSystem.disableBlend();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        buffer.vertex(matrix, (float) left, (float) bottom, 0.0F).color(r, green, b, 255).endVertex();
        buffer.vertex(matrix, (float) right, (float) bottom, 0.0F).color(r, green, b, 255).endVertex();
        buffer.vertex(matrix, (float) right, (float) top, 0.0F).color(r, green, b, 255).endVertex();
        buffer.vertex(matrix, (float) left, (float) top, 0.0F).color(r, green, b, 255).endVertex();
        BufferUploader.drawWithShader(buffer.end());
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
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
        switchPill(g, left, top, width, height, on, hovered, on ? 1.0D : 0.0D);
    }

    /**
     * 带滑块进度的药丸开关。
     *
     * @param progress 0 = 关，1 = 开；由控件按时间推进，形成滑块滑动效果
     */
    public static void switchPill(GuiGraphics g, int left, int top, int width, int height,
                                  boolean on, boolean hovered, double progress) {
        double p = Math.max(0.0D, Math.min(1.0D, progress));
        int radius = height / 2;
        int trackOff = hovered ? blend(VerityUiTheme.CONTROL_TRACK, 0x73FFFFFF, 0.35D)
                : VerityUiTheme.CONTROL_TRACK;
        int trackOn = hovered ? brighten(VerityUiTheme.CONTROL_ON) : VerityUiTheme.CONTROL_ON;
        int track = blend(trackOff, trackOn, p);
        fillRounded(g, left, top, left + width, top + height, radius, track);
        if (p < 0.98D) {
            // 关闭态的描边随滑块滑出逐渐淡掉，避免滑块走到一半边框还很硬。
            // 只淡 alpha，不从黑色插值——否则中途会泛灰。
            int borderAlpha = (int) Math.round(
                    ((VerityUiTheme.CONTROL_BORDER >>> 24) & 0xFF) * (1.0D - p));
            strokeRounded(g, left, top, left + width, top + height, radius,
                    withAlpha(VerityUiTheme.CONTROL_BORDER, borderAlpha));
        }
        int knobSize = height - 4;
        int travel = Math.max(0, width - knobSize - 4);
        int knobX = left + 2 + (int) Math.round(travel * p);
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

    /** 在两个 ARGB 颜色之间插值，t 会被夹到 0~1；悬停/开关动画共用。 */
    public static int blend(int from, int to, double t) {
        double p = Math.max(0.0D, Math.min(1.0D, t));
        int a = (int) Math.round(((from >>> 24) & 0xFF)
                + (((to >>> 24) & 0xFF) - ((from >>> 24) & 0xFF)) * p);
        int r = (int) Math.round(((from >> 16) & 0xFF)
                + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * p);
        int g = (int) Math.round(((from >> 8) & 0xFF)
                + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * p);
        int b = (int) Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * p);
        return (Math.max(0, Math.min(255, a)) << 24)
                | (Math.max(0, Math.min(255, r)) << 16)
                | (Math.max(0, Math.min(255, g)) << 8)
                | Math.max(0, Math.min(255, b));
    }

    /** 替换颜色里的 alpha，用于提示淡出。 */
    public static int withAlpha(int argb, int alpha) {
        int a = Math.max(0, Math.min(255, alpha));
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
