package com.hanchen.veritycustomapi.config;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.function.Consumer;

/**
 * 各界面共用的自绘控件。
 *
 * <p>原版 {@code Button} / {@code Checkbox} 是浅色描边的方块，压在深色半透明卡片上
 * 像贴了另一套软件的东西。这里保留原版控件的**交互**（点击、焦点、Tab 导航、朗读），
 * 只把 {@code renderWidget} 换掉——所以行为零风险，观感统一。
 */
@OnlyIn(Dist.CLIENT)
public final class VerityUiWidgets {

    private VerityUiWidgets() {
    }

    /**
     * 圆角按钮。
     *
     * @param background 底色；**alpha 为 0 时**按钮本体透明，只在悬停时给一层淡底
     *                   （用于侧栏这类「选中态自己画」的场景）
     */
    public static Button roundedButton(Font font, int x, int y, int width, int height,
                                       Component label, int background, int textColor,
                                       Button.OnPress onPress) {
        return new Button(x, y, width, height, label, onPress,
                narration -> narration.get().copy()) {
            /** -1 表示还没初始化；首帧直接对准目标，不播放入场动画。 */
            private double verityExt$hover = -1.0D;
            private long verityExt$lastFrame;

            @Override
            protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
                boolean hovered = mouseX >= getX() && mouseX < getX() + this.width
                        && mouseY >= getY() && mouseY < getY() + this.height;
                long now = net.minecraft.Util.getMillis();
                long frame = VerityUiAnimation.frameDelta(verityExt$lastFrame, now);
                verityExt$lastFrame = now;
                if (verityExt$hover < 0.0D) {
                    verityExt$hover = hovered ? 1.0D : 0.0D;
                }
                // 侧栏这类透明按钮是导航，悬停必须即时；普通按钮保留约 45ms 过渡
                boolean transparent = (background >>> 24) == 0;
                double divisor = transparent ? 12.0D : 45.0D;
                verityExt$hover = VerityUiAnimation.approach(verityExt$hover,
                        hovered ? 1.0D : 0.0D, frame / divisor);
                double hover = verityExt$hover;

                // 三种底色三种悬停方式：
                //   全透明 → 悬停给一层淡底；淡底 → 提不透明度；实色 → 提亮 RGB
                int alpha = background >>> 24;
                int fill;
                if (alpha == 0) {
                    // 透明按钮只淡入 alpha，不要从黑色开始插值，否则中途会闪一层灰
                    fill = VerityUiDraw.withAlpha(VerityUiTheme.ROW_HOVER,
                            (int) Math.round(((VerityUiTheme.ROW_HOVER >>> 24) & 0xFF) * hover));
                } else if (alpha >= 0xE0) {
                    fill = VerityUiDraw.blend(background, VerityUiDraw.brighten(background), hover);
                } else {
                    fill = VerityUiDraw.blend(background,
                            VerityUiDraw.raiseAlpha(background, 0x1A), hover);
                }
                if ((fill >>> 24) != 0) {
                    VerityUiDraw.fillRounded(graphics, getX(), getY(),
                            getX() + this.width, getY() + this.height, 4, fill);
                }
                // 🔴 描边只在「真的有一层底色」时画。
                // 原来用 (background >>> 24) < 0xC0 判断，把 alpha=0 的透明按钮也算进去了，
                // 结果侧栏每一项都被勾成一个灰框，看起来像一排 Windows 95 按钮。
                if ((fill >>> 24) >= 0x60) {
                    VerityUiDraw.strokeRounded(graphics, getX(), getY(),
                            getX() + this.width, getY() + this.height, 4,
                            VerityUiDraw.blend(VerityUiTheme.CONTROL_BORDER,
                                    VerityUiTheme.ACCENT, hover * 0.45D));
                }
                int animatedText = VerityUiDraw.blend(textColor,
                        VerityUiDraw.brighten(textColor), hover * 0.65D);
                // 悬停时文字轻微上浮 1px，配合底色变化形成「按钮被托起」的感觉
                int lift = (int) Math.round(hover);
                graphics.drawCenteredString(font, getMessage(),
                        getX() + this.width / 2,
                        getY() + (this.height - 8) / 2 - lift, animatedText);
            }
        };
    }

    /** 主按钮（实色青蓝）。 */
    public static Button primaryButton(Font font, int x, int y, int width, int height,
                                       Component label, Button.OnPress onPress) {
        return roundedButton(font, x, y, width, height, label,
                VerityUiTheme.CONTROL_ON, 0xFF0A1216, onPress);
    }

    /** 次级按钮（淡底白字）。 */
    public static Button secondaryButton(Font font, int x, int y, int width, int height,
                                         Component label, Button.OnPress onPress) {
        return roundedButton(font, x, y, width, height, label,
                VerityUiTheme.BUTTON, VerityUiTheme.TEXT, onPress);
    }

    /** 危险按钮（淡红）。 */
    public static Button dangerButton(Font font, int x, int y, int width, int height,
                                      Component label, Button.OnPress onPress) {
        return roundedButton(font, x, y, width, height, label,
                VerityUiTheme.BUTTON_DANGER, VerityUiTheme.DANGER, onPress);
    }

    /**
     * 药丸开关。借 {@link Checkbox} 的点击与焦点，外观整个换掉。
     *
     * @param onToggle 点击后回调新状态；传 null 表示由调用方自己处理
     */
    public static Checkbox pillSwitch(int x, int y, int width, int height,
                                      boolean selected, Consumer<Boolean> onToggle) {
        return new Checkbox(x, y, width, height, Component.empty(), selected) {
            /** 滑块位置：0=关，1=开；首帧直接对准初始状态。 */
            private double verityExt$knob = -1.0D;
            private long verityExt$lastFrame;

            @Override
            public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
                boolean hovered = mouseX >= getX() && mouseX < getX() + this.width
                        && mouseY >= getY() && mouseY < getY() + this.height;
                long now = net.minecraft.Util.getMillis();
                long frame = VerityUiAnimation.frameDelta(verityExt$lastFrame, now);
                verityExt$lastFrame = now;
                double target = this.selected() ? 1.0D : 0.0D;
                if (verityExt$knob < 0.0D) {
                    verityExt$knob = target;
                }
                // 约 65ms 滑到位，比按钮稍慢一点但仍要跟手
                verityExt$knob = VerityUiAnimation.approach(verityExt$knob, target, frame / 65.0D);
                VerityUiDraw.switchPill(graphics, getX(), getY(), this.width, this.height,
                        this.selected(), hovered, verityExt$knob);
            }

            @Override
            public void onPress() {
                super.onPress();
                if (onToggle != null) onToggle.accept(this.selected());
            }
        };
    }

    /**
     * 圆角输入框。
     *
     * <p>关掉原版直角边框，自绘圆角底；聚焦时描边变强调色。
     * 正文用 pose 平移做出内边距——**不动控件尺寸**，所以点击与光标逻辑不受影响。
     */
    public static EditBox roundedEditBox(Font font, int x, int y, int width, int height,
                                         Component narration) {
        EditBox box = new EditBox(font, x, y, width, height, narration) {
            @Override
            public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
                drawFieldBackground(graphics, getX(), getY(), this.width, this.height,
                        isFocused(), () -> super.renderWidget(graphics, mouseX, mouseY, partialTick));
            }
        };
        box.setBordered(false);
        return box;
    }

    /**
     * 给输入框画圆角底并做内边距。
     *
     * <p>调用前请先 {@code setBordered(false)}：原版会把文字画在 {@code (getX(), getY())}，
     * 零内边距——数字贴着框角，看起来像溢出到框外。这里用 pose 平移做出内边距，
     * 不动控件尺寸，所以点击与光标逻辑不受影响。
     *
     * @param drawBody 实际绘制正文的动作（通常是 {@code () -> super.renderWidget(...)}）
     */
    public static void drawFieldBackground(GuiGraphics graphics, int x, int y, int width, int height,
                                           boolean focused, Runnable drawBody) {
        VerityUiDraw.fillRounded(graphics, x, y, x + width, y + height, 4,
                VerityUiTheme.CONTROL_FIELD);
        VerityUiDraw.strokeRounded(graphics, x, y, x + width, y + height, 4,
                focused ? VerityUiTheme.ACCENT : VerityUiTheme.CONTROL_BORDER);
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.translate(5.0F, 4.0F, 0.0F);
        drawBody.run();
        pose.popPose();
    }
}
