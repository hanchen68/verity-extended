package com.hanchen.veritycustomapi.client;

import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.cloud.CloudBackupClient;
import com.hanchen.veritycustomapi.config.VerityUiDraw;
import com.hanchen.veritycustomapi.config.VerityUiTheme;
import com.hanchen.veritycustomapi.config.VerityUiWidgets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * 对话/日记云端备份：上传当前导出 JSON，或拉取云端最近一次备份并合并到本地。
 *
 * <p>上传必须由玩家主动点击；不做自动后台上传，避免聊天隐私在不知情时离开本机。
 */
public final class VerityCloudBackupScreen extends Screen {
    private final ConversationLogScreen parent;
    private String status;
    private boolean error;
    private boolean busy;
    private Button uploadButton;
    private Button restoreButton;

    public VerityCloudBackupScreen(ConversationLogScreen parent) {
        super(Component.literal("云端备份"));
        this.parent = parent;
        boolean ready = ConfigManager.isCloudEnabled() && !ConfigManager.getCloudApiKey().isBlank();
        this.status = ready
                ? "会把当前存档的对话与日记上传到当前云端账号，或恢复最近一次备份。"
                : "请先在「云端与账户」完成授权并开启云端。";
        this.error = !ready;
    }

    @Override
    protected void init() {
        int y = height - 40;
        int uploadWidth = 110;
        int restoreWidth = 110;
        int backWidth = 50;
        int gap = 6;
        int total = uploadWidth + restoreWidth + backWidth + gap * 2;
        int x = (width - total) / 2;
        boolean ready = ConfigManager.isCloudEnabled() && !ConfigManager.getCloudApiKey().isBlank();
        uploadButton = addRenderableWidget(VerityUiWidgets.secondaryButton(font, x, y, uploadWidth, 20,
                Component.literal("上传当前记录"), button -> upload()));
        uploadButton.active = ready && !busy;
        x += uploadWidth + gap;
        restoreButton = addRenderableWidget(VerityUiWidgets.secondaryButton(font, x, y, restoreWidth, 20,
                Component.literal("恢复最近备份"), button -> restore()));
        restoreButton.active = ready && !busy;
        x += restoreWidth + gap;
        Button back = addRenderableWidget(VerityUiWidgets.secondaryButton(font, x, y, backWidth, 20,
                Component.literal("返回"), button -> onClose()));
        back.active = !busy;
    }

    private void upload() {
        if (busy) return;
        if (!ConfigManager.isCloudEnabled() || ConfigManager.getCloudApiKey().isBlank()) {
            status = "请先在「云端与账户」完成授权并开启云端。";
            error = true;
            return;
        }
        List<ConversationLogStore.Entry> conversations = parent.allConversationsForBackup();
        List<DiaryManager.Entry> diaries = parent.allDiariesForBackup();
        String json = VerityExportService.buildExportJson(conversations, diaries);
        busy = true;
        error = false;
        status = "正在上传对话 " + conversations.size() + " 条、日记 " + diaries.size() + " 篇…";
        rebuildButtons();
        new Thread(() -> {
            CloudBackupClient.UploadResult result;
            try {
                result = new CloudBackupClient().upload(json);
            } catch (Throwable failure) {
                result = new CloudBackupClient.UploadResult(false, 0L,
                        "上传失败: " + safe(failure));
            }
            CloudBackupClient.UploadResult finalResult = result;
            Minecraft.getInstance().execute(() -> {
                busy = false;
                error = !finalResult.success();
                status = finalResult.success()
                        ? "上传成功：对话 " + conversations.size() + " 条、日记 " + diaries.size() + " 篇"
                        : finalResult.message();
                if (Minecraft.getInstance().screen == this) {
                    init(Minecraft.getInstance(), width, height);
                }
            });
        }, "Verity-Cloud-Backup").start();
    }

    private void restore() {
        if (busy) return;
        if (!ConfigManager.isCloudEnabled() || ConfigManager.getCloudApiKey().isBlank()) {
            status = "请先在「云端与账户」完成授权并开启云端。";
            error = true;
            return;
        }
        busy = true;
        error = false;
        status = "正在读取云端最近一次备份…";
        rebuildButtons();
        new Thread(() -> {
            CloudBackupClient.DownloadResult download;
            VerityExportService.ImportResult imported = null;
            try {
                download = new CloudBackupClient().downloadLatest();
                if (download.success()) {
                    imported = VerityExportService.importJson(download.json());
                }
            } catch (Throwable failure) {
                download = new CloudBackupClient.DownloadResult(false, null, 0L,
                        "读取备份失败: " + safe(failure));
            }
            CloudBackupClient.DownloadResult finalDownload = download;
            VerityExportService.ImportResult finalImported = imported;
            Minecraft.getInstance().execute(() -> {
                busy = false;
                if (finalDownload.success() && finalImported != null) {
                    boolean empty = finalImported.conversations() == 0 && finalImported.diaries() == 0;
                    error = empty && finalImported.message().contains("没有");
                    status = finalImported.message();
                    if (!empty) {
                        parent.refreshAfterImport();
                    }
                } else {
                    error = true;
                    status = finalDownload.message();
                }
                if (Minecraft.getInstance().screen == this) {
                    init(Minecraft.getInstance(), width, height);
                }
            });
        }, "Verity-Cloud-Restore").start();
    }

    private void rebuildButtons() {
        if (uploadButton != null) uploadButton.active = !busy;
        if (restoreButton != null) restoreButton.active = !busy;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ScreenBackgrounds.draw(this, graphics);
        int left = panelLeft();
        int right = panelRight();
        VerityUiDraw.card(graphics, left, 8, right, height - 8, 5);
        graphics.drawString(font, "云端备份", left + 14, 16, VerityUiTheme.TEXT);
        graphics.drawString(font, "对话与日记", left + 14, 27, VerityUiTheme.MUTED);

        int textLeft = left + 14;
        int textWidth = right - left - 28;
        int y = 48;
        y = drawParagraph(graphics, "上传：把当前存档的全部对话和日记打包上传到当前云端账号。",
                textLeft, y, textWidth);
        y = drawParagraph(graphics, "恢复：读取云端最近一次备份并按时间/正文去重合并到本地。",
                textLeft, y, textWidth);
        drawParagraph(graphics, "聊天记录属于隐私数据；只有点击上传才会发送，不会自动后台上传。",
                textLeft, y + 2, textWidth);

        if (!status.isBlank()) {
            int statusY = height - 88;
            for (FormattedCharSequence line : font.split(Component.literal(status), textWidth)) {
                if (statusY > height - 48) break;
                graphics.drawString(font, line, textLeft, statusY,
                        error ? VerityUiTheme.DANGER : VerityUiTheme.ACCENT);
                statusY += 10;
            }
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private int drawParagraph(GuiGraphics graphics, String text, int x, int y, int width) {
        for (FormattedCharSequence line : font.split(Component.literal(text), width)) {
            graphics.drawString(font, line, x, y, VerityUiTheme.MUTED);
            y += 10;
        }
        return y + 3;
    }

    private int panelLeft() {
        return Math.max(8, (width - Math.min(620, width - 16)) / 2);
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

    private static String safe(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        return message == null || message.isBlank() ? "网络异常" : message;
    }
}
