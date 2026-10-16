package com.hanchen.veritycustomapi.config;

import com.hanchen.veritycustomapi.config.VerityUiWidgets;
import com.hanchen.veritycustomapi.config.VerityUiDraw;
import com.hanchen.veritycustomapi.debug.ConnectionTestHelper;
import com.hanchen.veritycustomapi.debug.DiagnosticBundle;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

public final class ConfigHealthCheckScreen extends Screen {
    private static final int BACKGROUND = VerityUiTheme.BACKGROUND;
    private static final int SURFACE = VerityUiTheme.SURFACE;
    private static final int BORDER = VerityUiTheme.BORDER;
    private static final int TEXT = VerityUiTheme.TEXT;
    private static final int MUTED = VerityUiTheme.MUTED;
    private static final int ACCENT = VerityUiTheme.ACCENT;
    private static final int SUCCESS = VerityUiTheme.SUCCESS;
    private static final int DANGER = VerityUiTheme.DANGER;
    private static final int WARNING = VerityUiTheme.WARNING;
    private final Screen parent;
    private final List<ConnectionTestHelper.TestResult> results = new ArrayList<>();
    private final AtomicLong generation = new AtomicLong();
    private boolean running;
    private int autoCloseTicks = -1;
    private Button retryButton;
    private CompletableFuture<Void> activeCheck;

    public ConfigHealthCheckScreen(Screen parent) {
        super(Component.literal("服务连接检查"));
        this.parent = parent;
        results.addAll(ConnectionTestHelper.initialResults());
    }

    @Override
    protected void init() {
        clearWidgets();
        int panelLeft = panelLeft();
        int panelRight = panelRight();
        int buttonY = height - 38;
        int gap = 8;
        int innerWidth = panelRight - panelLeft - 24;
        int buttonWidth = Math.min(112, Math.max(42, (innerWidth - gap * 2) / 3));
        int actionLeft = panelRight - 12 - buttonWidth * 3 - gap * 2;
        addRenderableWidget(VerityUiWidgets.secondaryButton(font, actionLeft, buttonY, buttonWidth, 20,
                    Component.literal("导出诊断包"), button -> exportBundle()));
        retryButton = addRenderableWidget(VerityUiWidgets.secondaryButton(font, actionLeft + buttonWidth + gap, buttonY, buttonWidth, 20,
                    Component.literal("重新检查"), button -> startCheck()));
        retryButton.active = !running;
        addRenderableWidget(VerityUiWidgets.secondaryButton(font, actionLeft + (buttonWidth + gap) * 2, buttonY, buttonWidth, 20,
                    Component.literal("关闭"), button -> onClose()));
        if (!running && results.stream().allMatch(result -> result.state() == ConnectionTestHelper.State.WAITING)) {
            startCheck();
        }
    }

    /** 导出诊断包：连接检查结果 + 脱敏配置 + 日志尾部。 */
    private void exportBundle() {
        java.nio.file.Path path = DiagnosticBundle.export(results);
        Component message = path == null
                ? Component.literal("导出失败，请查看 latest.log")
                : Component.literal("已导出到 " + path.getFileName());
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(
                    Component.literal(path == null ? "§c【诊断】" : "§b【诊断】").append(message), false);
        }
        if (minecraft != null) {
            SystemToast.add(minecraft.getToasts(), SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
                    Component.literal("诊断包"), message);
        }
    }

    private void startCheck() {
        if (running) return;
        long run = generation.incrementAndGet();
        running = true;
        if (retryButton != null) retryButton.active = false;
        autoCloseTicks = -1;
        results.clear();
        results.addAll(ConnectionTestHelper.initialResults());
        activeCheck = ConnectionTestHelper.testAll(result -> {
            if (minecraft == null) return;
            minecraft.execute(() -> {
                if (run != generation.get()) return;
                replace(result);
            });
        }).whenComplete((ignored, error) -> {
            if (minecraft == null) return;
            minecraft.execute(() -> {
                if (run != generation.get()) return;
                running = false;
                activeCheck = null;
                if (retryButton != null) retryButton.active = true;
                if (allSuccessful()) autoCloseTicks = 30;
            });
        });
    }

    private void replace(ConnectionTestHelper.TestResult result) {
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i).id().equals(result.id())) {
                results.set(i, result);
                return;
            }
        }
    }

    private boolean allSuccessful() {
        return results.stream().allMatch(result -> result.state() == ConnectionTestHelper.State.PASSED
                || result.state() == ConnectionTestHelper.State.SKIPPED);
    }

    @Override
    public void tick() {
        if (autoCloseTicks > 0 && --autoCloseTicks == 0) onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 半透明：世界里打开时透出世界；主菜单打开时走原版不透明背景
        com.hanchen.veritycustomapi.client.ScreenBackgrounds.draw(this, graphics);
        int left = panelLeft();
        int right = panelRight();
        int top = Math.max(12, (height - Math.min(300, height - 24)) / 2);
        int bottom = height - 10;
        VerityUiDraw.card(graphics, left, top, right, bottom, 5);
        graphics.drawString(font, "服务连接检查", left + 14, top + 14, TEXT);
        String summary = running ? "正在检查" : allSuccessful() ? "检查通过，窗口将自动关闭" : "检查未通过";
        graphics.drawString(font, font.plainSubstrByWidth(summary, right - left - 28), left + 14, top + 28,
                running ? ACCENT : allSuccessful() ? SUCCESS : DANGER);
        int completed = (int) results.stream().filter(result -> result.state() != ConnectionTestHelper.State.WAITING
                && result.state() != ConnectionTestHelper.State.RUNNING).count();
        int progressLeft = left + 14;
        int progressRight = right - 14;
        int total = Math.max(1, results.size());
        graphics.fill(progressLeft, top + 43, progressLeft + (progressRight - progressLeft) * completed / total, top + 46, ACCENT);
        int rowTop = top + 58;
        int available = Math.max(90, bottom - rowTop - 42);
        int rowHeight = Math.max(30, Math.min(44, available / 3));
        for (int i = 0; i < results.size(); i++) renderResult(graphics, results.get(i), left + 14, rowTop + i * rowHeight, right - left - 28, rowHeight);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void renderResult(GuiGraphics graphics, ConnectionTestHelper.TestResult result, int left, int top, int width, int height) {
        int bottom = top + height - 6;
        int color = stateColor(result.state());
        graphics.fill(left, top + 2, left + 2, bottom - 2, color);
        String state = stateText(result.state());
        int stateWidth = font.width(state);
        graphics.drawString(font, font.plainSubstrByWidth(result.name(), Math.max(30, width - stateWidth - 28)),
                left + 10, top + 4, TEXT);
        graphics.drawString(font, state, left + width - 8 - stateWidth, top + 4, color);
        if (height >= 36) {
            boolean builtinCloudApi = result.mode() != null && result.mode().startsWith("云端内置");
            String context = builtinCloudApi ? "云端内置" : result.mode();
            if (!builtinCloudApi && result.endpoint() != null && !result.endpoint().isBlank()) {
                context += "  ·  " + result.endpoint();
            }
            graphics.drawString(font, font.plainSubstrByWidth(context, Math.max(30, width - 18)),
                    left + 10, top + 17, MUTED);
        }
        if (height >= 44 && result.detail() != null && !result.detail().isBlank()) {
            graphics.drawString(font, font.plainSubstrByWidth(result.detail(), Math.max(30, width - 18)),
                    left + 10, top + 29, result.state() == ConnectionTestHelper.State.FAILED ? DANGER : MUTED);
        }
    }

    private int stateColor(ConnectionTestHelper.State state) {
        return switch (state) {
            case PASSED -> SUCCESS;
            case FAILED -> DANGER;
            case SKIPPED -> WARNING;
            case RUNNING -> ACCENT;
            default -> MUTED;
        };
    }

    private String stateText(ConnectionTestHelper.State state) {
        return switch (state) {
            case PASSED -> "通过";
            case FAILED -> "未通过";
            case SKIPPED -> "已跳过";
            case RUNNING -> "检查中";
            default -> "等待中";
        };
    }

    private int panelLeft() {
        return Math.max(8, (width - Math.min(720, width - 16)) / 2);
    }

    private int panelRight() {
        return width - panelLeft();
    }

    @Override
    public void onClose() {
        generation.incrementAndGet();
        if (activeCheck != null) activeCheck.cancel(true);
        activeCheck = null;
        running = false;
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
