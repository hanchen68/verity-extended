package com.hanchen.veritycustomapi.client;

/**
 * 第二视角（双影模式）的稳定性规则自检。
 *
 * <p>为什么单独测：这一功能的问题都表现为「看起来不稳」，但成因是三个纯数值规则：
 * 平滑是否与帧率绑定、第三人称是否重复后退、距离上限是否超出客户端加载范围。
 * 它们都在渲染循环里，靠肉眼在游戏里试几乎分不出是哪一条坏了，只能把规则钉住。
 */
public final class SecondSightStabilityTest {
    private SecondSightStabilityTest() {
    }

    public static void main(String[] args) {
        frameStepsAreSane();
        smoothingIsFrameRateIndependent();
        cameraDoesNotDoubleBack();
        distanceStaysInsideLoadedChunks();
        System.out.println("第二视角稳定性检查通过");
    }

    /** 非法/极端帧间隔必须被压回正常步长，否则镜头会瞬移或卡死。 */
    private static void frameStepsAreSane() {
        double fallback = 1.0D / 60.0D;
        assertClose(SecondSightManager.clampFrameSeconds(0.0D), fallback, "零间隔应回退到 60fps");
        assertClose(SecondSightManager.clampFrameSeconds(-1.0D), fallback, "负间隔应回退");
        assertClose(SecondSightManager.clampFrameSeconds(Double.NaN), fallback, "NaN 应回退");
        assertClose(SecondSightManager.clampFrameSeconds(Double.POSITIVE_INFINITY), fallback, "无穷应回退");
        double huge = SecondSightManager.clampFrameSeconds(5.0D);
        assertTrue(huge <= 0.1D + 1e-9, "超大间隔必须被夹住，避免一帧瞬移");
        assertTrue(huge > 0.0D, "夹住后仍需为正");
        assertClose(SecondSightManager.clampFrameSeconds(1.0D / 120.0D), 1.0D / 120.0D, "正常间隔不应被改");
    }

    /**
     * 同样的「经过时间」必须走同样的比例。
     *
     * <p>这是本功能最关键的一条：旧的每帧固定系数会让 240fps 比 60fps 快 4 倍收敛，
     * 帧率波动时镜头忽快忽慢。所以用「1 秒 × 4 次 15fps 步进」对比「60 次 1/60s」来验。
     */
    private static void smoothingIsFrameRateIndependent() {
        double lowFps = 0.0D;   // 15fps，每帧 1/15 秒
        double highFps = 0.0D;  // 60fps，每帧 1/60 秒
        for (int i = 0; i < 4; i++) {
            double k = SecondSightManager.smoothingFactor(1.0D / 15.0D);
            lowFps = lowFps + (1.0D - lowFps) * k;
        }
        for (int i = 0; i < 60; i++) {
            double k = SecondSightManager.smoothingFactor(1.0D / 60.0D);
            highFps = highFps + (1.0D - highFps) * k;
        }
        // 两者都推进了约 0.2667 秒，位置差距必须在几个百分点内，而不是 4 倍
        assertTrue(Math.abs(lowFps - highFps) < 0.02D,
                "不同帧率下收敛速度不一致：low=" + lowFps + ", high=" + highFps);
        // 系数本身必须落在 (0,1)，否则会过冲或完全不动
        for (double dt : new double[]{1.0D / 240.0D, 1.0D / 60.0D, 1.0D / 20.0D, 0.1D}) {
            double k = SecondSightManager.smoothingFactor(dt);
            assertTrue(k > 0.0D && k < 1.0D, "平滑系数必须严格在 (0,1)：dt=" + dt + ", k=" + k);
        }
        assertTrue(SecondSightManager.smoothingFactor(0.1D) > SecondSightManager.smoothingFactor(1.0D / 240.0D),
                "时间越长，单步收敛比例应越大");
    }

    /**
     * 第三人称时不能再额外后退。
     *
     * <p>这条用源码约束来守：几何计算需要真实的 Camera/Level，纯 JVM 里没有。
     * 只要有人把 {@code thirdPerson ? 0 : CAMERA_BACK} 改回固定后退，这里就红。
     */
    private static void cameraDoesNotDoubleBack() {
        try {
            String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "src/main/java/com/hanchen/veritycustomapi/client/SecondSightManager.java"));
            assertTrue(source.contains("thirdPerson ? 0.0D : CAMERA_BACK"),
                    "第三人称时不得再额外后退镜头");
            assertTrue(source.contains("sampleCameraPosition(float partialTick, boolean thirdPerson)"),
                    "采样接口必须知道当前是否第三人称");
            String mixin = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "src/main/java/com/hanchen/veritycustomapi/mixin/CameraSecondSightMixin.java"));
            assertTrue(mixin.contains("sampleCameraPosition(partialTick, detached)"),
                    "注入点必须把 detached 传给采样，否则第三人称判断失效");
        } catch (java.io.IOException error) {
            throw new AssertionError("读取源码失败", error);
        }
    }

    /** 距离上限必须小于客户端保证加载范围，否则会看到未加载的虚空并「掉出视角」。 */
    private static void distanceStaysInsideLoadedChunks() {
        assertTrue(SecondSightManager.MAX_DISTANCE <= 48.0D,
                "距离上限超出客户端区块加载范围，会突然掉出视角");
        assertTrue(SecondSightManager.MAX_DISTANCE >= 16.0D,
                "距离上限过小会让正常跟随时频繁自动退出");
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void assertClose(double actual, double expected, String message) {
        if (Math.abs(actual - expected) > 1e-6) {
            throw new AssertionError(message + "（expected=" + expected + ", actual=" + actual + "）");
        }
    }
}
