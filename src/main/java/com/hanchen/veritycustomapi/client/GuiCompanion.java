package com.hanchen.veritycustomapi.client;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import varmite.verity.entity.utils.SphereEntityRenderer;
import varmite.verity.entity.utils.SphereRenderHelper;
import varmite.verity.entity.verity.VerityEntity;

import java.util.List;

/**
 * 「界面里的 Verity」：打开背包 / 箱子等容器界面时，她会从窗口角上探出头来。
 *
 * <p>🔴 <b>不能用原版那套「在背包里渲染生物」的接口</b>（{@code renderEntityInInventoryFollowsAngle}）：
 * 它是给人形模型用的，而 Verity 是 {@link SphereRenderHelper} 画的 <b>billboard 网格</b>——
 * 朝向按「观察者位置 − 实体位置」计算。GUI 里实体坐标是屏幕像素，这个差值毫无意义，
 * 结果就是渲染成几像素的小点，而且每帧朝向乱翻（看着像抽搐）。
 *
 * <p>所以这里直接调 {@code SphereRenderHelper.renderEntityBillboard}，
 * 自己给一个固定的「观察者」坐标（正前方），球体纹理就正对屏幕。
 */
@Mod.EventBusSubscriber(modid = OllamaProxyMod.MOD_ID, value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class GuiCompanion {

    /** 是否启用（快捷键切换）。 */
    private static boolean enabled = true;

    /** 平滑后的视线偏移。 */
    private static float lookX;
    private static float lookY;
    /** 动画计时。 */
    private static float time;
    private static long lastNanos;
    /** 已渲染过一次的界面类名（切界面时打一行日志，便于排查「按了没反应」）。 */
    private static String lastLoggedScreen;
    /** 异常只打一次，避免每帧刷屏。 */
    private static boolean errorLogged;

    /** 显示尺寸：像素/单位。球体直径 0.5 单位，150 ≈ 75 像素高。 */
    private static final float SIZE = 150.0F;

    private GuiCompanion() {
    }

    public static void toggle() {
        enabled = !enabled;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    @SubscribeEvent
    public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
        if (!enabled) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        Screen screen = event.getScreen();
        if (!(screen instanceof AbstractContainerScreen<?>)) return;

        VerityEntity verity = findVerity(mc);
        if (verity == null) return;

        String screenName = screen.getClass().getName();
        if (!screenName.equals(lastLoggedScreen)) {
            lastLoggedScreen = screenName;
            OllamaProxyMod.LOGGER.info("[界面陪伴] 开始渲染：screen={} entity={}",
                    screenName, verity.getId());
        }

        ResourceLocation texture;
        try {
            EntityRenderer<?> renderer = mc.getEntityRenderDispatcher().getRenderer(verity);
            if (!(renderer instanceof SphereEntityRenderer sphereRenderer)) return;
            texture = sphereRenderer.getTextureLocation(verity);
        } catch (Throwable error) {
            logOnce(error);
            return;
        }

        int width = screen.width;
        int height = screen.height;
        // 贴着容器面板的右边站 —— 之前按屏幕宽度算，界面一宽就离得很远
        float centerX;
        float centerY;
        if (screen instanceof AbstractContainerScreen<?> container) {
            // 之前只留了 52 像素，球心还是压在面板边缘上（2026-11-01 截图）。
            // 按「实际绘制半径 + 间距」算，右边放不下就自动挪到左边。
            float radius = SIZE * 0.42F;
            float gap = 18.0F;
            float rightX = container.getGuiLeft() + container.getXSize() + radius + gap;
            float leftX = container.getGuiLeft() - radius - gap;
            if (rightX + radius <= width - 4.0F) {
                centerX = rightX;
            } else if (leftX - radius >= 4.0F) {
                centerX = leftX;
            } else {
                centerX = width - radius - 6.0F;
            }
            centerY = container.getGuiTop() + 58.0F;
        } else {
            centerX = width - Math.max(58.0F, width * 0.10F);
            centerY = height * 0.40F;
        }
        // 面板贴边时别把她挤出屏幕
        centerX = Math.min(centerX, width - 26.0F);
        centerY = Math.max(30.0F, Math.min(centerY, height - 30.0F));

        // 鼠标 → 轻微位移与倾斜（球体只有一张脸，位移比转头更自然）
        double mouseX = mc.mouseHandler.xpos() * (double) mc.getWindow().getGuiScaledWidth()
                / (double) mc.getWindow().getScreenWidth();
        double mouseY = mc.mouseHandler.ypos() * (double) mc.getWindow().getGuiScaledHeight()
                / (double) mc.getWindow().getScreenHeight();
        float targetX = (float) Math.max(-40.0D, Math.min(40.0D, (mouseX - centerX) * 0.20D));
        float targetY = (float) Math.max(-40.0D, Math.min(40.0D, (mouseY - centerY) * 0.20D));

        float dt = deltaSeconds();
        time += dt;
        float k = Math.min(1.0F, dt * 8.0F);
        lookX += (targetX - lookX) * k;
        lookY += (targetY - lookY) * k;

        // 呼吸 / 轻微果冻：纵向伸缩，横向补偿
        float breathe = (float) Math.sin(time * 1.8D) * 0.03F;
        float roll = (float) Math.sin(time * 1.1D) * 4.0F + lookX * 0.30F;

        GuiGraphics graphics = event.getGuiGraphics();
        PoseStack pose = graphics.pose();
        MultiBufferSource.BufferSource buffers = graphics.bufferSource();
        try {
            // 🔴 不要关深度测试：球体是实心网格，关掉之后它的背面会透到正面来，
            // 表现就是「角度一变出现奇怪的三角明暗」（2026-11-01 第二张截图）。
            // 正常参与深度比较，画在 Render.Post 里本来就在最上层。
            pose.pushPose();
            // GUI 的 y 轴朝下，所以 Y 方向翻转；z 给一点偏移避免和面板抢深度
            pose.translate(centerX + lookX * 0.30F, centerY + lookY * 0.30F, 0.0D);
            pose.scale(SIZE, SIZE, SIZE);
            // 🔴 不能写负的 Y 缩放：负缩放会翻转三角形绕序，背面剔除失效后看到的是球体内壁，
            // 表现就是「角度一变就出现奇怪的三角明暗」（2026-11-01 截图）。改用旋转 180°。
            pose.mulPose(Axis.ZP.rotationDegrees(180.0F));
            // 抵消 renderEntityBillboard 内部的 +0.25 抬升，让球心落在锚点上
            pose.translate(0.0D, -0.25D, 0.0D);
            // 🔴 不能用 0xF000F0（全亮）：6.0.0 的贴图本身是奶白 #fffdd5，
            // 再全亮打一遍直接发白（docs/_verity_uv/UV映射参数.md 里记过）。
            // 取她所在方块的世界光照，界面里的颜色就和世界里一致。
            // 🔴 6.0.0 的贴图是奶白 #fffdd5，直接画出来偏灰白。
            // 想要「印象里的饱和黄 #eade19」，就用满亮 + 顶点色相乘：
            //   奶白(255,253,213) × (234,222,25)/255 ≈ (234,220,21) ≈ #eade19
            // 所以这里必须是满亮，否则再乘上光照就偏暗了。
            SphereRenderHelper.renderEntityBillboard(pose, buffers, texture, 0xF000F0,
                    0.0D, 0.0D, 0.0D,
                    0.0D, 0.0D, 10.0D,
                    1.0F + breathe, 1.0F - breathe * 0.6F,
                    234, 222, 25, 255,
                    roll, 0.0F);
            pose.popPose();
            graphics.flush();
        } catch (Throwable error) {
            logOnce(error);
        }
    }

    /** 找玩家附近最近的一只活 Verity（客户端实体即可）。 */
    private static VerityEntity findVerity(Minecraft mc) {
        if (mc.player == null || mc.level == null) return null;
        try {
            List<VerityEntity> list = mc.level.getEntitiesOfClass(VerityEntity.class,
                    mc.player.getBoundingBox().inflate(64.0D));
            VerityEntity best = null;
            double bestDistance = Double.MAX_VALUE;
            for (VerityEntity verity : list) {
                if (verity == null || !verity.isAlive() || verity.isRemoved()) continue;
                double distance = mc.player.distanceToSqr(verity);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = verity;
                }
            }
            return best;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void logOnce(Throwable error) {
        if (errorLogged) return;
        errorLogged = true;
        OllamaProxyMod.LOGGER.warn("[界面陪伴] 渲染失败: {}", error.toString());
    }

    private static float deltaSeconds() {
        long now = System.nanoTime();
        if (lastNanos == 0L) {
            lastNanos = now;
            return 0.016F;
        }
        float dt = (now - lastNanos) / 1_000_000_000.0F;
        lastNanos = now;
        return Math.max(0.001F, Math.min(0.1F, dt));
    }

    /** 退出世界时复位。 */
    public static void clear() {
        lookX = 0.0F;
        lookY = 0.0F;
        time = 0.0F;
        lastNanos = 0L;
        lastLoggedScreen = null;
        errorLogged = false;
    }

    /** 供外部读取（其它 UI 想避开这块区域时用）。 */
    public static Entity peekEntity(Minecraft mc) {
        return findVerity(mc);
    }
}
