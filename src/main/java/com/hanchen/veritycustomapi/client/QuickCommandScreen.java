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
public final class QuickCommandScreen extends HoldActionWheelScreen {
    private static final int SURFACE = VerityUiTheme.SURFACE;
    private static final int SEPARATOR = VerityUiTheme.SEPARATOR;
    private static final int TEXT = VerityUiTheme.TEXT;
    private static final int MUTED = VerityUiTheme.MUTED;
    private static final int ACCENT = VerityUiTheme.ACCENT;
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 5;
    private static final int GROUP_SIZE = 6;
    private int wheelPage=1;
    private int wheelRadius,centerX,centerY;
    private List<QuickWheelLayout.Span> wheelSpans=List.of();
    private static final String[] SHORT_LABELS={"看看我","我的背包","附近有什么","随便聊聊","夸夸我","给点建议","跟我来","停止跟随","到我身边","对话记录","写日记","定时提醒"};

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

    public QuickCommandScreen(){this(false);}
    public QuickCommandScreen(boolean hold){super("快捷指令",KeyBindings.QUICK_COMMAND_KEY,hold);}
    @Override protected String label(int sector){return wheelPage<2?SHORT_LABELS[wheelPage*6+sector]:new String[]{"动画控制","设置","资源中心","操作按键","对话记录","清除恶魔"}[sector];}
    @Override protected void choose(int sector){
        if(wheelPage<2){run(ENTRIES.get(wheelPage*6+sector));return;}
        switch(sector){
            case 0->minecraft.setScreen(new MmdPlaybackScreen(null));
            case 1->minecraft.setScreen(new com.hanchen.veritycustomapi.config.NativeOllamaProxyConfigScreen(null));
            case 2->minecraft.setScreen(new com.hanchen.veritycustomapi.config.ResourceCenterScreen(null));
            case 3->minecraft.setScreen(new ConversationLogScreen(null));
            case 4->minecraft.setScreen(new net.minecraft.client.gui.screens.controls.ControlsScreen(null,minecraft.options));
            case 5->minecraft.setScreen(new com.hanchen.veritycustomapi.config.VerityConfirmScreen(null,yes->{minecraft.setScreen(null);if(yes)killAllDemons();},Component.literal("清除所有恶魔？"),Component.literal("此操作无法撤销。")));
            default->{}
        }
    }
    @Override protected String pageName(){return new String[]{"对话","行动","工具"}[wheelPage];}
    @Override protected void changePage(int direction){wheelPage=(wheelPage+direction+3)%3;}

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

}
