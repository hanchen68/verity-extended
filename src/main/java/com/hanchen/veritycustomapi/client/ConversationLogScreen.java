package com.hanchen.veritycustomapi.client;

import com.hanchen.veritycustomapi.config.VerityUiWidgets;
import com.hanchen.veritycustomapi.config.VerityUiDraw;
import com.hanchen.veritycustomapi.config.VerityUiTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 对话记录 / 日记界面：两个页签共用同一套列表和详情渲染，
 * 数据分别来自 {@link ConversationLogStore} 和 {@link DiaryManager}。
 */
public final class ConversationLogScreen extends Screen {
    private static final int BACKGROUND = VerityUiTheme.BACKGROUND;
    private static final int SURFACE = VerityUiTheme.SURFACE;
    private static final int SEPARATOR = VerityUiTheme.SEPARATOR;
    private static final int BORDER = VerityUiTheme.BORDER;
    private static final int TEXT = VerityUiTheme.TEXT;
    private static final int MUTED = VerityUiTheme.MUTED;
    private static final int ACCENT = VerityUiTheme.ACCENT;
    private static final int DANGER = VerityUiTheme.DANGER;
    private static final int ROW_HEIGHT = 24;
    private static final int DETAIL_MAX_LINES = 8;

    private final Screen parent;
    private final List<ConversationLogStore.Entry> conversations = new ArrayList<>();
    private final List<DiaryManager.Entry> diaries = new ArrayList<>();
    private final List<Button> rowButtons = new ArrayList<>();
    private final List<String> rowTexts = new ArrayList<>();

    private int tab;
    private int scroll;
    private int selected = -1;
    private EditBox searchBox;
    private String searchQuery = "";
    private String status = "";
    private boolean loadedOnce;

    public ConversationLogScreen(Screen parent) {
        super(Component.literal("Verity 对话与日记"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        clearWidgets();
        rowButtons.clear();
        if (!loadedOnce) {
            reload();
            loadedOnce = true;
        }
        int left = panelLeft();
        int right = panelRight();
        int innerWidth = right - left - 24;
        boolean compact = compactToolbar();

        int tabWidth = Math.min(86, Math.max(64, (innerWidth - 4) / 2));
        addRenderableWidget(VerityUiWidgets.secondaryButton(font, left + 12, 34, tabWidth, 20,
                    Component.literal("对话记录"), button -> switchTab(0)));
        addRenderableWidget(VerityUiWidgets.secondaryButton(font, left + 16 + tabWidth, 34, tabWidth, 20,
                    Component.literal("日记"), button -> switchTab(1)));

        if (tab == 0) {
            int searchLeft = left + 24 + 2 * tabWidth;
            int searchRight = compact ? right - 12 : right - 250;
            int searchWidth = Math.max(50, searchRight - searchLeft);
            searchBox = VerityUiWidgets.roundedEditBox(font, searchLeft, 34, searchWidth, 20,
                Component.literal("搜索"));
            searchBox.setHint(Component.literal("按关键词查找"));
            searchBox.setValue(searchQuery);
            searchBox.setResponder(value -> {
                searchQuery = value;
                selected = -1;
                rebuildRows();
            });
            addRenderableWidget(searchBox);
        } else {
            searchBox = null;
        }

        int actionY = compact ? 58 : 34;
        int actionLeft = compact ? left + 12 : right - 240;
        int actionGap = compact ? 4 : 4;
        int actionWidth = compact ? Math.max(42, (innerWidth - actionGap * 3) / 4) : 0;
        int diaryWidth = compact ? actionWidth : 70;
        int exportWidth = compact ? actionWidth : 54;
        int refreshWidth = compact ? actionWidth : 44;
        int backWidth = compact ? actionWidth : 48;
        addRenderableWidget(VerityUiWidgets.secondaryButton(font, actionLeft, actionY, diaryWidth, 20,
                    Component.literal("生成日记"), button -> generateDiary()));
        addRenderableWidget(VerityUiWidgets.secondaryButton(font, actionLeft + diaryWidth + actionGap, actionY, exportWidth, 20,
                    Component.literal("导出"), button -> exportLog()));
        addRenderableWidget(VerityUiWidgets.secondaryButton(font,
                actionLeft + diaryWidth + exportWidth + actionGap * 2, actionY, refreshWidth, 20,
                Component.literal("刷新"), button -> {
                    reload();
                    rebuildRows();
                }));
        addRenderableWidget(VerityUiWidgets.secondaryButton(font, actionLeft + diaryWidth + exportWidth + refreshWidth + actionGap * 3, actionY, backWidth, 20,
                    Component.literal("返回"), button -> onClose()));

        rebuildRows();
    }

    private void switchTab(int next) {
        tab = next;
        scroll = 0;
        selected = -1;
        loadedOnce = false;
        init(minecraft, width, height);
    }

    private void reload() {
        conversations.clear();
        conversations.addAll(ConversationLogStore.load(0));
        diaries.clear();
        diaries.addAll(DiaryManager.load());
        status = tab == 0
                ? "已读取 " + conversations.size() + " 条对话记录"
                : "已读取 " + diaries.size() + " 篇日记";
    }

    private void generateDiary() {
        DiaryManager.requestGenerate(message -> {
            status = message;
            reload();
            rebuildRows();
        });
        tab = 1;
        selected = -1;
        rebuildRows();
    }

    private void exportLog() {
        if (tab == 1) {
            status = "日记暂不支持导出，可切到对话记录页导出";
            return;
        }
        Path path = ConversationLogStore.export(filteredConversations(), safeLabel());
        status = path == null ? "导出失败，请查看 latest.log" : "已导出到 " + path;
    }

    private String safeLabel() {
        String world = ConversationLogStore.currentWorldName();
        if (world == null || world.isBlank()) {
            return "world";
        }
        return world.replaceAll("[^\\p{L}\\p{N}_-]", "_");
    }

    private List<ConversationLogStore.Entry> filteredConversations() {
        String query = searchQuery == null ? "" : searchQuery.trim().toLowerCase();
        if (query.isEmpty()) {
            return new ArrayList<>(conversations);
        }
        List<ConversationLogStore.Entry> result = new ArrayList<>();
        for (ConversationLogStore.Entry entry : conversations) {
            if (entry.user().toLowerCase().contains(query)
                    || entry.assistant().toLowerCase().contains(query)) {
                result.add(entry);
            }
        }
        return result;
    }

    private List<String> currentRows() {
        List<String> rows = new ArrayList<>();
        if (tab == 0) {
            for (ConversationLogStore.Entry entry : filteredConversations()) {
                rows.add("[" + ConversationLogStore.formatTime(entry.at()) + "] 玩家: " + entry.user());
            }
        } else {
            for (DiaryManager.Entry entry : diaries) {
                rows.add("[" + DiaryManager.formatTime(entry.at()) + "] "
                        + entry.text().replace('\n', ' '));
            }
        }
        return rows;
    }

    private void rebuildRows() {
        if (minecraft == null) {
            return;
        }
        for (Button button : rowButtons) {
            removeWidget(button);
        }
        rowButtons.clear();
        rowTexts.clear();
        rowTexts.addAll(currentRows());

        int top = listTop();
        int rows = Math.max(1, (listBottom() - top) / ROW_HEIGHT);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rowTexts.size() - rows)));
        int left = panelLeft() + 12;
        int right = panelRight() - 12;
        for (int i = scroll; i < Math.min(rowTexts.size(), scroll + rows); i++) {
            final int index = i;
            String label = font.plainSubstrByWidth(rowTexts.get(i), Math.max(40, right - left - 10));
            Button row = VerityUiWidgets.roundedButton(font, left,
                    top + (i - scroll) * ROW_HEIGHT, Math.max(60, right - left), 20,
                    Component.literal(label), 0x00000000, VerityUiTheme.TEXT,
                    button -> selected = index);
            rowButtons.add(row);
            addRenderableWidget(row);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseY < listTop() || mouseY > listBottom()) {
            return false;
        }
        scroll = Math.max(0, scroll - (int) Math.signum(delta));
        rebuildRows();
        return true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 半透明：世界里打开时透出世界；主菜单打开时走原版不透明背景
        com.hanchen.veritycustomapi.client.ScreenBackgrounds.draw(this, graphics);
        int left = panelLeft();
        int right = panelRight();
        VerityUiDraw.card(graphics, left, 8, right, height - 8, 5);
        graphics.drawString(font, title, left + 12, 16, TEXT);
        graphics.drawString(font, tab == 0
                        ? "共 " + conversations.size() + " 条 · 当前显示 " + filteredConversations().size() + " 条"
                        : "共 " + diaries.size() + " 篇日记",
                left + 12, 25, MUTED);
        int tabWidth = Math.min(86, Math.max(64, (right - left - 28) / 2));
        int tabLeft = left + 12 + tab * (tabWidth + 4);
        graphics.fill(tabLeft, 55, tabLeft + tabWidth, 57, ACCENT);
        int dividerY = compactToolbar() ? 82 : 58;
        graphics.fill(left + 12, dividerY, right - 12, dividerY + 1, SEPARATOR);

        if (selected >= scroll && selected < scroll + rowButtons.size()) {
            int selectedTop = listTop() + (selected - scroll) * ROW_HEIGHT;
            graphics.fill(left + 12, selectedTop + 2, left + 14, selectedTop + 18, ACCENT);
        }
        if (rowTexts.isEmpty()) {
            graphics.drawString(font, tab == 0 ? "还没有对话记录" : "还没有日记，点「生成日记」写一篇",
                    left + 16, listTop() + 4, MUTED);
        }
        if (!status.isEmpty()) {
            graphics.drawString(font, font.plainSubstrByWidth(status, Math.max(40, right - left - 24)),
                    left + 12, height - 26, isError() ? DANGER : ACCENT);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        renderDetail(graphics);
    }

    private boolean isError() {
        return status.contains("失败") || status.contains("不支持") || status.contains("没有");
    }

    /** 底部详情：列表只放得下一行摘要，完整内容在这里看。 */
    private void renderDetail(GuiGraphics graphics) {
        int left = panelLeft() + 12;
        int right = panelRight() - 12;
        int top = detailTop();
        graphics.fill(left, top, right, top + 1, SEPARATOR);
        String text = selectedText();
        if (text == null || text.isBlank()) {
            graphics.drawString(font, "点上面任意一条查看完整内容", left + 2, top + 7, MUTED);
            return;
        }
        List<FormattedCharSequence> lines = font.split(Component.literal(text), Math.max(40, right - left - 4));
        int limit = Math.min(DETAIL_MAX_LINES, lines.size());
        for (int i = 0; i < limit; i++) {
            graphics.drawString(font, lines.get(i), left + 2, top + 6 + i * 10, TEXT);
        }
        if (lines.size() > limit) {
            graphics.drawString(font, "…（内容较长，已截断）", left + 2, top + 6 + limit * 10, MUTED);
        }
    }

    private String selectedText() {
        if (selected < 0) {
            return "";
        }
        if (tab == 0) {
            List<ConversationLogStore.Entry> visible = filteredConversations();
            if (selected >= visible.size()) {
                return "";
            }
            ConversationLogStore.Entry entry = visible.get(selected);
            return "玩家：" + entry.user() + "\nVerity：" + entry.assistant();
        }
        if (selected >= diaries.size()) {
            return "";
        }
        return diaries.get(selected).text();
    }

    private boolean compactToolbar() {
        return panelRight() - panelLeft() < 620;
    }

    private int listTop() {
        return compactToolbar() ? 90 : 66;
    }

    private int listBottom() {
        return detailTop() - 6;
    }

    private int detailTop() {
        return Math.max(listTop() + 48, height - 118);
    }

    private int panelLeft() {
        return Math.max(8, (width - Math.min(760, width - 16)) / 2);
    }

    private int panelRight() {
        return width - panelLeft();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
