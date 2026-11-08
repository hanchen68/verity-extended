package com.hanchen.veritycustomapi.client;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import varmite.verity.entity.verity.VerityEntity;

import java.util.List;

/**
 * 「第二视角」：临时通过 Verity 的眼睛看世界。
 *
 * <p>你留在原地不动，镜头移到她眼睛的位置；**朝向仍然由你的鼠标控制**，
 * 所以是「借她的位置看」，而不是「被她拖着转头」——后者在聊天指挥时会晕。
 *
 * <p>三条边界：
 * <ul>
 *   <li>距离超过 {@link #MAX_DISTANCE} 格自动退出（远处区块客户端根本没加载，看了也是虚空）；</li>
 *   <li>期间**冻结玩家移动输入**，避免镜头在山上、人却盲走出悬崖；</li>
 *   <li>她死掉 / 消失 / 换维度 / 开任何界面，都会立刻退出。</li>
 * </ul>
 *
 * <p>纯客户端：不改服务端位置，不涉及权限，退出即恢复。
 */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = OllamaProxyMod.MOD_ID,
        value = net.minecraftforge.api.distmarker.Dist.CLIENT,
        bus = net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus.FORGE)
public final class SecondSightManager {

    /**
     * 超过这个距离自动退出。
     *
     * <p>原来取 64 格，但客户端在默认视距下只保证实体/区块加载到约 40~48 格：
     * 55 格外的她经常已经不在 {@code level.getEntity} 的可见范围内，
     * 表现就是「突然掉出视角」或镜头卡在虚空里。收到 40 格留出余量，
     * 并在接近上限时提前提示退出，不做无预兆的硬切。
     */
    public static final double MAX_DISTANCE = 40.0D;
    /** 第三人称镜头离她多远（格）。 */
    private static final double CAMERA_BACK = 4.5D;
    /** 镜头比她的眼睛高多少（格），俯视一点更好看清球体与前方地形。 */
    private static final double CAMERA_UP = 1.2D;
    /** 镜头每秒收敛的比例（越大越跟手、越小越平滑）。与帧率无关。 */
    private static final double SMOOTH_PER_SECOND = 22.0D;
    /** 单帧允许的最大步长：卡顿/断点续帧时不能让镜头瞬移。 */
    private static final double MAX_FRAME_SECONDS = 0.1D;

    private static boolean active;
    private static int entityId = -1;
    /** 平滑后的镜头位置，避免她走路时镜头一跳一跳。 */
    private static double camX;
    private static double camY;
    private static double camZ;
    private static boolean hasCam;
    /** 上一帧的时间戳（nanoTime），用于把平滑折算成按秒收敛。 */
    private static long lastFrameNanos;
    /**
     * 渲染线程退出时挂起、等 tick 补发的提示。
     *
     * <p>渲染线程不能直接发聊天消息（要碰 GUI 状态），但也不能静默退出——
     * 否则玩家只看到镜头瞬移回去，以为卡了。
     */
    private static String pendingNotice;

    private SecondSightManager() {
    }

    public static boolean isActive() {
        return active;
    }

    /** 快捷键 / 命令入口。成功切换返回 true。 */
    public static boolean toggle(Minecraft mc) {
        if (active) {
            stop();
            return true;
        }
        VerityEntity verity = findVerity(mc);
        if (verity == null) return false;
        active = true;
        entityId = verity.getId();
        hasCam = false;
        return true;
    }

    public static void stop() {
        active = false;
        entityId = -1;
        hasCam = false;
        // 必须清掉时间基准：下次进入视角如果沿用上一次的时间戳，
        // 第一帧会算出一个巨大的 dt（被夹到上限），镜头会突然弹一下。
        lastFrameNanos = 0L;
    }
    /** Input must be suppressed BEFORE LocalPlayer consumes it, not at ClientTick.END. */
    @net.minecraftforge.eventbus.api.SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public static void onMovementInput(net.minecraftforge.client.event.MovementInputUpdateEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (!active || event.getEntity() != mc.player) return;
        onClientTick(mc); // Validate identity/range first; never block input after leaving the view.
        if (!active) return;
        net.minecraft.client.player.Input input = event.getInput();
        input.leftImpulse = 0.0F;
        input.forwardImpulse = 0.0F;
        input.up = false;
        input.down = false;
        input.left = false;
        input.right = false;
        input.jumping = false;
        input.shiftKeyDown = false;
        mc.player.setSprinting(false);
    }

    @net.minecraftforge.eventbus.api.SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.HIGHEST)
    public static void onInteraction(net.minecraftforge.client.event.InputEvent.InteractionKeyMappingTriggered event) {
        if (!active) return;
        event.setCanceled(true);
        event.setSwingHand(false);
    }

    /** 客户端 tick：校验是否还该继续。移动拦截由 MovementInputUpdateEvent 提前处理。 */
    public static void onClientTick(Minecraft mc) {
        // 渲染线程挂起的退出提示在这里补发（每 tick 最多一次，发完即清）
        if (pendingNotice != null) {
            String notice = pendingNotice;
            pendingNotice = null;
            if (mc != null && mc.player != null) {
                mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(notice), true);
            }
        }
        if (!active) return;
        if (mc.player == null || mc.level == null || mc.screen != null || !mc.player.isAlive()
                || mc.player.isPassenger() || mc.player.isSleeping()) {
            stopWithReason(mc, mc.screen != null ? "" : "视角已结束");
            return;
        }
        Entity entity = mc.level.getEntity(entityId);
        if (!(entity instanceof VerityEntity verity) || !verity.isAlive() || verity.isRemoved()) {
            // 实体被卸载时 getEntity 也会返回 null：这时不要说「她死了」，说清楚是失去了目标。
            stopWithReason(mc, entity == null ? "已失去同伴位置，退出视角" : "同伴已离开，退出视角");
            return;
        }
        if (mc.player.distanceToSqr(verity) > MAX_DISTANCE * MAX_DISTANCE) {
            stopWithReason(mc, "距离太远，退出视角");
            return;
        }
        // 冻结移动输入：镜头在前方，人不能盲走
        try {
            mc.player.input.leftImpulse = 0.0F;
            mc.player.input.forwardImpulse = 0.0F;
            mc.player.input.jumping = false;
            mc.player.input.shiftKeyDown = false;
        } catch (Throwable ignored) {
            // 输入结构变化时不影响退出
        }
    }

    /**
     * 主动退出时给一句话。
     *
     * <p>原来自动退出是静默的：玩家只看到镜头突然回到自己身上，会以为卡了或者按错了键。
     * 明确告知原因，才不会反复按 R 试。
     */
    private static void stopWithReason(Minecraft mc, String reason) {
        stop();
        if (mc != null && mc.player != null && reason != null && !reason.isBlank()) {
            mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(reason), true);
        }
    }

    /**
     * 由 {@code Camera} 注入点调用：返回这一帧镜头应该待的位置（null = 不接管）。
     *
     * <p>为什么不让这里直接改 Camera：1.20.1 的 {@code setPosition} 是 <b>protected</b>，
     * 外部类调不到；注入点自己用 {@code @Shadow} 改字段才是干净做法。
     *
     * @param thirdPerson 原版是否已经在第三人称（此时不能再额外后退，否则镜头离她两倍远）
     */
    public static Vec3 sampleCameraPosition(float partialTick, boolean thirdPerson) {
        if (!active) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        Entity entity = mc.level.getEntity(entityId);
        if (!(entity instanceof VerityEntity verity) || !verity.isAlive()) {
            // 渲染线程：只收掉接管并把提示挂起，实际发消息由 onClientTick 负责
            pendingNotice = entity == null ? "已失去同伴位置，退出视角" : "同伴已离开，退出视角";
            stop();
            return null;
        }
        // 🔴 球体没有「眼睛」，第一人称等于把镜头塞进球里（什么都看不到）。
        // 改成第三人称：镜头退到她身后 CAMERA_BACK 格、抬高一点，看的是「她 + 她前方」。
        Vec3 eye = verity.getEyePosition(partialTick);
        Vec3 look = mc.player == null ? Vec3.ZERO : mc.player.getViewVector(partialTick);
        if (look.lengthSqr() < 1.0E-6D) {
            look = new Vec3(0.0D, 0.0D, 1.0D);
        }
        look = look.normalize();
        // 原版第三人称已经自己后退过一次；再退 CAMERA_BACK 会变成「离她很远的第三人称」，
        // 玩家看到的就是镜头突然窜出去、再被墙剪回来——这也是「不稳」的来源之一。
        double back = thirdPerson ? 0.0D : CAMERA_BACK;
        Vec3 target = eye.subtract(look.scale(back)).add(0.0D, CAMERA_UP, 0.0D);

        // 身后有墙就把镜头拉近，避免穿进方块里（看着一片黑）
        try {
            net.minecraft.world.level.ClipContext ctx = new net.minecraft.world.level.ClipContext(
                    eye.add(0.0D, CAMERA_UP, 0.0D), target,
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, verity);
            net.minecraft.world.phys.BlockHitResult hit = mc.level.clip(ctx);
            if (hit != null && hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
                target = hit.getLocation().add(look.scale(0.35D));
            }
        } catch (Throwable ignored) {
            // 拿不到碰撞就按原距离来
        }

        if (!hasCam) {
            camX = target.x;
            camY = target.y;
            camZ = target.z;
            hasCam = true;
        } else {
            // 平滑必须按时间算，不能按帧。
            // 原来每帧固定走 45%：60fps 下约 0.03s 收敛、240fps 下 0.008s，
            // 帧率一抖镜头就忽快忽慢，看起来就是「视角不稳/在漂」。
            // 这里用「每秒收敛比例」再按 DT 折算，帧率高低走同样的速度。
            double k = smoothingFactor(frameSeconds());
            camX += (target.x - camX) * k;
            camY += (target.y - camY) * k;
            camZ += (target.z - camZ) * k;
        }
        return new Vec3(camX, camY, camZ);
    }

    /** 客户端退出世界时清理。 */
    public static void clear() {
        stop();
        // 退出世界时挂起的提示不能留到下一个存档
        pendingNotice = null;
    }

    /**
     * 这一帧的时间步长（秒）。
     *
     * <p>用 nanoTime 而不是 tick 数：镜头在渲染线程按帧插值，tick 只有 20Hz，
     * 用 tick 折算会让高帧率下的平滑完全失效。夹在 (0, MAX_FRAME_SECONDS] 之间，
     * 断点续帧或窗口卡顿后不会一次跳出很远。
     */
    private static double frameSeconds() {
        return clampFrameSeconds(frameSecondsRaw());
    }

    private static double frameSecondsRaw() {
        long now = System.nanoTime();
        long previous = lastFrameNanos;
        lastFrameNanos = now;
        if (previous == 0L) return 1.0D / 60.0D;
        return (now - previous) / 1_000_000_000.0D;
    }

    /**
     * 把「原始帧间隔」夹到安全范围。
     *
     * <p>抽成静态纯函数是为了能测：非法值（NaN / 负数 / 0）与超大间隔都必须被压回正常步长，
     * 否则镜头会一帧瞬移或永久卡住。
     */
    static double clampFrameSeconds(double seconds) {
        if (!Double.isFinite(seconds) || seconds <= 0.0D) return 1.0D / 60.0D;
        return Math.min(seconds, MAX_FRAME_SECONDS);
    }

    /**
     * 按时间折算的平滑系数：同样的经过时间，走同样的比例，与帧率无关。
     *
     * <p>旧实现是每帧固定 {@code 0.45}，60fps 与 240fps 的收敛速度差 4 倍，
     * 帧率一波动镜头就忽快忽慢——这就是玩家说的「不稳」。
     */
    static double smoothingFactor(double seconds) {
        double clamped = clampFrameSeconds(seconds);
        return 1.0D - Math.exp(-SMOOTH_PER_SECOND * clamped);
    }

    private static VerityEntity findVerity(Minecraft mc) {
        if (mc.player == null || mc.level == null) return null;
        try {
            List<VerityEntity> list = mc.level.getEntitiesOfClass(VerityEntity.class,
                    mc.player.getBoundingBox().inflate(MAX_DISTANCE));
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
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.debug("[第二视角] 查找 Verity 失败: {}", error.toString());
            return null;
        }
    }
}
