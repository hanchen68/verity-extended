package com.hanchen.veritycustomapi.config;

import com.hanchen.veritycustomapi.debug.ConnectionTestHelper;
import com.hanchen.veritycustomapi.debug.ConnectionTestHelper.State;
import com.hanchen.veritycustomapi.debug.ConnectionTestHelper.TestResult;
import com.hanchen.veritycustomapi.debug.DiagnosticBundle;
import com.hanchen.veritycustomapi.debug.LiveServiceTest;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Live service test. Every row is a real request through the same code path the mod uses at
 * runtime; there is no separate "ping only" mode, because a reachable host says nothing about
 * whether the service can answer, recognize or speak.
 *
 * <p>Requests cost quota, so a round starts only after explicit confirmation, and only one round
 * runs at a time. A result is never inferred from an HTTP status alone.
 */
public final class ConfigHealthCheckScreen extends Screen {
    private final Screen parent;
    private final List<TestResult> results = new ArrayList<>(ConnectionTestHelper.initialResults());
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private final boolean autoStart;
    private long generation;
    private boolean running, exporting, started;
    private int selected, detailScroll;
    private String stage = "";
    private Button allButton, singleButton, exportButton;

    /** @param autoStart true when the caller just saved or authorized and wants one automatic round. */
    public ConfigHealthCheckScreen(Screen parent, boolean autoStart) {
        super(Component.literal("服务真实测试"));
        this.parent = parent;
        this.autoStart = autoStart;
    }

    public ConfigHealthCheckScreen(Screen parent) {
        this(parent, true);
    }

    private HealthCheckLayout layout() {
        return HealthCheckLayout.of(width, height, VerityMenuContext.fullScreenWindow(minecraft));
    }

    @Override protected void init() {
        clearWidgets();
        HealthCheckLayout p = layout();
        int gap = 4, bw = Math.max(1, (p.innerWidth() - gap * 3) / 4), x = p.left() + 10;
        allButton = button(x, p.footerY(), bw, "全部测试", this::confirmAll);
        singleButton = button(x + (bw + gap), p.footerY(), bw, "测选中", this::confirmSelected);
        exportButton = button(x + (bw + gap) * 2, p.footerY(), bw, "导出诊断", this::confirmExport);
        button(x + (bw + gap) * 3, p.footerY(), bw, "返回", this::onClose);
        refreshButtons();
        if (!started) {
            started = true;
            if (autoStart) startAll();
        }
    }

    private Button button(int x, int y, int w, String text, Runnable action) {
        return addRenderableWidget(VerityUiWidgets.secondaryButton(font, x, y, w, 20,
                Component.literal(text), b -> action.run()));
    }

    private void refreshButtons() {
        if (allButton == null) return;
        allButton.setMessage(Component.literal(running ? "取消测试" : "全部测试"));
        allButton.active = !exporting;
        singleButton.active = !running && !exporting;
        exportButton.active = !running && !exporting;
    }

    private void confirmAll() {
        if (running) { cancel(); return; }
        ask("测试全部服务？", LiveServiceTest.DISCLAIMER
                + "会依次测试对话、语音输出、语音输入。语音输入会打开麦克风录 4 秒，需要你说话。",
                this::startAll);
    }

    private void confirmSelected() {
        if (running) return;
        String id = results.get(selected).id();
        ask("测试" + nameOf(id) + "？", warningFor(id), () -> start(List.of(id)));
    }

    private void ask(String title, String warning, Runnable action) {
        minecraft.setScreen(new VerityConfirmScreen(this, yes -> {
            minecraft.setScreen(this);
            if (yes) action.run();
        }, Component.literal(title), Component.literal(warning)));
    }

    private String warningFor(String id) {
        return switch (id) {
            case "stt" -> "会打开麦克风录 4 秒并识别，需要你说话。本机识别不上传。";
            case "tts" -> LiveServiceTest.DISCLAIMER + "合成成功后会播放一次短句，请留意音量。";
            default -> LiveServiceTest.DISCLAIMER + "会发送一句很短的测试消息。";
        };
    }

    /** Voice input goes last: it needs the player at the microphone. */
    private void startAll() {
        start(List.of("chat", "tts", "stt"));
    }

    private void start(List<String> ids) {
        if (running) return;
        long run = ++generation;
        running = true;
        queue.clear();
        queue.addAll(ids);
        detailScroll = 0;
        stage = "准备测试…";
        for (String id : ids) replace(pending(id, State.WAITING, "等待测试"));
        refreshButtons();
        next(run, ids.size());
    }

    private void next(long run, int total) {
        if (run != generation || !running) return;
        String id = queue.poll();
        if (id == null) {
            running = false;
            stage = "";
            refreshButtons();
            return;
        }
        selected = Math.max(0, indexOf(id));
        stage = "正在测试 " + nameOf(id) + "（" + (total - queue.size()) + "/" + total + "）";
        replace(pending(id, State.RUNNING, "正在发送请求"));
        CompletableFuture<TestResult> pending = switch (id) {
            case "stt" -> LiveServiceTest.runStt(this::progress, 4);
            case "tts" -> LiveServiceTest.runTts(this::progress);
            default -> LiveServiceTest.runChat(this::progress);
        };
        pending.whenComplete((result, error) -> minecraft.execute(() -> {
            if (run != generation || minecraft.screen != this) return;
            replace(error != null
                    ? pending(id, State.FAILED, "测试未能完成，请重试")
                    : result);
            next(run, total);
        }));
    }

    private TestResult pending(String id, State state, String detail) {
        return new TestResult(id, nameOf(id), state, "真实测试", "", detail);
    }

    private void progress(String text) {
        if (minecraft != null) minecraft.execute(() -> { if (running) stage = text; });
    }

    private void cancel() {
        ++generation;
        running = false;
        queue.clear();
        stage = "已取消";
        for (int i = 0; i < results.size(); i++) {
            TestResult r = results.get(i);
            if (r.state() == State.RUNNING || r.state() == State.WAITING) {
                results.set(i, new TestResult(r.id(), r.name(), State.CANCELLED, "真实测试", "",
                        "已取消，可重新测试"));
            }
        }
        refreshButtons();
    }

    private void confirmExport() {
        ask("导出诊断信息？", "包含测试结果、设置摘要和日志片段。已做脱敏，但分享前仍请检查个人信息。",
                this::exportBundle);
    }

    private void exportBundle() {
        if (exporting) return;
        exporting = true;
        refreshButtons();
        stage = "正在导出…";
        List<TestResult> snapshot = List.copyOf(results);
        CompletableFuture.supplyAsync(() -> DiagnosticBundle.export(snapshot))
                .whenComplete((path, error) -> minecraft.execute(() -> {
                    exporting = false;
                    stage = error != null || path == null ? "导出失败，请查看日志。" : "已导出：" + path.getFileName();
                    refreshButtons();
                }));
    }

    private String summary() {
        if (running) return stage.isBlank() ? "正在测试…" : stage;
        long failed = results.stream().filter(r -> r.state() == State.FAILED).count();
        if (failed > 0) return failed + " 项未通过 · 选中查看原因";
        if (results.stream().allMatch(r -> r.state() == State.SKIPPED)) return "三项服务都没有启用";
        if (results.stream().anyMatch(r -> r.state() == State.WAITING || r.state() == State.CANCELLED)) {
            return stage.isBlank() ? "尚未测试 · 点「全部测试」开始" : stage;
        }
        boolean voiceUnconfirmed = results.stream()
                .anyMatch(r -> r.mode().contains("朗读") && r.state() == State.WARNING);
        return voiceUnconfirmed ? "测试完成 · 语音是否听到请自行确认" : "测试完成";
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        com.hanchen.veritycustomapi.client.ScreenBackgrounds.draw(this, g);
        HealthCheckLayout p = layout();
        VerityUiDraw.card(g, p.left(), p.top(), p.right(), p.bottom(), 0);
        int x = p.left() + 10, w = p.innerWidth();
        g.drawString(font, title, x, p.top() + 9, VerityUiTheme.TEXT);
        line(g, summary(), x, p.top() + 23, w, VerityUiTheme.MUTED);
        int done = (int) results.stream()
                .filter(r -> r.state() != State.RUNNING && r.state() != State.WAITING).count();
        g.fill(x, p.top() + 39, x + w, p.top() + 41, VerityUiTheme.BORDER);
        g.fill(x, p.top() + 39, x + w * done / 3, p.top() + 41, VerityUiTheme.ACCENT);
        for (int i = 0; i < results.size(); i++) {
            TestResult r = results.get(i);
            int y = p.rowTop() + p.rowHeight() * i;
            if (i == selected) g.fill(x, y, x + w, y + p.rowHeight() - 2, VerityUiTheme.SURFACE);
            g.fill(x, y + 3, x + 2, y + p.rowHeight() - 5, color(r.state()));
            String state = stateText(r);
            int sw = font.width(state);
            line(g, r.name(), x + 8, y + 4, w - sw - 18, VerityUiTheme.TEXT);
            g.drawString(font, state, x + w - sw - 4, y + 4, color(r.state()));
            if (p.rowHeight() >= 29) line(g, r.mode(), x + 8, y + 16, w - 16, VerityUiTheme.MUTED);
        }
        g.enableScissor(x, p.detailTop(), x + w, p.detailBottom());
        TestResult r = results.get(selected);
        List<net.minecraft.util.FormattedCharSequence> lines = new ArrayList<>();
        String details = r.name() + " · " + stateText(r) + "\n"
                + (r.mode().isBlank() ? "" : r.mode() + "\n")
                + (r.endpoint().isBlank() ? "" : "服务：" + r.endpoint() + "\n") + r.detail()
                + (stage.isBlank() ? "" : "\n" + stage);
        lines.addAll(font.split(Component.literal(details), Math.max(1, w - 8)));
        int visible = Math.max(0, (p.detailBottom() - p.detailTop()) / 11);
        detailScroll = Math.max(0, Math.min(detailScroll, Math.max(0, lines.size() - visible)));
        for (int i = detailScroll; i < lines.size(); i++) {
            g.drawString(font, lines.get(i), x + 4, p.detailTop() + (i - detailScroll) * 11, VerityUiTheme.TEXT);
        }
        g.disableScissor();
        super.render(g, mouseX, mouseY, partialTick);
    }

    private void line(GuiGraphics g, String text, int x, int y, int w, int color) {
        g.drawString(font, font.plainSubstrByWidth(text == null ? "" : text, Math.max(0, w)), x, y, color);
    }

    private static int color(State state) {
        return switch (state) {
            case PASSED -> VerityUiTheme.SUCCESS;
            case FAILED -> VerityUiTheme.DANGER;
            case WARNING -> VerityUiTheme.WARNING;
            case RUNNING -> VerityUiTheme.ACCENT;
            default -> VerityUiTheme.MUTED;
        };
    }

    /** PASSED means the service really produced an answer; audio needs the player's own ears. */
    private static String stateText(TestResult r) {
        if (r.state() == State.PASSED) return "已通过";
        if (r.state() == State.WARNING && r.mode().contains("朗读")) return "已播放";
        if (r.state() == State.WARNING && r.mode().contains("录音")) return "没听到说话";
        return switch (r.state()) {
            case FAILED -> "未通过";
            case RUNNING -> "测试中";
            case SKIPPED -> "未启用";
            case CANCELLED -> "已取消";
            default -> "等待中";
        };
    }

    private String nameOf(String id) {
        for (TestResult r : results) if (r.id().equals(id)) return r.name();
        return "服务";
    }

    private int indexOf(String id) {
        for (int i = 0; i < results.size(); i++) if (results.get(i).id().equals(id)) return i;
        return 0;
    }

    private void replace(TestResult result) {
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i).id().equals(result.id())) { results.set(i, result); return; }
        }
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        HealthCheckLayout p = layout();
        if (button == 0 && x >= p.left() + 10 && x < p.right() - 10
                && y >= p.rowTop() && y < p.rowTop() + 3 * p.rowHeight()) {
            selected = Math.min(2, (int) (y - p.rowTop()) / p.rowHeight());
            detailScroll = 0;
            return true;
        }
        return super.mouseClicked(x, y, button);
    }

    @Override public boolean mouseScrolled(double x, double y, double delta) {
        HealthCheckLayout p = layout();
        if (x >= p.left() && x < p.right() && y >= p.detailTop() && y < p.detailBottom()) {
            detailScroll = Math.max(0, detailScroll + (delta < 0 ? 2 : -2));
            return true;
        }
        return super.mouseScrolled(x, y, delta);
    }

    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_UP || key == org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN) {
            selected = Math.floorMod(selected + (key == org.lwjgl.glfw.GLFW.GLFW_KEY_UP ? -1 : 1), 3);
            detailScroll = 0;
            return true;
        }
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN || key == org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_UP) {
            detailScroll = Math.max(0, detailScroll + (key == org.lwjgl.glfw.GLFW.GLFW_KEY_PAGE_DOWN ? 3 : -3));
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override public void removed() {
        // Leaving the screen must abort the round: otherwise the request keeps spending quota with
        // nowhere to show its result.
        if (running) cancel();
    }

    @Override public void onClose() {
        if (running) cancel();
        minecraft.setScreen(parent);
    }

    @Override public boolean isPauseScreen() {
        return false;
    }
}

