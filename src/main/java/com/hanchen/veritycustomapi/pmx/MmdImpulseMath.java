package com.hanchen.veritycustomapi.pmx;

/**
 * PMX 冲量 morph（类型 10）的纯数学部分。冲量在权重上升时触发一次，转成速度脉冲用的是上升量而不是绝对权重，
 * 所以表情一直保持着也不会持续往弹簧里灌能量；脉冲还钳了上限，免得单次 morph 把刚体甩得明显脱骨。
 */
public final class MmdImpulseMath {
    /** morph 力矩传进弹簧后保留的比例。 */
    public static final float SCALE = 0.05F;
    /** 单次脉冲的上限，正负对称。 */
    public static final float MAX_PULSE = 0.6F;
    /** 权重上升低于这个值就当噪声。 */
    public static final float MIN_DELTA = 0.001F;

    private MmdImpulseMath() {
    }

    /**
     * 把一个力矩分量转成速度脉冲。
     *
     * @param torque morph 的力矩分量
     * @param delta  本 tick 该 morph 权重的上升量
     * @return {@code [-MAX_PULSE, MAX_PULSE]} 内的脉冲，或不应触发时的 {@code 0}
     */
    public static float pulse(float torque, float delta) {
        if (!Float.isFinite(torque) || !Float.isFinite(delta) || delta <= MIN_DELTA) return 0.0F;
        float scaled = torque * delta * SCALE;
        if (!Float.isFinite(scaled)) return 0.0F;
        return Math.max(-MAX_PULSE, Math.min(MAX_PULSE, scaled));
    }

    /** @return 权重变化够不够算一次新冲量 */
    public static boolean isTriggering(float delta) {
        return Float.isFinite(delta) && delta > MIN_DELTA;
    }
}
