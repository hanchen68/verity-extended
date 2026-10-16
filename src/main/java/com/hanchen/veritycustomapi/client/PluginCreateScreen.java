package com.hanchen.veritycustomapi.client;

import com.hanchen.veritycustomapi.config.VerityUiWidgets;
import com.hanchen.veritycustomapi.config.VerityUiDraw;
import com.hanchen.veritycustomapi.config.VerityUiTheme;
import com.hanchen.veritycustomapi.network.PluginNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** 新建插件：写一句话，交给云端 AI 变成插件。 */
@OnlyIn(Dist.CLIENT)
public class PluginCreateScreen extends Screen {

    private final Screen parent;
    private EditBox input;
    private String status = "";

    public PluginCreateScreen(Screen parent) {
        super(Component.literal("新建插件"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        input = VerityUiWidgets.roundedEditBox(font, centerX - 150, 74, 300, 20,
                Component.literal("功能描述"));
        input.setMaxLength(200);
        addRenderableWidget(input);
        setInitialFocus(input);
        addRenderableWidget(VerityUiWidgets.secondaryButton(font, centerX - 84, 104, 80, 20,
                    Component.literal("生成"), button -> submit()));
        addRenderableWidget(VerityUiWidgets.secondaryButton(font, centerX + 4, 104, 80, 20,
                    Component.literal("返回"), button -> this.onClose()));
    }

    private void submit() {
        String text = input == null ? "" : input.getValue().trim();
        if (text.isEmpty()) {
            status = "§c先写一句描述再点生成";
            return;
        }
        // 主菜单下没有连接，发包会崩客户端
        if (this.minecraft == null || this.minecraft.getConnection() == null) {
            status = "§c请先进入一个世界，再来生成插件";
            return;
        }
        PluginNetwork.sendCreate(text);
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 与管理界面一致：半透明；主菜单打开时按普通菜单背景处理
        ScreenBackgrounds.draw(this, graphics);
        int centerX = this.width / 2;
        // 与其它界面统一：圆角卡片承载内容，草稿区在卡片内
        int cardLeft = Math.max(20, centerX - 180);
        int cardRight = Math.min(this.width - 20, centerX + 180);
        VerityUiDraw.card(graphics, cardLeft, 18, cardRight, this.height - 18, 5);
        graphics.drawCenteredString(this.font, this.title, centerX, 30, VerityUiTheme.TEXT);
        graphics.drawCenteredString(this.font, "用一句话描述你想要的功能，系统会把它变成插件",
                centerX, 46, VerityUiTheme.MUTED);
        graphics.drawCenteredString(this.font, "例如：挖矿的时候自动整理背包 / 血量低于三颗心时给我一瓶药水",
                centerX, 58, VerityUiTheme.MUTED);
        if (!status.isBlank()) {
            graphics.drawCenteredString(this.font, status, centerX, 132, VerityUiTheme.WARNING);
        }
        graphics.drawCenteredString(this.font,
                "能做：提示 / 音效 / 语音 / 给扣物品 / 整理背包 / 计时与条件判断", centerX, 152, VerityUiTheme.MUTED);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }
}
