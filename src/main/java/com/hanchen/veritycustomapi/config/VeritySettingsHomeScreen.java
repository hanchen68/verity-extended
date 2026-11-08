package com.hanchen.veritycustomapi.config;

import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.client.ScreenBackgrounds;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** 只负责导航与本地连接状态，不发起网络请求或服务测试。 */
@OnlyIn(Dist.CLIENT)
public final class VeritySettingsHomeScreen extends Screen {
    // ConfigLayout 仅定义几何；页序核对 NativeOllamaProxyConfigScreen.buildCategories()。
    private static final int CONVERSATION_PAGE = 1;
    private static final int COMPANION_PAGE = 2;
    private static final int APPEARANCE_PAGE = 6;
    private static final int MMD_PAGE = 12;
    private static final int INTERFACE_PAGE = 13;
    private static final String[] PAGE_TITLES = {
            "概览", "对话与记忆", "世界交互", "多人游戏设置", "语音识别", "语音朗读",
            "外观与资源", "原版 Verity", "主动感知", "摄像头视觉", "AI 建造", "云端与账户",
            "MMD 动画", "界面与记录", "高级与诊断"
    };
    private static final int BUTTON_HEIGHT = 24;
    private static final int ROW_GAP = 8;
    private static final double DRAG_THRESHOLD = 4.0D;

    private enum View { HOME, APPEARANCE, ADVANCED }

    private record Entry(Component label, Component description, Runnable action) {}

    private final Screen parent;
    private final List<Entry> entries = new ArrayList<>();
    private final List<Button> entryButtons = new ArrayList<>();
    private final List<List<FormattedCharSequence>> descriptions = new ArrayList<>();
    private final List<Integer> rowOffsets = new ArrayList<>();
    private View view = View.HOME;
    private Button backButton;
    private String connectionStatus = "未连接";
    private int panelLeft, panelRight, panelTop, panelBottom;
    private int listLeft, listRight, listTop, listBottom, contentHeight;
    private double scrollOffset, homeScrollOffset;
    private boolean pointerDown, dragged;
    private double pointerX, pointerY, pointerScroll;
    private Button pressedButton;

    public VeritySettingsHomeScreen(Screen parent) {
        super(Component.literal("Verity 设置"));
        this.parent = parent;
    }

    /** 外层界面（主菜单 / 模组列表 / 游戏内暂停）。切换菜单时新界面用它当父屏，避免返回绕回设置。 */
    public Screen parentScreen() {
        return parent;
    }

    @Override
    protected void init() {
        clearWidgets();
        setFocused(null);
        resetPointer();
        entries.clear();
        entryButtons.clear();
        descriptions.clear();
        rowOffsets.clear();

        // 已保存授权不等于服务可用；不检查模型、额度或网络连通性。
        connectionStatus = ConfigManager.isCloudEnabled() && !ConfigManager.isCloudAuthFailed()
                && !ConfigManager.getCloudApiKey().isBlank() ? "已连接" : "未连接";

        int margin = ConfigLayout.outerMargin(width, height);
        int panelWidth = Math.max(1, Math.min(520, width - margin * 2));
        panelLeft = (width - panelWidth) / 2;
        panelRight = panelLeft + panelWidth;
        panelTop = margin;
        panelBottom = height - margin;
        listLeft = panelLeft + 8;
        listRight = panelRight - 14;
        listTop = panelTop + font.lineHeight * 2 + 16;
        int footerY = panelBottom - BUTTON_HEIGHT - 6;
        listBottom = footerY - 9;

        buildEntries();
        int rowWidth = Math.max(1, listRight - listLeft);
        int offset = 0;
        for (Entry entry : entries) {
            rowOffsets.add(offset);
            List<FormattedCharSequence> lines = font.split(entry.description(), Math.max(1, rowWidth - 8));
            descriptions.add(lines);
            Button button = VerityUiWidgets.secondaryButton(font, listLeft, listTop + offset,
                    rowWidth, BUTTON_HEIGHT, entry.label(), ignored -> entry.action().run());
            // 列表单独裁剪绘制；仍注册原版焦点、键盘操作和旁白。
            entryButtons.add(addWidget(button));
            offset += BUTTON_HEIGHT + (lines.isEmpty() ? 0 : 4 + lines.size() * font.lineHeight) + ROW_GAP;
        }
        contentHeight = Math.max(0, offset - ROW_GAP);
        setScroll(scrollOffset);
        int backWidth = Math.min(160, rowWidth);
        backButton = addRenderableWidget(VerityUiWidgets.secondaryButton(font,
                (width - backWidth) / 2, footerY, backWidth, BUTTON_HEIGHT,
                Component.literal("返回"), ignored -> onClose()));
    }

    private void buildEntries() {
        if (view == View.ADVANCED) {
            for (int page = 0; page < PAGE_TITLES.length; page++) {
                final int index = page;
                addEntry(PAGE_TITLES[page], "", () -> openPage(index));
            }
        } else if (view == View.APPEARANCE) {
            addEntry("外观与资源", "皮肤、动作与资源", () -> openPage(APPEARANCE_PAGE));
            addEntry("MMD 动画", "舞蹈、音乐与镜头", () -> openPage(MMD_PAGE));
            addEntry("界面与记录", "屏幕显示、快捷指令与对话记录", () -> openPage(INTERFACE_PAGE));
        } else {
            addEntry("连接与云端服务", "连接账户，管理本设备的服务选择。",
                    () -> minecraft.setScreen(new InGameCloudSetupScreen(this)));
            addEntry("对话与声音", "对话、记忆、语音输入与朗读。", () -> openPage(CONVERSATION_PAGE));
            addEntry("伙伴玩法", "世界交互、伙伴互动、主动感知与建造。", () -> openPage(COMPANION_PAGE));
            addEntry("外观与界面", "皮肤、动作、屏幕显示与对话记录。", () -> showView(View.APPEARANCE));
            addEntry("全部高级设置", "按原有分类查看全部设置。", () -> showView(View.ADVANCED));
            addEntry("切换新手 / 完整菜单", "只改变设置界面，不改动服务与授权。", () -> minecraft.setScreen(new SettingsExperienceScreen(parent)));
        }
    }

    private void addEntry(String label, String description, Runnable action) {
        entries.add(new Entry(Component.literal(label), Component.literal(description), action));
    }

    private void openPage(int index) {
        minecraft.setScreen(NativeOllamaProxyConfigScreen.openPage(this, index));
    }

    private void showView(View next) {
        if (view == View.HOME) homeScrollOffset = scrollOffset;
        view = next;
        scrollOffset = next == View.HOME ? homeScrollOffset : 0;
        init();
    }

    private int viewportHeight() {
        return Math.max(1, listBottom - listTop);
    }

    private double maxScroll() {
        return Math.max(0, contentHeight - viewportHeight());
    }

    private void setScroll(double offset) {
        scrollOffset = Math.max(0, Math.min(maxScroll(), offset));
        for (int i = 0; i < entryButtons.size(); i++) {
            entryButtons.get(i).setY(listTop + rowOffsets.get(i) - (int) Math.round(scrollOffset));
        }
    }

    private boolean insideList(double x, double y) {
        return x >= listLeft && x < panelRight - 6 && y >= listTop && y < listBottom;
    }

    private Button buttonAt(double x, double y) {
        if (!insideList(x, y)) return null;
        for (Button button : entryButtons) {
            if (button.isMouseOver(x, y)) return button;
        }
        return null;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (insideList(mouseX, mouseY)) {
            if (pointerDown) dragged = true;
            setScroll(scrollOffset - delta * (BUTTON_HEIGHT + ROW_GAP));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && insideList(mouseX, mouseY)) {
            // 原版按钮按下即跳页；延迟到抬起，避免触屏滑动误触入口。
            pointerDown = true;
            dragged = false;
            pointerX = mouseX;
            pointerY = mouseY;
            pointerScroll = scrollOffset;
            pressedButton = buttonAt(mouseX, mouseY);
            setFocused(pressedButton);
            setDragging(true);
            return true;
        }
        // 直接分发到底栏，屏外的列表按钮不能抢走返回点击。
        if (backButton != null && backButton.isMouseOver(mouseX, mouseY)) {
            setFocused(backButton);
            return backButton.mouseClicked(mouseX, mouseY, button);
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && pointerDown) {
            if (Math.abs(mouseY - pointerY) > DRAG_THRESHOLD
                    || Math.abs(mouseX - pointerX) > DRAG_THRESHOLD) dragged = true;
            if (dragged) setScroll(pointerScroll + pointerY - mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && pointerDown) {
            boolean moved = dragged || Math.abs(mouseY - pointerY) > DRAG_THRESHOLD
                    || Math.abs(mouseX - pointerX) > DRAG_THRESHOLD;
            Button selected = !moved && pressedButton == buttonAt(mouseX, mouseY) ? pressedButton : null;
            resetPointer();
            if (selected != null) selected.mouseClicked(mouseX, mouseY, button);
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void resetPointer() {
        pointerDown = false;
        dragged = false;
        pressedButton = null;
        setDragging(false);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_PAGE_UP || keyCode == GLFW.GLFW_KEY_PAGE_DOWN) {
            resetPointer();
            setScroll(scrollOffset + (keyCode == GLFW.GLFW_KEY_PAGE_UP ? -1 : 1)
                    * Math.max(BUTTON_HEIGHT, viewportHeight() - BUTTON_HEIGHT));
            return true;
        }
        boolean handled = super.keyPressed(keyCode, scanCode, modifiers);
        // Tab / 方向键定位到屏外入口时，将整个按钮滚入视野。
        if (handled && (keyCode == GLFW.GLFW_KEY_TAB || keyCode == GLFW.GLFW_KEY_UP
                || keyCode == GLFW.GLFW_KEY_DOWN || keyCode == GLFW.GLFW_KEY_LEFT
                || keyCode == GLFW.GLFW_KEY_RIGHT)) {
            for (Button button : entryButtons) {
                if (getFocused() != button) continue;
                if (button.getY() < listTop) setScroll(scrollOffset + button.getY() - listTop);
                else if (button.getY() + button.getHeight() > listBottom)
                    setScroll(scrollOffset + button.getY() + button.getHeight() - listBottom);
                break;
            }
        }
        return handled;
    }

    @Override
    public void onClose() {
        if (view != View.HOME) showView(View.HOME);
        else minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        resetPointer();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ScreenBackgrounds.draw(this, graphics);
        // 单一灰色方窗与像素边线，不使用卡片、圆角或渐变。
        graphics.fill(panelLeft, panelTop, panelRight, panelBottom, VerityUiTheme.BORDER);
        graphics.fill(panelLeft + 1, panelTop + 1, panelRight - 1, panelBottom - 1, VerityUiTheme.SURFACE);
        graphics.fill(panelLeft + 1, panelTop + 1, panelRight - 1, panelTop + 2, VerityUiTheme.SEPARATOR);
        String heading = view == View.HOME ? title.getString()
                : view == View.APPEARANCE ? "外观与界面" : "全部高级设置";
        graphics.drawString(font, heading, listLeft, panelTop + 7, VerityUiTheme.TEXT, false);
        graphics.drawString(font, connectionStatus, listLeft, panelTop + 10 + font.lineHeight,
                VerityUiTheme.MUTED, false);
        graphics.fill(listLeft, listTop - 4, panelRight - 7, listTop - 3, VerityUiTheme.ROW_DIVIDER);

        graphics.enableScissor(listLeft, listTop, listRight, listBottom);
        try {
            for (int i = 0; i < entryButtons.size(); i++) {
                Button button = entryButtons.get(i);
                if (button.getY() < listBottom && button.getY() + button.getHeight() > listTop)
                    button.render(graphics, insideList(mouseX, mouseY) ? mouseX : -1,
                            insideList(mouseX, mouseY) ? mouseY : -1, partialTick);
                int textY = button.getY() + BUTTON_HEIGHT + 4;
                for (FormattedCharSequence line : descriptions.get(i)) {
                    if (textY + font.lineHeight > listTop && textY < listBottom)
                        graphics.drawString(font, line, listLeft + 4, textY, VerityUiTheme.MUTED, false);
                    textY += font.lineHeight;
                }
            }
        } finally {
            graphics.disableScissor();
        }
        if (maxScroll() > 0) {
            int trackX = panelRight - 9;
            int trackHeight = viewportHeight();
            int thumbHeight = Math.min(trackHeight, Math.max(12, trackHeight * trackHeight / contentHeight));
            int thumbY = listTop + (int) Math.round((trackHeight - thumbHeight) * scrollOffset / maxScroll());
            graphics.fill(trackX, listTop, trackX + 3, listBottom, VerityUiTheme.CONTROL_TRACK);
            graphics.fill(trackX, thumbY, trackX + 3, thumbY + thumbHeight, VerityUiTheme.CONTROL_BORDER);
        }
        graphics.fill(listLeft, listBottom + 4, panelRight - 7, listBottom + 5, VerityUiTheme.ROW_DIVIDER);
        super.render(graphics, mouseX, mouseY, partialTick);
    }
}
