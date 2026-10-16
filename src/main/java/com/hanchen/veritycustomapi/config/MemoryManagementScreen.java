package com.hanchen.veritycustomapi.config;

import net.minecraft.client.gui.components.Button;
import com.hanchen.veritycustomapi.config.VerityUiWidgets;
import com.hanchen.veritycustomapi.config.VerityUiDraw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import com.hanchen.veritycustomapi.memory.MemoryCategory;
import com.hanchen.veritycustomapi.memory.VerityMemoryBridge;
import varmite.verity.entity.llm.store.memory.VerityMemoryManager;
import varmite.verity.entity.llm.store.memory.VerityMemoryStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class MemoryManagementScreen extends Screen {
    private static final int BACKGROUND = VerityUiTheme.BACKGROUND;
    private static final int SURFACE = VerityUiTheme.SURFACE;
    private static final int SEPARATOR = VerityUiTheme.SEPARATOR;
    private static final int TEXT = VerityUiTheme.TEXT;
    private static final int MUTED = VerityUiTheme.MUTED;
    private static final int ACCENT = VerityUiTheme.ACCENT;
    private static final int DANGER = VerityUiTheme.DANGER;
    private static final int ROW_HOVER = 0x332D3942;
    private static final int ROW_SELECTED = 0x55314854;
    private static final int ROW_HEIGHT = 30;
    private final Screen parent;
    private final List<Map.Entry<String, String>> entries = new ArrayList<>();
    private final List<Button> deleteButtons = new ArrayList<>();
    private EditBox searchBox;
    private EditBox keyBox;
    private EditBox valueBox;
    private String status = "";
    private boolean loading;
    private boolean loaded;
    private int scroll;
    private String searchQuery = "";
    private String draftKey = "";
    private String draftValue = "";
    private String selectedKey = "";

    public MemoryManagementScreen(Screen parent) {
        super(Component.literal("Verity 记忆"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        clearWidgets();
        deleteButtons.clear();
        int left = getPanelLeft();
        int right = getPanelRight();
        int innerWidth = right - left - 24;
        int searchButtonWidth = innerWidth < 300 ? 48 : 62;
        int refreshWidth = innerWidth < 300 ? 48 : 60;
        int searchWidth = Math.max(48, innerWidth - searchButtonWidth - refreshWidth - 12);
        searchBox = VerityUiWidgets.roundedEditBox(font, left + 12, 42, searchWidth, 20,
                Component.literal("查找记忆"));
        searchBox.setHint(Component.literal("按名称或内容查找"));
        searchBox.setValue(searchQuery);
        addRenderableWidget(searchBox);
        int searchButtonX = left + 16 + searchWidth;
        addRenderableWidget(VerityUiWidgets.secondaryButton(font, searchButtonX, 42, searchButtonWidth, 20,
                Component.literal("搜索"), button -> {
                    searchQuery = searchBox.getValue();
                    scroll = 0;
                    rebuildDeleteButtons();
                }));
        addRenderableWidget(VerityUiWidgets.secondaryButton(font, searchButtonX + searchButtonWidth + 4, 42, refreshWidth, 20,
                    Component.literal("刷新"), button -> refresh()));

        int editorTop = getEditorTop();
        int saveWidth = innerWidth < 300 ? 62 : 80;
        int fieldWidth = innerWidth - saveWidth - 12;
        int keyWidth = Math.max(54, Math.min(180, fieldWidth / 3));
        int valueWidth = Math.max(54, fieldWidth - keyWidth - 6);
        keyBox = VerityUiWidgets.roundedEditBox(font, left + 12, editorTop, keyWidth, 20,
                Component.literal("记忆名称"));
        keyBox.setHint(Component.literal("请输入记忆名称"));
        keyBox.setMaxLength(128);
        keyBox.setValue(draftKey);
        keyBox.setResponder(value -> draftKey = value);
        addRenderableWidget(keyBox);
        valueBox = VerityUiWidgets.roundedEditBox(font, left + 18 + keyWidth, editorTop, valueWidth, 20,
                Component.literal("记忆内容"));
        valueBox.setHint(Component.literal("记忆内容"));
        valueBox.setMaxLength(4096);
        valueBox.setValue(draftValue);
        valueBox.setResponder(value -> draftValue = value);
        addRenderableWidget(valueBox);
        addRenderableWidget(VerityUiWidgets.secondaryButton(font, left + 24 + keyWidth + valueWidth, editorTop, saveWidth, 20,
                    Component.literal("保存记忆"), button -> put()));
        addRenderableWidget(VerityUiWidgets.secondaryButton(font, left + 12, height - 30, 80, 20,
                    Component.literal("清空所有记忆"), button -> confirmClear()));
        addRenderableWidget(VerityUiWidgets.secondaryButton(font, right - 72, height - 30, 60, 20,
                    Component.literal("返回"), button -> onClose()));
        rebuildDeleteButtons();
        if (!loaded && !loading) refresh();
    }

    private void rebuildDeleteButtons() {
        if (minecraft == null || searchBox == null) return;
        for (Button button : deleteButtons) removeWidget(button);
        deleteButtons.clear();
        List<Map.Entry<String, String>> visible = filteredEntries();
        int rows = visibleRows();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, visible.size() - rows)));
        for (int i = scroll; i < Math.min(visible.size(), scroll + rows); i++) {
            Map.Entry<String, String> entry = visible.get(i);
            int y = getListTop() + (i - scroll) * ROW_HEIGHT;
            int right = getPanelRight() - 12;
            Button delete = VerityUiWidgets.secondaryButton(font, right - 52, y + 6, 48, 20,
                    Component.literal("删除"), button -> confirmDelete(entry.getKey()));
            delete.active = !loading;
            deleteButtons.add(delete);
            addRenderableWidget(delete);
        }
    }

    private void selectEntry(Map.Entry<String, String> entry) {
        selectedKey = entry.getKey();
        draftKey = entry.getKey();
        draftValue = entry.getValue();
        keyBox.setValue(draftKey);
        valueBox.setValue(draftValue);
        status = "正在编辑记忆：" + entry.getKey();
    }

    private List<Map.Entry<String, String>> filteredEntries() {
        String query = searchQuery.trim().toLowerCase();
        if (query.isEmpty()) return new ArrayList<>(entries);
        return entries.stream().filter(entry -> entry.getKey().toLowerCase().contains(query)
                || entry.getValue().toLowerCase().contains(query)).toList();
    }

    private void refresh() {
        runOnServer(store -> Map.copyOf(store.getAllMemories()), snapshot -> {
            entries.clear();
            entries.addAll(snapshot.entrySet());
            entries.sort(Comparator.comparing(Map.Entry::getKey));
            status = "已读取当前世界的 " + entries.size() + " 条记忆";
            loading = false;
            loaded = true;
            init(minecraft, width, height);
        });
    }

    private void put() {
        String key = keyBox.getValue().trim();
        String value = valueBox.getValue().trim();
        if (!key.matches("[a-z0-9]+(?:_[a-z0-9]+)*") || key.length() > 128) {
            status = "名称只能使用小写字母、数字和下划线";
            return;
        }
        if (value.isEmpty()) {
            status = "记忆内容不能为空";
            return;
        }
        runOnServer(store -> {
            if (!store.getAllKeys().contains(key) && store.getAllKeys().size() >= 500) {
                throw new IllegalStateException("最多只能保存 500 条记忆");
            }
            store.addMemory(key, value);
            VerityMemoryBridge.ensureMeta(key);
            return true;
        }, ignored -> {
            selectedKey = "";
            draftKey = "";
            draftValue = "";
            refresh();
        });
    }

    private void confirmDelete(String key) {
        minecraft.setScreen(new ConfirmScreen(confirmed -> {
            minecraft.setScreen(this);
            if (confirmed) runOnServer(store -> {
                store.removeMemory(key);
                VerityMemoryBridge.forgetMeta(key);
                return true;
            }, ignored -> {
                if (selectedKey.equals(key)) {
                    selectedKey = "";
                    draftKey = "";
                    draftValue = "";
                }
                refresh();
            });
        }, Component.literal("删除记忆"), Component.literal("要删除“" + key + "”吗？")));
    }

    private void confirmClear() {
        minecraft.setScreen(new ConfirmScreen(confirmed -> {
            minecraft.setScreen(this);
            if (confirmed) runOnServer(store -> {
                for (String key : store.getAllKeys()) store.removeMemory(key);
                VerityMemoryBridge.forgetAllMeta();
                return true;
            }, ignored -> {
                selectedKey = "";
                draftKey = "";
                draftValue = "";
                refresh();
            });
        }, Component.literal("清空所有记忆"), Component.literal("将删除当前世界保存的全部记忆，且无法撤销。")));
    }

    private <T> void runOnServer(java.util.function.Function<VerityMemoryStore, T> operation,
                                 java.util.function.Consumer<T> success) {
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            // 记忆存在世界存档目录里，远程服务器的存档不在本机，因此只能读本地单人存档
            status = mc.getConnection() == null
                    ? "请先进入单人世界"
                    : "远程服务器的记忆存档在服务端，本机无法直接修改";
            return;
        }
        loading = true;
        status = "正在读取当前世界的记忆…";
        server.execute(() -> {
            try {
                T result = operation.apply(VerityMemoryManager.get());
                mc.execute(() -> success.accept(result));
            } catch (Exception error) {
                mc.execute(() -> {
                    loading = false;
                    status = "操作失败：" + error.getMessage();
                });
            }
        });
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button != 0 || mouseX < getPanelLeft() + 12 || mouseX > getPanelRight() - 68
                || mouseY < getListTop() || mouseY >= getListBottom()) return false;
        List<Map.Entry<String, String>> visible = filteredEntries();
        int index = scroll + (int) ((mouseY - getListTop()) / ROW_HEIGHT);
        if (index < visible.size() && index < scroll + visibleRows()) {
            selectEntry(visible.get(index));
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX < getPanelLeft() || mouseX > getPanelRight()
                || mouseY < getListTop() || mouseY > getListBottom()) return false;
        List<Map.Entry<String, String>> visible = filteredEntries();
        int previous = scroll;
        scroll = Math.max(0, Math.min(scroll - (int) Math.signum(delta), Math.max(0, visible.size() - visibleRows())));
        if (scroll == previous) return false;
        rebuildDeleteButtons();
        return true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 半透明：世界里打开时透出世界；主菜单打开时走原版不透明背景
        com.hanchen.veritycustomapi.client.ScreenBackgrounds.draw(this, graphics);
        int left = getPanelLeft();
        int right = getPanelRight();
        VerityUiDraw.card(graphics, left, 10, right, height - 8, 5);
        graphics.drawString(font, "记忆", left + 12, 18, TEXT);
        graphics.drawString(font, font.plainSubstrByWidth(
                        "当前世界 · 最多保存 500 条 · 前缀决定分类（pref/fact/place/plan/temp）", right - left - 24),
                left + 12, 29, MUTED);
        graphics.fill(left + 12, 68, right - 12, 69, SEPARATOR);
        graphics.drawString(font, "已保存的记忆", left + 12, 74, TEXT);
        List<Map.Entry<String, String>> visible = filteredEntries();
        int end = Math.min(visible.size(), scroll + visibleRows());
        String count = visible.size() > visibleRows()
                ? (scroll + 1) + "-" + end + " / " + visible.size() + " 条"
                : visible.size() + " 条";
        graphics.drawString(font, count, right - 12 - font.width(count), 74, MUTED);
        renderMemoryRows(graphics, mouseX, mouseY, visible);
        graphics.fill(left + 12, getEditorTop() - 8, right - 12, getEditorTop() - 7, SEPARATOR);
        graphics.drawString(font, "名称", left + 12, getEditorTop() - 19, MUTED);
        graphics.drawString(font, "内容", left + 18 + keyBox.getWidth(), getEditorTop() - 19, MUTED);
        renderScrollBar(graphics, visible.size());
        if (!status.isEmpty()) {
            int color = status.startsWith("操作失败") || status.startsWith("名称")
                    || status.startsWith("记忆内容") ? DANGER : ACCENT;
            graphics.drawString(font, font.plainSubstrByWidth(status, Math.max(40, right - left - 210)),
                    left + 102, height - 24, color);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderMemoryRows(GuiGraphics graphics, int mouseX, int mouseY,
                                  List<Map.Entry<String, String>> visible) {
        int left = getPanelLeft() + 12;
        int right = getPanelRight() - 12;
        int textWidth = Math.max(40, right - left - 76);
        for (int i = scroll; i < Math.min(visible.size(), scroll + visibleRows()); i++) {
            Map.Entry<String, String> entry = visible.get(i);
            int y = getListTop() + (i - scroll) * ROW_HEIGHT;
            boolean selected = selectedKey.equals(entry.getKey());
            boolean hovered = mouseX >= left && mouseX <= right - 56 && mouseY >= y && mouseY < y + ROW_HEIGHT;
            if (selected) graphics.fill(left, y + 1, right, y + ROW_HEIGHT - 1, ROW_SELECTED);
            else if (hovered) graphics.fill(left, y + 1, right, y + ROW_HEIGHT - 1, ROW_HOVER);
            if (selected) graphics.fill(left, y + 4, left + 2, y + ROW_HEIGHT - 4, ACCENT);
            String title = MemoryCategory.inferFromKey(entry.getKey()).label() + "  ·  " + entry.getKey();
            graphics.drawString(font, font.plainSubstrByWidth(title, textWidth), left + 8, y + 5, TEXT);
            graphics.drawString(font, font.plainSubstrByWidth(entry.getValue(), textWidth), left + 8, y + 17, MUTED);
            graphics.fill(left, y + ROW_HEIGHT - 1, right, y + ROW_HEIGHT, SEPARATOR);
        }
        if (visible.isEmpty()) {
            String message = loading ? "正在读取记忆…" : searchQuery.isBlank() ? "当前世界还没有保存记忆" : "没有符合条件的记忆";
            graphics.drawCenteredString(font, message, width / 2, getListTop() + 22, MUTED);
        }
    }

    private void renderScrollBar(GuiGraphics graphics, int entryCount) {
        int rows = visibleRows();
        if (entryCount <= rows) return;
        int top = getListTop();
        int bottom = getListBottom();
        int trackX = getPanelRight() - 9;
        int thumbHeight = Math.max(12, (bottom - top) * rows / entryCount);
        int maxScroll = entryCount - rows;
        int thumbTop = top + (bottom - top - thumbHeight) * scroll / Math.max(1, maxScroll);
        graphics.fill(trackX, top, trackX + 2, bottom, SEPARATOR);
        graphics.fill(trackX, thumbTop, trackX + 2, thumbTop + thumbHeight, ACCENT);
    }

    private int visibleRows() {
        return Math.max(1, (getListBottom() - getListTop()) / ROW_HEIGHT);
    }

    private int getPanelLeft() {
        return Math.max(8, (width - Math.min(760, width - 16)) / 2);
    }

    private int getPanelRight() {
        return width - getPanelLeft();
    }

    private int getListTop() {
        return 88;
    }

    private int getEditorTop() {
        return Math.max(144, height - 64);
    }

    private int getListBottom() {
        return getEditorTop() - 28;
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
