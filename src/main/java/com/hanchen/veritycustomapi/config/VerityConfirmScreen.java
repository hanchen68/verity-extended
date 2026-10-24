package com.hanchen.veritycustomapi.config;

import com.hanchen.veritycustomapi.client.ScreenBackgrounds;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 统一的确认弹窗。
 *
 * <p><b>为什么要自己写一个</b>：原版 {@code ConfirmScreen} 是**没有面板底色的**——
 * 它只把整屏压一层半透明暗幕，然后把标题和正文直接画在那层暗幕上。
 * 结果是背后的内容（配置行、记忆列表、资源列表）原样透出来，
 * 看起来就是「弹窗是透明的、字和字叠在一起」。
 *
 * <p>这类弹窗在模组里有很多处（清除授权、删除记忆、清空缓存…），
 * 每处各写一遍样式必然会漂移，所以统一收在这里：
 * 不透明卡片 + 圆角描边 + 标题/正文两级字号 + 右下角两个按钮，
 * 配色全部取自 {@link VerityUiTheme}，和配置界面是同一套视觉语言。
 *
 * <p>用法与原版 {@code ConfirmScreen} 对齐（回调在前、标题正文在后），
 * 这样替换时调用点几乎不用改：
 * <pre>{@code
 * minecraft.setScreen(new VerityConfirmScreen(this, confirmed -> { ... },
 *         Component.literal("删除记忆"), Component.literal("要删除吗？")));
 * }</pre>
 *
 * <p>🔴 回调约定：
 * <ul>
 *   <li>点确认 → {@code callback.accept(true)}；</li>
 *   <li>点取消 / 按 ESC → {@code callback.accept(false)}；</li>
 *   <li>回调里通常自己 {@code setScreen(parent)}；若回调没有换界面，
 *       本类会兜底回到 {@code parent}，避免卡在弹窗上。</li>
 * </ul>
 * 这与原版一致（原版 {@code onClose()} 也回调 false），所以调用点语义不变。
 */
@OnlyIn(Dist.CLIENT)
public final class VerityConfirmScreen extends Screen {

    /** 卡片最宽 340：再宽正文一行太长，读起来要来回扫。 */
    private static final int MAX_CARD_WIDTH = 340;
    private static final int MIN_CARD_WIDTH = 200;
    private static final int PADDING = 16;
    private static final int LINE_HEIGHT = 11;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_WIDTH = 96;
    private static final int BUTTON_GAP = 8;

    /** 卡片底色：比浮层略亮一档，让「弹窗」和「说明浮层」区分得开，但同样完全不透明。 */
    private static final int PANEL = 0xFF14181F;
    private static final int TITLE_COLOR = VerityUiTheme.SECTION_TITLE;
    private static final int MESSAGE_COLOR = VerityUiTheme.TEXT;

    private final Screen parent;
    private final Consumer<Boolean> callback;
    private final Component title;
    private final Component message;
    private final Component yesLabel;
    private final Component noLabel;
    /** 危险操作（删除 / 清空）用红按钮，普通确认用主色按钮。 */
    private final boolean dangerous;

    private int cardLeft;
    private int cardTop;
    private int cardWidth;
    private int cardHeight;
    private List<FormattedCharSequence> titleLines = List.of();
    private List<FormattedCharSequence> messageLines = List.of();

    public VerityConfirmScreen(Screen parent, Consumer<Boolean> callback,
                               Component title, Component message) {
        this(parent, callback, title, message,
                Component.literal("确定"), Component.literal("取消"), false);
    }

    public VerityConfirmScreen(Screen parent, Consumer<Boolean> callback,
                               Component title, Component message,
                               Component yesLabel, Component noLabel) {
        this(parent, callback, title, message, yesLabel, noLabel, false);
    }

    public VerityConfirmScreen(Screen parent, Consumer<Boolean> callback,
                               Component title, Component message,
                               Component yesLabel, Component noLabel, boolean dangerous) {
        super(title);
        this.parent = parent;
        this.callback = callback;
        this.title = title;
        this.message = message;
        this.yesLabel = yesLabel;
        this.noLabel = noLabel;
        this.dangerous = dangerous;
    }

    /** 删除 / 清空这类不可撤销的操作，用红色确认按钮。 */
    public static VerityConfirmScreen dangerous(Screen parent, Consumer<Boolean> callback,
                                                Component title, Component message) {
        return new VerityConfirmScreen(parent, callback, title, message,
                Component.literal("确定"), Component.literal("取消"), true);
    }

    @Override
    protected void init() {
        int maxTextWidth = Math.max(MIN_CARD_WIDTH, Math.min(MAX_CARD_WIDTH, this.width - 40)) - PADDING * 2;
        titleLines = split(title, maxTextWidth);
        messageLines = split(message, maxTextWidth);

        int textWidth = 0;
        for (FormattedCharSequence line : titleLines) textWidth = Math.max(textWidth, font.width(line));
        for (FormattedCharSequence line : messageLines) textWidth = Math.max(textWidth, font.width(line));

        cardWidth = Math.min(Math.max(MIN_CARD_WIDTH, textWidth + PADDING * 2), Math.max(60, this.width - 24));
        int titleBlock = titleLines.size() * LINE_HEIGHT;
        int messageBlock = messageLines.size() * LINE_HEIGHT;
        int titleGap = messageBlock > 0 ? 6 : 0;
        cardHeight = PADDING + titleBlock + titleGap + messageBlock + 12 + BUTTON_HEIGHT + PADDING;

        cardLeft = (this.width - cardWidth) / 2;
        cardTop = (this.height - cardHeight) / 2;

        int buttonsWidth = BUTTON_WIDTH * 2 + BUTTON_GAP;
        int buttonsLeft = cardLeft + (cardWidth - buttonsWidth) / 2;
        int buttonsTop = cardTop + cardHeight - PADDING - BUTTON_HEIGHT;

        addRenderableWidget(VerityUiWidgets.secondaryButton(font, buttonsLeft, buttonsTop,
                BUTTON_WIDTH, BUTTON_HEIGHT, noLabel, b -> resolve(false)));
        Button yes = dangerous
                ? VerityUiWidgets.dangerButton(font, buttonsLeft + BUTTON_WIDTH + BUTTON_GAP, buttonsTop,
                        BUTTON_WIDTH, BUTTON_HEIGHT, yesLabel, b -> resolve(true))
                : VerityUiWidgets.primaryButton(font, buttonsLeft + BUTTON_WIDTH + BUTTON_GAP, buttonsTop,
                        BUTTON_WIDTH, BUTTON_HEIGHT, yesLabel, b -> resolve(true));
        addRenderableWidget(yes);
    }

    private List<FormattedCharSequence> split(Component text, int maxWidth) {
        if (text == null || text.getString().isBlank()) return List.of();
        // 用字体的折行：中文没有空格，按像素折才能保证不溢出卡片
        return new ArrayList<>(font.split(text, maxWidth));
    }

    private void resolve(boolean confirmed) {
        // 回调里可能自己 setScreen；先关掉本界面，再交给回调决定去哪
        callback.accept(confirmed);
        if (minecraft != null && minecraft.screen == this) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 背景：世界里走 30% 淡幕，主菜单走原版背景——与配置界面一致
        ScreenBackgrounds.draw(this, graphics);

        // 🔴 卡片必须完全不透明。原版 ConfirmScreen 只有暗幕没有卡片，
        // 所以背后的字会原样透出来。这里用 fillOpaqueNow：立即提交 + 关闭混合，
        // 写进去什么颜色就是什么颜色，不受 RenderType 的混合状态影响。
        //
        // ⚠️ 立即提交前必须先 flush：ScreenBackgrounds 的淡幕还是攒批状态，
        // 不先提交的话它会在帧末才画，反而盖住这张卡片。
        graphics.flush();
        VerityUiDraw.fillOpaqueNow(graphics, cardLeft, cardTop, cardLeft + cardWidth, cardTop + cardHeight, PANEL);
        // 直角描边，与整矩形对齐
        graphics.fill(cardLeft, cardTop, cardLeft + cardWidth, cardTop + 1, VerityUiTheme.CARD_BORDER);
        graphics.fill(cardLeft, cardTop + cardHeight - 1, cardLeft + cardWidth, cardTop + cardHeight,
                VerityUiTheme.CARD_BORDER);
        graphics.fill(cardLeft, cardTop, cardLeft + 1, cardTop + cardHeight, VerityUiTheme.CARD_BORDER);
        graphics.fill(cardLeft + cardWidth - 1, cardTop, cardLeft + cardWidth, cardTop + cardHeight,
                VerityUiTheme.CARD_BORDER);

        int textLeft = cardLeft + PADDING;
        int y = cardTop + PADDING;
        for (FormattedCharSequence line : titleLines) {
            graphics.drawString(font, line, textLeft, y, TITLE_COLOR, false);
            y += LINE_HEIGHT;
        }
        if (!messageLines.isEmpty()) {
            y += 6;
            for (FormattedCharSequence line : messageLines) {
                graphics.drawString(font, line, textLeft, y, MESSAGE_COLOR, false);
                y += LINE_HEIGHT;
            }
        }

        // 按钮最后画（super.render 会渲染子控件），避免被卡片底色盖住
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        resolve(false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
