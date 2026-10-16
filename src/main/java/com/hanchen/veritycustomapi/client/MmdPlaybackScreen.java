package com.hanchen.veritycustomapi.client;

import net.minecraft.client.gui.components.Button;
import com.hanchen.veritycustomapi.config.VerityUiWidgets;
import com.hanchen.veritycustomapi.config.VerityUiDraw;
import com.hanchen.veritycustomapi.config.VerityUiTheme;
import com.hanchen.veritycustomapi.mmd.MmdDanceProject;
import com.hanchen.veritycustomapi.mmd.MmdPlaybackManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class MmdPlaybackScreen extends Screen {
    private static final int SURFACE = VerityUiTheme.SURFACE;
    private static final int SEPARATOR = VerityUiTheme.SEPARATOR;
    private static final int BORDER = VerityUiTheme.BORDER;
    private static final int TEXT = VerityUiTheme.TEXT;
    private static final int MUTED = VerityUiTheme.MUTED;
    private static final int ACCENT = VerityUiTheme.ACCENT;
    private static final int SUCCESS = VerityUiTheme.SUCCESS;
    private static final int WARNING = VerityUiTheme.WARNING;
    private static final int DANGER = VerityUiTheme.DANGER;
    private static final int ROW = 0x222A3038;
    private static final int PLAYING = 0xFF244756;
    private static final int MOTION = 0xFF78C6E8;
    private static final int CAMERA = 0xFFC792EA;
    private static final int AUDIO = 0xFF89D185;
    private static final int ITEM_HEIGHT = 76;

    private final Screen parent;
    private final MmdPlaybackManager manager = MmdPlaybackManager.get();
    private final List<ProjectItem> projects = new ArrayList<>();
    private final List<Button> itemButtons = new ArrayList<>();
    private int scroll;
    private String status = "在下方选择一个项目，即可让当前世界内的 Demon Mode 同步起舞";
    private int statusColor = ACCENT;
    /** 待二次确认删除的项目名；非 null 表示 UI 上"再按一次"阶段。 */
    private String pendingDeleteProject;
    /** pendingDeleteProject 失效时间（ms）。过期则自动清理。 */
    private long confirmExpireAt;

    public MmdPlaybackScreen(Screen parent) {
        super(Component.literal("MMD 动画控制"));
        this.parent = parent;
        reloadCachedClips();
    }

    @Override
    protected void init() {
        clearWidgets();
        itemButtons.clear();
        int left = panelLeft();
        int right = panelRight();
        int innerWidth = right - left - 24;
        boolean compact = compactToolbar();
        int y = 43;
        int actionWidth = Math.max(48, Math.min(72, (innerWidth - 12) / 4));

        addRenderableWidget(VerityUiWidgets.secondaryButton(font, left + 12, y, actionWidth, 20,
                    Component.literal("重新扫描"), button -> rescan()));
        addRenderableWidget(VerityUiWidgets.secondaryButton(font, left + 16 + actionWidth, y, actionWidth, 20,
                    Component.literal("停止全部"), button -> stopAll()));
        addRenderableWidget(VerityUiWidgets.secondaryButton(font, left + 20 + actionWidth * 2, y, actionWidth, 20,
                    Component.literal("删除恶魔"), button -> removeSpawnedDemons()));
        int freezeWidth = compact ? Math.max(64, innerWidth - actionWidth * 3 - 12) : 104;
        addRenderableWidget(VerityUiWidgets.secondaryButton(font, left + 24 + actionWidth * 3, y, freezeWidth, 20,
                    freezeLabel(), button -> toggleFreeze()));

        int speedY = compact ? 67 : y;
        int speedX = compact ? left + 12 : right - 140;
        addRenderableWidget(VerityUiWidgets.secondaryButton(font, speedX, speedY, 24, 20,
                    Component.literal("-"), button -> changeSpeed(-0.25f)));
        addRenderableWidget(VerityUiWidgets.secondaryButton(font, speedX + 28, speedY, 72, 20,
                    speedLabel(), button -> resetSpeed()));
        addRenderableWidget(VerityUiWidgets.secondaryButton(font, speedX + 104, speedY, 24, 20,
                    Component.literal("+"), button -> changeSpeed(0.25f)));
        addRenderableWidget(VerityUiWidgets.secondaryButton(font, right - 62, height - 29, 50, 20,
                    Component.literal("返回"), button -> onClose()));
        rebuildItemButtons();
    }

    private void removeSpawnedDemons() {
        int n = manager.removeSpawnedDemons();
        if (n > 0) {
            status = "已删除 " + n + " 个由本 mod 自动生成的恶魔";
            statusColor = SUCCESS;
            if (minecraft != null && minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.literal("§a[MMD] §r已删除 " + n + " 个由本 mod 自动生成的恶魔"), false);
            }
        } else {
            status = "当前没有由本 mod 自动生成的恶魔需要清理";
            statusColor = MUTED;
        }
    }

    private void deleteProject(ProjectItem item) {
        // 双击确认：第一次按下变红色"再按一次删除"
        if (pendingDeleteProject != null && pendingDeleteProject.equals(item.name())) {
            boolean ok = manager.deleteProject(item.name());
            if (ok) {
                status = "已删除舞蹈项目「" + item.name() + "」";
                statusColor = SUCCESS;
                if (minecraft != null && minecraft.player != null) {
                    minecraft.player.displayClientMessage(
                            Component.literal("§a[MMD] §r已删除项目文件夹 " + item.name()), false);
                }
                pendingDeleteProject = null;
                reloadCachedClips();
                scroll = Math.max(0, Math.min(scroll, Math.max(0, projects.size() - visibleRows())));
                init(minecraft, width, height);
            } else {
                status = "删除项目「" + item.name() + "」失败，请查看日志";
                statusColor = DANGER;
            }
            return;
        }
        pendingDeleteProject = item.name();
        status = "再次点击「删除」以确认删除项目「" + item.name() + "」及其全部文件";
        statusColor = WARNING;
        // 5 秒后自动取消待确认状态
        confirmExpireAt = System.currentTimeMillis() + 5_000L;
        init(minecraft, width, height);
    }

    private void reloadCachedClips() {
        projects.clear();
        manager.listProjects().forEach((name, project) -> projects.add(new ProjectItem(name, project)));
        projects.sort(Comparator.comparing(ProjectItem::name, String.CASE_INSENSITIVE_ORDER));
    }

    private void rescan() {
        status = "正在扫描 MMD 项目…";
        statusColor = ACCENT;
        manager.scanAndLoad();
        reloadCachedClips();
        scroll = 0;
        status = projects.isEmpty() ? "未发现舞蹈项目，请在 config/verity_extended/mmd/ 中创建项目文件夹"
                : "扫描完成，共识别到 " + projects.size() + " 个舞蹈项目";
        statusColor = projects.isEmpty() ? WARNING : SUCCESS;
        init(minecraft, width, height);
    }

    private void play(ProjectItem item) {
        if (minecraft == null || minecraft.level == null) {
            status = "当前未进入世界，无法播放";
            statusColor = DANGER;
            return;
        }
        int count = manager.startPlaybackForDemons(minecraft.level, item.name());
        if (count > 0) {
            status = "正在播放「" + item.name() + "」，已应用到 " + count + " 个 Demon Mode"
                    + (item.project().cameraClip() == null ? "；未找到镜头，仅播放动作" : "");
            statusColor = item.project().cameraClip() == null ? WARNING : SUCCESS;
        } else if (item.name().equals(manager.getPendingProject())) {
            status = "附近没有 Demon Mode，已向服务器请求自动生成一只（请等待数秒）";
            statusColor = ACCENT;
        } else {
            status = "附近没有可播放的 Demon Mode，且自动生成未启用（请检查配置菜单）";
            statusColor = WARNING;
        }
    }

    private void stopAll() {
        manager.stopPlaybackForAllDemons();
        status = "已停止全部 MMD 动画，Demon Mode 的 AI 行为已恢复";
        statusColor = SUCCESS;
    }

    private void toggleFreeze() {
        manager.setFreezeOnPlay(!manager.isFreezeOnPlay());
        status = manager.isFreezeOnPlay() ? "下次播放时将冻结 Demon Mode 的 AI 行为" : "下次播放时将保留 Demon Mode 的 AI 行为";
        statusColor = ACCENT;
        init(minecraft, width, height);
    }

    private void changeSpeed(float delta) {
        manager.setPlaybackSpeed(Math.max(0.1f, Math.min(10.0f, manager.getPlaybackSpeed() + delta)));
        status = "播放速度已调整为 " + speedText();
        statusColor = ACCENT;
        init(minecraft, width, height);
    }

    private void resetSpeed() {
        manager.setPlaybackSpeed(1.0f);
        status = "播放速度已恢复为默认的 1.00x";
        statusColor = ACCENT;
        init(minecraft, width, height);
    }

    private void rebuildItemButtons() {
        for (Button button : itemButtons) removeWidget(button);
        itemButtons.clear();
        int rows = visibleRows();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, projects.size() - rows)));
        // 自动清理过期未确认的待删除项目
        if (pendingDeleteProject != null && System.currentTimeMillis() > confirmExpireAt) {
            pendingDeleteProject = null;
        }
        for (int i = scroll; i < Math.min(projects.size(), scroll + rows); i++) {
            ProjectItem item = projects.get(i);
            int y = listTop() + (i - scroll) * ITEM_HEIGHT;
            // 播放按钮
            Button playBtn = VerityUiWidgets.secondaryButton(font, panelRight() - 70, y + 27, 52, 20,
                    Component.literal("播放"), ignored -> play(item));
            itemButtons.add(playBtn);
            addRenderableWidget(playBtn);
            // 删除按钮（双击确认），放在播放按钮下方
            boolean isPending = item.name().equals(pendingDeleteProject);
            Button delBtn = VerityUiWidgets.secondaryButton(font, panelRight() - 70, y + 49, 52, 20,
                    Component.literal(isPending ? "再按一次" : "删除"), ignored -> deleteProject(item));
            itemButtons.add(delBtn);
            addRenderableWidget(delBtn);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX < panelLeft() || mouseX > panelRight() || mouseY < listTop() || mouseY > listBottom()) {
            return super.mouseScrolled(mouseX, mouseY, delta);
        }
        int previous = scroll;
        int max = Math.max(0, projects.size() - visibleRows());
        scroll = Math.max(0, Math.min(scroll - (int) Math.signum(delta), max));
        if (scroll != previous) rebuildItemButtons();
        return scroll != previous;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 半透明：世界透得出来；主菜单打开时按普通菜单背景处理
        ScreenBackgrounds.draw(this, graphics);
        int left = panelLeft();
        int right = panelRight();
        VerityUiDraw.card(graphics, left, 8, right, height - 8, 5);
        graphics.drawString(font, title, left + 12, 17, TEXT);
        graphics.drawString(font, fit("按 N 可重新打开此界面 · 关闭不会中断播放 · 速度与冻结设置会被保留",
                right - left - 24), left + 12, 30, MUTED);

        for (int i = scroll; i < Math.min(projects.size(), scroll + visibleRows()); i++) {
            ProjectItem item = projects.get(i);
            MmdDanceProject project = item.project();
            int y = listTop() + (i - scroll) * ITEM_HEIGHT;
            boolean playing = item.name().equals(manager.getCurrentProjectId());
            graphics.fill(left + 12, y, right - 12, y + ITEM_HEIGHT - 4, playing ? PLAYING : ROW);
            int textWidth = Math.max(80, right - left - 120);
            String kind = project.legacy() ? "旧格式" : project.manifestConfigured() ? "清单项目" : "自动识别";
            graphics.drawString(font, fit(project.displayName() + " [" + kind + "]" + (playing ? "  正在播放" : ""), textWidth), left + 20, y + 6, TEXT);
            String motions = "动作×" + project.motions().size() + ": " + project.motions().stream()
                    .map(asset -> asset.file().getFileName().toString()).reduce((a, b) -> a + ", " + b).orElse("无");
            graphics.drawString(font, fit(motions, textWidth), left + 20, y + 20, MOTION);
            graphics.drawString(font, fit("镜头: " + (project.cameraFile() == null ? "无" : project.cameraFile().getFileName())
                    + "  音频: " + (project.audioFile() == null ? "无" : project.audioFile().getFileName()), textWidth), left + 20, y + 34,
                    project.audioFile() == null ? CAMERA : AUDIO);
            graphics.drawString(font, fit("文件: " + project.fileListSummary(), textWidth), left + 20, y + 48, MUTED);
            graphics.drawString(font, String.format(Locale.ROOT, "%.2f 秒 · %s循环", project.durationSeconds(),
                    project.loopMode() == MmdDanceProject.LoopMode.INDEPENDENT ? "独立" : "整体"), left + 20, y + 62, MUTED);
            if (playing) {
                int progressLeft = left + 180;
                int progressRight = right - 88;
                int progressY = y + 64;
                graphics.fill(progressLeft, progressY,
                        progressLeft + Math.round((progressRight - progressLeft) * manager.getCurrentProgress()), progressY + 3, ACCENT);
            }
        }
        if (projects.isEmpty()) {
            graphics.drawCenteredString(font, "还没有舞蹈项目，请在 config/verity_extended/mmd/ 中创建文件夹并放入动作、镜头与音乐文件", width / 2, listTop() + 32, MUTED);
        }
        graphics.drawString(font, fit(status, Math.max(80, right - left - 90)), left + 12, height - 23, statusColor);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private Component freezeLabel() {
        return Component.literal("冻结 AI：" + (manager.isFreezeOnPlay() ? "开" : "关"));
    }

    private Component speedLabel() {
        return Component.literal(speedText());
    }

    private String speedText() {
        return String.format(Locale.ROOT, "%.2fx", manager.getPlaybackSpeed());
    }

    private int panelLeft() {
        return Math.max(8, (width - Math.min(760, width - 16)) / 2);
    }

    private int panelRight() {
        return Math.min(width - 8, panelLeft() + 760);
    }

    private boolean compactToolbar() {
        return panelRight() - panelLeft() < 620;
    }

    private int toolbarBottom() {
        return compactToolbar() ? 94 : 70;
    }

    private int listTop() {
        return toolbarBottom() + 8;
    }

    private int listBottom() {
        return height - 38;
    }

    private int visibleRows() {
        return Math.max(1, (listBottom() - listTop()) / ITEM_HEIGHT);
    }

    private String fit(String value, int maxWidth) {
        return font.plainSubstrByWidth(value == null ? "" : value, maxWidth);
    }

    private record ProjectItem(String name, MmdDanceProject project) { }
}
