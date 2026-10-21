package com.hanchen.veritycustomapi.config;

import com.hanchen.veritycustomapi.ApiProviderEnum;
import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.SttApiProviderEnum;
import com.hanchen.veritycustomapi.TtsApiProviderEnum;
import com.hanchen.veritycustomapi.client.ClientEventHandler;
import com.hanchen.veritycustomapi.cloud.CloudConfigFetcher;
import com.hanchen.veritycustomapi.cloud.CloudConfigManager;
import com.hanchen.veritycustomapi.cloud.CloudConfig;
import com.hanchen.veritycustomapi.cloud.CloudCredentialStore;
import com.hanchen.veritycustomapi.cloud.CloudSkin;
import com.hanchen.veritycustomapi.compat.ExternalFileOpener;
import com.hanchen.veritycustomapi.compat.OriginalVerityConfigSync;
import com.hanchen.veritycustomapi.compat.OriginalVerityPersonalization;
import com.hanchen.veritycustomapi.compat.RuntimePlatform;
import com.hanchen.veritycustomapi.resource.PairingResourceFallback;
import com.hanchen.veritycustomapi.skin.SkinManager;
import com.hanchen.veritycustomapi.stt.SpeechRecognitionManager;
import com.hanchen.veritycustomapi.stt.VoskModelInstaller;
import com.hanchen.veritycustomapi.stt.VoskRuntimeSupport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.network.chat.Style;
import net.minecraft.util.Mth;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;

import org.lwjgl.glfw.GLFW;

public class NativeOllamaProxyConfigScreen extends Screen {
    /** 卡片圆角半径。4 是「看得出是圆的、又不占地方」的平衡点。 */
    private static final int CARD_RADIUS = 5;

    private static final int ACCENT = VerityUiTheme.ACCENT;
    private static final int ACCENT_SOFT = VerityUiTheme.ACCENT_SOFT;
    private static final int TEXT = VerityUiTheme.TEXT;
    private static final int MUTED = VerityUiTheme.MUTED;
    private static final int WARNING = VerityUiTheme.WARNING;
    private static final int DANGER = VerityUiTheme.DANGER;
    private static final int SUCCESS = VerityUiTheme.SUCCESS;
    /** 极淡的分组分隔线，替代原先每行一张卡片产生的视觉噪音。 */
    private static final int SEPARATOR = VerityUiTheme.ROW_DIVIDER;
    /** 分组标题色：比正文弱、比说明文字略亮。 */
    private static final int SECTION_TITLE = VerityUiTheme.SECTION_TITLE;
    private static final int ROW_HEIGHT = 28;
    /** 换行符。写成常量是为了避免在源码里出现转义序列，被工具链反复吃掉。 */
    private static final String NEWLINE = "\n";
    private static final ExecutorService CAMERA_TEST_POOL = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Verity-Camera-Test");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean CAMERA_TEST_RUNNING = new AtomicBoolean();

    private final Screen parent;
    private final List<Category> categories = new ArrayList<>();
    private final List<ValueRow> values = new ArrayList<>();
    private int categoryIndex;
    private double scrollOffset;
    /** 侧栏分类按钮（保留引用，放不下时靠滚动调整它们的 Y 与可见性）。 */
    private final List<Button> sidebarButtons = new ArrayList<>();
    private double sidebarScroll;
    /** 上一次布局使用的宽高；窗口尺寸变了就强制重排，避免控件停在旧坐标。 */
    private int layoutWidth = -1;
    private int layoutHeight = -1;
    /** 是否正在用鼠标/手指拖动内容列表（触屏没有滚轮时的主要滚动方式）。 */
    private boolean draggingContent;
    private boolean draggingSidebar;
    /** 按下后先不判定拖动，移动超过阈值才接管，避免点和拖冲突。 */
    private boolean dragCandidate;
    private boolean dragSidebarCandidate;
    private double dragStartY;
    private double lastDragY;
    /** 置标志等下一次 tick 重建：控件回调里正在遍历 children()，不能当场重建。 */
    private boolean rebuildPending;
    /** 「立即生效」防抖倒计时（tick）：连点开关时合并成最后一次改动后 0.5 秒同步一次。 */
    private int applySyncDelayTicks;
    private String statusMessage = "";
    private int statusMessageTimer;
    /** 分组折叠状态：键为「分类标题|分组标题」，值为 true 表示已收起。 */
    private final Map<String, Boolean> collapsedSections = new HashMap<>();
    /** 摄像头"测试"按钮最近一次的结果文本；null 表示还没测过。 */
    private String cameraTestResult;
    private String pairingCode = "";
    /** 入场动画起点：构造时锁定，之后 resize 不重播。 */
    private final long openedAt = net.minecraft.Util.getMillis();
    /** 侧栏选中高亮的当前 Y；导航菜单直接对准目标行。 */
    private double sidebarHighlightY = -1.0D;
    /** 最近一次切换分类的时间，用于标题轻微滑入。 */
    private long categorySwitchAt = openedAt;
    /** 当前悬停的说明行与开始时间，用于浮层淡入上浮。 */
    private Row hintRow;
    private long hintHoverAt;

    public NativeOllamaProxyConfigScreen(Screen parent) {
        super(Component.literal("Verity 扩展增强配置"));
        this.parent = parent;
    }

    // ==================== Lifecycle ====================

    @Override
    protected void init() {
        clearWidgets();
        categories.clear();
        values.clear();
        buildCategories();
        categoryIndex = clamp(categoryIndex, 0, Math.max(0, categories.size() - 1));
        scrollOffset = 0;
        sidebarScroll = 0;
        sidebarHighlightY = -1.0D;
        draggingContent = false;
        draggingSidebar = false;
        dragCandidate = false;
        dragSidebarCandidate = false;
        addWidgets();
    }

    @Override
    public void tick() {
        super.tick();
        if (statusMessageTimer > 0) {
            statusMessageTimer--;
        }
        if (width != layoutWidth || height != layoutHeight) {
            // 窗口尺寸变化但控件仍是旧坐标时，会出现侧栏/按钮溢出卡片。
            // resize() 正常会重建，这里再兜底一次，保证最迟下一 tick 回到正确布局。
            rebuildRows();
        }
        if (rebuildPending) {
            rebuildPending = false;
            rebuildRows();
        }
        if (applySyncDelayTicks > 0 && --applySyncDelayTicks == 0) {
            // 和点「保存」时同一套同步，让改动立刻作用到原版注入、代理、TTS、皮肤等子系统
            ClientEventHandler.onConfigSaved();
        }
    }

    /**
     * 请求在下一次 tick 重建行与控件，并安排一次防抖的「立即生效」同步。
     */
    private void requestRebuild() {
        rebuildPending = true;
        applySyncDelayTicks = 10;
    }

    /** 重建全部行与控件，保留当前分类和滚动位置（供开关/下拉改动后立即生效用）。 */
    private void rebuildRows() {
        double keepScroll = scrollOffset;
        categories.clear();
        values.clear();
        buildCategories();
        categoryIndex = clamp(categoryIndex, 0, Math.max(0, categories.size() - 1));
        scrollOffset = clamp(keepScroll, 0, getMaxScroll());
        draggingContent = false;
        draggingSidebar = false;
        dragCandidate = false;
        dragSidebarCandidate = false;
        addWidgets();
    }

    // ==================== Input ====================

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            if (Screen.hasControlDown()) {
                saveAndClose();
                return true;
            }
            // 普通回车：把当前输入框的值立刻写回配置，不必再保存并重新打开配置页
            if (applyFocusedTextRow()) {
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /**
     * 把当前聚焦的文本框内容立刻写回配置。
     *
     * @return 是否处理了某个文本框
     */
    private boolean applyFocusedTextRow() {
        for (ValueRow value : values) {
            if (!(value instanceof TextRow row) || row.box == null || !row.box.isFocused()) continue;
            row.save();
            requestRebuild();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        // 鼠标在侧栏上时滚动分类列表，而不是内容区
        if (!usesPagedNavigation()
                && mouseX >= getSidebarLeft() && mouseX <= getSidebarLeft() + getSidebarWidth()
                && mouseY >= SIDEBAR_TOP && mouseY <= getContentBottom()) {
            double max = getMaxSidebarScroll();
            if (max > 0) {
                double next = clamp(sidebarScroll - delta * getSidebarItemStep() * 2, 0, max);
                if (next != sidebarScroll) {
                    sidebarScroll = next;
                    applySidebarScroll();
                    return true;
                }
            }
        }
        if (mouseX < getContentLeft() || mouseX > getPanelRight()
                || mouseY < getContentTop() || mouseY > getContentBottom()) {
            return super.mouseScrolled(mouseX, mouseY, delta);
        }
        double next = clamp(scrollOffset - delta * ROW_HEIGHT * 2, 0, getMaxScroll());
        if (next == scrollOffset) return false;
        cacheWidgetValues();
        scrollOffset = next;
        addWidgets();
        return true;
    }

    /**
     * 触屏/鼠标都先记“拖动候选”，移动超过 4px 才接管滚动。
     * 这样从按钮/行上开始拖也能滚动列表，正常点击仍然交给控件；
     * 滑块和输入框保持原操作，不抢拖动。
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        if (!handled && button == 0 && isInsideContent(mouseX, mouseY) && toggleSectionAt(mouseX, mouseY)) {
            return true;
        }
        if (button == 0 && !isDragSensitiveControl(mouseX, mouseY)) {
            boolean content = isInsideContent(mouseX, mouseY) && hasScrollableContent();
            boolean sidebar = isInsideSidebar(mouseX, mouseY) && getMaxSidebarScroll() > 0;
            if (content || sidebar) {
                dragCandidate = true;
                dragSidebarCandidate = sidebar && !content;
                dragStartY = mouseY;
                lastDragY = mouseY;
            }
        }
        return handled;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && dragCandidate) {
            if (Math.abs(mouseY - dragStartY) > 4.0D) {
                dragCandidate = false;
                draggingSidebar = dragSidebarCandidate;
                draggingContent = !dragSidebarCandidate;
                lastDragY = mouseY;
            }
        }
        if (button == 0 && (draggingContent || draggingSidebar)) {
            double deltaY = mouseY - lastDragY;
            lastDragY = mouseY;
            if (draggingSidebar) {
                double max = getMaxSidebarScroll();
                double next = clamp(sidebarScroll - deltaY, 0, max);
                if (next != sidebarScroll) {
                    sidebarScroll = next;
                    applySidebarScroll();
                }
                return true;
            }
            double next = clamp(scrollOffset - deltaY, 0, getMaxScroll());
            if (next != scrollOffset) {
                cacheWidgetValues();
                scrollOffset = next;
                addWidgets();
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        draggingContent = false;
        draggingSidebar = false;
        dragCandidate = false;
        dragSidebarCandidate = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private boolean isInsideContent(double mouseX, double mouseY) {
        return mouseX >= getContentLeft() - 6 && mouseX <= getPanelRight()
                && mouseY >= getContentTop() && mouseY <= getContentBottom();
    }

    private boolean isInsideSidebar(double mouseX, double mouseY) {
        return !usesPagedNavigation()
                && mouseX >= getSidebarLeft() && mouseX <= getSidebarLeft() + getSidebarWidth()
                && mouseY >= SIDEBAR_TOP && mouseY <= getContentBottom();
    }

    /** 滑块/输入框需要自己处理拖动，不能让列表抢走。 */
    private boolean isDragSensitiveControl(double mouseX, double mouseY) {
        for (GuiEventListener child : children()) {
            if (!(child instanceof AbstractWidget widget)) continue;
            if (!(widget instanceof AbstractSliderButton) && !(widget instanceof EditBox)) continue;
            if (mouseX >= widget.getX() && mouseX <= widget.getX() + widget.getWidth()
                    && mouseY >= widget.getY() && mouseY <= widget.getY() + widget.getHeight()) {
                return true;
            }
        }
        return false;
    }

    private static String sectionKey(String categoryTitle, String sectionTitle) {
        return categoryTitle + '|' + sectionTitle;
    }

    /** 命中分组标题时切换收起状态：先缓存控件值再重排，收起后把滚动位置夹回合法范围。 */
    private boolean toggleSectionAt(double mouseX, double mouseY) {
        if (categories.isEmpty()) return false;
        Category category = categories.get(categoryIndex);
        int contentLeft = getContentLeft();
        int contentRight = contentLeft + getContentWidth();
        int y = getContentTop() - (int) scrollOffset;
        for (Row row : getCurrentCategoryRows()) {
            int rowHeight = row.getHeight();
            if (row instanceof SectionRow section && section.collapsible
                    && mouseX >= contentLeft && mouseX <= contentRight
                    && mouseY >= y && mouseY <= y + rowHeight) {
                cacheWidgetValues();
                section.toggle(category.title);
                scrollOffset = clamp(scrollOffset, 0, getMaxScroll());
                addWidgets();
                return true;
            }
            y += rowHeight;
        }
        return false;
    }

    // ==================== Layout Calculations ====================

    private boolean isCompactLayout() {
        return width < 680 || height < 400;
    }

    private boolean isUltraCompactLayout() {
        return width < 430;
    }

    /** 侧栏列表的顶部起点。 */
    private static final int SIDEBAR_TOP = 44;

    private boolean usesPagedNavigation() {
        // 只留给真正放不下的极窄/极矮窗口；普通矮窗口保留左侧列表，改为列表内滚动。
        return width < 300 || height < 220;
    }

    private int getSidebarItemStep() {
        if (categories.isEmpty()) return 22;
        int available = Math.max(1, getContentBottom() - 44);
        return Math.max(14, Math.min(22, available / categories.size()));
    }

    private int getSidebarButtonHeight() {
        return Math.max(13, Math.min(19, getSidebarItemStep() - 2));
    }

    private int getSidebarViewport() {
        return Math.max(1, getContentBottom() - SIDEBAR_TOP);
    }

    private int getSidebarContentHeight() {
        return categories.size() * getSidebarItemStep();
    }

    private double getMaxSidebarScroll() {
        if (usesPagedNavigation() || sidebarButtons.isEmpty()) return 0;
        return Math.max(0, getSidebarContentHeight() - getSidebarViewport());
    }

    /** 按滚动量重新摆放侧栏按钮；移出可视区的按钮直接隐藏，避免画到标题或底部按钮上。 */
    private void applySidebarScroll() {
        sidebarScroll = clamp(sidebarScroll, 0, getMaxSidebarScroll());
        int step = getSidebarItemStep();
        int buttonHeight = getSidebarButtonHeight();
        int bottom = getContentBottom();
        for (int i = 0; i < sidebarButtons.size(); i++) {
            Button button = sidebarButtons.get(i);
            int y = SIDEBAR_TOP + i * step - (int) Math.round(sidebarScroll);
            button.setY(y);
            button.visible = y >= SIDEBAR_TOP && y + buttonHeight <= bottom;
        }
    }

    private int getOuterMargin() {
        return isCompactLayout() ? 8 : 14;
    }

    private int getSidebarWidth() {
        if (usesPagedNavigation()) return 0;
        if (width < 560) return 108;
        return isCompactLayout() ? 124 : 156;
    }

    private int getSidebarLeft() {
        int usableWidth = Math.max(0, width - getOuterMargin() * 2);
        int maxWidth = Math.min(920, usableWidth);
        return Math.max(getOuterMargin(), (width - maxWidth) / 2);
    }

    private int getPanelRight() {
        return Math.min(width - getOuterMargin(), getSidebarLeft() + 920);
    }

    private int getContentLeft() {
        return getSidebarLeft() + getSidebarWidth() + (usesPagedNavigation() ? 0 : 12);
    }

    private int getContentWidth() {
        return Math.max(0, getPanelRight() - getContentLeft());
    }

    /** 当前分类内容是否超出可视区域（只依赖行高，可安全用于布局判断）。 */
    private boolean hasScrollableContent() {
        if (categories.isEmpty()) return false;
        return getRowsHeight(getCurrentCategoryRows()) > getVisibleRowsHeight();
    }

    private int getContentTop() {
        return 58;
    }

    private int getContentBottom() {
        return height - 50;
    }

    private int getVisibleRowsHeight() {
        return getContentBottom() - getContentTop();
    }

    private int getLabelWidth(int rowWidth) {
        if (isUltraCompactLayout()) return Math.max(48, rowWidth - 16);
        return Math.max(76, Math.min(148, rowWidth * 2 / 5));
    }

    private int getControlWidth(int rowWidth) {
        if (isUltraCompactLayout()) return Math.max(64, rowWidth - 12);
        int labelWidth = getLabelWidth(rowWidth);
        int available = rowWidth - labelWidth - 18;
        return Math.max(72, Math.min(isCompactLayout() ? 190 : 260, available));
    }

    private int getControlLeft(int left, int rowWidth) {
        if (isUltraCompactLayout()) return left + 6;
        return left + rowWidth - getControlWidth(rowWidth) - 6;
    }

    private double getMaxScroll() {
        List<Row> rows = getCurrentCategoryRows();
        int totalHeight = getRowsHeight(rows);
        int visibleHeight = getVisibleRowsHeight();
        return Math.max(0, totalHeight - visibleHeight);
    }

    private int getRowsHeight(List<Row> rows) {
        int height = 0;
        for (Row row : rows) height += row.getHeight();
        return height;
    }

    /** 当前分类中真正参与布局的行：被收起的分组内部的行会被跳过。 */
    private List<Row> getCurrentCategoryRows() {
        if (categories.isEmpty()) return List.of();
        Category category = categories.get(categoryIndex);
        List<Row> visible = new ArrayList<>(category.rows.size());
        boolean collapsed = false;
        for (Row row : category.rows) {
            if (row instanceof SectionRow section) {
                collapsed = section.collapsible && section.isCollapsed(category.title);
                visible.add(row);
            } else if (!collapsed) {
                visible.add(row);
            }
        }
        return visible;
    }

    private boolean isRowVisible(int top, int rowHeight) {
        return top >= getContentTop() && top + rowHeight <= getContentBottom();
    }

    // ==================== Widget Building ====================

    private void addWidgets() {
        clearWidgets();
        sidebarButtons.clear();
        int sidebarLeft = getSidebarLeft();
        int sidebarWidth = getSidebarWidth();
        int sidebarButtonWidth = Math.max(64, sidebarWidth - 12);

        if (usesPagedNavigation()) {
            addRenderableWidget(roundedButton(sidebarLeft + 4, 34, 24, 18,
                    Component.literal("‹"), VerityUiTheme.BUTTON, VerityUiTheme.TEXT,
                    button -> switchCategory((categoryIndex - 1 + categories.size()) % categories.size())));
            addRenderableWidget(roundedButton(getPanelRight() - 28, 34, 24, 18,
                    Component.literal("›"), VerityUiTheme.BUTTON, VerityUiTheme.TEXT,
                    button -> switchCategory((categoryIndex + 1) % categories.size())));
        } else {
            int itemStep = getSidebarItemStep();
            int buttonHeight = getSidebarButtonHeight();
            for (int i = 0; i < categories.size(); i++) {
                int index = i;
                String displayTitle = categories.get(i).sidebarTitle != null
                        ? categories.get(i).sidebarTitle
                        : categories.get(i).title;
                String title = fitText(font, displayTitle, sidebarButtonWidth - 10);
                int y = SIDEBAR_TOP + i * itemStep;
                boolean active = index == categoryIndex;
                Button button = roundedButton(sidebarLeft + 6, y, sidebarButtonWidth, buttonHeight,
                        Component.literal(title),
                        0x00000000,
                        active ? VerityUiTheme.TEXT : VerityUiTheme.MUTED,
                        ignored -> switchCategory(index));
                sidebarButtons.add(button);
                addRenderableWidget(button);
            }
            // 高度不足时靠滚动查看剩余分类，而不是把整个侧栏换成左右箭头
            applySidebarScroll();
        }

        // Content rows
        int contentLeft = getContentLeft();
        int contentWidth = getContentWidth();
        int contentTop = getContentTop();
        List<Row> rows = getCurrentCategoryRows();
        int y = contentTop - (int) scrollOffset;
        for (Row row : rows) {
            row.addWidgets(contentLeft, y, contentWidth);
            y += row.getHeight();
        }

        addFooterWidgets(contentLeft, contentWidth);
        layoutWidth = width;
        layoutHeight = height;
    }

    /**
     * 圆角自绘按钮。
     *
     * <p>原版 Button 是带边框的灰色方块，和圆角卡片、药丸开关放一起会显得像另一个软件。
     * 这里只保留它的点击、焦点、键盘可达性，外观整个换掉。
     */
    private Button roundedButton(int x, int y, int width, int height, Component label,
                                 int background, int textColor, Button.OnPress onPress) {
        return new Button(x, y, width, height, label, onPress, narration -> narration.get().copy()) {
            @Override
            protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
                boolean hovered = mouseX >= getX() && mouseX < getX() + this.width
                        && mouseY >= getY() && mouseY < getY() + this.height;
                // 三种底色三种悬停方式：
                //   全透明（侧栏分类）→ 悬停给淡底；淡底 → 提不透明度；实色 → 提亮 RGB
                int alpha = background >>> 24;
                int fill;
                if (alpha == 0) {
                    fill = hovered ? VerityUiTheme.ROW_HOVER : 0;
                } else if (!hovered) {
                    fill = background;
                } else if (alpha >= 0xE0) {
                    fill = VerityUiDraw.brighten(background);
                } else {
                    fill = VerityUiDraw.raiseAlpha(background, 0x1A);
                }
                if ((fill >>> 24) != 0) {
                    VerityUiDraw.fillRounded(graphics, getX(), getY(),
                            getX() + this.width, getY() + this.height, 4, fill);
                }
                // 🔴 只在「真的有一层底色」时描边；透明按钮（侧栏分类）绝不能勾边，
                // 否则每一项都变成灰框。旧判断 (background>>>24) < 0xC0 把 alpha=0 也算进去了。
                if ((fill >>> 24) >= 0x60) {
                    VerityUiDraw.strokeRounded(graphics, getX(), getY(),
                            getX() + this.width, getY() + this.height, 4,
                            VerityUiTheme.CONTROL_BORDER);
                }
                graphics.drawCenteredString(font, getMessage(),
                        getX() + this.width / 2,
                        getY() + (this.height - 8) / 2, textColor);
            }
        };
    }

    private void addFooterWidgets(int left, int contentWidth) {
        int gap = isUltraCompactLayout() ? 3 : 6;
        int bottom = height - 28;

        int narrowButtonWidth = Math.max(28, (contentWidth - gap * 2) / 3);
        int saveWidth = isUltraCompactLayout() ? narrowButtonWidth : isCompactLayout() ? 58 : 76;
        int cancelWidth = isUltraCompactLayout() ? narrowButtonWidth : isCompactLayout() ? 58 : 76;
        int resetWidth = isUltraCompactLayout() ? narrowButtonWidth : isCompactLayout() ? 56 : 92;

        int right = left + contentWidth;

        // Scroll indicator
        double maxScroll = getMaxScroll();
        if (maxScroll > 0) {
            double pct = maxScroll > 0 ? scrollOffset / maxScroll : 0;
            String scrollInfo = String.format("%.0f%%", pct * 100);
            int scrollIndicatorWidth = font.width(scrollInfo);
            int indicatorX = left + contentWidth / 2 - scrollIndicatorWidth / 2;
            // We'll render this in render() instead
        }

        // Status message is rendered separately in render(), no widget needed

        // Action buttons: right-aligned
        int actionTotal = saveWidth + cancelWidth + resetWidth + gap * 2;
        int actionLeft = Math.max(left, right - actionTotal);

        addRenderableWidget(roundedButton(actionLeft, bottom, resetWidth, 20,
                Component.literal(isCompactLayout() ? "重置" : "重置当前分类"),
                VerityUiTheme.BUTTON_DANGER, VerityUiTheme.DANGER,
                button -> confirmResetCategory()));
        actionLeft += resetWidth + gap;

        addRenderableWidget(roundedButton(actionLeft, bottom, saveWidth, 20,
                Component.literal("保存"),
                VerityUiTheme.CONTROL_ON, 0xFF0A1216,
                button -> saveAndClose()));
        actionLeft += saveWidth + gap;

        addRenderableWidget(roundedButton(actionLeft, bottom, cancelWidth, 20,
                Component.literal("取消"),
                VerityUiTheme.BUTTON, VerityUiTheme.TEXT,
                button -> onClose()));
    }

    // ==================== Navigation ====================

    private void switchCategory(int nextCategory) {
        cacheWidgetValues();
        int target = clamp(nextCategory, 0, Math.max(0, categories.size() - 1));
        categoryIndex = target;
        scrollOffset = 0;
        statusMessage = "";
        categorySwitchAt = net.minecraft.Util.getMillis();
        addWidgets();
    }

    /**
     * 重建当前分类并刷新界面，用于异步数据更新后（如云端皮肤列表拉取完成）。
     */
    private void refreshCurrentCategory() {
        refreshCurrentCategory(true);
    }

    /**
     * @param cacheValues false 用于刚改过配置对象的场景（如云端授权）：
     *                    此时旧控件里可能还是改之前的值，缓存会把新值覆盖回去。
     */
    private void refreshCurrentCategory(boolean cacheValues) {
        if (cacheValues) {
            cacheWidgetValues();
        }
        int idx = categoryIndex;
        categories.clear();
        values.clear();
        buildCategories();
        categoryIndex = clamp(idx, 0, Math.max(0, categories.size() - 1));
        scrollOffset = 0;
        addWidgets();
    }

    private void startCameraTest(boolean screenOnly) {
        if (!CAMERA_TEST_RUNNING.compareAndSet(false, true)) {
            statusMessage = "画面采集测试正在进行，请稍候";
            statusMessageTimer = 120;
            return;
        }
        statusMessage = screenOnly ? "正在测试游戏画面…" : "正在测试画面采集…";
        statusMessageTimer = 600;
        addWidgets();

        try {
            CAMERA_TEST_POOL.execute(() -> {
                StringBuilder report = new StringBuilder();
                boolean ok;
                try {
                    ok = screenOnly
                            ? com.hanchen.veritycustomapi.client.GameScreenCaptureService.testCapture(report)
                            : com.hanchen.veritycustomapi.client.CameraCaptureService.testCapture(report);
                } catch (Throwable t) {
                    ok = false;
                    report.append("失败：")
                            .append(t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage());
                }
                boolean success = ok;
                String result = report.toString();
                CAMERA_TEST_RUNNING.set(false);
                Minecraft minecraft = Minecraft.getInstance();
                if (minecraft == null) return;
                minecraft.execute(() -> {
                    if (minecraft.screen != this) return;
                    cameraTestResult = result;
                    statusMessage = success
                            ? (screenOnly ? "游戏画面测试成功" : "画面采集测试成功")
                            : (screenOnly ? "游戏画面测试失败，详见下方结果" : "画面采集测试失败，详见下方结果");
                    statusMessageTimer = 120;
                    refreshCurrentCategory();
                });
            });
        } catch (RuntimeException e) {
            CAMERA_TEST_RUNNING.set(false);
            statusMessage = "无法启动画面采集测试";
            statusMessageTimer = 120;
            addWidgets();
        }
    }

    private void installVoskModel() {
        statusMessage = "已开始安装离线中文模型，进度会显示在聊天栏";
        statusMessageTimer = 200;
        addWidgets();
        VoskModelInstaller.installAsync(() -> {
            ModConfig.RECOGNITION_MODE.set("local");
            SpeechRecognitionManager.INSTANCE.onConfigSaved();
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) {
                mc.execute(() -> mc.setScreen(new NativeOllamaProxyConfigScreen(parent)));
            }
        });
    }

    private void exchangePairingCode() {
        // 直接从 EditBox 读取配对码，不走 pairingCode 字段（cacheWidgetValues 不会触发 saver 回调）
        String code = readFirstEditBoxValue();
        if (code == null || code.isBlank()) {
            statusMessage = "请先输入配对码";
            statusMessageTimer = 120;
            addWidgets();
            return;
        }
        statusMessage = "正在完成账号授权…";
        statusMessageTimer = 300;
        addWidgets();

        String apiUrl = ModConfig.CLOUD_API_URL.get();
        int timeout = ModConfig.CLOUD_TIMEOUT.get();

        new Thread(() -> {
            try {
                CloudConfigFetcher.ExchangeResult result = new CloudConfigFetcher().exchangeCode(apiUrl, code, timeout);
                Minecraft mc = Minecraft.getInstance();
                if (mc == null) return;

                if (result.success() && result.apiKey() != null && !result.apiKey().isBlank()) {
                    mc.execute(() -> {
                        try {
                            CloudConfigManager.stop();
                            ConfigManager.clearCloudConfig();
                            CloudCredentialStore.getInstance().saveAuthorization(result.apiKey(), result.configId());
                            ModConfig.CLOUD_API_KEY.set("");
                            ModConfig.CLOUD_ENABLE.set(true);
                            ConfigManager.saveCommonConfig();
                            OllamaProxyMod.LOGGER.info("云端授权完成: cloud.enable={}, 凭据已写入",
                                    ModConfig.CLOUD_ENABLE.get());
                            statusMessage = "授权成功，正在同步云端设置…";
                            statusMessageTimer = 300;
                            pairingCode = "";
                            // 旧界面里的开关可能还是授权前的 false，不能让它缓存回配置。
                            refreshCurrentCategory(false);
                            ClientEventHandler.syncCloudConfigNow(success -> {
                                // 同步期间若被其他配置保存路径改回 false，这里再兜底确认一次。
                                ensureCloudEnabledForAuthorization();
                                statusMessage = success
                                        ? "授权成功，云端设置已立即生效"
                                        : "授权成功，但同步失败，继续使用本地备用设置";
                                statusMessageTimer = 300;
                                refreshCurrentCategory(false);
                            });
                        } catch (RuntimeException ignored) {
                            statusMessage = "授权信息保存失败，请检查配置目录权限";
                            statusMessageTimer = 200;
                            addWidgets();
                        }
                    });
                } else {
                    mc.execute(() -> {
                        statusMessage = result.error() != null ? result.error() : "兑换失败";
                        statusMessageTimer = 200;
                        addWidgets();
                    });
                }
            } catch (Throwable t) {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null) {
                    mc.execute(() -> {
                        statusMessage = "网络异常，请稍后重试";
                        statusMessageTimer = 200;
                        addWidgets();
                    });
                }
            }
        }, "Cloud-Pairing-Thread").start();
    }

    /** 授权后凭据存在时，确保 cloud.enable 不被旧界面/保存路径改回 false。 */
    private void ensureCloudEnabledForAuthorization() {
        String key = ConfigManager.getCloudApiKey();
        if (key == null || key.isBlank()) return;
        if (!ModConfig.CLOUD_ENABLE.get()) {
            ModConfig.CLOUD_ENABLE.set(true);
            ConfigManager.saveCommonConfig();
            OllamaProxyMod.LOGGER.info("云端授权兜底：cloud.enable 曾被改回 false，现已恢复 true");
        }
    }

    private void cacheWidgetValues() {
        for (Category category : categories) {
            for (Row row : category.rows) {
                row.cache();
            }
        }
    }

    /** 从当前界面的第一个 EditBox 中读取输入值（用于配对码等场景） */
    private String readFirstEditBoxValue() {
        for (net.minecraft.client.gui.components.events.GuiEventListener w : children()) {
            if (w instanceof EditBox) {
                return ((EditBox) w).getValue();
            }
        }
        return "";
    }

    private void confirmResetCategory() {
        cacheWidgetValues();
        if (categories.isEmpty()) return;
        Category category = categories.get(categoryIndex);
        // 重置要覆盖被收起分组里的设置，否则用户看不到的那些项不会被复位
        for (Row row : category.rows) {
            if (row instanceof ValueRow valueRow) {
                valueRow.reset();
            }
        }
        statusMessage = category.title + " 已恢复默认值";
        statusMessageTimer = 120;
        scrollOffset = 0;
        // 重置也立即生效：先按默认值重建控件（否则开关类控件里还留着旧状态，
        // 紧接着写回会把旧值又写一遍），再统一写回配置并同步子系统。
        rebuildRows();
        for (ValueRow value : values) {
            value.save();
        }
        ClientEventHandler.onConfigSaved();
    }

    private void saveAndClose() {
        cacheWidgetValues();
        // 保存时会完整跑一遍同步，取消尚未触发的防抖同步，避免重复执行
        rebuildPending = false;
        applySyncDelayTicks = 0;
        // 一次性保存，不再需要第二次点击。
        // 旧实现里「云端已启用」时第一次点击只做确认：不保存、不关界面，只把按钮文字改成
        // 「仍然保存」。玩家往往以为已经保存，随后切换分类或按 ESC 就让改动静默丢失。
        // 云端会覆盖本地值的说明由 cloudManagedWarning() 写在对应分类里，保存时不再额外弹提示。
        for (ValueRow value : values) {
            value.save();
        }
        if (ConfigManager.isEnableBuiltinTtsApis()) {
            ModConfig.TTS_MODE.set("online");
        }
        // 清除旧 YACL 界面的 pending 值，防止覆盖用户在自定义界面的选择
        ClientEventHandler.setPendingBuiltinMimoVoice(null);
        ClientEventHandler.onConfigSaved();
        ConfigManager.saveCommonConfig();
        ConfigManager.saveClientConfig();
        minecraft.setScreen(new ConfigHealthCheckScreen(parent));
    }

    // ==================== Render ====================

    /**
     * 画一张半透明卡片：投影 → 圆角底色 → 圆角描边。
     *
     * <p>{@link GuiGraphics} 没有圆角也没有描边，靠 {@link VerityUiDraw} 用逐行矩形拼出来。
     * 在半透明背景上「描边」比「阴影」更重要——没有描边，卡片会和背后的世界糊在一起。
     */
    private static void drawCard(GuiGraphics graphics, int left, int top, int right, int bottom) {
        if (right - left <= 2 || bottom - top <= 2) return;
        VerityUiDraw.card(graphics, left, top, right, bottom, CARD_RADIUS);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 半透明：世界透得出来；背后没有世界（主菜单打开）时按普通菜单背景处理
        com.hanchen.veritycustomapi.client.ScreenBackgrounds.draw(this, graphics);

        long now = net.minecraft.Util.getMillis();
        // 入场：约 120ms 从下方 8px 滑到位置。只包 UI，不移动背景；
        // 动画期间按钮的点击框仍在最终位置，持续时间很短，不影响操作。
        double enter = VerityUiAnimation.easeOutCubic(
                VerityUiAnimation.clamp01((now - openedAt) / 120.0D));
        int enterOffset = (int) Math.round((1.0D - enter) * 8.0D);
        graphics.pose().pushPose();
        if (enterOffset != 0) {
            graphics.pose().translate(0.0F, enterOffset, 0.0F);
        }

        int sidebarLeft = getSidebarLeft();
        int sidebarWidth = getSidebarWidth();
        int contentLeft = getContentLeft();
        int contentWidth = getContentWidth();
        int contentTop = getContentTop();
        int contentBottom = getContentBottom();

        // 背景是世界本身（30% 淡幕），所以面板不能是一整块实心板——
        // 拆成「侧栏卡片 + 内容卡片」两张，中间留缝让世界透出来。
        int panelTop = 12;
        if (usesPagedNavigation()) {
            // 极窄屏没有侧栏，整块一张卡片
            drawCard(graphics, sidebarLeft, panelTop, getPanelRight(), contentBottom);
        } else {
            drawCard(graphics, sidebarLeft, panelTop, sidebarLeft + sidebarWidth, contentBottom);
            drawCard(graphics, contentLeft - 6, panelTop, getPanelRight(), contentBottom);
            // 内容区标题栏：圆角抬升条，让分类标题与说明有归属，而不是浮在卡片顶上
            VerityUiDraw.raised(graphics, contentLeft - 5, panelTop + 1,
                    getPanelRight() - 1, contentTop - 8, CARD_RADIUS);
        }
        if (!usesPagedNavigation()) {
            graphics.drawString(font, "Verity 扩展增强", sidebarLeft + 10, 22, TEXT);
            graphics.drawString(font, "版本 " + OllamaProxyMod.version(), sidebarLeft + 10, 32, MUTED);
        }

        if (!categories.isEmpty()) {
            Category category = categories.get(categoryIndex);
            int titleWidth = usesPagedNavigation() ? Math.max(36, contentWidth - 64) : contentWidth;
            int titleLeft = usesPagedNavigation()
                    ? contentLeft + (contentWidth - font.width(fitText(font, category.title, titleWidth))) / 2
                    : contentLeft;
            // 切页时标题从右侧 6px 滑入，和侧栏高亮滑动形成同一方向的动作
            double titleT = VerityUiAnimation.easeOutCubic(
                    VerityUiAnimation.clamp01((now - categorySwitchAt) / 90.0D));
            int titleSlide = (int) Math.round((1.0D - titleT) * 6.0D);
            graphics.drawString(font, fitText(font, category.title, titleWidth),
                    titleLeft + titleSlide, 20, TEXT);
            if (!usesPagedNavigation() && !category.description.isBlank()) {
                graphics.drawString(font, fitText(font, category.description, contentWidth),
                        contentLeft + titleSlide, 34, MUTED);
            }
        }

        if (!usesPagedNavigation()) {
            int itemStep = getSidebarItemStep();
            int buttonHeight = getSidebarButtonHeight();
            int targetY = SIDEBAR_TOP + categoryIndex * itemStep - (int) Math.round(sidebarScroll);
            // 侧栏是导航：选中块直接跟到目标行，不做滑动，避免点一下还要等它滑过去
            sidebarHighlightY = targetY;
            int activeY = targetY;
            // 选中项：圆角底块 + 左侧强调条。只画一条竖线在半透明卡片上太弱
            if (activeY >= SIDEBAR_TOP && activeY + buttonHeight <= contentBottom) {
                VerityUiDraw.fillRounded(graphics, sidebarLeft + 3, activeY - 1,
                        sidebarLeft + sidebarWidth - 3, activeY + buttonHeight + 1,
                        3, VerityUiTheme.CARD_RAISED);
                VerityUiDraw.fillRounded(graphics, sidebarLeft + 3, activeY - 1,
                        sidebarLeft + 6, activeY + buttonHeight + 1, 2, ACCENT);
            }
            // 分类放不下时给侧栏一条自己的滚动条，避免玩家不知道还能往下滚
            double maxSidebarScroll = getMaxSidebarScroll();
            if (maxSidebarScroll > 0) {
                int trackX = sidebarLeft + sidebarWidth - 2;
                int viewport = getSidebarViewport();
                int thumbHeight = Math.max(16,
                        (int) (viewport * (double) viewport / getSidebarContentHeight()));
                int thumbY = SIDEBAR_TOP
                        + (int) (sidebarScroll * (viewport - thumbHeight) / maxSidebarScroll);
                graphics.fill(trackX, SIDEBAR_TOP, trackX + 2, contentBottom, SEPARATOR);
                graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumbHeight, ACCENT);
            }
        }

        graphics.enableScissor(contentLeft - 6, contentTop, contentLeft + contentWidth + 6, contentBottom);
        int y = contentTop - (int) scrollOffset;
        List<Row> rows = getCurrentCategoryRows();
        for (Row row : rows) {
            row.render(graphics, contentLeft, y, contentWidth);
            y += row.getHeight();
        }
        graphics.disableScissor();

        double maxScroll = getMaxScroll();
        if (maxScroll > 0) {
            int visibleHeight = getVisibleRowsHeight();
            int scrollBarX = contentLeft + contentWidth - 3;
            int scrollBarHeight = Math.max(20, (int)(visibleHeight * (double)visibleHeight / getRowsHeight(rows)));
            int scrollBarY = contentTop + (int)(scrollOffset * (visibleHeight - scrollBarHeight) / maxScroll);
            graphics.fill(scrollBarX, contentTop, scrollBarX + 2, contentBottom, SEPARATOR);
            graphics.fill(scrollBarX, scrollBarY, scrollBarX + 2, scrollBarY + scrollBarHeight, ACCENT);
        }

        graphics.fill(sidebarLeft, contentBottom, getPanelRight(), contentBottom + 1, SEPARATOR);
        if (!statusMessage.isEmpty() && statusMessageTimer > 0) {
            // 放在内容卡片与底部按钮之间的空档，用整行宽度并居中；
            // 原来画在按钮同一行、宽度还要减 260，稍长一点的提示就会被截断。
            String text = fitText(font, statusMessage, Math.max(80, contentWidth));
            // 最后 0.5 秒淡出；前半段保持纯色，避免刚出现就发灰
            int statusAlpha = statusMessageTimer >= 10
                    ? 255 : Math.max(0, statusMessageTimer * 255 / 10);
            graphics.drawString(font, text,
                    contentLeft + Math.max(0, (contentWidth - font.width(text)) / 2),
                    height - 40, VerityUiDraw.withAlpha(SUCCESS, statusAlpha));
        }

        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.pose().popPose();

        // 说明浮层画在最上层（super.render 之后），否则会被控件盖住
        drawHintTooltip(graphics, mouseX, mouseY);
    }

    /**
     * 鼠标悬停在某一行上时，把挂在它身上的说明浮出来。
     *
     * <p>优先显示在鼠标上方（用户要求「放在按钮上面」），上方放不下才翻到下方；
     * 左右也会夹在屏幕内，避免贴边被切掉。
     */
    private void drawHintTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (!isInsideContent(mouseX, mouseY)) {
            // 离开内容区就清掉，重新悬停同一行时重新播淡入
            hintRow = null;
            return;
        }
        List<Row> rows = getCurrentCategoryRows();
        int y = getContentTop() - (int) scrollOffset;
        Row hovered = null;
        for (Row row : rows) {
            int height = row.getHeight();
            if (height > 0 && row.hint != null && mouseY >= y && mouseY < y + height) {
                hovered = row;
                break;
            }
            y += height;
        }
        if (hovered == null) {
            hintRow = null;
            return;
        }
        long now = net.minecraft.Util.getMillis();
        if (hovered != hintRow) {
            hintRow = hovered;
            hintHoverAt = now;
        }
        double hintT = VerityUiAnimation.easeOutCubic(
                VerityUiAnimation.clamp01((now - hintHoverAt) / 80.0D));
        int hintLift = (int) Math.round((1.0D - hintT) * 4.0D);
        int hintAlpha = (int) Math.round(255.0D * hintT);

        int maxWidth = Math.min(240, Math.max(120, width - 40));
        List<FormattedCharSequence> lines = new ArrayList<>();
        // 标题单独一行、用强调色，和正文区分开
        String title = hovered.hintTitle;
        if (title != null && !title.isBlank()) {
            lines.addAll(font.split(Component.literal(title), maxWidth));
        }
        for (String paragraph : hovered.hint.split(NEWLINE)) {
            if (paragraph.isBlank()) continue;
            lines.addAll(font.split(Component.literal(paragraph), maxWidth));
        }
        if (lines.isEmpty()) return;

        int textWidth = 0;
        for (FormattedCharSequence line : lines) textWidth = Math.max(textWidth, font.width(line));
        int boxWidth = textWidth + 14;
        int lineHeight = 10;
        int boxHeight = lines.size() * lineHeight + 10;

        int x = clamp(mouseX + 10, 4, Math.max(4, width - boxWidth - 4));
        int boxY = mouseY - boxHeight - 10;
        if (boxY < 4) boxY = Math.min(mouseY + 14, height - boxHeight - 4);
        boxY = Math.max(2, Math.max(4, boxY) - hintLift);

        // 🔴 必须先 flush：GuiGraphics 的 fill 与 drawString 走不同渲染批次，
        // 不 flush 的话前面画过的文字会在浮层之后才提交，直接压在浮层上面——
        // 表现就像「浮层太透明、背后文字透出来」，其实根本不是透明度问题。
        graphics.flush();

        // 浮层底色用接近实心：它盖在内容之上，透出背景只会干扰阅读
        VerityUiDraw.fillRounded(graphics, x, boxY, x + boxWidth, boxY + boxHeight, 4,
                VerityUiDraw.withAlpha(0xFC14171C, (int) Math.round(0xFC * hintT)));
        VerityUiDraw.strokeRounded(graphics, x, boxY, x + boxWidth, boxY + boxHeight, 4,
                VerityUiDraw.withAlpha(VerityUiTheme.CARD_BORDER,
                        (int) Math.round(((VerityUiTheme.CARD_BORDER >>> 24) & 0xFF) * hintT)));
        int lineY = boxY + 5;
        for (int i = 0; i < lines.size(); i++) {
            int color = (i == 0 && title != null && !title.isBlank())
                    ? VerityUiTheme.ACCENT : VerityUiTheme.TEXT;
            graphics.drawString(font, lines.get(i), x + 7, lineY,
                    VerityUiDraw.withAlpha(color, hintAlpha));
            lineY += lineHeight;
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ==================== Category Builders ====================

    private void buildCategories() {
        buildCategory("概览", "概览", this::overviewCategory);
        buildCategory("对话与记忆", "对话与记忆", this::conversationCategory);
        buildCategory("多人游戏设置", "多人游戏设置", this::serverSettingsCategory);
        buildCategory("语音识别", "语音识别", this::speechRecognitionCategory);
        buildCategory("语音朗读", "语音朗读", this::speechSynthesisCategory);
        buildCategory("外观与资源", "外观与资源", this::appearanceCategory);
        buildCategory("原版 Verity", "原版 Verity", this::verityBehaviorCategory);
        buildCategory("主动感知", "主动感知", this::proactiveAwarenessCategory);
        buildCategory("摄像头视觉", "摄像头视觉", this::cameraVisionCategory);
        buildCategory("AI 建造", "AI 建造", this::buildingCategory);
        buildCategory("云端与账户", "云端与账户", this::cloudAccountCategory);
        buildCategory("MMD 动画", "MMD 动画", this::mmdCategory);
        buildCategory("界面与记录", "界面与记录", this::interfaceCategory);
        buildCategory("高级与诊断", "高级与诊断", this::systemCategory);
    }

    private void overviewCategory() {
        category("概览", "各项功能是否已配置好", "概览");
        statusCategory();
    }

    private void conversationCategory() {
        aiChatCategory();
    }

    private void serverSettingsCategory() {
        category("多人游戏设置", "多人聊天、权限和 Verity 回复显示范围", "多人游戏设置");
        section("AI 对话 API");
        info("处理方式", "所有玩家的 /v 对话统一使用服务器配置的原版 Verity 公共 API");
        info("服务器职责", "服务器调用原版 Verity 的提示词、记忆、工具和 AI 链路，并把回复广播到公屏");
        info("配置位置", "请在服务端 verity-common.toml 的 GeneralSettings.AISettings 中配置 API 地址、模型和密钥");

        section("对话触发方式");
        toggle("必须使用 /v 指令才能对话",
                ModConfig.SERVER_VERITY_REQUIRE_COMMAND::get, true,
                ModConfig.SERVER_VERITY_REQUIRE_COMMAND::set);
        info("开启后", "只有 /v 私聊或 /v! 公开指令会触发 Verity，普通聊天只发送给其他玩家");
        info("关闭后", "普通聊天也会触发 Verity，不建议多人服务器使用");
        info("单人游戏", "单人游戏不受本开关限制，直接打字就能对话，无需 /v");

        section("公开 Verity 对话");
        toggle("允许玩家使用 /v! 公开回答",
                ModConfig.SERVER_VERITY_ALLOW_PUBLIC_CHAT::get, true,
                ModConfig.SERVER_VERITY_ALLOW_PUBLIC_CHAT::set);
        info("开启后", "玩家可将自己客户端 API 生成的回答广播给全服，并显示 @玩家名");
        info("关闭后", "/v! 会被服务器拒绝；/v 私聊仍然可用");
        info("API 位置", "服务器不提供 AI API，每位玩家都使用自己的客户端 API");
    }

    private void speechRecognitionCategory() {
        category("语音识别", "识别模式、离线模型与在线服务", "语音识别");
        sttCategory();
    }

    private void speechSynthesisCategory() {
        category("语音朗读", "朗读音色、语速与音量", "语音朗读");
        ttsCategory();
    }

    private void appearanceCategory() {
        category("外观与资源", "更换皮肤、管理资源", "外观与资源");
        skinCategory();
    }

    private void verityBehaviorCategory() {
        category("原版 Verity", "原版玩法、善恶值与危险操作", "原版 Verity");
        originalVerityCategory();
    }

    private void proactiveAwarenessCategory() {
        category("主动感知", "设置 Verity 在哪些游戏事件中回应", "主动感知");
        awarenessCategory();
    }

    private void cameraVisionCategory() {
        category("摄像头视觉", "让 Verity 看到你的摄像头画面与游戏内画面", "摄像头视觉");
        cameraVisionSection();
    }

    private void buildingCategory() {
        category("AI 建造", "建造方式、速度与规模上限", "AI 建造");
        aiBuildingCategory();
    }

    private void cloudAccountCategory() {
        category("云端与账户", "云端授权、同步与用量", "云端与账户");
        cloudCategory();
    }

    private void mmdCategory() {
        category("MMD 动画", "让 Demon Mode 跟着音乐跳舞，并同步镜头", "MMD 动画");
        section("内置 PMX 模型（新增功能）");
        toggle("启用内置 PMX 模型", ModConfig.ENABLE_BUILTIN_PMX::get, false,
                ModConfig.ENABLE_BUILTIN_PMX::set);
        text("PMX 模型目录或路径（可选）", ModConfig.BUILTIN_PMX_MODEL.get(), "留空自动检测",
                ModConfig.BUILTIN_PMX_MODEL::set);
        info("模型路径", "支持 config/verity_extended/pmx/<目录名>/ 下任意 .pmx/.pmd 文件，也支持项目内绝对路径；留空时自动检测第一个有效模型；关闭后完全使用原版模型");
        action("重新加载 PMX 模型", "读取当前配置的内置 PMX 模型，失败时自动回退原版",
                () -> {
                    com.hanchen.veritycustomapi.pmx.BuiltinPmxModelManager.get().reload();
                    statusMessage = "PMX 模型缓存已重置；下一次渲染时重新加载";
                    statusMessageTimer = 60;
                }, "重新加载");
        info("兼容说明", "这是新增旁路功能，不会修改原有 Geckolib、MMD/VMD 或皮肤渲染路径");

        com.hanchen.veritycustomapi.mmd.MmdPlaybackManager.get().scanAndLoad();
        java.util.Map<String, com.hanchen.veritycustomapi.mmd.MmdDanceProject> projects =
                com.hanchen.veritycustomapi.mmd.MmdPlaybackManager.get().listProjects();
        section("开始使用");
        info("舞蹈项目", "将动作、镜头和音乐放入 config/verity_extended/mmd/<项目名>，重新扫描后按 N 选择播放");
        advancedSection("进阶玩法");
        info("自定义项目", "在项目文件夹中新建 manifest.json，可设置显示名称、模型缩放与循环方式");
        info("多动作分配", "一个项目可放入多个动作文件，播放时会自动分给附近多个 Demon Mode");
        info("循环播放", "默认只播放一遍；在 manifest.json 中开启循环后可反复播放");
        advancedSection("镜头设置");
        decimal("镜头高度（方块）", ModConfig.MMD_CAMERA_HEIGHT.get(), 2.4D, -16.0D, 32.0D,
                ModConfig.MMD_CAMERA_HEIGHT::set);
        info("调整建议", "镜头偏低就调高、偏高就调低，默认 2.4 格，保存后立即生效");
        advancedSection("恶魔行为");
        toggle("自动生成恶魔", ModConfig.AI_DEMON_AUTO_SPAWN::get, true,
                ModConfig.AI_DEMON_AUTO_SPAWN::set);
        info("生成规则", "播放 MMD 时如果世界内没有恶魔，会让服务器在玩家正前方自动生成一只");
        toggle("恶魔友好模式（不攻击玩家）", ModConfig.AI_DEMON_FRIENDLY_MODE::get, true,
                ModConfig.AI_DEMON_FRIENDLY_MODE::set);
        info("生效范围", "永久关闭恶魔的「追玩家」「破门」「抓玻璃」等行为，包括按需生成的恶魔");
        integer("生成请求超时（毫秒）", ModConfig.AI_DEMON_SPAWN_RETRY_MS.get(), 8000, 2000, 30000,
                ModConfig.AI_DEMON_SPAWN_RETRY_MS::set);
        info("超时建议", "8 秒默认；联机卡顿时建议调大，单机高延迟请保持默认");
        advancedSection("项目列表（共 " + projects.size() + " 个）");
        if (projects.isEmpty()) {
            listItem("暂无项目", "还没有识别到舞蹈项目，按上面「三步开始」放入文件后重新扫描");
        } else {
            for (com.hanchen.veritycustomapi.mmd.MmdDanceProject project : projects.values()) {
                listItem(project.displayName(), project.contentSummary()
                        + "  时长=" + String.format("%.2f", project.durationSeconds()) + "s"
                        + (project.legacy() ? "  [旧格式]" : project.manifestConfigured() ? "  [已配置]" : "  [自动识别]"));
            }
        }
        section("控制");
        action("重新扫描目录", "重新读取 config/verity_extended/mmd/ 并刷新上方项目列表",
                () -> {
                    com.hanchen.veritycustomapi.mmd.MmdPlaybackManager.get().scanAndLoad();
                    int n = com.hanchen.veritycustomapi.mmd.MmdPlaybackManager.get().listProjects().size();
                    statusMessage = "扫描完成，共 " + n + " 个舞蹈项目";
                    statusMessageTimer = 60;
                }, "重新扫描");
        info("命令用法", "/verity-mmd play <文件夹名> 播放整个项目；散装的 VMD 文件名同样可用");
    }

    private void interfaceCategory() {
        category("界面与记录", "游戏内 HUD、快捷指令、对话记录与日记", "界面与记录");

        section("游戏内 HUD");
        toggle("显示对话气泡", ModConfig.HUD_ENABLED::get, true, ModConfig.HUD_ENABLED::set);
        info("对话气泡", "Verity 说话时在屏幕右下角显示台词（圆形表情头像 + 文字）；按 K 打开快捷指令面板");
        if (ModConfig.HUD_ENABLED.get()) {
            toggle("显示表情头像", ModConfig.HUD_PORTRAIT::get, true, ModConfig.HUD_PORTRAIT::set);
            integer("气泡停留（秒）", ModConfig.HUD_DIALOGUE_SECONDS.get(), 12, 3, 60,
                    ModConfig.HUD_DIALOGUE_SECONDS::set);
        }

        section("沉浸式对话");
        toggle("沉浸式对话模式", ModConfig.IMMERSIVE_CHAT::get, false, ModConfig.IMMERSIVE_CHAT::set);
        info("沉浸式说明", "开启后原版聊天栏不再显示任何内容（含其它玩家与指令反馈），"
                + "平时只看右下角气泡；按 T 打开聊天框时，全部聊天记录会照常显示");
        if (ModConfig.IMMERSIVE_CHAT.get() && !ModConfig.HUD_ENABLED.get()) {
            info("注意", "对话气泡当前是关闭的，此时不会隐藏聊天栏，否则你没有地方能看到消息");
        }
        toggle("显示状态角标", ModConfig.HUD_STATUS::get, false, ModConfig.HUD_STATUS::set);
        info("状态角标", "左上角显示好感成长、记忆条数、今日对话次数与剩余额度");
        toggle("导航箭头", ModConfig.HUD_NAV_ARROW::get, true, ModConfig.HUD_NAV_ARROW::set);
        info("导航箭头", "指定导航目标后，准星下方会出现指向箭头与距离");

        section("移动与提醒");
        info("跟随与导航", "按 K 打开快捷指令面板，点「跟我来 / 到我身边」就行；"
                + "也可以直接输 /verity-ai follow、come、stop、goto <x> <y> <z>");
        toggle("启用定时提醒", ModConfig.REMINDER_ENABLED::get, true, ModConfig.REMINDER_ENABLED::set);
        info("定时提醒", "直接说「20 分钟后提醒我收菜」，或输入 /verity-ai remind 20 收菜");

        advancedSection("上下文与记录");
        toggle("背包摘要写入对话", ModConfig.INVENTORY_CONTEXT_ENABLED::get, true,
                ModConfig.INVENTORY_CONTEXT_ENABLED::set);
        info("背包摘要", "把背包物品统计写进提示词，Verity 才能回答「我还有多少木头」这类问题");
        if (ModConfig.INVENTORY_CONTEXT_ENABLED.get()) {
            integer("最多列出物品数", ModConfig.INVENTORY_MAX_TYPES.get(), 12, 3, 40,
                    ModConfig.INVENTORY_MAX_TYPES::set);
        }
        toggle("记录本地对话", ModConfig.CHAT_LOG_ENABLED::get, true, ModConfig.CHAT_LOG_ENABLED::set);
        if (ModConfig.CHAT_LOG_ENABLED.get()) {
            integer("最多保留条数", ModConfig.CHAT_LOG_MAX_ENTRIES.get(), 500, 50, 5000,
                    ModConfig.CHAT_LOG_MAX_ENTRIES::set);
        }
        action("查看对话记录与日记", "浏览最近的对话、导出文本，或生成一篇日记",
                () -> minecraft.setScreen(
                        new com.hanchen.veritycustomapi.client.ConversationLogScreen(this)), "打开");
    }

    private void systemCategory() {
        category("高级与诊断", "高级选项与故障排查", "高级与诊断");
        advancedCategory();
        debugCategory();
    }

    private void buildCategory(String title, String sidebarTitle, Runnable builder) {
        int categoryStart = categories.size();
        int valueStart = values.size();
        try {
            builder.run();
            if (categories.size() != categoryStart + 1) {
                throw new IllegalStateException("分类构建数量异常");
            }
        } catch (Throwable error) {
            while (categories.size() > categoryStart) categories.remove(categories.size() - 1);
            while (values.size() > valueStart) values.remove(values.size() - 1);
            category(title, "该分类加载失败，其他分类仍可正常使用", sidebarTitle);
            warning("加载失败", error.getClass().getSimpleName(), DANGER);
            OllamaProxyMod.LOGGER.error("配置分类加载失败: {}", title, error);
        }
    }

    private void statusCategory() {
        section("功能状态");
        String voskStatus = VoskRuntimeSupport.getOfflineStatus(ModConfig.VOSK_MODEL_PATH.get());
        String voskLibStatus = VoskRuntimeSupport.getUnavailableReason();
        boolean sttAvailable = ConfigManager.getRecognitionMode() == ConfigManager.RecognitionMode.API
                || voskStatus.contains("可用") && voskLibStatus == null;
        // 文档 §4.3：档位总数 > 0 但健康线路为 0 时显示「暂时不可用」
        boolean sttUnavailable = ConfigManager.isBuiltinRouteTemporarilyUnavailable("stt");
        boolean ttsUnavailable = ConfigManager.isBuiltinRouteTemporarilyUnavailable("tts");
        boolean chatUnavailable = ConfigManager.isBuiltinRouteTemporarilyUnavailable("chat");
        info("语音识别", !ConfigManager.isEnableVoiceInput() ? "已关闭"
                : sttUnavailable ? "暂时不可用" : sttAvailable ? "已配置" : "需要设置");
        if (ConfigManager.getRecognitionMode() == ConfigManager.RecognitionMode.LOCAL
                && (!voskStatus.contains("可用") || (voskLibStatus != null && voskLibStatus.contains("未包含")))) {
            warning("离线识别", "暂时不可用，可到「语音识别」页安装中文模型", WARNING);
        }
        boolean ttsReady = !ConfigManager.isEnableTts()
                || ConfigManager.getTtsMode() == ConfigManager.TtsMode.LOCAL
                || !ConfigManager.snapshotTtsRoutes().isEmpty();
        info("语音朗读", !ConfigManager.isEnableTts() ? "已关闭"
                : ttsUnavailable ? "暂时不可用" : ttsReady ? "已配置" : "需要设置");
        info("AI 对话", chatUnavailable ? "暂时不可用"
                : ConfigManager.snapshotChatRoutes().isEmpty() ? "需要设置" : "已配置");
        info("云端服务", ConfigManager.isCloudEnabled()
                ? ConfigManager.isCloudConfigLoaded() ? "已同步" : "同步中…"
                : "已关闭");
        if (chatUnavailable || sttUnavailable || ttsUnavailable) {
            warning("云端线路", "部分内置线路暂时不可用（当前没有健康线路），会保留最近一次有效配置并自动重试", WARNING);
        }

        if (ConfigManager.isCloudEnabled()) {
            if (ConfigManager.isCloudAuthFailed()) {
                warning("授权失效", "云端配置密钥已失效，请重新授权（当前仍使用最近一次有效配置）", DANGER);
            }
            double remaining = ConfigManager.getCreditsRemainingExact();
            if (remaining < 0) {
                info("剩余额度", "同步中…");
            } else {
                info("剩余额度", ConfigManager.formatCredits(remaining));
                if (ConfigManager.isCreditsExhausted()) {
                    warning("额度不足", "付费线路额度已用完，暂时无法使用", DANGER);
                } else if (ConfigManager.isCreditsLow()) {
                    warning("额度预警", "额度快用完了", WARNING);
                }
            }
        }
    }

    private void cloudCategory() {
        String currentKey = ConfigManager.getCloudApiKey();
        boolean authorized = currentKey != null && !currentKey.isBlank();

        section("账号授权");
        if (authorized) {
            info("授权状态", "已授权 (" + maskKey(currentKey) + ")");
            info("同步方式", "授权后自动获取云端配置，无需重启游戏");
            action("重新授权", "清除当前授权并关闭云端开关，之后可输入新配对码", () -> {
                PairingResourceFallback.apply(currentKey);
                CloudCredentialStore.getInstance().clearAuthorization();
                ModConfig.CLOUD_API_KEY.set("");
                ModConfig.CLOUD_ENABLE.set(false);
                ConfigManager.saveCommonConfig();
                pairingCode = "";
                refreshCurrentCategory();
            }, "清除授权");
        } else {
            info("获取配对码", "打开 verity.xrstudio.cn，登录后生成配对码");
            text("配对码", pairingCode, "", v -> pairingCode = v);
            action("一键授权", "输入配对码后点击，自动完成账号授权", this::exchangePairingCode, "授权");
            info("说明", "授权成功后会立即同步并应用云端配置");
        }

        section("云端设置");
        toggle("启用云端配置", ModConfig.CLOUD_ENABLE::get, false, ModConfig.CLOUD_ENABLE::set);
        if (authorized && !ModConfig.CLOUD_ENABLE.get()) {
            info("当前状态", "已授权，但云端开关是关闭的；打开上面的开关并保存后才会同步云端配置");
        }
        info("同步状态", ConfigManager.isCloudConfigLoaded() ? "云端配置已生效" : "等待云端配置");
        // 「额度消耗」分组（计费方式 / 单次最低扣费 / 各服务单价 / 结算说明）已移除：
        // 这些是云端结算信息，属于运营侧数据，不放在玩家界面上展示。
    }

    private void originalVerityCategory() {
        section("原版 Verity");
        text("原版朗读音色", OriginalVerityConfigSync.getVoiceValue("voice", "Daniel"), "Daniel",
                v -> OriginalVerityConfigSync.queueVoiceString("voice", v));

        section("互动");
        toggle("允许给 Verity 送礼", ModConfig.ALLOW_VERITY_GIFT::get, true,
                ModConfig.ALLOW_VERITY_GIFT::set);
        info("送礼方式", "潜行 + 丢弃物品（Q），准星对着 Verity；每次消耗 1 个，30 秒冷却，好感度 +1");
        toggle("允许踢 Verity", ModConfig.ALLOW_VERITY_KICK::get, true,
                ModConfig.ALLOW_VERITY_KICK::set);
        info("踢的方式", "潜行 + 左键：不造成伤害，只把她推开并让她回应，10 秒冷却");
        toggle("跨存档追击（实验性）", ModConfig.CROSS_SAVE_PURSUIT::get, false,
                ModConfig.CROSS_SAVE_PURSUIT::set);
        info("跨存档追击", "Verity 恶魔化后会记住你：删除存档/新建世界仍会追来。"
                + "击败追击恶魔即可解除；不会锁退出按钮或删除存档");
        integer("每日好感上限", ModConfig.DAILY_KARMA_GAIN_CAP.get(), 3, 0, 20,
                ModConfig.DAILY_KARMA_GAIN_CAP::set);
        info("好感上限", "喂食、送礼和 Verity 自己在对话中加的好感共用这个每日额度；0 表示不限制");

        section("界面汉化");
        toggle("汉化原版 Verity 界面", ModConfig.ENABLE_VERITY_LOCALIZATION::get, true,
                ModConfig.ENABLE_VERITY_LOCALIZATION::set);
        info("生效时机", "游戏启动时完成汉化，修改后需要重启游戏");

        section("恐怖增强");
        toggle("启用分层恐怖导演", ModConfig.HORROR_ENABLED::get, true, ModConfig.HORROR_ENABLED::set);
        decimal("恐怖强度", ModConfig.HORROR_INTENSITY.get(), 1.35D, 0.0D, 2.0D,
                ModConfig.HORROR_INTENSITY::set);
        toggle("暗角、黑场与故障视觉", ModConfig.HORROR_VISUALS::get, true, ModConfig.HORROR_VISUALS::set);
        toggle("心跳、脚步与环境异响", ModConfig.HORROR_AUDIO::get, true, ModConfig.HORROR_AUDIO::set);
        toggle("追逐镜头呼吸与震颤", ModConfig.HORROR_CAMERA_SHAKE::get, true,
                ModConfig.HORROR_CAMERA_SHAKE::set);
        toggle("允许短暂跳脸惊吓", ModConfig.HORROR_JUMPSCARES::get, true,
                ModConfig.HORROR_JUMPSCARES::set);
        toggle("允许视野边缘幻影", ModConfig.HORROR_HALLUCINATIONS::get, true,
                ModConfig.HORROR_HALLUCINATIONS::set);
        advancedSection("日常异常");
        toggle("正常阶段偶发异常", ModConfig.HORROR_AMBIENT_ANOMALIES::get, true,
                ModConfig.HORROR_AMBIENT_ANOMALIES::set);
        toggle("显示细思极恐的异常文字", ModConfig.HORROR_ANOMALY_TEXT::get, true,
                ModConfig.HORROR_ANOMALY_TEXT::set);
        decimal("日常异常频率", ModConfig.HORROR_ANOMALY_FREQUENCY.get(), 1.0D, 0.0D, 3.0D,
                ModConfig.HORROR_ANOMALY_FREQUENCY::set);
        info("触发规律", "普通 Verity 在附近时，夜晚、地下、低亮度和独处会提高概率；每次仍有长冷却");
        info("安全边界", "仅影响本地声音和画面，不生成额外敌人、不破坏方块、不修改世界规则");

        Minecraft mc = Minecraft.getInstance();
        boolean remoteServer = mc != null && mc.getCurrentServer() != null && !mc.hasSingleplayerServer();
        if (remoteServer) {
            section("多人服务器限制");
            info("当前环境", "远程多人服务器，无法修改服务器权威设置");
            return;
        }

        section("常用玩法");
        integer("敌对状态开始天数", OriginalVerityConfigSync.getGeneralInt("dayCount", 5), 5, 1, 100,
                v -> OriginalVerityConfigSync.queueGeneralInt("dayCount", v));
        toggle("每句话必须含 Verity",
                () -> OriginalVerityConfigSync.getGeneralBoolean("requireVerity", false), false,
                v -> OriginalVerityConfigSync.queueGeneralBoolean("requireVerity", v));
        toggle("播放启动视频",
                () -> OriginalVerityConfigSync.getGeneralBoolean("playVideo", true), true,
                v -> OriginalVerityConfigSync.queueGeneralBoolean("playVideo", v));
        toggle("真实黑暗",
                () -> OriginalVerityConfigSync.getGeneralBoolean("trueDarkness", true), true,
                v -> OriginalVerityConfigSync.queueGeneralBoolean("trueDarkness", v));
        toggle("显示善恶值条",
                () -> !OriginalVerityConfigSync.getGeneralBoolean("showKarma", false), true,
                v -> OriginalVerityConfigSync.queueGeneralBoolean("showKarma", !v));

        advancedSection("危险行为");
        toggle("允许 Verity 变异",
                ModConfig.ALLOW_VERITY_DEMON_TRANSFORMATION::get, true,
                ModConfig.ALLOW_VERITY_DEMON_TRANSFORMATION::set);
        info("变异说明", "关闭后只会阻止之后生成的恶魔，不会移除已经存在的恶魔");
        toggle("允许踢出或崩溃玩家",
                () -> OriginalVerityConfigSync.getGeneralBoolean("canCrash", true), true,
                v -> OriginalVerityConfigSync.queueGeneralBoolean("canCrash", v));
        toggle("随机杀死野生动物",
                () -> OriginalVerityConfigSync.getGeneralBoolean("killWildlife", true), true,
                v -> OriginalVerityConfigSync.queueGeneralBoolean("killWildlife", v));
        toggle("允许杀死实体",
                () -> OriginalVerityConfigSync.getGeneralBoolean("killEntities", true), true,
                v -> OriginalVerityConfigSync.queueGeneralBoolean("killEntities", v));
        toggle("允许杀死村民",
                () -> OriginalVerityConfigSync.getGeneralBoolean("killVillagers", true), true,
                v -> OriginalVerityConfigSync.queueGeneralBoolean("killVillagers", v));
    }

    private void aiChatCategory() {
        category("对话与记忆", "对话服务、人设、记忆与搜索", "对话与记忆");
        cloudManagedWarning("对话服务、密钥、模型和超时",
                ModConfig.TARGET_API_URL.get(), ModConfig.TARGET_API_KEY.get(), ModConfig.TARGET_MODEL.get());
        section("使用方式");
        toggle("英文朗读与中文字幕", ModConfig.BILINGUAL_SPEECH_SUBTITLES::get, false,
                ModConfig.BILINGUAL_SPEECH_SUBTITLES::set);
        info("双语模式", "让 Verity 说英文、照英文配音；中文译文由系统生成，聊天栏以 [译] 显示，头顶不再重复。"
                + "想换配音语言，用下方「朗读路线与语言」里的「配音语言」");
        toggle("双语二次翻译兜底", ModConfig.BILINGUAL_SECOND_PASS::get, false,
                ModConfig.BILINGUAL_SECOND_PASS::set);
        info("二次翻译", "开启：模型没在同一次回复里给出中文时，系统再调用一次模型补翻译（中文更稳，但每条回复会多等几秒）；关闭：回复最快，模型没给中文时只显示英文");
        toggle("回复仅在头顶显示", ModConfig.OVERHEAD_ONLY_CHAT::get, false,
                ModConfig.OVERHEAD_ONLY_CHAT::set);
        info("显示位置", "开启后 Verity 的回复只显示在实体上方，不再进入聊天栏（双语模式下头顶字幕显示中文译文）");
        toggle("对话流式输出", ModConfig.CHAT_STREAM::get, false,
                ModConfig.CHAT_STREAM::set);
        info("流式输出", "开启后回复会逐字显示；关闭时一次性返回完整内容，兼容性最好");
        toggle("启用体型成长", ModConfig.ENABLE_VERITY_GROWTH::get, false,
                ModConfig.ENABLE_VERITY_GROWTH::set);
        info("成长说明", "开启后，每次成功喂食都会让 Verity 逐渐变大；关闭后恢复正常大小");
        section("人设");
        toggle("启用自定义人设", ModConfig.ENABLE_CUSTOM_PROMPT::get, false, ModConfig.ENABLE_CUSTOM_PROMPT::set);
        if (ModConfig.ENABLE_CUSTOM_PROMPT.get()) {
            choice("人设预设", getCurrentPreset().name(), ConfigManager.PromptPreset.ORIGINAL_VERITY.name(),
                    promptNames(), promptValues(), ModConfig.SELECTED_PRESET::set);
            if (ConfigManager.PromptPreset.CUSTOM_FILE.name().equalsIgnoreCase(ModConfig.SELECTED_PRESET.get())) {
                text("人设文件", ModConfig.CUSTOM_PROMPT_FILE.get(), "./config/verity_custom_prompt.txt",
                        ModConfig.CUSTOM_PROMPT_FILE::set);
            }
            // 让人设「到底生效了没有」一眼可见：以前只能靠翻日志里的一行注入记录
            info("当前生效人设", describeEffectivePersona());
            if (isRemoteServerSession()) {
                warning("多人游戏", "连服务器时 AI 请求由服务端代理处理，人设要用服务端的 config/verity_extended-common.toml "
                        + "（或让管理员用 /verity-admin 配置）才会生效；这里改的只影响你自己开的单人世界。", WARNING);
            }
        }
        section("记忆");
        toggle("启用长期记忆", ModConfig.MEMORY_ENABLED::get, true, ModConfig.MEMORY_ENABLED::set);
        if (ModConfig.MEMORY_ENABLED.get()) {
            action("管理当前世界记忆", "查看和管理当前世界保存的记忆",
                    () -> minecraft.setScreen(new MemoryManagementScreen(this)), "打开");
            toggle("对话时自动检索记忆", ModConfig.MEMORY_RETRIEVAL_ENABLED::get, true,
                    ModConfig.MEMORY_RETRIEVAL_ENABLED::set);
            info("检索说明", "把与当前话题最相关的几条记忆直接写进提示词，避免模型忘了翻记忆");
            if (ModConfig.MEMORY_RETRIEVAL_ENABLED.get()) {
                integer("每次注入条数", ModConfig.MEMORY_RETRIEVAL_TOP_N.get(), 6, 1, 20,
                        ModConfig.MEMORY_RETRIEVAL_TOP_N::set);
                integer("记忆衰减半衰期（天）", ModConfig.MEMORY_DECAY_DAYS.get(), 30, 1, 365,
                        ModConfig.MEMORY_DECAY_DAYS::set);
                info("衰减说明", "越久没用到的记忆权重越低，数值越大越耐时间冲刷");
            }
            toggle("自动记住对话内容", ModConfig.MEMORY_AUTO_WRITE_ENABLED::get, false,
                    ModConfig.MEMORY_AUTO_WRITE_ENABLED::set);
            info("自动记忆", "对话结束后额外请求一次模型做摘要并写入记忆，会产生少量额外用量");
            if (ModConfig.MEMORY_AUTO_WRITE_ENABLED.get()) {
                integer("自动记忆最小间隔（秒）", ModConfig.MEMORY_AUTO_WRITE_MIN_INTERVAL_SECONDS.get(), 120, 30, 3600,
                        ModConfig.MEMORY_AUTO_WRITE_MIN_INTERVAL_SECONDS::set);
                info("间隔说明", "两次自动记忆之间的最短间隔，避免每句话都多请求一次");
            }
            toggle("记忆按玩家隔离", ModConfig.MEMORY_PER_PLAYER::get, true, ModConfig.MEMORY_PER_PLAYER::set);
            info("隔离说明", "多人共用一个世界时，自动写入的记忆只对本人可见；原版工具手写的记忆仍为公共");
        }
        section("搜索");
        toggle("启用联网搜索", ModConfig.WEB_SEARCH_ENABLED::get, false, ModConfig.WEB_SEARCH_ENABLED::set);
        if (ModConfig.WEB_SEARCH_ENABLED.get()) {
            toggle("每轮都搜索", ModConfig.WEB_SEARCH_ALWAYS::get, false,
                    ModConfig.WEB_SEARCH_ALWAYS::set);
            info("触发方式", "默认只在话里出现「搜索/查一下/最新/今天/价格」这类信号时才搜，"
                    + "漏判就会觉得「开了没反应」；打开上面的开关则每轮都搜，更稳，"
                    + "代价是每句话多一次请求和几百毫秒延迟");
            choice("搜索服务", ModConfig.WEB_SEARCH_PROVIDER.get(), "TAVILY",
                    new String[]{"Tavily", "Brave Search", "DeepSeek", "自定义 API"},
                    new String[]{"TAVILY", "BRAVE", "DEEPSEEK", "CUSTOM"},
                    ModConfig.WEB_SEARCH_PROVIDER::set);
            info("切换方式", "点一下「搜索服务」会按顺序循环："
                    + "Tavily → Brave Search → DeepSeek → 自定义 API。"
                    + "要接自己的接口就点到「自定义 API」，下面会多出 5 个输入项。");
            boolean customSearch = "CUSTOM".equalsIgnoreCase(ModConfig.WEB_SEARCH_PROVIDER.get());
            text("搜索访问密钥", ModConfig.WEB_SEARCH_API_KEY.get(), "", ModConfig.WEB_SEARCH_API_KEY::set);
            if (!customSearch) {
                info("密钥说明", "Tavily 与 Brave 需填各自的 API Key；"
                        + "DeepSeek 直接填 DeepSeek 平台的 API Key（与对话用的是同一个即可）");
            } else {
                info("自定义说明", "把任意返回 JSON 的搜索接口接进来：地址、请求体模板、"
                        + "结果路径、字段映射都可配。密钥可留空（本机或免鉴权服务）。");
                text("接口地址", ModConfig.WEB_SEARCH_CUSTOM_URL.get(), "",
                        ModConfig.WEB_SEARCH_CUSTOM_URL::set);
                text("请求体模板", ModConfig.WEB_SEARCH_CUSTOM_BODY.get(),
                        "{\"query\":\"{query}\",\"count\":{count}}",
                        ModConfig.WEB_SEARCH_CUSTOM_BODY::set);
                text("附加请求头", ModConfig.WEB_SEARCH_CUSTOM_HEADERS.get(),
                        "Authorization: Bearer {key}",
                        ModConfig.WEB_SEARCH_CUSTOM_HEADERS::set);
                text("结果路径", ModConfig.WEB_SEARCH_CUSTOM_RESULTS.get(), "results",
                        ModConfig.WEB_SEARCH_CUSTOM_RESULTS::set);
                text("字段映射", ModConfig.WEB_SEARCH_CUSTOM_FIELDS.get(),
                        "title=title,content=summary|snippet,url=url",
                        ModConfig.WEB_SEARCH_CUSTOM_FIELDS::set);
                info("占位符", "请求体与请求头里可用 {query} 搜索词、{count} 条数、{key} 密钥，"
                        + "替换时会自动做 JSON 转义");
                info("结果路径示例", "results / data.results / web.pages；留空表示响应本身就是数组");
                info("字段映射示例", "title=title,content=summary|snippet,url=url"
                        + "——竖线表示回退顺序，取第一个非空字段");
            }
        }
    }

    private void advancedCategory() {
        section("游戏体验");
        toggle("友好模式（友好对话且禁止恶魔变异）",
                () -> ModConfig.FRIENDLY_MODE.get(), false, ModConfig.FRIENDLY_MODE::set);
        info("生效范围", "开启后使用友好、积极的对话语气，并阻止之后生成恶魔实体；不会移除已经存在的恶魔");
        if (ModConfig.ENABLE_CUSTOM_PROMPT.get()) {
            text("自定义人设（覆盖预设）", ModConfig.CUSTOM_PERSONA.get(), "", ModConfig.CUSTOM_PERSONA::set);
        }
        section("语音朗读高级");
        toggle("实时语音播报（MiMo）", () -> ModConfig.TTS_STREAM.get(), true, ModConfig.TTS_STREAM::set);
        toggle("同时启用原版朗读", () -> !ModConfig.DISABLE_ORIGINAL_TTS.get(), false,
                enabled -> ModConfig.DISABLE_ORIGINAL_TTS.set(!enabled));
        section("朗读路线与语言");
        choice("朗读路线", ModConfig.TTS_ROUTE_KIND.get(), "auto",
                new String[]{"自动", "原版朗读", "本地离线模型", "网络端点"},
                new String[]{"auto", "native", "local", "endpoint"},
                ModConfig.TTS_ROUTE_KIND::set);
        info("路线", "自动 = 跟随上面的语音模式（老配置不变）；"
                + "原版 = 完全交给原版朗读，扩展不接管（会顺带把原版 TTS 打开）；"
                + "本地 = 只用离线模型，不可用时交还原版（不会偷偷改用要花钱的端点）；"
                + "端点 = 联网合成，不可用时退回本地，再不行交还原版");
        choice("台词阅读语言", ModConfig.VOICE_DISPLAY_LANGUAGE.get(), "follow",
                new String[]{"照原文看", "简体中文", "English", "日本語", "한국어"},
                new String[]{"follow", "zh", "en", "ja", "ko"},
                ModConfig.VOICE_DISPLAY_LANGUAGE::set);
        choice("配音语言", ModConfig.VOICE_SPEAK_LANGUAGE.get(), "follow",
                new String[]{"照原文念", "简体中文", "English", "日本語", "한국어"},
                new String[]{"follow", "zh", "en", "ja", "ko"},
                ModConfig.VOICE_SPEAK_LANGUAGE::set);
        info("配音语言", "看和念可以各选各的。选了与阅读语言不同的配音语言时，"
                + "配音前先把台词转换过去；转不动就照原文念，不会因此没声音");
        if (!"follow".equalsIgnoreCase(ModConfig.VOICE_SPEAK_LANGUAGE.get())) {
            text("配音转换服务", ModConfig.VOICE_TRANSLATE_URL.get(), "", ModConfig.VOICE_TRANSLATE_URL::set);
            text("配音转换密钥", ModConfig.VOICE_TRANSLATE_API_KEY.get(), "", ModConfig.VOICE_TRANSLATE_API_KEY::set);
            text("配音转换模型", ModConfig.VOICE_TRANSLATE_MODEL.get(), "", ModConfig.VOICE_TRANSLATE_MODEL::set);
            info("配音转换线路", "三项都不填就借用下面的自定义对话服务。"
                    + "想给配音单独配一个便宜、响应快的模型时再填，免得每句台词都去占用主对话模型");
        }
        section("自定义对话服务");
        choice("服务商", ModConfig.LLM_API_PROVIDER.get(), "CUSTOM_OPENAI",
                names(ApiProviderEnum.values()), values(ApiProviderEnum.values()), ModConfig.LLM_API_PROVIDER::set);
        text("服务地址", ModConfig.TARGET_API_URL.get(), "", ModConfig.TARGET_API_URL::set);
        text("访问密钥", ModConfig.TARGET_API_KEY.get(), "", ModConfig.TARGET_API_KEY::set);
        text("模型", ModConfig.TARGET_MODEL.get(), "", ModConfig.TARGET_MODEL::set);
        integer("请求超时（秒）", ModConfig.REQUEST_TIMEOUT.get(), 30, 5, 300, ModConfig.REQUEST_TIMEOUT::set);
        section("本地代理");
        integer("代理端口", ModConfig.SERVER_PORT.get(), 4000, 1, 65535, ModConfig.SERVER_PORT::set);
        section("自定义语音识别协议");
        integer("录音设备采样率", ModConfig.SAMPLE_RATE.get(), 16000, 8000, 48000, ModConfig.SAMPLE_RATE::set);
        choice("请求体格式", ModConfig.SPEECH_REQUEST_FORMAT.get(), "multipart",
                new String[]{"Multipart", "JSON", "URL 编码"},
                new String[]{"multipart", "json", "url_encoded"}, ModConfig.SPEECH_REQUEST_FORMAT::set);
        choice("认证方式", ModConfig.SPEECH_AUTH_TYPE.get(), "bearer",
                new String[]{"Bearer", "API Key 请求头", "API Key 参数", "自定义请求头"},
                new String[]{"bearer", "api_key_header", "api_key_param", "custom_header"}, ModConfig.SPEECH_AUTH_TYPE::set);
        text("识别文本字段", ModConfig.SPEECH_RESPONSE_FIELD.get(), "text", ModConfig.SPEECH_RESPONSE_FIELD::set);
        section("云端连接");
        text("云端接口地址", ModConfig.CLOUD_API_URL.get(), "https://verity.xrstudio.cn/api/v1/getconfig", ModConfig.CLOUD_API_URL::set);
        integer("后台同步间隔（秒）", ModConfig.CLOUD_PULL_INTERVAL.get(), 0, 0, 86400, ModConfig.CLOUD_PULL_INTERVAL::set);
        integer("云端请求超时（秒）", ModConfig.CLOUD_TIMEOUT.get(), 10, 1, 120, ModConfig.CLOUD_TIMEOUT::set);
        toggle("允许加载云端缓存", ModConfig.CLOUD_FALLBACK_TO_LOCAL::get, true, ModConfig.CLOUD_FALLBACK_TO_LOCAL::set);
        section("开发与调试");
        toggle("开发诊断模式", () -> ModConfig.DEV_MODE.get(), false, ModConfig.DEV_MODE::set);
    }

    private void sttCategory() {
        boolean offlineEnabled = VoiceUiPolicy.offlineEntriesEnabled(RuntimePlatform.isAndroidRuntime());
        cloudManagedWarning("语音识别、服务、模型和录音设置",
                ModConfig.SPEECH_API_URL.get(), ModConfig.SPEECH_API_KEY.get(), ModConfig.SPEECH_API_MODEL.get());
        section("主设置");
        toggle("启用语音识别", ModConfig.ENABLE_VOICE_INPUT::get, true, ModConfig.ENABLE_VOICE_INPUT::set);
        toggle("使用内置识别服务", ModConfig.ENABLE_BUILTIN_STT_APIS::get, false,
                ModConfig.ENABLE_BUILTIN_STT_APIS::set);
        boolean localMode = offlineEnabled && "local".equalsIgnoreCase(ModConfig.RECOGNITION_MODE.get());
        if (offlineEnabled) {
            choice("识别模式", ModConfig.RECOGNITION_MODE.get(), "local",
                    new String[]{"本地识别（无需联网）", "在线识别"},
                    new String[]{"local", "api"},
                    ModConfig.RECOGNITION_MODE::set);
        }
        if (localMode) {
            section("本地识别");
            text("模型文件夹", ModConfig.VOSK_MODEL_PATH.get(), "./config/vosk-model-small-cn", ModConfig.VOSK_MODEL_PATH::set);
            info("本地识别状态", VoskRuntimeSupport.getOfflineStatus(ModConfig.VOSK_MODEL_PATH.get()));
            action("安装中文识别模型", "自动下载并解压，完成后自动切到本地识别", this::installVoskModel, "安装");
        } else if (!offlineEnabled) {
            info("识别模式", "在线识别");
        }
        if (!localMode && !ModConfig.ENABLE_BUILTIN_STT_APIS.get()) {
            advancedSection("自定义在线识别");
            choice("服务商", ModConfig.STT_API_PROVIDER.get(), "CUSTOM_OPENAI",
                    names(SttApiProviderEnum.values()), values(SttApiProviderEnum.values()), ModConfig.STT_API_PROVIDER::set);
            text("服务地址", ModConfig.SPEECH_API_URL.get(), "", ModConfig.SPEECH_API_URL::set);
            text("访问密钥", ModConfig.SPEECH_API_KEY.get(), "", ModConfig.SPEECH_API_KEY::set);
            text("模型", ModConfig.SPEECH_API_MODEL.get(), "", ModConfig.SPEECH_API_MODEL::set);
        }
    }

    private void ttsCategory() {
        boolean offlineEnabled = VoiceUiPolicy.offlineEntriesEnabled(RuntimePlatform.isAndroidRuntime());
        cloudManagedWarning("语音朗读、服务、音色和播放设置",
                ModConfig.TTS_API_URL.get(), ModConfig.TTS_API_KEY.get(), ModConfig.TTS_API_MODEL.get());
        section("主设置");
        toggle("启用语音朗读", ModConfig.ENABLE_TTS::get, true, ModConfig.ENABLE_TTS::set);
        toggle("使用内置朗读服务", ModConfig.ENABLE_BUILTIN_TTS_APIS::get, false,
                ModConfig.ENABLE_BUILTIN_TTS_APIS::set);
        boolean localMode = offlineEnabled && "local".equalsIgnoreCase(ModConfig.TTS_MODE.get());
        if (offlineEnabled) {
            choice("语音模式", ModConfig.TTS_MODE.get(), "online",
                    new String[]{"本地朗读", "在线朗读"},
                    new String[]{"local", "online"},
                    ModConfig.TTS_MODE::set);
        } else {
            info("语音模式", "在线朗读");
        }
        if (localMode) {
            section("本地朗读");
            text("模型文件夹", ModConfig.TTS_MODEL_PATH.get(), "./config/tts-model", ModConfig.TTS_MODEL_PATH::set);
            integer("输出采样率", ModConfig.TTS_SAMPLE_RATE.get(), 22050, 8000, 48000, ModConfig.TTS_SAMPLE_RATE::set);
        } else if (ModConfig.ENABLE_BUILTIN_TTS_APIS.get()) {
            section("内置朗读");
            CloudConfig.MimoVoice[] voices = ConfigManager.getMimoVoices();
            String selectedVoice = ConfigManager.loadMimoVoice();
            if (voices.length == 0) {
                choice("音色", ModConfig.BUILTIN_MIMO_TTS_VOICE.get(), "苏打",
                        new String[]{"冰糖", "茉莉", "苏打", "白桦"},
                        new String[]{"冰糖", "茉莉", "苏打", "白桦"},
                        v -> ConfigManager.saveMimoVoice(v, false));
                action("刷新云端音色", "同步账号可用的内置和克隆音色", this::refreshMimoVoices, "刷新");
            } else {
                for (CloudConfig.MimoVoice voice : voices) {
                    if (voice == null || voice.id == null || voice.id.isBlank()) continue;
                    boolean clone = "clone".equalsIgnoreCase(voice.type);
                    boolean selected = voice.id.equals(selectedVoice);
                    String name = voice.name == null || voice.name.isBlank() ? voice.id : voice.name;
                    String label = name + (clone ? "（克隆）" : "（内置）") + (selected ? " · 已选择" : "");
                    action(label, clone ? "使用账号克隆音色，会消耗对应额度" : "使用 MiMo 内置音色", () -> {
                        ConfigManager.saveMimoVoice(voice.id, clone);
                        ModConfig.ENABLE_BUILTIN_TTS_APIS.set(true);
                        statusMessage = "已选择音色：" + name;
                        statusMessageTimer = 120;
                        refreshCurrentCategory();
                    }, selected ? "当前" : "选择");
                }
                action("刷新云端音色", "重新同步账号可用音色", this::refreshMimoVoices, "刷新");
            }
        } else {
            advancedSection("自定义在线朗读");
            choice("服务商", ModConfig.TTS_API_PROVIDER.get(), "CUSTOM_OPENAI",
                    names(TtsApiProviderEnum.values()), values(TtsApiProviderEnum.values()), ModConfig.TTS_API_PROVIDER::set);
            text("服务地址", ModConfig.TTS_API_URL.get(), "", ModConfig.TTS_API_URL::set);
            text("访问密钥", ModConfig.TTS_API_KEY.get(), "", ModConfig.TTS_API_KEY::set);
            text("模型", ModConfig.TTS_API_MODEL.get(), "", ModConfig.TTS_API_MODEL::set);
            text("音色", ModConfig.TTS_API_VOICE.get(), "alloy", ModConfig.TTS_API_VOICE::set);
        }
        section("播放");
        integer("语速(%)", (int)(ModConfig.TTS_SPEED.get() * 100), 100, 25, 400,
                v -> ModConfig.TTS_SPEED.set(v / 100.0));
        integer("音量(%)", (int)(ModConfig.TTS_VOLUME.get() * 100), 100, 0, 200,
                v -> ModConfig.TTS_VOLUME.set(v / 100.0));
    }

    private void refreshMimoVoices() {
        statusMessage = "正在同步云端音色…";
        statusMessageTimer = 180;
        addWidgets();
        ConfigManager.refreshMimoVoices(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) {
                mc.execute(() -> {
                    statusMessage = ConfigManager.getMimoVoices().length == 0
                            ? "未获取到云端音色，请检查账号授权"
                            : "云端音色已更新";
                    statusMessageTimer = 180;
                    refreshCurrentCategory();
                });
            }
        });
    }

    private void skinCategory() {
        SkinManager skinMgr = SkinManager.getInstance();
        String activeSkin = skinMgr.getActiveSkinName();
        boolean cloudEnabled = ConfigManager.isCloudEnabled();

        // ===== 云端皮肤 =====
        section("云端皮肤");
        if (!cloudEnabled) {
            warning("云端未启用", "请到「云端与账户」页开启后使用", WARNING);
        } else {
            info("保存方式", "云端皮肤按账号保存，可在资源中心统一管理");
            action("打开资源中心", "查看、下载、应用和管理云端皮肤", () -> minecraft.setScreen(new ResourceCenterScreen(this)), "打开");
            action("插件管理", "用一句话描述想要的功能，系统生成插件；可启用、停用、删除",
                    () -> minecraft.setScreen(new com.hanchen.veritycustomapi.client.PluginManagerScreen(this)), "打开");
            action("上传与隐私管理", "在网页资源中心完成", com.hanchen.veritycustomapi.resource.ResourceUploadClient::openWebsite, "打开");
        }

        // ===== 本地皮肤 =====
        section("本地皮肤");
        java.util.List<String> availableSkins = skinMgr.getAvailableSkins();
        if (availableSkins.isEmpty()) {
            info("本地皮肤", "还没有本地皮肤，请放入皮肤文件或从云端下载");
        }
        for (String name : availableSkins) {
            boolean isActive = name.equals(activeSkin);
            String label = name + (isActive ? " \u2714 当前使用" : "");
            action(label, "点击应用此皮肤", () -> {
                boolean ok = skinMgr.setSkin(name);
                if (ok) ModConfig.VERITY_SKIN_NAME.set(name);
                statusMessage = ok ? "皮肤已切换: " + name : "皮肤切换失败: " + name;
                statusMessageTimer = 100;
                refreshCurrentCategory();
            }, "应用");
        }
        action("刷新本地列表", "重新查找已安装的皮肤文件", this::refreshCurrentCategory, "刷新");
        action("恢复 Verity 默认皮肤", "清除自定义皮肤，使用原版外观", () -> {
            skinMgr.setSkin("Verity");
            ModConfig.VERITY_SKIN_NAME.set("Verity");
            statusMessage = "已恢复 Verity 默认皮肤";
            statusMessageTimer = 100;
            refreshCurrentCategory();
        }, "恢复");

        // ===== 原版个性化（直接调用原版实现）=====
        advancedSection("原版个性化");
        info("说明", "直接调用原版 Verity 的个性化设置，预览图会跟随色相变化");
        image("Verity 预览", 96);
        hueSlider("自定义颜色（色相）", OriginalVerityPersonalization.getHue());
        info("色相说明", "0 = 原始颜色，1-360 = 色相偏移；拖动时上方预览会实时变化");
        text("自定义名称", OriginalVerityPersonalization.getCustomName(), "Verity",
                OriginalVerityPersonalization::setCustomName);
        text("人格", OriginalVerityPersonalization.getPersonality(), "",
                OriginalVerityPersonalization::setPersonality);
    }

    private void awarenessCategory() {
        section("游戏事件");
        toggle("启用主动事件感知", ModConfig.ENABLE_PROACTIVE_AWARENESS::get, false, ModConfig.ENABLE_PROACTIVE_AWARENESS::set);
        if (ModConfig.ENABLE_PROACTIVE_AWARENESS.get()) {
            toggle("打开暂停菜单", ModConfig.PROACTIVE_PAUSE_EVENT::get, true, ModConfig.PROACTIVE_PAUSE_EVENT::set);
            toggle("玩家死亡", ModConfig.PROACTIVE_DEATH_EVENT::get, true, ModConfig.PROACTIVE_DEATH_EVENT::set);
            toggle("睡觉与醒来", ModConfig.PROACTIVE_SLEEP_EVENT::get, true, ModConfig.PROACTIVE_SLEEP_EVENT::set);
            toggle("进入与退出世界", ModConfig.PROACTIVE_WORLD_EVENT::get, true, ModConfig.PROACTIVE_WORLD_EVENT::set);

            section("血量提醒");
            toggle("血量低于阈值时提醒", ModConfig.PROACTIVE_LOW_HEALTH_EVENT::get, true,
                    ModConfig.PROACTIVE_LOW_HEALTH_EVENT::set);
            toggle("分级提醒（50% / 25% / 10%）", ModConfig.PROACTIVE_HEALTH_TIERS::get, true,
                    ModConfig.PROACTIVE_HEALTH_TIERS::set);
            info("分级说明", "开启后每档只提醒一次，血量回到 60% 以上才会重新提醒");
            info("关闭分级", "关闭后只在血量低于 25% 时提醒一次，回到 35% 以上重新武装");

            section("环境事件");
            toggle("昼夜交替", ModConfig.PROACTIVE_DAY_NIGHT_EVENT::get, false,
                    ModConfig.PROACTIVE_DAY_NIGHT_EVENT::set);
            toggle("天气变化", ModConfig.PROACTIVE_WEATHER_EVENT::get, false,
                    ModConfig.PROACTIVE_WEATHER_EVENT::set);
            toggle("维度切换", ModConfig.PROACTIVE_DIMENSION_EVENT::get, false,
                    ModConfig.PROACTIVE_DIMENSION_EVENT::set);

            section("状态事件");
            toggle("饥饿值偏低", ModConfig.PROACTIVE_HUNGER_EVENT::get, false,
                    ModConfig.PROACTIVE_HUNGER_EVENT::set);
            toggle("经验等级提升", ModConfig.PROACTIVE_LEVEL_EVENT::get, false,
                    ModConfig.PROACTIVE_LEVEL_EVENT::set);

            advancedSection("节奏控制");
            integer("全局回应冷却（秒）", ModConfig.PROACTIVE_COOLDOWN_SECONDS.get(), 300, 30, 3600,
                    ModConfig.PROACTIVE_COOLDOWN_SECONDS::set);
            info("冷却说明", "所有事件共用这个冷却，冷却期内不会重复打扰你");
            integer("战斗静默（秒）", ModConfig.PROACTIVE_COMBAT_SILENCE_SECONDS.get(), 15, 0, 120,
                    ModConfig.PROACTIVE_COMBAT_SILENCE_SECONDS::set);
            info("战斗静默", "你出手攻击或挨打后的这段时间内不打扰，填 0 表示关闭");
            toggle("定时闲聊", ModConfig.PROACTIVE_IDLE_CHAT::get, false, ModConfig.PROACTIVE_IDLE_CHAT::set);
            info("闲聊说明", "长时间没有事件时主动说一句，只在游戏进行中计时");
            if (ModConfig.PROACTIVE_IDLE_CHAT.get()) {
                integer("闲聊间隔（分钟）", ModConfig.PROACTIVE_IDLE_INTERVAL_MINUTES.get(), 20, 5, 180,
                        ModConfig.PROACTIVE_IDLE_INTERVAL_MINUTES::set);
            }
            toggle("高优先级事件排队补发", ModConfig.PROACTIVE_DEFER_EVENTS::get, true,
                    ModConfig.PROACTIVE_DEFER_EVENTS::set);
            info("排队说明", "上一句还没说完时，更重要的事件（如死亡）会等结束后补发，而不是直接丢掉");

            if (!RuntimePlatform.isAndroidRuntime()) {
                advancedSection("通知");
                toggle("桌面通知", ModConfig.PROACTIVE_DESKTOP_NOTIFICATIONS::get, false,
                        ModConfig.PROACTIVE_DESKTOP_NOTIFICATIONS::set);
            }
        }
    }

    /** 摄像头视觉。全局可用：快捷键、普通对话、主动事件（需要视觉模型）。 */
    private void cameraVisionSection() {
        section("总开关");
        toggle("启用视觉能力", ModConfig.CAMERA_ENABLED::get, false, ModConfig.CAMERA_ENABLED::set);
        if (!ModConfig.CAMERA_ENABLED.get()) {
            info("说明", "开启后 AI 可在需要时主动采集画面看你，无需按键；也可按 O 键立即看一眼");
            return;
        }
        info("快捷键", "游戏内按 O 键（可在「选项 → 控制」里改键）可立即让 Verity 看一眼当前画面");
        info("所需条件", "对话服务需要支持看图（多模态）模型，例如 gpt-4o / qwen-vl / glm-4v 等");

        section("画面来源");
        choice("采集来源", ModConfig.CAMERA_SOURCE.get(), "camera",
                new String[]{"摄像头 + 游戏画面", "只看真实摄像头", "只看游戏内画面"},
                new String[]{"both", "camera", "screen"},
                ModConfig.CAMERA_SOURCE::set);
        info("说明", "游戏画面=当前屏幕截图（含 HUD 和打开的界面），让 AI 能看到你在游戏里的场景");
        boolean useCamera = ConfigManager.isCameraSourceCamera();
        boolean useScreen = ConfigManager.isCameraSourceScreen();

        if (useCamera) {
            section("摄像头采集后端");
            String backend = ModConfig.CAMERA_BACKEND.get();
            action("切换采集方式（当前：" + backendLabel(backend) + "）",
                    "自动=依次尝试；Windows 内置=免安装；ffmpeg=需自行下载；HTTP=网络快照地址",
                    () -> {
                        String next = switch (backend) {
                            case "auto" -> "powershell";
                            case "powershell" -> "ffmpeg";
                            case "ffmpeg" -> "http";
                            default -> "auto";
                        };
                        ModConfig.CAMERA_BACKEND.set(next);
                        statusMessage = "摄像头后端已切换为 " + backendLabel(next);
                        statusMessageTimer = 60;
                        refreshCurrentCategory();
                    }, "切换");
            if ("ffmpeg".equals(backend) || "auto".equals(backend)) {
                text("ffmpeg 路径（留空用 PATH）", ModConfig.CAMERA_FFMPEG_PATH.get(), "", ModConfig.CAMERA_FFMPEG_PATH::set);
                text("DirectShow 设备名（留空自动枚举）", ModConfig.CAMERA_DEVICE_NAME.get(), "", ModConfig.CAMERA_DEVICE_NAME::set);
            }
            if ("http".equals(backend) || "auto".equals(backend)) {
                text("HTTP 快照地址", ModConfig.CAMERA_HTTP_URL.get(), "", ModConfig.CAMERA_HTTP_URL::set);
            }
        }

        advancedSection("画面参数");
        integer("宽度（像素）", ModConfig.CAMERA_WIDTH.get(), 640, 160, 1920, ModConfig.CAMERA_WIDTH::set);
        if (useScreen) {
            info("游戏画面尺寸", "截屏按屏幕真实比例缩放：宽 " + ModConfig.CAMERA_WIDTH.get()
                    + "，高按比例自动计算（16:9 屏幕约 " + (ModConfig.CAMERA_WIDTH.get() * 9 / 16) + "）");
        }
        if (useCamera) {
            integer("摄像头高度（像素）", ModConfig.CAMERA_HEIGHT.get(), 480, 120, 1080, ModConfig.CAMERA_HEIGHT::set);
        }
        integer("JPEG 质量（1-100）", ModConfig.CAMERA_JPEG_QUALITY.get(), 70, 10, 100, ModConfig.CAMERA_JPEG_QUALITY::set);
        integer("采集超时（毫秒）", ModConfig.CAMERA_CAPTURE_TIMEOUT_MS.get(), 6000, 1000, 30000, ModConfig.CAMERA_CAPTURE_TIMEOUT_MS::set);

        section("按需查看");
        info("采集时机", "画面不会随每条对话自动附带，只有 AI 判断需要时才会调用");
        toggle("允许 AI 主动调用视觉", ModConfig.CAMERA_SKILL_ENABLED::get, true, ModConfig.CAMERA_SKILL_ENABLED::set);
        info("说明", "开启后会把视觉能力写进人设；AI 需要时会在回复里请求一张新画面");
        toggle("主动事件附带画面", ModConfig.CAMERA_SEND_ON_PROACTIVE_EVENT::get, false, ModConfig.CAMERA_SEND_ON_PROACTIVE_EVENT::set);

        advancedSection("视觉模型");
        toggle("允许模型描述画面", ModConfig.CAMERA_ALLOW_MODEL_TO_SEE::get, true, ModConfig.CAMERA_ALLOW_MODEL_TO_SEE::set);
        text("视觉模型名（留空用当前对话服务）", ModConfig.CAMERA_VISION_MODEL.get(), "", ModConfig.CAMERA_VISION_MODEL::set);

        section("测试");
        action("测试采集", "按当前来源立刻采集一次并检查返回结果，用于确认是否可用",
                () -> startCameraTest(false), CAMERA_TEST_RUNNING.get() ? "测试中" : "开始测试");
        if (useScreen) {
            action("单独测试游戏画面", "只截取一次游戏内画面，确认能正常取到屏幕内容",
                    () -> startCameraTest(true), CAMERA_TEST_RUNNING.get() ? "测试中" : "开始测试");
        }
        if (cameraTestResult != null) {
            info("上次测试结果", cameraTestResult);
        }
    }

    private static String backendLabel(String backend) {
        return switch (backend == null ? "auto" : backend) {
            case "powershell" -> "Windows 内置";
            case "ffmpeg" -> "ffmpeg";
            case "http" -> "HTTP 快照";
            default -> "自动";
        };
    }

    private void aiBuildingCategory() {
        section("功能开关");
        toggle("启用 AI 建造", ModConfig.ENABLE_AI_BUILDING::get, false, ModConfig.ENABLE_AI_BUILDING::set);
        if (ModConfig.ENABLE_AI_BUILDING.get()) {
            toggle("让 Verity 亲自施工", ModConfig.AI_BUILDING_VERITY_BUILDER_MODE::get, false,
                    ModConfig.AI_BUILDING_VERITY_BUILDER_MODE::set);
            toggle("允许覆盖已有方块", ModConfig.AI_BUILDING_OVERWRITE_BLOCKS::get, true,
                    ModConfig.AI_BUILDING_OVERWRITE_BLOCKS::set);
            toggle("施工前显示轮廓预览", ModConfig.AI_BUILDING_GHOST_PREVIEW::get, false,
                    ModConfig.AI_BUILDING_GHOST_PREVIEW::set);
            integer("预览时长（游戏刻）", ModConfig.AI_BUILDING_GHOST_PREVIEW_TICKS.get(), 30, 0, 600,
                    ModConfig.AI_BUILDING_GHOST_PREVIEW_TICKS::set);
            toggle("顶部进度条", ModConfig.AI_BUILDING_BOSS_BAR_PROGRESS::get, true,
                    ModConfig.AI_BUILDING_BOSS_BAR_PROGRESS::set);
            toggle("显示粒子效果", ModConfig.AI_BUILDING_PARTICLES::get, true,
                    ModConfig.AI_BUILDING_PARTICLES::set);
            toggle("播放音效", ModConfig.AI_BUILDING_SOUND::get, true,
                    ModConfig.AI_BUILDING_SOUND::set);
            toggle("退出后保留建造进度", ModConfig.AI_BUILDING_PERSIST::get, false,
                    ModConfig.AI_BUILDING_PERSIST::set);
            toggle("允许在多人服务器使用", ModConfig.AI_BUILDING_ALLOW_MULTIPLAYER::get, false,
                    ModConfig.AI_BUILDING_ALLOW_MULTIPLAYER::set);
            advancedSection("建造限制");
            integer("施工速度（每刻方块数）", ModConfig.AI_BUILDING_BLOCKS_PER_TICK.get(), 2, 1, 32,
                    ModConfig.AI_BUILDING_BLOCKS_PER_TICK::set);
            integer("最大建筑边长", ModConfig.AI_BUILDING_MAX_SIZE.get(), 64, 3, 64,
                    ModConfig.AI_BUILDING_MAX_SIZE::set);
            integer("方块数量上限", ModConfig.AI_BUILDING_MAX_BLOCKS.get(), 32768, 64, 32768,
                    ModConfig.AI_BUILDING_MAX_BLOCKS::set);
            advancedSection("蓝图库与指令");
            info("蓝图目录", String.valueOf(ModConfig.AI_BUILDING_BLUEPRINT_DIR.get()));
            info("/verity-build save <名字>", "把当前建造任务保存为本地蓝图");
            info("/verity-build load <名字>", "用本地蓝图开始建造");
            info("/verity-build list", "列出全部蓝图");
            info("/verity-build undo", "撤销上一次完成的建造");
        }
    }

    private void debugCategory() {
        section("游戏日志");
        info("日志位置", "游戏目录/logs/latest.log");
        action("打开 latest.log", "用默认程序打开当前日志",
                () -> openLogTarget(false), "打开");
        action("打开日志目录", "在文件管理器中打开 logs 目录",
                () -> openLogTarget(true), "打开");
        section("运行状态记录");
        toggle("记录性能指标", ModConfig.DEBUG_RUNTIME_METRICS::get, false, ModConfig.DEBUG_RUNTIME_METRICS::set);
        if (ModConfig.DEBUG_RUNTIME_METRICS.get()) {
            integer("记录间隔（秒）", ModConfig.DEBUG_RUNTIME_METRICS_INTERVAL_SECONDS.get(), 60, 5, 3600,
                    ModConfig.DEBUG_RUNTIME_METRICS_INTERVAL_SECONDS::set);
        }
    }

    private void openLogTarget(boolean directory) {
        ExternalFileOpener.OpenResult result = directory
                ? ExternalFileOpener.openLogsDirectory() : ExternalFileOpener.openLatestLog();
        statusMessage = result.message();
        statusMessageTimer = result.success() ? 120 : 240;
        if (!result.success() && minecraft != null) {
            minecraft.keyboardHandler.setClipboard(result.target().toString());
            statusMessage += "，路径已复制";
        }
    }

    // ==================== Builder Helpers ====================

    private void category(String title, String description, String sidebarTitle) {
        categories.add(new Category(title, description, sidebarTitle));
    }

    private Category currentCategory() {
        if (categories.isEmpty()) {
            category("配置", "Verity 扩展增强配置", "配置");
        }
        return categories.get(categories.size() - 1);
    }

    private void section(String title) {
        Category category = currentCategory();
        category.rows.add(new SectionRow(title, "高级与诊断".equals(category.title)));
    }

    /** 高级参数与说明型分组默认收起，用户仍可点击标题展开。 */
    private void advancedSection(String title) {
        currentCategory().rows.add(new SectionRow(title, true));
    }

    /**
     * 列表项：**强制常驻显示**，不受「短状态 / 长说明」的判据影响。
     *
     * <p>存在的理由：项目列表、皮肤列表这类内容是**一组数据**，
     * 每条的 value 都是长描述——如果走 {@link #info} 的判据就会被当成说明收进浮层，
     * 整个列表直接消失。这类调用必须显式声明「我要常驻」。
     */
    private void listItem(String label, String value) {
        currentCategory().rows.add(new InfoRow(label, value));
    }

    /**
     * 添加一条信息。**短的当状态常驻显示，长的当说明收进悬停浮层。**
     *
     * <p>概览里的「语音朗读 → 已配置」「剩余额度 → 98951」这类是**状态**，
     * 就是要一眼看到的，藏进浮层反而要玩家去猜；
     * 而「间隔说明 → 两次自动记忆之间的最短间隔…」这种长句是**解释**，平时没人看。
     *
     * <p>⚠️ 列表数据请用 {@link #listItem}，不要用本方法——判据分不清「长描述的列表项」
     * 和「说明文字」，会把整个列表收走。
     */
    private void info(String label, String value) {
        if (isStatusValue(value)) {
            currentCategory().rows.add(new InfoRow(label, value));
            return;
        }
        List<Row> rows = currentCategory().rows;
        for (int i = rows.size() - 1; i >= 0; i--) {
            Row row = rows.get(i);
            // 🔴 遇到分组头必须 **break**，不能 continue：
            // 说明只应挂在本组的配置行上。用 continue 会一路往前跨组找，
            // 把整页的说明全吸到第一个按钮上，各分组头下面反而全空（踩过）。
            if (row instanceof SectionRow) break;
            // 已有的说明行不是挂靠目标，跳过继续找本组的配置行
            if (row instanceof InfoRow) continue;
            row.hint = row.hint == null ? value : row.hint + NEWLINE + value;
            row.hintTitle = row.hintTitle == null ? label : row.hintTitle + " / " + label;
            return;
        }
        // 本组里没有可挂靠的配置行（分组头下面直接就是说明）→ 常驻显示
        rows.add(new InfoRow(label, value));
    }

    /**
     * 判断这个值算「状态」还是「说明」。
     *
     * <p>三类算状态、直接常驻显示：
     * <ol>
     *   <li>极短的状态词：「已配置」「已同步」「已关闭」「同步中…」；</li>
     *   <li>数值：「98951」「1.0」；</li>
     *   <li>命令帮助：以 {@code /} 开头，本来就是给人照着敲的清单。</li>
     * </ol>
     *
     * <p>其余都是说明句，收进浮层。
     * 阈值取 10 是照现有文案定的：状态词最长不过「在线识别」这种 4~5 字，
     * 而最短的说明句「授权成功后会立即同步并应用云端配置」也有 18 字——
     * 之前用 24 会把这类短说明误判成状态。
     */
    private static boolean isStatusValue(String value) {
        if (value == null) return false;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return false;
        // 先排除带标点的句子：状态词与命令清单都不会有句号分号，
        // 而「关闭后 → /v! 会被服务器拒绝；/v 私聊仍然可用」这种说明会。
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '，' || c == '。' || c == '；' || c == '：'
                    || c == ',' || c == '.' || c == ';' || c == ':') {
                return false;
            }
        }
        // 命令清单本身就是给人照着敲的，长度不限也常驻
        if (trimmed.charAt(0) == '/') return true;
        return trimmed.length() <= 10;
    }

    private void image(String label, int imageSize) {
        currentCategory().rows.add(new ImageRow(label, imageSize));
    }

    private void hueSlider(String label, int value) {
        HueSliderRow row = new HueSliderRow(label, value);
        currentCategory().rows.add(row);
        values.add(row);
    }

    private void warning(String label, String value, int color) {
        currentCategory().rows.add(new WarningRow(label, value, color));
    }

    /**
     * 「云端已启用」的覆盖提示：只在云端已启用、且玩家确实填过这一组本地配置时才提示；
     * 本地一直为空时这些设置本来就来自云端，不必再提示。
     *
     * @param fields      这一组设置的文案
     * @param localValues 该组对应的本地配置项；全为空则不提示
     */
    private void cloudManagedWarning(String fields, String... localValues) {
        if (!ModConfig.CLOUD_ENABLE.get()) return;
        boolean localConfigured = false;
        for (String value : localValues) {
            if (value != null && !value.isBlank()) {
                localConfigured = true;
                break;
            }
        }
        if (!localConfigured) return;
        warning("云端已启用", fields + "会优先使用云端下发的值；在这里修改只会保存为本地备用配置", WARNING);
    }

    private void action(String label, String hint, Runnable action) {
        action(label, hint, action, "执行");
    }

    private void action(String label, String hint, Runnable action, String buttonText) {
        currentCategory().rows.add(new ActionRow(label, hint, action, buttonText));
    }

    private void text(String label, String value, Consumer<String> saver) {
        text(label, value, value, saver);
    }

    private void text(String label, String value, String defaultValue, Consumer<String> saver) {
        TextRow row = new TextRow(label, value, defaultValue, saver);
        currentCategory().rows.add(row);
        values.add(row);
    }

    private void integer(String label, int value, int min, int max, IntConsumer saver) {
        integer(label, value, value, min, max, saver);
    }

    private void integer(String label, int value, int defaultValue, int min, int max, IntConsumer saver) {
        IntRow row = new IntRow(label, value, defaultValue, min, max, saver);
        currentCategory().rows.add(row);
        values.add(row);
    }

    private void decimal(String label, double value, double defaultValue, double min, double max, DoubleConsumer saver) {
        DoubleRow row = new DoubleRow(label, value, defaultValue, min, max, saver);
        currentCategory().rows.add(row);
        values.add(row);
    }

    private void toggle(String label, BooleanSupplier getter, Consumer<Boolean> saver) {
        toggle(label, getter, getter.getAsBoolean(), saver);
    }

    private void toggle(String label, BooleanSupplier getter, boolean defaultValue, Consumer<Boolean> saver) {
        ToggleRow row = new ToggleRow(label, getter, defaultValue, saver);
        currentCategory().rows.add(row);
        values.add(row);
    }

    private void choice(String label, String value, String[] labels, String[] storedValues, Consumer<String> saver) {
        choice(label, value, value, labels, storedValues, saver);
    }

    private void choice(String label, String value, String defaultValue, String[] labels, String[] storedValues, Consumer<String> saver) {
        ChoiceRow row = new ChoiceRow(label, value, defaultValue, labels, storedValues, saver);
        currentCategory().rows.add(row);
        values.add(row);
    }

    // ==================== Utility ====================

    private static String describeRecognitionMode(String value) {
        return "local".equalsIgnoreCase(value) ? "本地识别" : "在线识别";
    }

    private static String describePlatform() {
        if (RuntimePlatform.isAndroidRuntime()) return "Android / FCL";
        return "PC (Desktop)";
    }

    private static String describeLocalTtsModel() {
        String path = ModConfig.TTS_MODEL_PATH.get();
        if (path == null || path.isBlank()) return "路径未配置";
        try {
            java.nio.file.Path modelPath = Paths.get(path);
            if (!java.nio.file.Files.isDirectory(modelPath)) return "目录不存在";
            try (var stream = java.nio.file.Files.list(modelPath)) {
                long count = stream.limit(10).count();
                return count > 0 ? "已检测到模型文件" : "目录为空";
            }
        } catch (Exception e) {
            return "路径无效";
        }
    }

    private static String getTtsProviderDisplay() {
        try {
            return TtsApiProviderEnum.valueOf(ModConfig.TTS_API_PROVIDER.get()).displayName;
        } catch (Exception e) {
            return ModConfig.TTS_API_PROVIDER.get();
        }
    }

    private static String getLlmProviderDisplay() {
        try {
            return ApiProviderEnum.valueOf(ModConfig.LLM_API_PROVIDER.get()).displayName;
        } catch (Exception e) {
            return ModConfig.LLM_API_PROVIDER.get();
        }
    }

    private static String maskKey(String key) {
        if (key == null || key.isBlank()) return "";
        if (key.length() <= 8) return "*".repeat(key.length());
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    private static ConfigManager.PromptPreset getCurrentPreset() {
        String selectedName = ModConfig.SELECTED_PRESET.get();
        for (ConfigManager.PromptPreset preset : ConfigManager.PromptPreset.values()) {
            if (preset.name().equals(selectedName) || preset.displayName.equals(selectedName)) {
                return preset;
            }
        }
        return ConfigManager.PromptPreset.ORIGINAL_VERITY;
    }

    private static String[] promptNames() {
        ConfigManager.PromptPreset[] presets = ConfigManager.PromptPreset.values();
        String[] result = new String[presets.length];
        for (int i = 0; i < presets.length; i++) result[i] = presets[i].displayName;
        return result;
    }

    private static String[] promptValues() {
        ConfigManager.PromptPreset[] presets = ConfigManager.PromptPreset.values();
        String[] result = new String[presets.length];
        for (int i = 0; i < presets.length; i++) result[i] = presets[i].name();
        return result;
    }

    /** 一句话说明当前实际会注入什么人设（注入来源 + 实际字符数，0 字符表示不会注入）。 */
    private static String describeEffectivePersona() {
        if (!ModConfig.ENABLE_CUSTOM_PROMPT.get()) {
            return "未启用，使用原版 Verity 提示词";
        }
        ConfigManager.PromptPreset preset = getCurrentPreset();
        String label;
        String persona = ConfigManager.getCustomPersona();
        if (persona != null && !persona.isBlank()) {
            label = "自定义人设（覆盖预设）";
        } else if (preset == ConfigManager.PromptPreset.CUSTOM_FILE) {
            String file = ModConfig.CUSTOM_PROMPT_FILE.get();
            label = "外部文件 " + (file == null || file.isBlank() ? "（未填写路径）" : file);
        } else if (preset == ConfigManager.PromptPreset.ORIGINAL_VERITY) {
            label = "原版人设";
        } else {
            label = preset.displayName;
        }
        String effective = ConfigManager.getCustomPersonaPrompt();
        int length = effective == null ? 0 : effective.strip().length();
        return length == 0
                ? label + " —— 不会注入任何内容（等于用原版提示词）"
                : label + " —— 实际注入 " + length + " 字符";
    }

    /** 是否连着别人的服务器（配置作用域提示用）。 */
    private static boolean isRemoteServerSession() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.getCurrentServer() != null && !mc.hasSingleplayerServer();
    }

    private static String[] names(Enum<?>[] enums) {
        String[] result = new String[enums.length];
        for (int i = 0; i < enums.length; i++) result[i] = enums[i].toString();
        return result;
    }

    private static String[] values(Enum<?>[] enums) {
        String[] result = new String[enums.length];
        for (int i = 0; i < enums.length; i++) result[i] = enums[i].name();
        return result;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String fitText(net.minecraft.client.gui.Font font, String value, int maxWidth) {
        if (value == null || value.isBlank()) return "";
        String singleLine = value.replace('\n', ' ').replace('\r', ' ');
        if (font.width(singleLine) <= maxWidth) return singleLine;
        String ellipsis = "...";
        int ellipsisWidth = font.width(ellipsis);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < singleLine.length(); i++) {
            String next = builder + String.valueOf(singleLine.charAt(i));
            if (font.width(next) + ellipsisWidth > maxWidth) break;
            builder.append(singleLine.charAt(i));
        }
        return builder + ellipsis;
    }

    // ==================== Data Classes ====================

    private static class Category {
        final String title;
        final String description;
        final String sidebarTitle;
        final List<Row> rows = new ArrayList<>();

        Category(String title, String description, String sidebarTitle) {
            this.title = title;
            this.description = description;
            this.sidebarTitle = sidebarTitle;
        }
    }

    private interface ValueRow {
        void save();
        void reset();
    }

    private abstract class Row {
        /**
         * 悬停本行时显示的说明文字（原 InfoRow 的内容）。
         *
         * <p>说明原来各占一整行 28px，95 条说明就是 2660px 的滚动高度——
         * 绝大多数时候没人看，却天天占着地方。改成挂在相邻行上、鼠标移上去才浮出来。
         */
        String hint;
        /** 说明的标题（多个说明挂同一行时用「 / 」拼接）。 */
        String hintTitle;

        void cache() {}
        int getHeight() {
            return ROW_HEIGHT;
        }
        abstract void addWidgets(int left, int top, int rowWidth);
        abstract void render(GuiGraphics graphics, int left, int top, int rowWidth);
    }

    // ==================== Row Implementations ====================

    private class SectionRow extends Row {
        private final String title;
        private final boolean defaultCollapsed;
        private final boolean collapsible = true;

        SectionRow(String title, boolean defaultCollapsed) {
            this.title = title;
            this.defaultCollapsed = defaultCollapsed;
        }

        boolean isCollapsed(String categoryTitle) {
            return collapsedSections.getOrDefault(sectionKey(categoryTitle, title), defaultCollapsed);
        }

        void toggle(String categoryTitle) {
            String key = sectionKey(categoryTitle, title);
            collapsedSections.put(key, !isCollapsed(categoryTitle));
        }

        @Override
        void addWidgets(int left, int top, int rowWidth) {}

        @Override
        void render(GuiGraphics graphics, int left, int top, int rowWidth) {
            if (!isRowVisible(top, getHeight())) return;
            // 分组头 = 抬升底色块 + 左侧强调条 + 顶部 1px 强调线。
            // 三层叠加而不是只靠底色：半透明卡片上单靠底色差容易糊成一片，
            // 一条细线能明确划出「这一组从这里开始」。
            int blockTop = top + 2;
            int blockBottom = top + getHeight() - 3;
            if (blockBottom <= blockTop) return;
            VerityUiDraw.fillRounded(graphics, left, blockTop, left + rowWidth, blockBottom,
                    3, VerityUiTheme.CARD_RAISED);
            VerityUiDraw.fillRounded(graphics, left, blockTop, left + 3, blockBottom, 2, ACCENT);
            graphics.fill(left + 3, blockTop, left + rowWidth, blockTop + 1, VerityUiTheme.ACCENT_SOFT);
            boolean collapsed = !categories.isEmpty() && isCollapsed(categories.get(categoryIndex).title);
            graphics.drawString(font, collapsed ? "▸" : "▾", left + 8, top + 14, SECTION_TITLE, false);
            graphics.drawString(font, fitText(font, title, Math.max(36, rowWidth - 26)),
                    left + 20, top + 14, SECTION_TITLE, false);
        }
    }

    /** 原版预览图行：直接绘制原版 Verity 的动态预览纹理。 */
    private class ImageRow extends Row {
        private final String label;
        private final int imageSize;

        ImageRow(String label, int imageSize) {
            this.label = label;
            this.imageSize = imageSize;
        }

        @Override
        int getHeight() {
            return imageSize + 22;
        }

        @Override
        void addWidgets(int left, int top, int rowWidth) {
        }

        @Override
        void render(GuiGraphics graphics, int left, int top, int rowWidth) {
            if (!isRowVisible(top, getHeight())) return;
            graphics.drawString(font, fitText(font, label, rowWidth - 16), left + 8, top + 4, MUTED);
            net.minecraft.resources.ResourceLocation texture =
                    OriginalVerityPersonalization.previewTextureId();
            int size = Math.max(32, Math.min(imageSize, rowWidth - 16));
            int x = left + (rowWidth - size) / 2;
            int y = top + 16;
            if (texture == null) {
                graphics.drawString(font, "原版预览不可用（未检测到原版 Verity）", x, y, DANGER);
                return;
            }
            graphics.blit(texture, x, y, 0, 0, size, size, size, size);
        }
    }

    private class InfoRow extends Row {
        protected final String label;
        protected final String value;

        InfoRow(String label, String value) {
            this.label = label;
            this.value = value == null ? "" : value;
        }

        @Override
        int getHeight() {
            return isUltraCompactLayout() ? 42 : ROW_HEIGHT;
        }

        @Override
        void addWidgets(int left, int top, int rowWidth) {}

        @Override
        void render(GuiGraphics graphics, int left, int top, int rowWidth) {
            if (!isRowVisible(top, getHeight())) return;
            renderInfo(graphics, left, top, rowWidth, TEXT, MUTED);
        }

        protected void renderInfo(GuiGraphics graphics, int left, int top, int rowWidth, int labelColor, int valueColor) {
            if (isUltraCompactLayout()) {
                graphics.drawString(font, fitText(font, label, rowWidth - 16), left + 8, top + 5, labelColor);
                graphics.drawString(font, fitText(font, value, rowWidth - 16), left + 8, top + 22, valueColor);
                return;
            }
            int labelWidth = getLabelWidth(rowWidth);
            graphics.drawString(font, fitText(font, label, labelWidth), left + 8, top + 7, labelColor);
            int valueLeft = left + labelWidth + 18;
            int valueWidth = rowWidth - labelWidth - 28;
            if (valueWidth > 36) {
                graphics.drawString(font, fitText(font, value, valueWidth), valueLeft, top + 7, valueColor);
            }
        }
    }

    private class WarningRow extends InfoRow {
        private final int color;

        WarningRow(String label, String value, int color) {
            super(label, value);
            this.color = color;
        }

        @Override
        void render(GuiGraphics graphics, int left, int top, int rowWidth) {
            if (!isRowVisible(top, getHeight())) return;
            renderInfo(graphics, left, top, rowWidth, color, color);
        }
    }

    private class ActionRow extends Row {
        private final String label;
        /** 按钮说明。**不能叫 hint**——基类 `Row.hint` 是悬停浮层用的字段，
         *  同名会构成字段隐藏：子类读自己那份、基类读父类那份，两边容易串。 */
        private final String description;
        private final Runnable action;
        private final String buttonText;

        ActionRow(String label, String description, Runnable action, String buttonText) {
            this.label = label;
            this.description = description;
            this.action = action;
            this.buttonText = buttonText;
        }

        @Override
        int getHeight() {
            return isUltraCompactLayout() ? 44 : ROW_HEIGHT;
        }

        @Override
        void addWidgets(int left, int top, int rowWidth) {
            if (!isRowVisible(top, getHeight())) return;
            int buttonWidth = font.width(buttonText) + 16;
            buttonWidth = Math.max(48, Math.min(buttonWidth, rowWidth / 3));
            addRenderableWidget(roundedButton(left + rowWidth - buttonWidth - 6,
                    top + (isUltraCompactLayout() ? 22 : 2), buttonWidth, 18,
                    Component.literal(buttonText),
                    VerityUiTheme.BUTTON, VerityUiTheme.ACCENT,
                    button -> action.run()));
        }

        @Override
        void render(GuiGraphics graphics, int left, int top, int rowWidth) {
            if (!isRowVisible(top, getHeight())) return;
            int buttonWidth = font.width(buttonText) + 16;
            buttonWidth = Math.max(48, Math.min(buttonWidth, rowWidth / 3));
            if (isUltraCompactLayout()) {
                graphics.drawString(font, fitText(font, label, rowWidth - 16), left + 8, top + 5, TEXT);
                graphics.drawString(font, fitText(font, description, rowWidth - buttonWidth - 20), left + 8, top + 27, MUTED);
                return;
            }
            int labelWidth = getLabelWidth(rowWidth);
            graphics.drawString(font, fitText(font, label, labelWidth), left + 8, top + 7, TEXT);
            int hintLeft = left + labelWidth + 18;
            int hintWidth = rowWidth - labelWidth - buttonWidth - 36;
            if (hintWidth > 36) {
                graphics.drawString(font, fitText(font, description, hintWidth), hintLeft, top + 7, MUTED);
            }
        }
    }

    private class TextRow extends Row implements ValueRow {
        private final String label;
        private final Consumer<String> saver;
        private final String defaultValue;
        protected String value;
        protected EditBox box;

        TextRow(String label, String value, String defaultValue, Consumer<String> saver) {
            this.label = label;
            this.saver = saver;
            this.defaultValue = defaultValue == null ? "" : defaultValue;
            this.value = value == null ? "" : value;
        }

        @Override
        int getHeight() {
            return isUltraCompactLayout() ? 50 : ROW_HEIGHT;
        }

        @Override
        void cache() {
            if (box != null) value = box.getValue();
        }

        @Override
        void addWidgets(int left, int top, int rowWidth) {
            if (!isRowVisible(top, getHeight())) return;
            int controlLeft = getControlLeft(left, rowWidth);
            int controlWidth = getControlWidth(rowWidth);
            // 关掉原版边框，自己画圆角底——原版 EditBox 是直角深灰框，和圆角卡片不搭
            box = new EditBox(font, controlLeft, top + (isUltraCompactLayout() ? 25 : 3),
                    controlWidth, 17, Component.literal(label)) {
                @Override
                public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
                    VerityUiDraw.fillRounded(graphics, getX(), getY(),
                            getX() + this.width, getY() + this.height, 4,
                            VerityUiTheme.CONTROL_FIELD);
                    VerityUiDraw.strokeRounded(graphics, getX(), getY(),
                            getX() + this.width, getY() + this.height, 4,
                            isFocused() ? VerityUiTheme.ACCENT : VerityUiTheme.CONTROL_BORDER);
                    // 🔴 setBordered(false) 后原版把文字画在 (getX(), getY())——零内边距，
                    // 数字会贴着框左上角、看起来像溢出到框外。用 pose 把文字（含光标）
                    // 整体右下平移，做出内边距；不改 EditBox 尺寸，点击与光标逻辑不受影响。
                    graphics.pose().pushPose();
                    graphics.pose().translate(5.0F, 4.0F, 0.0F);
                    super.renderWidget(graphics, mouseX, mouseY, partialTick);
                    graphics.pose().popPose();
                }
            };
            box.setBordered(false);
            box.setMaxLength(4096);
            box.setValue(value);
            addRenderableWidget(box);
        }

        @Override
        void render(GuiGraphics graphics, int left, int top, int rowWidth) {
            if (!isRowVisible(top, getHeight())) return;
            if (isUltraCompactLayout()) {
                graphics.drawString(font, fitText(font, label, rowWidth - 16), left + 8, top + 7, TEXT);
                return;
            }
            int labelWidth = Math.max(36, getControlLeft(left, rowWidth) - left - 16);
            graphics.drawString(font, fitText(font, label, labelWidth), left + 8, top + 7, TEXT);
        }

        @Override
        public void save() {
            cache();
            saver.accept(value);
        }

        @Override
        public void reset() {
            value = defaultValue;
            if (box != null) box.setValue(defaultValue);
        }
    }

    private class IntRow extends TextRow {
        private final int min, max, defaultNumber;
        private final IntConsumer saver;

        IntRow(String label, int value, int defaultValue, int min, int max, IntConsumer saver) {
            super(label, String.valueOf(value), String.valueOf(defaultValue), ignored -> {});
            this.min = min;
            this.max = max;
            this.defaultNumber = defaultValue;
            this.saver = saver;
        }

        @Override
        public void save() {
            cache();
            try {
                int parsed = Integer.parseInt(value.trim());
                saver.accept(clamp(parsed, min, max));
            } catch (NumberFormatException ignored) {}
        }

        @Override
        public void reset() {
            value = String.valueOf(defaultNumber);
            if (box != null) box.setValue(value);
        }
    }

    private class DoubleRow extends TextRow {
        private final double min, max, defaultNumber;
        private final DoubleConsumer saver;

        DoubleRow(String label, double value, double defaultValue, double min, double max, DoubleConsumer saver) {
            super(label, String.valueOf(value), String.valueOf(defaultValue), ignored -> {});
            this.min = min;
            this.max = max;
            this.defaultNumber = defaultValue;
            this.saver = saver;
        }

        @Override
        public void save() {
            cache();
            try {
                double parsed = Double.parseDouble(value.trim());
                if (Double.isFinite(parsed)) saver.accept(Math.max(min, Math.min(max, parsed)));
            } catch (NumberFormatException ignored) {}
        }

        @Override
        public void reset() {
            value = String.valueOf(defaultNumber);
            if (box != null) box.setValue(value);
        }
    }

    private class ToggleRow extends Row implements ValueRow {
        private final String label;
        private final Consumer<Boolean> saver;
        private final boolean defaultValue;
        private boolean selected;
        private Checkbox checkbox;

        ToggleRow(String label, BooleanSupplier getter, boolean defaultValue, Consumer<Boolean> saver) {
            this.label = label;
            this.saver = saver;
            this.defaultValue = defaultValue;
            this.selected = getter.getAsBoolean();
        }

        @Override
        void cache() {
            if (checkbox != null) selected = checkbox.selected();
        }

        @Override
        void addWidgets(int left, int top, int rowWidth) {
            if (!isRowVisible(top, getHeight())) return;
            // 自绘药丸开关：原版 Checkbox 是浅色描边的方块，压在深色卡片上像贴了张便利贴。
            // 这里只借它的点击与焦点逻辑，外观整个换掉。
            final int pillWidth = 30;
            final int pillHeight = 14;
            checkbox = new Checkbox(left + 6, top + 3, pillWidth, pillHeight,
                    Component.empty(), selected) {
                @Override
                public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
                    boolean hovered = mouseX >= getX() && mouseX < getX() + width
                            && mouseY >= getY() && mouseY < getY() + height;
                    VerityUiDraw.switchPill(graphics, getX(), getY(), width, height,
                            this.selected(), hovered);
                }

                @Override
                public void onPress() {
                    super.onPress();
                    // 开关立即写回配置并重建界面：依赖它的行（如云端提示、模式分支）当场跟着变，
                    // 不用再保存并重新打开配置页。
                    selected = this.selected();
                    saver.accept(selected);
                    requestRebuild();
                }
            };
            addRenderableWidget(checkbox);
        }

        @Override
        void render(GuiGraphics graphics, int left, int top, int rowWidth) {
            if (!isRowVisible(top, getHeight())) return;
            int labelWidth = Math.max(36, rowWidth - 44);
            graphics.drawString(font, fitText(font, label, labelWidth), left + 42, top + 7, TEXT);
        }

        @Override
        public void save() {
            cache();
            saver.accept(selected);
        }

        @Override
        public void reset() {
            selected = defaultValue;
        }
    }

    private class ChoiceRow extends Row implements ValueRow {
        private final String label;
        private final String[] labels;
        private final String[] storedValues;
        private final Consumer<String> saver;
        private final int defaultIndex;
        private int selectedIndex;

        ChoiceRow(String label, String value, String defaultValue, String[] labels, String[] storedValues, Consumer<String> saver) {
            if (labels.length == 0 || labels.length != storedValues.length) {
                throw new IllegalArgumentException("选项标签和值数量不一致");
            }
            this.label = label;
            this.labels = labels;
            this.storedValues = storedValues;
            this.saver = saver;
            this.defaultIndex = findIndex(defaultValue, storedValues);
            this.selectedIndex = findIndex(value, storedValues, defaultIndex);
        }

        @Override
        int getHeight() {
            return isUltraCompactLayout() ? 50 : ROW_HEIGHT;
        }

        @Override
        void addWidgets(int left, int top, int rowWidth) {
            if (!isRowVisible(top, getHeight())) return;
            int controlLeft = getControlLeft(left, rowWidth);
            int controlWidth = getControlWidth(rowWidth);
            addRenderableWidget(roundedButton(controlLeft,
                    top + (isUltraCompactLayout() ? 25 : 3), controlWidth, 18,
                    Component.literal(fitText(font, labels[selectedIndex], controlWidth - 18)),
                    VerityUiTheme.BUTTON, VerityUiTheme.ACCENT,
                    button -> {
                        selectedIndex = (selectedIndex + 1) % labels.length;
                        // 立即写回配置再重建：以前只重建界面、不落值，
                        // 换服务商/模式后必须保存并重开才真正生效。
                        saver.accept(storedValues[selectedIndex]);
                        requestRebuild();
                    }));
        }

        @Override
        void render(GuiGraphics graphics, int left, int top, int rowWidth) {
            if (!isRowVisible(top, getHeight())) return;
            if (isUltraCompactLayout()) {
                graphics.drawString(font, fitText(font, label, rowWidth - 16), left + 8, top + 7, TEXT);
                return;
            }
            int labelWidth = Math.max(36, getControlLeft(left, rowWidth) - left - 16);
            graphics.drawString(font, fitText(font, label, labelWidth), left + 8, top + 7, TEXT);
        }

        @Override
        public void save() {
            saver.accept(storedValues[selectedIndex]);
        }

        @Override
        public void reset() {
            selectedIndex = defaultIndex;
        }

        private int findIndex(String value, String[] values) {
            return findIndex(value, values, 0);
        }

        private int findIndex(String value, String[] values, int fallback) {
            if (value == null) return fallback;
            for (int i = 0; i < values.length; i++) {
                if (values[i].equalsIgnoreCase(value)) return i;
            }
            return fallback;
        }
    }

    /**
     * 原版色相滑块行：拖动时实时刷新原版预览纹理，松手后写入原版配置。
     * 参考原版 YACL 的 IntegerSliderController，取值范围 0-360，0 表示原始颜色。
     */
    private class HueSliderRow extends Row implements ValueRow {
        private final String label;
        private int value;
        private HueSlider slider;

        HueSliderRow(String label, int value) {
            this.label = label;
            this.value = clamp(value, 0, 360);
        }

        @Override
        void cache() {
            if (slider != null) value = slider.hue;
        }

        @Override
        void addWidgets(int left, int top, int rowWidth) {
            if (!isRowVisible(top, getHeight())) return;
            int controlLeft = getControlLeft(left, rowWidth);
            int controlWidth = getControlWidth(rowWidth);
            slider = new HueSlider(controlLeft, top + (isUltraCompactLayout() ? 25 : 3), controlWidth, 18, value);
            addRenderableWidget(slider);
        }

        @Override
        void render(GuiGraphics graphics, int left, int top, int rowWidth) {
            if (!isRowVisible(top, getHeight())) return;
            if (isUltraCompactLayout()) {
                graphics.drawString(font, fitText(font, label, rowWidth - 16), left + 8, top + 7, TEXT);
                return;
            }
            int labelWidth = Math.max(36, getControlLeft(left, rowWidth) - left - 16);
            graphics.drawString(font, fitText(font, label, labelWidth), left + 8, top + 7, TEXT);
        }

        @Override
        public void save() {
            cache();
            OriginalVerityPersonalization.setHue(value);
        }

        @Override
        public void reset() {
            value = 0;
            if (slider != null) slider.setHue(0);
        }
    }

    /** 0-360 的色相滑块，数值变化时同步原版预览纹理。 */
    private class HueSlider extends AbstractSliderButton {
        private int hue;

        HueSlider(int x, int y, int width, int height, int hue) {
            super(x, y, width, height, Component.empty(), hue / 360.0D);
            this.hue = clamp(hue, 0, 360);
            updateMessage();
        }

        /** 供“重置”按钮使用：直接设置数值并刷新预览。 */
        void setHue(int hue) {
            this.hue = clamp(hue, 0, 360);
            this.value = this.hue / 360.0D;
            updateMessage();
            OriginalVerityPersonalization.applyHue(this.hue);
        }

        @Override
        protected void updateMessage() {
            if (hue <= 0) {
                setMessage(Component.literal("0（原始颜色）"));
                return;
            }
            int rgb = Mth.hsvToRgb(hue / 360.0F, 1.0F, 1.0F);
            setMessage(Component.literal("■■ " + hue).withStyle(Style.EMPTY.withColor(rgb)));
        }

        @Override
        protected void applyValue() {
            hue = (int) Math.round(this.value * 360.0D);
            updateMessage();
            // 拖动过程中只重着色预览纹理，不写配置，避免高频落盘
            OriginalVerityPersonalization.applyHue(hue);
        }

        @Override
        public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            OriginalVerityPersonalization.setHue(hue);
        }
    }
}
