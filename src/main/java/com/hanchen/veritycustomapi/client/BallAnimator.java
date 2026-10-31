package com.hanchen.veritycustomapi.client;

import com.hanchen.veritycustomapi.compat.VerityEntityTalkAnimator;
import com.hanchen.veritycustomapi.config.ModConfig;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import varmite.verity.entity.verity.VerityEntity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 球体「活着」的动画器：呼吸、走动、跳跃、落地、转向倾斜。
 *
 * <p><b>为什么不用直接算正弦</b>：硬编码的 {@code sin(t)} 每次状态切换（走→停、空中→着地）
 * 都是瞬间跳到新曲线，看起来像「代码动画」。这里改成**弹簧**：
 * 每帧朝目标形变值收敛，但带速度、欠阻尼，所以会自己冲过头再回弹——落地压扁、
 * 起跳拉长、停下时晃两下，都是同一条弹簧自然产生的，不需要给每个动作单独写曲线。
 *
 * <p>状态按实体 id 存，客户端每帧更新一次（{@code partialTick} 只用于步频相位）。
 * 纯渲染层：不碰碰撞箱、寻路、伤害。
 */
public final class BallAnimator {

    /** 弹簧参数：k 越大越硬，damp 越小回弹越明显。 */
    private static final float STIFFNESS = 230.0F;
    private static final float DAMPING = 7.5F;
    /** 单帧最多积分多久（切后台回来时 dt 会很大，夹住避免爆炸）。 */
    private static final float MAX_DT = 0.1F;
    /** 弹簧积分子步数：dt 偏大时分成几步，保证稳定。 */
    private static final int SUBSTEPS = 4;

    /** 静止时的呼吸幅度与频率。 */
    private static final float BREATH_AMPLITUDE = 0.022F;
    private static final float BREATH_SPEED = 1.7F;
    /** 走路时每一步的压扁幅度。 */
    private static final float STEP_SQUASH = 0.17F;
    /** 下落时的最大拉长。 */
    private static final float FALL_STRETCH = 0.30F;
    /** 落地冲击给弹簧的初速度（越大弹得越狠）。 */
    private static final float LAND_IMPULSE = 3.2F;
    /** 转向倾斜：每 1 格/秒的横向速度倾斜多少度。 */
    private static final float LEAN_PER_SPEED = 16.0F;
    private static final float LEAN_MAX = 22.0F;

    private static final Map<Integer, State> STATES = new ConcurrentHashMap<>();

    private BallAnimator() {
    }

    /** 一帧的渲染修正量；由调用方乘/加到原版的参数上。 */
    public static final class Pose {
        public float stretchY = 1.0F;
        public float stretchXZ = 1.0F;
        /** 屏幕空间的额外滚动（度），用来做「往哪走往哪倒」。 */
        public float rollAdd = 0.0F;
        /** 垂直偏移（格），压扁时整体下沉一点，视觉上像踩实了地面。 */
        public double yOffset = 0.0D;
    }

    private static final class State {
        long lastNanos;
        float squash = 1.0F;
        float squashVel;
        float lean;
        float leanVel;
        float lastAudio;
        float time;
        boolean wasOnGround = true;
        boolean wasWall;
        boolean wasCeiling;
        float wallRock;
        float walkPhase;
        double lastFallSpeed;
        double lastX = Double.NaN;
        double lastZ = Double.NaN;
    }

    /**
     * 采样一帧的动画。
     *
     * @param viewerX 摄像机/观察者位置，用来算「屏幕右方向」，从而决定往哪边倾
     */
    public static Pose sample(VerityEntity entity, float partialTick,
                              double viewerX, double viewerY, double viewerZ) {
        Pose pose = new Pose();
        if (entity == null) return pose;

        State state = STATES.computeIfAbsent(entity.getId(), ignored -> new State());

        // ---- 时间步 ----
        long now = System.nanoTime();
        float dt = state.lastNanos == 0L ? 0.016F : (now - state.lastNanos) / 1_000_000_000.0F;
        state.lastNanos = now;
        dt = Mth.clamp(dt, 0.001F, MAX_DT);
        state.time += dt;

        boolean onGround = entity.onGround();
        Vec3 motion = entity.getDeltaMovement();
        double fallSpeed = Math.max(0.0D, -motion.y);

        // 🔴 不能用 deltaMovement 判「在不在走」：远程实体的位置是同步包 lerp 过来的，
        // 客户端上 deltaMovement 常常恒为 0（2026-10-31 实测：走路动画完全不触发）。
        // 用逐帧位置差算速度，才是真实位移。
        // 🔴 必须用 isNaN：`x == Double.NaN` 恒为 false，会导致 dx 一直是 NaN，
        // 一路污染到 stretch/roll，最终渲染矩阵退化 —— 表现就是「球体隐身」。
        if (Double.isNaN(state.lastX) || Double.isNaN(state.lastZ)) {
            state.lastX = entity.getX();
            state.lastZ = entity.getZ();
        }
        double dx = entity.getX() - state.lastX;
        double dz = entity.getZ() - state.lastZ;
        state.lastX = entity.getX();
        state.lastZ = entity.getZ();
        double movedDist = Math.sqrt(dx * dx + dz * dz);
        float moveSpeed = (float) Math.min(2.0D, movedDist / dt);
        double horizSpeed = Math.max(
                Math.sqrt(motion.x * motion.x + motion.z * motion.z), moveSpeed);

        // 步频按位移累加：每 0.35 格左右一步
        state.walkPhase += (float) (movedDist * 9.0D);
        float walkSpeed = Mth.clamp(moveSpeed * 1.6F, 0.0F, 1.0F);
        float walkPhase = state.walkPhase;

        // ---- 目标形变 ----
        float target;
        if (!onGround && fallSpeed > 0.05D) {
            // 下落：纵向拉长
            target = 1.0F + Math.min(FALL_STRETCH, (float) fallSpeed * 0.12F);
        } else if (walkSpeed > 0.05F) {
            // 走路：每一步压一下（步频相位驱动），落地那一下最深
            float step = (float) Math.abs(Math.sin(walkPhase * Math.PI));
            target = 1.0F - STEP_SQUASH * walkSpeed * step;
        } else {
            // 站着：很轻的呼吸，避免完全静止像贴图
            target = 1.0F + BREATH_AMPLITUDE * (float) Math.sin(state.time * BREATH_SPEED);
        }

        // ---- 说话 Q 弹：每个音节给弹簧一次冲量，回弹完全交给弹簧 ----
        float audio = VerityEntityTalkAnimator.getAudioLevel(entity.getId());
        boolean talking = audio > 0.01F
                || entity.clientIsTalking
                || VerityEntityTalkAnimator.isTalking(entity.getId());
        float squashFactor = 0.55F;
        if (talking && jellyEnabled()) {
            float amp = (float) jellyAmplitude();
            float scale = amp / 0.45F;
            if (jellyChaos()) {
                scale *= 1.6F;
            }
            // 音频包络「涨上来」的那一下才打冲量：一句话里每个字都弹一次
            if (audio > state.lastAudio + 0.03F) {
                state.squashVel -= (0.9F + 1.8F * audio) * scale;
            }
            // 说话时整体略扁一点，像被声音压着
            target -= 0.05F * audio * scale;
            squashFactor = (float) jellySquash();
            state.lastAudio = audio;
        } else {
            state.lastAudio = 0.0F;
        }

        // ---- 落地冲击：给弹簧一个向下的初速度，剩下的交给物理 ----
        if (!state.wasOnGround && onGround) {
            float power = Mth.clamp((float) state.lastFallSpeed / 0.9F, 0.0F, 1.0F);
            if (power > 0.08F) {
                state.squashVel -= LAND_IMPULSE * power;
            }
        }
        state.wasOnGround = onGround;
        if (!onGround) {
            state.lastFallSpeed = fallSpeed;
        }

        // ---- 碰撞反应：撞墙、顶天花板也弹一下 ----
        // 一直贴着墙推的时候 horizontalCollision 会持续为 true，所以只在「刚撞上」那一下打冲量，
        // 否则会变成每帧都在抖。
        boolean wall = entity.horizontalCollision;
        if (wall && !state.wasWall && horizSpeed > 0.06D) {
            float power = Mth.clamp((float) horizSpeed * 3.5F, 0.25F, 1.0F);
            state.squashVel -= 1.1F * power;
            // 撞哪边就往反方向倒一下（用随机正负代替方向判定，避免依赖相机）
            state.wallRock = (entity.getRandom().nextBoolean() ? 1.0F : -1.0F) * 7.0F * power;
        }
        if (!wall) {
            state.wallRock *= 0.82F;
            if (Math.abs(state.wallRock) < 0.05F) state.wallRock = 0.0F;
        }
        state.wasWall = wall;

        boolean ceiling = entity.verticalCollision && motion.y > 0.02D;
        if (ceiling && !state.wasCeiling) {
            // 头撞天花板：向上顶一下 → 纵向拉长
            state.squashVel += 1.4F;
        }
        state.wasCeiling = ceiling;

        // ---- 弹簧积分（子步，保证稳定） ----
        float sub = dt / SUBSTEPS;
        for (int i = 0; i < SUBSTEPS; i++) {
            state.squashVel += (target - state.squash) * STIFFNESS * sub;
            state.squashVel -= state.squashVel * DAMPING * sub;
            state.squash += state.squashVel * sub;
        }
        state.squash = Mth.clamp(state.squash, 0.55F, 1.50F);

        // ---- 转向倾斜：往哪边走就往哪边倒（复用上面算好的逐帧位移）----
        double moveX = motion.x;
        double moveZ = motion.z;
        if (movedDist > 0.0005D) {
            moveX = dx;
            moveZ = dz;
        }

        float leanTarget = 0.0F;
        if (horizSpeed > 0.02D) {
            // 屏幕右方向 = 视线方向 × 上方向（y 轴）
            double vx = entity.getX() - viewerX;
            double vz = entity.getZ() - viewerZ;
            double viewLen = Math.sqrt(vx * vx + vz * vz);
            if (viewLen > 0.01D) {
                double rightX = -vz / viewLen;
                double rightZ = vx / viewLen;
                double dot = (moveX * rightX + moveZ * rightZ);
                leanTarget = (float) Mth.clamp(dot * LEAN_PER_SPEED * 4.0D, -LEAN_MAX, LEAN_MAX);
            }
        }
        // 倾斜也走弹簧，转方向时有个自然的滞后
        for (int i = 0; i < SUBSTEPS; i++) {
            state.leanVel += (leanTarget - state.lean) * STIFFNESS * sub;
            state.leanVel -= state.leanVel * DAMPING * sub;
            state.lean += state.leanVel * sub;
        }

        // ---- 输出 ----
        // 🔴 所有输出都做「NaN 兜底 + 范围钳制」：弹簧一旦算出 NaN/Inf，
        // 传进渲染矩阵会让整个四边形退化 → 玩家看到的就是「球体隐身」。
        if (!Float.isFinite(state.squash) || !Float.isFinite(state.squashVel)) {
            state.squash = 1.0F;
            state.squashVel = 0.0F;
        }
        if (!Float.isFinite(state.lean) || !Float.isFinite(state.wallRock)
                || !Float.isFinite(state.walkPhase)) {
            state.lean = 0.0F;
            state.leanVel = 0.0F;
            state.wallRock = 0.0F;
            state.walkPhase = 0.0F;
        }
        float squash = Mth.clamp(state.squash, 0.60F, 1.45F);
        pose.stretchY = squash;
        // 体积守恒：压扁时横向鼓起来（鼓多少由「Q 弹压扁」配置决定）
        pose.stretchXZ = Mth.clamp(1.0F + (1.0F - squash) * squashFactor, 0.65F, 1.75F);
        // 滚动跟着弹簧速度一起摇：弹一下晃一下，比单纯倾斜更有果冻感
        pose.rollAdd = Mth.clamp(state.lean + state.squashVel * 2.2F + state.wallRock, -25.0F, 25.0F);
        pose.yOffset = Mth.clamp((1.0F - squash) * 0.22D, -0.12D, 0.12D);

        // 待机也保持「活着」的微动：站着不动时轻轻上下浮 + 左右微晃，
        // 免得停住就变成一张贴图。幅度刻意很小，只提供呼吸感，不抢戏。
        float idle = Math.max(0.0F, 1.0F - walkSpeed) * (onGround ? 1.0F : 0.0F);
        if (idle > 0.01F) {
            pose.yOffset += Math.sin(state.time * 1.6D) * 0.008D * idle;
            pose.rollAdd += (float) Math.sin(state.time * 0.9D) * 1.2F * idle;
        }
        return pose;
    }

    private static boolean jellyEnabled() {
        try {
            return ModConfig.VERITY_JELLY_ENABLED.get();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static double jellyAmplitude() {
        try {
            return ModConfig.VERITY_JELLY_AMPLITUDE.get();
        } catch (Throwable ignored) {
            return 0.45D;
        }
    }

    private static boolean jellyChaos() {
        try {
            return ModConfig.VERITY_JELLY_CHAOS.get();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static double jellySquash() {
        try {
            return ModConfig.VERITY_JELLY_SQUASH.get();
        } catch (Throwable ignored) {
            return 0.85D;
        }
    }

    /** 退出世界时清空（实体 id 每存档从 0 开始）。 */
    public static void clear() {
        STATES.clear();
    }
}
