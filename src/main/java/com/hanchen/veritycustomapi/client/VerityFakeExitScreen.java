package com.hanchen.veritycustomapi.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;

/**
 * 真正的原版暂停菜单，叠加 30 秒花屏/红字。
 *
 * <p>不是自绘假面板：背景、标题、按钮全部由 {@link PauseScreen} 原版渲染，
 * 因此视觉和玩家平时按 ESC 看到的菜单一致。ESC/返回游戏保持原版行为；
 * “保存并退出”被替换成同位置、同样式的按钮，点击只花屏，不真的退出。
 * 30 秒后自动关闭并回到游戏。
 */
public final class VerityFakeExitScreen extends PauseScreen {
    private static final long DURATION_MS = 30_000L;
    private static final String[] LINES = {
            "你退不掉的",
            "退出键在我这里也是摆设",
            "保存失败",
            "你换了存档也没用",
            "看着！说你不走了",
            "回来，我数到三。",
            "我会找到你的"
    };

    private final long openedAt = System.currentTimeMillis();
    private long quitGlitchUntil;
    private int quitAttempts;
    private Button pendingRemove;
    private long pendingRemoveAt;
    private long removeFlashUntil;
    private int removeFlashX;
    private int removeFlashY;
    private int removeFlashW;
    private int removeFlashH;

    public VerityFakeExitScreen() {
        super(true);
    }

    @Override
    protected void init() {
        super.init();
        pendingRemove = null;
        // 触发后这层菜单里的所有按钮都只剩花屏反馈：
        // 返回游戏 / 选项 / 保存并退出都不能直接关掉效果，点一下还会被“吃掉”。
        for (Object child : new ArrayList<>(children())) {
            if (!(child instanceof Button button)) continue;
            int x = button.getX();
            int y = button.getY();
            int w = button.getWidth();
            int h = button.getHeight();
            Component label = button.getMessage();
            String text = label.getString();
            boolean quitButton = isQuitLabel(text);
            boolean returnButton = isReturnLabel(text);
            removeWidget(button);
            if (quitButton) {
                // 打开假菜单时，原版“保存并回到标题屏幕”已经是消失状态；
                // 先把残影留在它原来的位置，制造“刚被吃掉”的视觉。
                removeFlashX = x;
                removeFlashY = y;
                removeFlashW = w;
                removeFlashH = h;
                removeFlashUntil = System.currentTimeMillis() + 520L;
                continue;
            }
            if (returnButton) {
                // 只保留“回到游戏”的原版功能：点击立即关闭假菜单并回到世界
                addRenderableWidget(Button.builder(label, clicked -> VerityHorrorDirector.forceUnlock())
                        .bounds(x, y, w, h).build());
                continue;
            }
            addRenderableWidget(Button.builder(label, clicked -> fakeBlocked(clicked))
                    .bounds(x, y, w, h).build());
        }
    }

    private static boolean isQuitLabel(String text) {
        return text.contains("保存并退出")
                || text.contains("保存并回到标题屏幕")
                || text.contains("保存并回到")
                || text.contains("退出并保存")
                || text.contains("回到标题屏幕")
                || text.contains("Save and Quit")
                || text.contains("断开连接")
                || text.contains("Disconnect");
    }

    private static boolean isReturnLabel(String text) {
        return text.contains("回到游戏")
                || text.contains("返回游戏")
                || text.contains("Back to Game")
                || text.contains("Resume");
    }

    private void fakeBlocked(Button button) {
        quitAttempts++;
        long now = System.currentTimeMillis();
        quitGlitchUntil = now + 2_500L;
        removeFlashUntil = now + 420L;
        removeFlashX = button.getX();
        removeFlashY = button.getY();
        removeFlashW = button.getWidth();
        removeFlashH = button.getHeight();
        // 先隐藏，下一 tick 再真正移除，避免在点击遍历 children 时并发修改
        button.visible = false;
        pendingRemove = button;
        pendingRemoveAt = now + 120L;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        // 触发后 ESC 不能直接取消；右 Shift / /verityunlock / 30 秒超时才结束。
        return false;
    }

    @Override
    public void onClose() {
        // 故意不关闭；真正关闭走 VerityHorrorDirector.forceUnlock()
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 先让原版 PauseScreen 画完整菜单，再只叠一层干扰，不遮住按钮
        super.render(graphics, mouseX, mouseY, partialTick);
        renderGlitch(graphics, width, height);
    }

    @Override
    public void tick() {
        super.tick();
        long now = System.currentTimeMillis();
        if (pendingRemove != null && now >= pendingRemoveAt) {
            removeWidget(pendingRemove);
            pendingRemove = null;
        }
        if (now - openedAt >= DURATION_MS) {
            VerityHorrorDirector.forceUnlock();
        }
    }

    private void renderGlitch(GuiGraphics graphics, int width, int height) {
        long elapsed = System.currentTimeMillis() - openedAt;
        int tick = (int) (elapsed / 50L);
        float fadeIn = Math.min(1.0F, elapsed / 350.0F);
        float fadeOut = Math.min(1.0F, (DURATION_MS - elapsed) / 700.0F);
        float fade = Math.max(0.0F, Math.min(fadeIn, fadeOut));
        int alpha = Math.round(255.0F * fade);
        int jitterX = pseudo(tick * 3 + 1, 7) - 3;
        int jitterY = pseudo(tick * 7 + 2, 5) - 2;

        // 底光与边缘压暗：保持原版菜单可读，只让整屏泛红
        graphics.fill(0, 0, width, height, argb(Math.round(24 * fade), 80, 0, 0));
        int edge = Math.max(10, width / 22);
        for (int i = 0; i < 6; i++) {
            int a = Math.round((8 + i * 3) * fade);
            graphics.fill(i * edge / 6, 0, width - i * edge / 6, 3, argb(a, 50, 0, 0));
            graphics.fill(i * edge / 6, height - 3, width - i * edge / 6, height, argb(a, 50, 0, 0));
        }

        graphics.pose().pushPose();
        graphics.pose().translate(jitterX, jitterY, 0.0F);

        // 扫描线：亮度轻微起伏，避免像固定条纹
        for (int y = 0; y < height; y += 8) {
            int lineAlpha = 7 + pseudo(tick + y * 3, 10);
            graphics.fill(0, y, width, y + 1, argb(Math.round(lineAlpha * fade), 150, 0, 0));
        }

        // 随机横向撕裂：红/黑相间，少量才像信号故障
        for (int i = 0; i < 7; i++) {
            int seed = tick + i * 53;
            int y = pseudo(seed, Math.max(1, height));
            int h = 1 + pseudo(seed + 3, 4);
            int x = pseudo(seed + 7, Math.max(1, width / 4));
            int color = (i & 1) == 0
                    ? argb(Math.round((28 + pseudo(seed + 17, 55)) * fade), 200, 0, 0)
                    : argb(Math.round((18 + pseudo(seed + 19, 35)) * fade), 0, 0, 0);
            graphics.fill(x, y,
                    Math.min(width, x + width / 2 + pseudo(seed + 11, width / 2)),
                    Math.min(height, y + h), color);
        }

        // 原版按钮文字色差重影：菜单还是原版，但看起来信号不稳
        for (Object child : children()) {
            if (!(child instanceof Button button)) continue;
            if (!button.visible) continue;
            String label = button.getMessage().getString();
            int textX = button.getX() + Math.max(0, (button.getWidth() - font.width(label)) / 2);
            int textY = button.getY() + Math.max(0, (button.getHeight() - 8) / 2);
            if (pseudo(tick + button.getY() * 7 + button.getX(), 100) < 45) {
                graphics.drawString(font, label, textX + 2, textY, argb(Math.round(55 * fade), 255, 35, 35), false);
                graphics.drawString(font, label, textX - 2, textY, argb(Math.round(42 * fade), 0, 220, 220), false);
            }
        }

        // 红字只贴边，避开中间按钮
        for (int i = 0; i < 8; i++) {
            if (pseudo(tick + i * 29, 100) < 18) continue;
            String line = LINES[Math.abs(tick / 3 + i * 13) % LINES.length];
            int x = pseudo(tick * 7 + i * 31, Math.max(1, width - 70));
            int y = i % 2 == 0
                    ? 34 + pseudo(tick + i * 19, Math.max(1, height / 3))
                    : height - 70 - pseudo(tick + i * 23, Math.max(1, height / 3));
            int lineAlpha = Math.round((55 + pseudo(tick + i, 80)) * fade);
            graphics.drawString(font, line, x, y, argb(lineAlpha, 225, 18, 18), true);
        }

        // 按钮被“吃掉”的残影：原位置短暂闪红，然后彻底消失
        long now = System.currentTimeMillis();
        if (now < removeFlashUntil) {
            float left = (removeFlashUntil - now) / 420.0F;
            int flashAlpha = Math.round(140 * left * fade);
            graphics.fill(removeFlashX, removeFlashY, removeFlashX + removeFlashW,
                    removeFlashY + removeFlashH, argb(flashAlpha, 190, 0, 0));
            graphics.fill(removeFlashX, removeFlashY, removeFlashX + removeFlashW,
                    removeFlashY + 1, argb(Math.round(220 * left * fade), 255, 50, 50));
            graphics.fill(removeFlashX, removeFlashY + removeFlashH - 1,
                    removeFlashX + removeFlashW, removeFlashY + removeFlashH,
                    argb(Math.round(220 * left * fade), 255, 50, 50));
        }

        // 点击“保存并退出后”额外闪一下，强调“没走成”
        if (now < quitGlitchUntil) {
            int flash = Math.round(55 * fade);
            graphics.fill(0, 0, width, height, argb(flash, 120, 0, 0));
            graphics.pose().pushPose();
            graphics.pose().translate(width / 2.0F, height / 2.0F + 36.0F, 0.0F);
            graphics.pose().scale(1.8F, 1.8F, 1.0F);
            graphics.drawCenteredString(font, "你退不掉的", 0, 0, argb(alpha, 255, 30, 30));
            graphics.pose().popPose();
        }

        graphics.pose().popPose();
    }

    private static int pseudo(int seed, int bound) {
        if (bound <= 1) return 0;
        int value = seed * 1103515245 + 12345;
        return Math.floorMod(value, bound);
    }

    private static int argb(int alpha, int red, int green, int blue) {
        return (Math.max(0, Math.min(255, alpha)) << 24)
                | (Math.max(0, Math.min(255, red)) << 16)
                | (Math.max(0, Math.min(255, green)) << 8)
                | Math.max(0, Math.min(255, blue));
    }
}
