package com.hanchen.veritycustomapi.client;

import com.hanchen.veritycustomapi.util.Silent;

import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.compat.VerityGrowthState;
import com.hanchen.veritycustomapi.memory.VerityMemoryBridge;
import com.hanchen.veritycustomapi.skin.SkinManager;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import varmite.verity.entity.verity.VerityEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 游戏内 HUD：对话气泡（带圆形表情头像）、状态角标和导航箭头。
 * 不额外要贴图——头像直接用实体表情贴图，箭头用 fill 现画三角形；
 * 开着任何界面时整块不画，气泡钉在右下角躲开原版聊天栏。
 */
public final class VerityHudOverlay {
    private static final int PANEL_BG = 0xC0101316;
    private static final int PANEL_BORDER = 0x80343B46;
    private static final int TEXT = 0xFFF1F3F5;
    private static final int MUTED = 0xFFA7AFBA;
    private static final int ACCENT = 0xFF65DDE0;
    private static final int ARROW = 0xFF65DDE0;

    /** 头像绘制尺寸。 */
    private static final int PORTRAIT_DRAW = 48;
    /** 头像 UV 精度：只用于归一化坐标换算整数贴图坐标，与贴图像素数无关。 */
    private static final int PORTRAIT_UV = 1024;
    /** 圆形头像的描边色，用来掩盖逐行裁切产生的锯齿。 */
    private static final int PORTRAIT_RING = 0xFF2B3138;
    private static final int BUBBLE_MAX_LINES = 4;

    /** 记忆条数不需要每帧统计，缓存几秒即可。 */
    private static final long MEMORY_CACHE_MS = 5000L;
    /** 跟 VerityGrowthMath 的体型换算对齐：每喂一次 +0.1 倍。 */
    private static final float GROWTH_SCALE_PER_FEED = 0.1F;
    private static volatile int cachedMemoryCount;
    private static volatile long cachedMemoryAt;
    private static final Map<String, ResourceLocation> PORTRAIT_CACHE = new HashMap<>();

    public static final IGuiOverlay HUD = (forgeGui, graphics, partialTick, screenWidth, screenHeight) -> {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            return;
        }
        if (minecraft.options != null && minecraft.options.hideGui) {
            return;
        }
        // 开着任何界面（聊天、背包、配置…）时整个 HUD 都不画：
        // 那些界面正好压着气泡的位置，不躲就糊成一团。
        if (minecraft.screen != null) {
            return;
        }
        try {
            if (ConfigManager.isDialogueHudEnabled()) {
                renderDialogueBubble(minecraft, graphics, screenHeight);
            }
            if (ConfigManager.isStatusBadgeEnabled()) {
                renderStatusBadge(minecraft, graphics, screenWidth);
            }
            if (ConfigManager.isNavigationArrowEnabled()) {
                renderNavigationArrow(minecraft, graphics, screenWidth, screenHeight);
            }
        } catch (Throwable ignored) {
            // HUD 永远不能让游戏崩：任何异常都静默跳过这一帧
        }
    };

    private VerityHudOverlay() {
    }

    // ==================== 对话气泡 ====================

    private static void renderDialogueBubble(Minecraft minecraft, GuiGraphics graphics, int screenHeight) {
        String line = VerityDialogueFeed.latestText();
        if (line.isBlank() || !VerityDialogueFeed.isFresh(ConfigManager.getDialogueHudSeconds())) {
            return;
        }
        Font font = minecraft.font;
        boolean withPortrait = ConfigManager.isDialoguePortraitEnabled();
        int maxWidth = Math.min(260, Math.max(120, graphics.guiWidth() / 3));
        // 钉在右下角。原版聊天栏（含没打开时的历史）占着左下角，
        // 气泡放左边必被聊天字压住，靠右就永远不打架。
        int left = Math.max(8, graphics.guiWidth() - maxWidth - 8);
        int textWidth = maxWidth - (withPortrait ? PORTRAIT_DRAW + 10 : 0) - 12;

        List<FormattedCharSequence> wrapped = font.split(Component.literal(line), Math.max(40, textWidth));
        int lines = Math.min(BUBBLE_MAX_LINES, Math.max(1, wrapped.size()));
        int lineHeight = 10;
        int textBlockHeight = lines * lineHeight;
        int contentHeight = Math.max(withPortrait ? PORTRAIT_DRAW : 0, textBlockHeight);
        int top = screenHeight - 70 - contentHeight;
        int boxWidth = maxWidth;
        int boxHeight = contentHeight + 12;

        graphics.fill(left, top, left + boxWidth, top + boxHeight, PANEL_BG);
        graphics.fill(left, top, left + boxWidth, top + 1, PANEL_BORDER);
        graphics.fill(left, top + boxHeight - 1, left + boxWidth, top + boxHeight, PANEL_BORDER);
        graphics.fill(left, top, left + 1, top + boxHeight, PANEL_BORDER);
        graphics.fill(left + boxWidth - 1, top, left + boxWidth, top + boxHeight, PANEL_BORDER);

        int textLeft = left + 6;
        if (withPortrait) {
            ResourceLocation portrait = portraitTexture(VerityDialogueFeed.displayVariant());
            int portraitTop = top + 6 + Math.max(0, (contentHeight - PORTRAIT_DRAW) / 2);
            if (portrait != null) {
                drawCircularPortrait(graphics, portrait, textLeft, portraitTop, PORTRAIT_DRAW);
            }
            textLeft += PORTRAIT_DRAW + 6;
        }
        int textTop = top + 6 + Math.max(0, (contentHeight - textBlockHeight) / 2);
        for (int i = 0; i < lines; i++) {
            graphics.drawString(font, wrapped.get(i), textLeft, textTop + i * lineHeight, TEXT, false);
        }
    }

    /**
     * 把表情贴图裁成圆形画出来。
     *
     * 取样范围不固定取整张贴图，而是让 {@link PortraitFaceCrop} 找出脸那一块，脸才填得满圆框；
     * 再逐行算圆在该行的半宽、只画圆内那一段，等于给头像套了个圆形遮罩，边缘补一圈描边盖住锯边。
     */
    private static void drawCircularPortrait(GuiGraphics graphics, ResourceLocation texture,
                                             int left, int top, int size) {
        float[] crop = PortraitFaceCrop.of(texture);
        float cropWidth = crop[2] - crop[0];
        float cropHeight = crop[3] - crop[1];
        double radius = size / 2.0D;
        for (int row = 0; row < size; row++) {
            double dy = row + 0.5D - radius;
            double halfWidth = Math.sqrt(Math.max(0.0D, radius * radius - dy * dy));
            int inset = (int) Math.round(radius - halfWidth);
            int width = size - inset * 2;
            if (width <= 0) {
                continue;
            }
            int x = left + inset;
            graphics.fill(x - 1, top + row, x, top + row + 1, PORTRAIT_RING);
            graphics.fill(x + width, top + row, x + width + 1, top + row + 1, PORTRAIT_RING);
            float u0 = crop[0] + inset / (float) size * cropWidth;
            float u1 = crop[0] + (inset + width) / (float) size * cropWidth;
            float v0 = crop[1] + row / (float) size * cropHeight;
            float v1 = crop[1] + (row + 1) / (float) size * cropHeight;
            graphics.blit(texture, x, top + row, width, 1,
                    u0 * PORTRAIT_UV, v0 * PORTRAIT_UV,
                    Math.max(1, Math.round((u1 - u0) * PORTRAIT_UV)),
                    Math.max(1, Math.round((v1 - v0) * PORTRAIT_UV)),
                    PORTRAIT_UV, PORTRAIT_UV);
        }
    }

    /** 优先使用当前皮肤的动态纹理（会跟随实体表情），否则回退到原版表情贴图。 */
    private static ResourceLocation portraitTexture(String variant) {
        try {
            String activeSkin = SkinManager.getInstance().getActiveSkinName();
            if (activeSkin != null && !activeSkin.isBlank()) {
                ResourceLocation skinTexture = SkinManager.getInstance().getSkinTexture().getTextureId();
                if (skinTexture != null) {
                    return skinTexture;
                }
            }
        } catch (Throwable ignored) { Silent.ignore("VerityHudOverlay", ignored); }
        String key = variant == null || variant.isBlank() ? "happy" : variant;
        ResourceLocation cached = PORTRAIT_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            ResourceLocation candidate = ResourceLocation.tryParse("verity:textures/entity/" + key + ".png");
            if (candidate != null
                    && Minecraft.getInstance().getResourceManager().getResource(candidate).isPresent()) {
                PORTRAIT_CACHE.put(key, candidate);
                return candidate;
            }
        } catch (Throwable ignored) { Silent.ignore("VerityHudOverlay", ignored); }
        ResourceLocation fallback = ResourceLocation.tryParse("verity:textures/entity/happy.png");
        PORTRAIT_CACHE.put(key, fallback);
        return fallback;
    }

    // ==================== 状态角标 ====================

    private static void renderStatusBadge(Minecraft minecraft, GuiGraphics graphics, int screenWidth) {
        List<String> lines = new ArrayList<>();
        VerityEntity entity = VerityDialogueFeed.nearestVerity();
        if (entity != null) {
            float scale = VerityGrowthState.getClientScale(entity);
            // 跟 VerityGrowthMath 的「每喂一次 +0.1 倍体型」对齐
            int feeds = Math.round((scale - 1.0F) / GROWTH_SCALE_PER_FEED);
            lines.add("好感成长 +" + Math.max(0, feeds));
        }
        lines.add("记忆 " + memoryCount() + " 条");
        lines.add("今日对话 " + VerityDialogueFeed.todayCount() + " 次");
        double credits = ConfigManager.getCreditsRemainingExact();
        if (credits >= 0) {
            lines.add("额度 " + ConfigManager.formatCredits(credits));
        }
        if (VerityNavigationService.current() != null) {
            lines.add("导航中…");
        } else if (VerityNavigationService.isFollowing()) {
            lines.add("跟随中");
        }

        Font font = minecraft.font;
        int left = 6;
        int top = 6;
        int lineHeight = 10;
        int boxWidth = 0;
        for (String line : lines) {
            boxWidth = Math.max(boxWidth, font.width(line));
        }
        boxWidth += 10;
        int boxHeight = lines.size() * lineHeight + 8;
        graphics.fill(left, top, left + boxWidth, top + boxHeight, PANEL_BG);
        graphics.fill(left, top, left + boxWidth, top + 1, PANEL_BORDER);
        graphics.fill(left, top + boxHeight - 1, left + boxWidth, top + boxHeight, PANEL_BORDER);
        graphics.fill(left, top, left + 1, top + boxHeight, PANEL_BORDER);
        graphics.fill(left + boxWidth - 1, top, left + boxWidth, top + boxHeight, PANEL_BORDER);
        for (int i = 0; i < lines.size(); i++) {
            graphics.drawString(font, lines.get(i), left + 5, top + 4 + i * lineHeight,
                    i == 0 ? ACCENT : MUTED, false);
        }
    }

    private static int memoryCount() {
        long now = System.currentTimeMillis();
        if (now - cachedMemoryAt < MEMORY_CACHE_MS) {
            return cachedMemoryCount;
        }
        cachedMemoryAt = now;
        try {
            cachedMemoryCount = VerityMemoryBridge.snapshot().size();
        } catch (Throwable ignored) {
            cachedMemoryCount = 0;
        }
        return cachedMemoryCount;
    }

    // ==================== 导航箭头 ====================

    private static void renderNavigationArrow(Minecraft minecraft, GuiGraphics graphics,
                                              int screenWidth, int screenHeight) {
        VerityNavigationService.Target target = VerityNavigationService.current();
        if (target == null || minecraft.player == null
                || !minecraft.player.level().dimension().equals(target.dimension())) {
            return;
        }
        double dx = target.x() - minecraft.player.getX();
        double dz = target.z() - minecraft.player.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 0.5D) {
            return;
        }
        // Minecraft 的 yaw：0 为南(+Z)，顺时针增大；屏幕上方代表玩家正前方
        double bearing = Math.toDegrees(Math.atan2(-dx, dz));
        float relative = (float) (bearing - minecraft.player.getYRot());
        relative = (relative % 360.0F + 540.0F) % 360.0F - 180.0F;

        int centerX = screenWidth / 2;
        int centerY = screenHeight / 2 + 34;
        graphics.pose().pushPose();
        graphics.pose().translate(centerX, centerY, 0.0F);
        graphics.pose().mulPose(Axis.ZP.rotationDegrees(relative));
        graphics.fill(-1, -5, 1, 8, ARROW);
        for (int i = 0; i <= 5; i++) {
            graphics.fill(-i, -10 + i, i + 1, -9 + i, ARROW);
        }
        graphics.pose().popPose();

        String label = target.label() + " · " + Math.round(distance) + " 格";
        Font font = minecraft.font;
        graphics.drawString(font, label, centerX - font.width(label) / 2, centerY + 12, ARROW, true);
    }
}
