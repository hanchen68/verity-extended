package com.hanchen.veritycustomapi.client;

import com.hanchen.veritycustomapi.config.VerityUiWidgets;
import com.hanchen.veritycustomapi.config.VerityUiDraw;
import com.hanchen.veritycustomapi.config.VerityUiTheme;
import com.hanchen.veritycustomapi.network.PluginNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.List;

/**
 * 插件管理界面：列插件、开关、删除、新建。
 *
 * 界面自己不留任何权威状态——只把服务端推来的清单画出来，点击转成网络请求。
 * 这样几个玩家同时操作，看到的也始终是服务端的真实状态。
 */
@OnlyIn(Dist.CLIENT)
public class PluginManagerScreen extends Screen {

    private static final int ROW_HEIGHT = 24;
    private static final int LIST_TOP = 46;
    private static final int BUTTONS_WIDTH = 148;
    private static final int TOGGLE_W = 48;
    private static final int DETAIL_W = 44;
    private static final int DELETE_W = 48;

    /** 列表面板底色（半透明）。 */
    private static final int PANEL = 0x99181C22;

    private int scroll;
    private int selected = -1;
    private int pendingDelete = -1;
    private String status = "";

    private final Screen parent;

    public PluginManagerScreen() {
        this(null);
    }

    public PluginManagerScreen(Screen parent) {
        super(Component.literal("插件管理"));
        this.parent = parent;
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    @Override
    protected void init() {
        // 设置界面在主菜单也能打开，那时没有连接；无条件发包会直接空指针崩客户端
        if (connected()) {
            PluginNetwork.requestList();
        }
        int y = this.height - 28;
        addRenderableWidget(VerityUiWidgets.primaryButton(font, 20, y, 90, 20,
                Component.literal("新建插件"), button -> {
                    if (connected() && this.minecraft != null) {
                        this.minecraft.setScreen(new PluginCreateScreen(this));
                    } else {
                        status = "§c请先进入一个世界，再来管理插件";
                    }
                }));
        addRenderableWidget(VerityUiWidgets.secondaryButton(font, 116, y, 56, 20,
                Component.literal("刷新"), button -> {
                    if (connected()) {
                        PluginNetwork.requestList();
                        status = "正在刷新…";
                    } else {
                        status = "§c请先进入一个世界，再来管理插件";
                    }
                }));
        addRenderableWidget(VerityUiWidgets.secondaryButton(font, 178, y, 56, 20,
                    Component.literal("关闭"), button -> this.onClose()));
    }

    /** 只有真正连上服务器（进入了世界）才能收发插件数据。 */
    private boolean connected() {
        return this.minecraft != null && this.minecraft.getConnection() != null;
    }

    /** 服务端推来新清单后调用。 */
    public void onListUpdated() {
        selected = -1;
        pendingDelete = -1;
    }

    public void onStatus(String text) {
        status = text;
        pendingDelete = -1;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 半透明：世界透得出来；主菜单打开时按普通菜单背景处理
        ScreenBackgrounds.draw(this, graphics);
        List<PluginUiBridge.Entry> entries = PluginUiBridge.entries();

        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, VerityUiTheme.TEXT);
        graphics.drawString(this.font, "共 " + entries.size() + " 个插件", 20, 28, VerityUiTheme.MUTED);
        graphics.drawString(this.font, "插件只能调用扩展内置的动作，且只对你生效",
                20 + this.font.width("共 " + entries.size() + " 个插件") + 12, 28, VerityUiTheme.MUTED);

        int listBottom = this.height - 62;
        VerityUiDraw.card(graphics, 16, LIST_TOP - 4, this.width - 16, listBottom, 5);
        if (!connected()) {
            graphics.drawString(this.font, "§e请先进入一个世界，才能管理插件。", 20, LIST_TOP, VerityUiTheme.WARNING);
        } else if (entries.isEmpty()) {
            graphics.drawString(this.font, "还没有插件。点「新建插件」，用一句话描述你想要的功能。",
                    20, LIST_TOP, VerityUiTheme.MUTED);
        }

        int startX = this.width - 20 - BUTTONS_WIDTH;
        graphics.enableScissor(16, LIST_TOP - 2, this.width - 16, listBottom + 2);
        for (int i = 0; i < entries.size(); i++) {
            int y = LIST_TOP + i * ROW_HEIGHT - scroll;
            if (y + ROW_HEIGHT < LIST_TOP || y > listBottom) {
                continue;
            }
            PluginUiBridge.Entry entry = entries.get(i);
            boolean selectedRow = selected == i;
            if (selectedRow) {
                graphics.fill(16, y, this.width - 16, y + ROW_HEIGHT - 2, VerityUiTheme.ROW_HOVER);
            }
            String label = (entry.enabled() ? "§a" : "§7") + entry.name()
                    + " §8" + entry.id() + " §7· " + entry.trigger()
                    + " · " + entry.actions() + " 动作";
            graphics.drawString(this.font, label, 22, y + 7, 0xFFFFFF);

            boolean canManage = entry.mine();
            drawButton(graphics, startX, y + 3, TOGGLE_W, 18,
                    entry.enabled() ? "停用" : "启用", canManage, mouseX, mouseY);
            drawButton(graphics, startX + TOGGLE_W + 4, y + 3, DETAIL_W, 18,
                    selectedRow ? "收起" : "详情", true, mouseX, mouseY);
            drawButton(graphics, startX + TOGGLE_W + DETAIL_W + 8, y + 3, DELETE_W, 18,
                    pendingDelete == i ? "确认?" : "删除", canManage, mouseX, mouseY);
        }
        graphics.disableScissor();

        if (selected >= 0 && selected < entries.size()) {
            renderDetail(graphics, entries.get(selected));
        }
        if (!status.isBlank()) {
            graphics.drawString(this.font, status, 20, this.height - 42, 0xFFFF80);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderDetail(GuiGraphics graphics, PluginUiBridge.Entry entry) {
        int y = this.height - 58;
        graphics.drawString(this.font, "§f" + entry.name() + " §8(" + entry.id() + ")", 20, y, 0xFFFFFF);
        String description = entry.description().isBlank() ? "（没有描述）" : entry.description();
        graphics.drawString(this.font, "§7" + trim(description, 96), 20, y + 11, 0xA0A0A0);
        graphics.drawString(this.font, "§7触发器 §f" + entry.trigger()
                + "   §7条件 §f" + entry.conditions()
                + "   §7动作 §f" + entry.actions(), 20, y + 22, 0xA0A0A0);
    }

    private void drawButton(GuiGraphics graphics, int x, int y, int width, int height,
                            String label, boolean active, int mouseX, int mouseY) {
        boolean hovered = active && mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        int background = !active ? 0x40202020 : hovered ? 0x80FFFFFF : 0x60000000;
        graphics.fill(x, y, x + width, y + height, background);
        int color = active ? 0xFFFFFF : 0x808080;
        graphics.drawCenteredString(this.font, label, x + width / 2, y + 5, color);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        List<PluginUiBridge.Entry> entries = PluginUiBridge.entries();
        int listBottom = this.height - 62;
        int startX = this.width - 20 - BUTTONS_WIDTH;
        for (int i = 0; i < entries.size(); i++) {
            int y = LIST_TOP + i * ROW_HEIGHT - scroll;
            if (y < LIST_TOP || y + ROW_HEIGHT > listBottom) {
                continue;
            }
            PluginUiBridge.Entry entry = entries.get(i);
            if (inside(mouseX, mouseY, startX, y + 3, TOGGLE_W, 18)) {
                if (entry.mine() && connected()) {
                    PluginNetwork.sendToggle(entry.id(), !entry.enabled());
                    status = "已请求" + (entry.enabled() ? "停用 " : "启用 ") + entry.id();
                }
                return true;
            }
            if (inside(mouseX, mouseY, startX + TOGGLE_W + 4, y + 3, DETAIL_W, 18)) {
                selected = selected == i ? -1 : i;
                return true;
            }
            if (inside(mouseX, mouseY, startX + TOGGLE_W + DETAIL_W + 8, y + 3, DELETE_W, 18)) {
                if (!entry.mine() || !connected()) {
                    return true;
                }
                if (pendingDelete == i) {
                    PluginNetwork.sendDelete(entry.id());
                    status = "已请求删除 " + entry.id();
                    pendingDelete = -1;
                } else {
                    pendingDelete = i;
                    status = "再点一次「确认?」才会真的删除 " + entry.id();
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int contentHeight = PluginUiBridge.entries().size() * ROW_HEIGHT;
        int viewHeight = (this.height - 62) - LIST_TOP;
        int max = Math.max(0, contentHeight - viewHeight);
        scroll = (int) Math.max(0, Math.min(max, scroll - delta * 14));
        return true;
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }

    private static String trim(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars - 1) + "…";
    }
}
