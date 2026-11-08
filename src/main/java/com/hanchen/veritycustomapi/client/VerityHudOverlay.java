package com.hanchen.veritycustomapi.client;

import com.hanchen.veritycustomapi.util.Silent;

import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.compat.ReflectiveTarget;
import com.hanchen.veritycustomapi.compat.VerityGrowthState;
import com.hanchen.veritycustomapi.memory.VerityMemoryBridge;
import com.hanchen.veritycustomapi.skin.SkinManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import varmite.verity.entity.verity.VerityEntity;

import java.lang.reflect.Field;
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
    private static final int PANEL_BG = 0xFF282828;
    private static final int PANEL_BORDER = 0xFF707070;
    private static final int TEXT = 0xFFE0E0E0;
    private static final int MUTED = 0xFFB0B0B0;
    private static final int ACCENT = 0xFFFFFFA0;
    private static final int ARROW = 0xFFFFFFA0;

    /** 头像绘制尺寸。 */
    private static final int PORTRAIT_DRAW = 48;
    /** 头像 UV 精度：只用于归一化坐标换算整数贴图坐标，与贴图像素数无关。 */
    private static final int PORTRAIT_UV = 1024;
    /** 圆形头像的描边色，用来掩盖逐行裁切产生的锯齿。 */
    private static final int PORTRAIT_RING = 0xFF171717;
    private static final int BUBBLE_MAX_LINES = 4;

    /**
     * 原版 {@code VerityConfig.COLOR} 字段。首次用到时解析，取不到（老版本没有这个配置）就一直是 null。
     *
     * <p>🔴 为什么头像要读它：Verity 6.0.0 把表情贴图从饱和黄（{@code #EADE19}）改成了
     * 淡奶油色（{@code #FFFDD5}），但**实体渲染时会用这个配置给贴图着色**，所以世界里
     * 的球看起来仍然是黄的。我们的气泡头像直接 blit 贴图、不做着色，结果头像比实体淡一大截
     * ——玩家看到的就是「头像颜色变淡了」。
     *
     * <p>所以这里必须跟原版用**同一套**着色规则，头像才和实体一致。
     */
    private static volatile Field verityColorField;
    private static volatile boolean verityColorResolved;

    /** 记忆条数不需要每帧统计，缓存几秒即可。 */
    private static final long MEMORY_CACHE_MS = 5000L;
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
        int portraitSize=HudLayout.portraitSize(graphics.guiWidth());
        int maxWidth=HudLayout.dialogue(graphics.guiWidth(),screenHeight,withPortrait,1).width();
        int textWidth=Math.max(20,maxWidth-12-(withPortrait?portraitSize+6:0));
        List<FormattedCharSequence> wrapped=font.split(Component.literal(line),textWidth);
        int lines=Math.min(BUBBLE_MAX_LINES,Math.max(1,wrapped.size()));
        HudLayout.Box box=HudLayout.dialogue(graphics.guiWidth(),screenHeight,withPortrait,lines);
        int left=box.x(),top=box.y();
        drawHudPanel(graphics,left,top,box.width(),box.height());
        int textLeft=left+6;
        if(withPortrait) {
            ResourceLocation portrait=portraitTexture(VerityDialogueFeed.displayVariant());
            if(portrait!=null)drawCircularPortrait(graphics,portrait,textLeft,top+6,portraitSize);
            textLeft+=portraitSize+6;
        }
        String name=com.hanchen.veritycustomapi.compat.VeritySpeaker.currentName();
        graphics.drawString(font,font.plainSubstrByWidth(name,textWidth),textLeft,top+5,ACCENT,true);
        for(int i=0;i<lines;i++)graphics.drawString(font,wrapped.get(i),textLeft,top+17+i*10,TEXT,true);
        if(wrapped.size()>lines)graphics.drawString(font,"...",left+box.width()-20,top+box.height()-10,MUTED,false);
    }

    /** Solid, compact pixel panel; no floating cards or translucent world bleed. */
    private static void drawHudPanel(GuiGraphics g,int x,int y,int w,int h) {
        g.fill(x,y,x+w,y+h,0xFF101010);
        g.fill(x+1,y+1,x+w-1,y+h-1,PANEL_BG);
        g.fill(x+1,y+1,x+w-1,y+2,PANEL_BORDER);
        g.fill(x+1,y+1,x+2,y+h-1,PANEL_BORDER);
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
        // 跟原版实体一致的着色：贴图本身是淡色的，不加这层头像会比实体白一截。
        // 🔴 必须 try/finally 复原——setShaderColor 是全局状态，
        // 漏掉复原会让之后画的每一个 HUD 元素（包括聊天栏、快捷栏）都染上这层颜色。
        boolean tinted = applyVerityTint();
        try {
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
        } finally {
            if (tinted) {
                RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            }
        }
    }

    /**
     * 按原版 {@code SphereEntityRenderer} 的规则给头像着色。
     *
     * <p>规则与原版逐条对齐：
     * <ul>
     *   <li>{@code COLOR == 0}（默认）→ 固定 {@code (255,225,30)}；</li>
     *   <li>{@code COLOR > 360} → 当作 {@code 0xRRGGBB} 直接用；</li>
     *   <li>否则 → 当作 0~360 的色相，饱和度与亮度取 1。</li>
     * </ul>
     *
     * @return 是否真的设置了着色（没设置就不用复原）
     */
    private static boolean applyVerityTint() {
        int rgb = verityTintRgb();
        if (rgb < 0) return false;
        RenderSystem.setShaderColor(
                ((rgb >> 16) & 0xFF) / 255.0F,
                ((rgb >> 8) & 0xFF) / 255.0F,
                (rgb & 0xFF) / 255.0F,
                1.0F);
        return true;
    }

    /**
     * 原版当前的颜色设置对应的 RGB。
     *
     * @return {@code 0xRRGGBB}；读不到这个配置时返回 -1（表示「不处理，按贴图原色画」）
     */
    private static int verityTintRgb() {
        Object raw = readVerityColor();
        if (!(raw instanceof Integer color)) return -1;
        if (color == 0) {
            // 原版的默认值就是「不填颜色」，此时它自己用这个固定的黄
            return 0xFFE11E;
        }
        if (color > 360) {
            return color & 0xFFFFFF;
        }
        return Mth.hsvToRgb(color / 360.0F, 1.0F, 1.0F) & 0xFFFFFF;
    }

    /** 读原版 {@code VerityConfig.COLOR} 的当前值；读不到返回 null。 */
    private static Object readVerityColor() {
        try {
            if (!verityColorResolved) {
                verityColorResolved = true;
                Class<?> config = ReflectiveTarget.type("varmite.verity.VerityConfig", "[HudTint]");
                verityColorField = ReflectiveTarget.field(config, "COLOR", "[HudTint]");
            }
            Field field = verityColorField;
            if (field == null) return null;
            Object holder = ReflectiveTarget.get(field, null);
            // ForgeConfigSpec.ConfigValue 实现了 Supplier，直接取当前值比反射调 get() 省事
            if (holder instanceof java.util.function.Supplier<?> supplier) {
                return supplier.get();
            }
            return null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 优先使用当前皮肤的动态纹理（会跟随实体表情），否则回退到原版表情贴图。 */
    /** Share the existing skin/expression crop and tint with narrative dialogue; no copied assets. */
    public static void drawStoryPortrait(GuiGraphics graphics,int x,int y,int size) {
        ResourceLocation texture=portraitTexture(VerityDialogueFeed.displayVariant());
        if(texture!=null)drawCircularPortrait(graphics,texture,x,y,size);
    }

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
        Font font=minecraft.font;
        boolean moving=VerityNavigationService.current()!=null;
        boolean following=VerityNavigationService.isFollowing();
        String state=moving?"导航中":following?"跟随中":"陪伴";
        String name=com.hanchen.veritycustomapi.compat.VeritySpeaker.currentName();
        double credits=ConfigManager.getCreditsRemainingExact();
        VerityEntity entity=VerityDialogueFeed.nearestVerity();
        float scale=entity==null?1.0F:VerityGrowthState.getClientScale(entity);
        // Growth is body size, not karma. Omit the meaningless +0 at the normal size.
        boolean showSize=entity!=null&&Math.abs(scale-1.0F)>=0.05F;
        String size=String.format(java.util.Locale.ROOT,"体型 %.1f 倍",scale);
        String memories=Integer.toString(memoryCount());
        String conversations=Integer.toString(VerityDialogueFeed.todayCount());
        int desired=Math.max(142,Math.max(font.width("记忆 "+memories)+font.width("今日对话 "+conversations)+24,
                credits>=0?font.width("额度 "+ConfigManager.formatCredits(credits))+16:0));
        HudLayout.Box box=HudLayout.status(screenWidth,desired,credits>=0,showSize);
        int x=box.x(),y=box.y(),w=box.width();
        drawHudPanel(graphics,x,y,w,box.height());
        int inner=w-12;
        String status=font.plainSubstrByWidth(state,Math.max(1,inner/2));
        int stateWidth=font.width(status);
        String title=font.plainSubstrByWidth(name,Math.max(1,inner-stateWidth-12));
        graphics.drawString(font,title,x+6,y+5,ACCENT,false);
        graphics.drawString(font,status,x+w-6-stateWidth,y+5,moving||following?0xFF9DCD91:MUTED,false);
        graphics.fill(x+5,y+17,x+w-5,y+18,0xFF484848);
        int gap=8,colWidth=Math.max(1,(inner-gap)/2);
        drawBadgeMetric(graphics,font,"记忆",memories,x+6,y+23,colWidth,TEXT);
        drawBadgeMetric(graphics,font,"今日对话",conversations,x+6+colWidth+gap,y+23,colWidth,TEXT);
        int rowY=y+38;
        if(credits>=0){
            int colour=ConfigManager.isCreditsExhausted()?0xFFFF8888:ConfigManager.isCreditsLow()?0xFFFFCC66:TEXT;
            drawBadgeMetric(graphics,font,"额度",ConfigManager.formatCredits(credits),x+6,rowY,inner,colour);
            rowY+=13;
        }
        if(showSize)graphics.drawString(font,font.plainSubstrByWidth(size,inner),x+6,rowY,MUTED,false);
    }

    /** Right-aligned values; reserve a gap so long counts can never collide with labels. */
    private static void drawBadgeMetric(GuiGraphics graphics,Font font,String label,String value,
                                        int x,int y,int width,int valueColour) {
        String shown=font.plainSubstrByWidth(value,Math.max(1,width-6));
        int valueWidth=font.width(shown);
        String caption=font.plainSubstrByWidth(label,Math.max(0,width-valueWidth-6));
        graphics.drawString(font,caption,x,y,MUTED,false);
        graphics.drawString(font,shown,x+width-valueWidth,y,valueColour,false);
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
        int centerY = HudLayout.navigationY(screenHeight);
        graphics.pose().pushPose();
        graphics.pose().translate(centerX, centerY, 0.0F);
        graphics.pose().mulPose(Axis.ZP.rotationDegrees(relative));
        graphics.fill(-1, -5, 1, 8, ARROW);
        for (int i = 0; i <= 5; i++) {
            graphics.fill(-i, -10 + i, i + 1, -9 + i, ARROW);
        }
        graphics.pose().popPose();

        String label = minecraft.font.plainSubstrByWidth(target.label(),Math.max(20,Math.min(120,screenWidth/3))) + "  " + Math.round(distance) + " 格";
        Font font = minecraft.font;
        graphics.drawString(font, label, centerX - font.width(label) / 2, centerY + 12, ARROW, true);
    }
}
