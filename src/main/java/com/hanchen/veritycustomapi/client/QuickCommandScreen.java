package com.hanchen.veritycustomapi.client;

import com.hanchen.veritycustomapi.util.Silent;

import com.hanchen.veritycustomapi.config.VerityUiWidgets;
import com.hanchen.veritycustomapi.config.VerityUiDraw;
import com.hanchen.veritycustomapi.config.VerityUiTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.network.PacketDistributor;

import java.util.List;

/**
 * 快捷指令面板：常用话术做成按钮。普通按钮直接发聊天给 Verity，
 * 命令按钮执行 /verity-ai（跟随、导航、打开记录等本地动作）。
 */
public final class QuickCommandScreen extends Screen {
    private static final int SURFACE = VerityUiTheme.SURFACE;
    private static final int SEPARATOR = VerityUiTheme.SEPARATOR;
    private static final int TEXT = VerityUiTheme.TEXT;
    private static final int MUTED = VerityUiTheme.MUTED;
    private static final int ACCENT = VerityUiTheme.ACCENT;
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 5;
    private static final int GROUP_SIZE = 6;

    /** @param command true 表示执行命令，false 表示发送聊天内容 */
    private record Entry(String label, boolean command, String payload) {
    }

    private static final List<Entry> ENTRIES = List.of(
            new Entry("看看我", false, "看看我现在在做什么"),
            new Entry("我的背包", false, "我背包里现在有什么？"),
            new Entry("附近有什么", false, "我附近有什么值得注意的吗？"),
            new Entry("随便聊聊", false, "随便和我聊点什么吧"),
            new Entry("夸夸我", false, "夸夸我"),
            new Entry("给我点建议", false, "接下来我该做什么？给我点建议"),
            new Entry("跟我来", true, "verity-nav follow"),
            new Entry("别跟着了", true, "verity-nav stop"),
            new Entry("到我身边", true, "verity-nav come"),
            new Entry("对话记录", true, "verity-ai log"),
            new Entry("写一篇日记", true, "verity-ai diary"),
            new Entry("定时提醒", true, "verity-ai remind-help")
    );

    public QuickCommandScreen() {
        super(Component.literal("Verity 快捷指令"));
    }

    @Override
    protected void init() {
        clearWidgets();
        int left = panelLeft();
        int innerWidth = panelRight() - left - 24;
        int buttonWidth = Math.max(48, (innerWidth - 8) / 2);
        int footerTop = height - 30;
        // 标题与分栏线随窗口高度上移，给按钮区留出空间
        int headerTop = height < 240 ? 42 : 58;
        // 12 个条目分两列共 GROUP_SIZE 行：按可用高度自适应行距与按钮高度，
        // 避免窗口变矮时最后一行压到底部按钮上（旧实现是固定 25px 步进）。
        int rows = GROUP_SIZE;
        int usable = Math.max(rows * 12, footerTop - 8 - headerTop);
        int step = Math.max(12, Math.min(BUTTON_HEIGHT + GAP, usable / rows));
        int buttonHeight = Math.max(11, Math.min(BUTTON_HEIGHT, step - 2));
        for (int i = 0; i < ENTRIES.size(); i++) {
            Entry entry = ENTRIES.get(i);
            int column = i / GROUP_SIZE;
            int row = i % GROUP_SIZE;
            addRenderableWidget(VerityUiWidgets.secondaryButton(font, left + 12 + column * (buttonWidth + 8), headerTop + row * step, buttonWidth, buttonHeight,
                    Component.literal(entry.label()), button -> run(entry)));
        }
        addRenderableWidget(VerityUiWidgets.dangerButton(font, left + 12, footerTop,
                Math.min(140, innerWidth - 76), 20,
                Component.literal("杀死所有恶魔"), button -> killAllDemons()));
        addRenderableWidget(VerityUiWidgets.secondaryButton(font, panelRight() - 72, footerTop, 60, 20,
                    Component.literal("关闭"), button -> onClose()));
    }

    private void killAllDemons() {
        Minecraft minecraft = Minecraft.getInstance();
        onClose();
        if (minecraft.player == null) return;
        try {
            com.hanchen.veritycustomapi.network.MmdDemonNetwork.CHANNEL.send(
                    PacketDistributor.SERVER.noArg(),
                    new com.hanchen.veritycustomapi.network.KillAllDemonsPayload());
        } catch (Throwable error) {
            minecraft.player.sendSystemMessage(Component.literal("§c无法发送杀死恶魔请求"));
        }
    }

    private void run(Entry entry) {
        Minecraft minecraft = Minecraft.getInstance();
        onClose();
        if (minecraft.player == null) {
            return;
        }
        try {
            if (entry.command()) {
                // verity-nav 是服务端指令：服务器没装本 mod 时不存在，
                // 直接发过去只会得到一句「未知的指令」，不如自己给个明确说明。
                if (entry.payload().startsWith("verity-nav") && !hasServerCommand(minecraft, "verity-nav")) {
                    minecraft.player.displayClientMessage(Component.literal(
                            "§7【导航】服务器没有安装 Verity 扩展增强，这个功能需要服务端也装本 mod。"), false);
                    return;
                }
                minecraft.player.connection.sendCommand(entry.payload());
            } else {
                com.hanchen.veritycustomapi.client.VerityChatSender.sendToVerity(entry.payload());
            }
        } catch (Throwable ignored) { Silent.ignore("QuickCommandScreen", ignored); }
    }

    /** 服务器命令表里是否存在某个指令。 */
    private static boolean hasServerCommand(Minecraft minecraft, String name) {
        try {
            var commands = minecraft.player.connection.getCommands();
            return commands != null && commands.getRoot().getChild(name) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 半透明：世界透得出来；主菜单打开时按普通菜单背景处理
        ScreenBackgrounds.draw(this, graphics);
        int left = panelLeft();
        int right = panelRight();
        VerityUiDraw.card(graphics, left, 8, right, height - 8, 5);
        graphics.drawString(font, title, left + 12, 17, TEXT);
        // 分栏线跟随 init() 里的自适应 headerTop，避免小窗口下标题与按钮错位
        int headerTop = height < 240 ? 42 : 58;
        int sectionY = headerTop - 15;
        graphics.drawString(font, "对话", left + 12, sectionY, MUTED);
        graphics.drawString(font, "行动", left + 20 + (right - left - 32) / 2, sectionY, MUTED);
        graphics.fill(left + 12, headerTop - 5, right - 12, headerTop - 4, SEPARATOR);
        graphics.fill(left + 12, headerTop - 5, left + 54, headerTop - 3, ACCENT);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private int panelLeft() {
        return Math.max(8, (width - Math.min(420, width - 16)) / 2);
    }

    private int panelRight() {
        return width - panelLeft();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
