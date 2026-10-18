package com.hanchen.veritycustomapi.client;

import com.hanchen.veritycustomapi.compat.ExternalFileOpener;
import com.hanchen.veritycustomapi.config.VerityUiWidgets;
import com.hanchen.veritycustomapi.config.VerityUiDraw;
import com.hanchen.veritycustomapi.config.VerityUiTheme;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.nio.file.Files;
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
    /** 日记页按全文排版后的块，列表不再截断。 */
    private final List<DiaryBlock> diaryBlocks = new ArrayList<>();
    private int diaryContentHeight;

    private int tab;
    private int scroll;
    private int selected = -1;
    private EditBox searchBox;
    private String searchQuery = "";
    private String status = "";
    private boolean loadedOnce;
    private Path lastExportPath;
    private Button openExportButton;

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
        if (lastExportPath == null) {
            lastExportPath = VerityExportService.latestExport();
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
            int searchRight = compact ? right - 12 : right - 360;
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
        int actionGap = 4;
        if (compact) {
            // 窄窗口一行放 7 个短按钮，避免换行挤压列表
            int count = 7;
            int actionWidth = Math.max(32, (innerWidth - actionGap * (count - 1)) / count);
            int x = left + 12;
            addRenderableWidget(VerityUiWidgets.secondaryButton(font, x, actionY, actionWidth, 20,
                    Component.literal("日记"), button -> generateDiary()));
            x += actionWidth + actionGap;
            addRenderableWidget(VerityUiWidgets.secondaryButton(font, x, actionY, actionWidth, 20,
                    Component.literal("导出"), button -> exportLog()));
            x += actionWidth + actionGap;
            addRenderableWidget(VerityUiWidgets.secondaryButton(font, x, actionY, actionWidth, 20,
                    Component.literal("导入"), button -> openImportScreen()));
            x += actionWidth + actionGap;
            openExportButton = addRenderableWidget(VerityUiWidgets.secondaryButton(font, x, actionY, actionWidth, 20,
                    Component.literal("打开"), button -> openExportFile()));
            openExportButton.active = lastExportPath != null;
            x += actionWidth + actionGap;
            addRenderableWidget(VerityUiWidgets.secondaryButton(font, x, actionY, actionWidth, 20,
                    Component.literal("云"), button -> openCloudBackupScreen()));
            x += actionWidth + actionGap;
            addRenderableWidget(VerityUiWidgets.secondaryButton(font, x, actionY, actionWidth, 20,
                    Component.literal("刷新"), button -> {
                        reload();
                        rebuildRows();
                    }));
            x += actionWidth + actionGap;
            addRenderableWidget(VerityUiWidgets.secondaryButton(font, x, actionY, actionWidth, 20,
                    Component.literal("返回"), button -> onClose()));
        } else {
            int diaryWidth = 64;
            int exportWidth = 44;
            int importWidth = 44;
            int openWidth = 48;
            int cloudWidth = 40;
            int refreshWidth = 38;
            int backWidth = 38;
            int total = diaryWidth + exportWidth + importWidth + openWidth + cloudWidth
                    + refreshWidth + backWidth + actionGap * 6;
            int x = right - total;
            addRenderableWidget(VerityUiWidgets.secondaryButton(font, x, actionY, diaryWidth, 20,
                    Component.literal("生成日记"), button -> generateDiary()));
            x += diaryWidth + actionGap;
            addRenderableWidget(VerityUiWidgets.secondaryButton(font, x, actionY, exportWidth, 20,
                    Component.literal("导出"), button -> exportLog()));
            x += exportWidth + actionGap;
            addRenderableWidget(VerityUiWidgets.secondaryButton(font, x, actionY, importWidth, 20,
                    Component.literal("导入"), button -> openImportScreen()));
            x += importWidth + actionGap;
            openExportButton = addRenderableWidget(VerityUiWidgets.secondaryButton(font, x, actionY, openWidth, 20,
                    Component.literal("打开"), button -> openExportFile()));
            openExportButton.active = lastExportPath != null;
            x += openWidth + actionGap;
            addRenderableWidget(VerityUiWidgets.secondaryButton(font, x, actionY, cloudWidth, 20,
                    Component.literal("云端"), button -> openCloudBackupScreen()));
            x += cloudWidth + actionGap;
            addRenderableWidget(VerityUiWidgets.secondaryButton(font, x, actionY, refreshWidth, 20,
                    Component.literal("刷新"), button -> {
                        reload();
                        rebuildRows();
                    }));
            x += refreshWidth + actionGap;
            addRenderableWidget(VerityUiWidgets.secondaryButton(font, x, actionY, backWidth, 20,
                    Component.literal("返回"), button -> onClose()));
        }

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
        // 走 switchTab，确保搜索框等页签专属控件被清理
        switchTab(1);
    }

    private void exportLog() {
        Path path = VerityExportService.exportAll(filteredConversations(), diaries, safeLabel());
        if (path == null) {
            status = "导出失败，请查看 latest.log";
            return;
        }
        lastExportPath = path;
        if (openExportButton != null) {
            openExportButton.active = true;
        }
        status = "已导出 对话 " + filteredConversations().size() + " 条 / 日记 "
                + diaries.size() + " 篇到 " + path.getFileName();
    }

    private void openImportScreen() {
        minecraft.setScreen(new VerityImportScreen(this));
    }

    private void openCloudBackupScreen() {
        minecraft.setScreen(new VerityCloudBackupScreen(this));
    }

    private void openExportFile() {
        if (lastExportPath == null || !Files.isRegularFile(lastExportPath)) {
            status = "还没有可打开的导出文件";
            return;
        }
        ExternalFileOpener.OpenResult result = ExternalFileOpener.openFile(lastExportPath);
        status = result.message();
    }

    /** 导入完成后由 {@link VerityImportScreen} / 云端恢复调用，刷新列表与状态。 */
    void refreshAfterImport() {
        reload();
        rebuildRows();
        status = "导入完成，已刷新列表";
    }

    List<ConversationLogStore.Entry> allConversationsForBackup() {
        return new ArrayList<>(conversations);
    }

    List<DiaryManager.Entry> allDiariesForBackup() {
        return new ArrayList<>(diaries);
    }

    String backupLabel() {
        return safeLabel();
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
        if (tab == 1) {
            // 日记页是全文文档，不再生成单行截断按钮
            rebuildDiaryBlocks();
            scroll = Math.max(0, Math.min(scroll, maxDiaryScroll()));
            return;
        }
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

    /** 把每篇日记按当前宽度排成多行，计算文档总高度；内容不再截断。 */
    private void rebuildDiaryBlocks() {
        diaryBlocks.clear();
        diaryContentHeight = 0;
        int width = Math.max(80, panelRight() - panelLeft() - 28);
        for (DiaryManager.Entry entry : diaries) {
            List<FormattedCharSequence> lines = new ArrayList<>();
            for (String paragraph : entry.text().split("\\R")) {
                if (paragraph.isBlank()) continue;
                lines.addAll(font.split(Component.literal(paragraph), width));
            }
            if (lines.isEmpty()) {
                lines.add(FormattedCharSequence.EMPTY);
            }
            DiaryBlock block = new DiaryBlock("[" + DiaryManager.formatTime(entry.at()) + "]", lines);
            diaryBlocks.add(block);
            diaryContentHeight += block.height();
        }
    }

    private int diaryTop() {
        return listTop();
    }

    private int diaryBottom() {
        return height - 32;
    }

    private int maxDiaryScroll() {
        return Math.max(0, diaryContentHeight - Math.max(1, diaryBottom() - diaryTop()));
    }

    private record DiaryBlock(String stamp, List<FormattedCharSequence> lines) {
        int height() {
            return 12 + Math.max(1, lines.size()) * 10 + 10;
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseY < diaryTop() || mouseY > diaryBottom()) {
            return false;
        }
        if (tab == 1) {
            int max = maxDiaryScroll();
            if (max <= 0) return false;
            scroll = Math.max(0, Math.min(max, scroll - (int) Math.round(delta * 18)));
            return true;
        }
        if (mouseY > listBottom()) {
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

        if (tab == 1) {
            renderDiaryDocument(graphics);
        } else {
            if (selected >= scroll && selected < scroll + rowButtons.size()) {
                int selectedTop = listTop() + (selected - scroll) * ROW_HEIGHT;
                graphics.fill(left + 12, selectedTop + 2, left + 14, selectedTop + 18, ACCENT);
            }
            if (rowTexts.isEmpty()) {
                graphics.drawString(font, "还没有对话记录", left + 16, listTop() + 4, MUTED);
            }
        }
        if (!status.isEmpty()) {
            graphics.drawString(font, font.plainSubstrByWidth(status, Math.max(40, right - left - 24)),
                    left + 12, height - 26, isError() ? DANGER : ACCENT);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        if (tab == 0) {
            renderDetail(graphics);
        }
    }

    private boolean isError() {
        return status.contains("失败") || status.contains("不支持") || status.contains("没有");
    }

    /** 日记全文：按篇分段、自动换行，滚动查看，不做行数截断。 */
    private void renderDiaryDocument(GuiGraphics graphics) {
        int left = panelLeft() + 12;
        int right = panelRight() - 12;
        int top = diaryTop();
        int bottom = diaryBottom();
        if (diaryBlocks.isEmpty()) {
            graphics.drawString(font, "还没有日记，点「生成日记」写一篇", left + 4, top + 4, MUTED);
            return;
        }
        graphics.enableScissor(left, top, right, bottom);
        int y = top - scroll;
        for (DiaryBlock block : diaryBlocks) {
            int blockHeight = block.height();
            if (y + blockHeight < top) {
                y += blockHeight;
                continue;
            }
            if (y > bottom) break;
            graphics.drawString(font, block.stamp(), left, y, ACCENT);
            y += 12;
            for (FormattedCharSequence line : block.lines()) {
                if (y + 10 >= top && y <= bottom) {
                    graphics.drawString(font, line, left, y, TEXT);
                }
                y += 10;
            }
            y += 8;
            if (y >= top && y <= bottom) {
                graphics.fill(left, y, right, y + 1, SEPARATOR);
            }
            y += 2;
        }
        graphics.disableScissor();

        int max = maxDiaryScroll();
        if (max > 0) {
            int viewport = Math.max(1, bottom - top);
            int thumb = Math.max(16, viewport * viewport / Math.max(1, diaryContentHeight));
            int thumbY = top + (int) (scroll * (viewport - thumb) / (double) max);
            int x = right - 3;
            graphics.fill(x, top, x + 2, bottom, SEPARATOR);
            graphics.fill(x, thumbY, x + 2, thumbY + thumb, ACCENT);
        }
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
