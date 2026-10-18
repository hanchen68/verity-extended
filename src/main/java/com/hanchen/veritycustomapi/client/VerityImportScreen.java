package com.hanchen.veritycustomapi.client;

import com.hanchen.veritycustomapi.compat.ExternalFileOpener;
import com.hanchen.veritycustomapi.config.VerityUiDraw;
import com.hanchen.veritycustomapi.config.VerityUiTheme;
import com.hanchen.veritycustomapi.config.VerityUiWidgets;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** 导入对话/日记导出文件：显示导出目录里的 JSON/JSONL，点选后导入。 */
public final class VerityImportScreen extends Screen {
    private static final int TEXT = VerityUiTheme.TEXT;
    private static final int MUTED = VerityUiTheme.MUTED;
    private static final int ACCENT = VerityUiTheme.ACCENT;
    private static final int DANGER = VerityUiTheme.DANGER;
    private static final int SEPARATOR = VerityUiTheme.SEPARATOR;
    private static final int ROW_HEIGHT = 26;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final ConversationLogScreen parent;
    private final List<Path> files = new ArrayList<>();
    private int scroll;
    private int selected = -1;
    private String status = "";

    public VerityImportScreen(ConversationLogScreen parent) {
        super(Component.literal("导入 Verity 数据"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        files.clear();
        files.addAll(VerityExportService.listImportableFiles());
        if (selected >= files.size()) selected = files.isEmpty() ? -1 : files.size() - 1;
        if (selected < 0 && !files.isEmpty()) selected = 0;

        int left = panelLeft();
        int right = panelRight();
        int y = height - 30;
        int importWidth = 80;
        int folderWidth = 70;
        int refreshWidth = 48;
        int backWidth = 44;
        int gap = 4;
        int total = importWidth + folderWidth + refreshWidth + backWidth + gap * 3;
        int x = right - 12 - total;
        addRenderableWidget(VerityUiWidgets.secondaryButton(font, x, y, importWidth, 20,
                Component.literal("导入选中"), button -> importSelected()));
        x += importWidth + gap;
        addRenderableWidget(VerityUiWidgets.secondaryButton(font, x, y, folderWidth, 20,
                Component.literal("打开目录"), button -> openDirectory()));
        x += folderWidth + gap;
        addRenderableWidget(VerityUiWidgets.secondaryButton(font, x, y, refreshWidth, 20,
                Component.literal("刷新"), button -> {
                    files.clear();
                    files.addAll(VerityExportService.listImportableFiles());
                    scroll = 0;
                    selected = files.isEmpty() ? -1 : 0;
                    status = files.isEmpty() ? "导出目录里还没有可导入的文件" : "";
                }));
        x += refreshWidth + gap;
        addRenderableWidget(VerityUiWidgets.secondaryButton(font, x, y, backWidth, 20,
                Component.literal("返回"), button -> onClose()));
    }

    private void importSelected() {
        if (selected < 0 || selected >= files.size()) {
            status = "请先选择一个导出文件";
            return;
        }
        VerityExportService.ImportResult result = VerityExportService.importFile(files.get(selected));
        status = result.message();
        if (result.conversations() > 0 || result.diaries() > 0) {
            parent.refreshAfterImport();
        }
    }

    private void openDirectory() {
        ExternalFileOpener.OpenResult result =
                ExternalFileOpener.openDirectory(VerityExportService.exportsDirectory());
        status = result.message();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int top = listTop();
        int bottom = height - 62;
        if (button == 0 && mouseX >= panelLeft() + 12 && mouseX <= panelRight() - 12
                && mouseY >= top && mouseY <= bottom && !files.isEmpty()) {
            int index = scroll + (int) ((mouseY - top) / ROW_HEIGHT);
            if (index >= 0 && index < files.size()) {
                selected = index;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int visible = Math.max(1, (height - 62 - listTop()) / ROW_HEIGHT);
        if (files.size() <= visible) return false;
        scroll = Math.max(0, Math.min(files.size() - visible, scroll - (int) Math.signum(delta)));
        return true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        com.hanchen.veritycustomapi.client.ScreenBackgrounds.draw(this, graphics);
        int left = panelLeft();
        int right = panelRight();
        VerityUiDraw.card(graphics, left, 8, right, height - 8, 5);
        graphics.drawString(font, "导入 Verity 数据", left + 12, 16, TEXT);
        graphics.drawString(font, "选择导出目录中的 .json / .jsonl 文件；重复条目会自动跳过",
                left + 12, 27, MUTED);

        int top = listTop();
        int bottom = height - 62;
        graphics.fill(left + 12, top - 4, right - 12, top - 3, SEPARATOR);
        if (files.isEmpty()) {
            graphics.drawString(font, "导出目录里还没有可导入的文件", left + 16, top + 4, MUTED);
        } else {
            int visible = Math.max(1, (bottom - top) / ROW_HEIGHT);
            graphics.enableScissor(left + 10, top, right - 10, bottom);
            for (int i = scroll; i < Math.min(files.size(), scroll + visible); i++) {
                int y = top + (i - scroll) * ROW_HEIGHT;
                Path file = files.get(i);
                boolean hovered = mouseX >= left + 12 && mouseX <= right - 12
                        && mouseY >= y && mouseY < y + ROW_HEIGHT;
                if (i == selected) {
                    graphics.fill(left + 12, y, right - 12, y + ROW_HEIGHT - 2, VerityUiTheme.ROW_HOVER);
                    graphics.fill(left + 12, y, left + 14, y + ROW_HEIGHT - 2, ACCENT);
                } else if (hovered) {
                    graphics.fill(left + 12, y, right - 12, y + ROW_HEIGHT - 2, VerityUiTheme.ROW_HOVER);
                }
                graphics.drawString(font, fit(file.getFileName().toString(), right - left - 40),
                        left + 20, y + 4, TEXT);
                graphics.drawString(font, meta(file), left + 20, y + 14, MUTED);
                graphics.fill(left + 12, y + ROW_HEIGHT - 2, right - 12, y + ROW_HEIGHT - 1, SEPARATOR);
            }
            graphics.disableScissor();
            if (files.size() > visible) {
                int thumb = Math.max(16, visible * visible / files.size());
                int thumbY = top + (int) (scroll * (bottom - top - thumb) / (double) Math.max(1, files.size() - visible));
                int x = right - 5;
                graphics.fill(x, top, x + 2, bottom, SEPARATOR);
                graphics.fill(x, thumbY, x + 2, thumbY + thumb, ACCENT);
            }
        }
        if (!status.isBlank()) {
            graphics.drawString(font, fit(status, right - left - 24), left + 12, height - 44,
                    status.contains("失败") || status.contains("无法") || status.contains("没有") ? DANGER : ACCENT);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(font, "导出目录: " + fit(VerityExportService.exportsDirectory().toString(), right - left - 100),
                left + 12, height - 56, MUTED);
    }

    private int listTop() {
        return 48;
    }

    private int panelLeft() {
        return Math.max(8, (width - Math.min(760, width - 16)) / 2);
    }

    private int panelRight() {
        return width - panelLeft();
    }

    private String meta(Path file) {
        try {
            long size = Files.size(file);
            String time = TIME.format(Instant.ofEpochMilli(Files.getLastModifiedTime(file).toMillis())
                    .atZone(ZoneId.systemDefault()));
            return time + " · " + formatBytes(size);
        } catch (IOException ignored) {
            return "";
        }
    }

    private String fit(String value, int maxWidth) {
        return font.plainSubstrByWidth(value == null ? "" : value, Math.max(20, maxWidth));
    }

    private static String formatBytes(long value) {
        return value < 1024 ? value + " B"
                : value < 1024 * 1024 ? String.format("%.1f KB", value / 1024.0)
                : String.format("%.1f MB", value / 1048576.0);
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
