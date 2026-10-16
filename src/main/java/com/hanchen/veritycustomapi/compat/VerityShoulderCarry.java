package com.hanchen.veritycustomapi.compat;

import varmite.verity.entity.verity.VerityEntity;

import java.lang.reflect.Method;

/**
 * 读取「Verity 正被玩家扛在肩上」这个状态。
 *
 * 这套机制只有部分构建才有，编译期依赖的构建里没这个方法，所以既做能力探测
 * （{@link VerityStructureDetector#hasShoulderMountSystem()}）又走反射：探测为 false 时
 * 压根不碰反射，免得在不支持的构建上抛 {@code NoSuchMethodError}。
 *
 * 一定要避让：扛肩只把实体的「扛肩标记」置位、每 tick 自己摆位置，不占乘客位。
 * 不避让的话，Verity 会同时被扛在肩上、又被我们挂成乘客/载具，两套定位一起写它的坐标。
 */
public final class VerityShoulderCarry {

    private static volatile Method isShoulderActive;
    private static volatile boolean resolved;

    private VerityShoulderCarry() {
    }

    /** Verity 此刻是否正被扛在肩上；不支持该机制的构建恒为 false。 */
    public static boolean isCarried(VerityEntity verity) {
        if (verity == null || !VerityStructureDetector.hasShoulderMountSystem()) return false;
        Method method = resolve(verity.getClass());
        if (method == null) return false;
        try {
            Object value = method.invoke(verity);
            return value instanceof Boolean carried && carried;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 仅供自检使用：清掉反射缓存。 */
    public static void resetCacheForTesting() {
        isShoulderActive = null;
        resolved = false;
    }

    private static Method resolve(Class<?> type) {
        if (resolved) return isShoulderActive;
        synchronized (VerityShoulderCarry.class) {
            if (!resolved) {
                try {
                    isShoulderActive = type.getMethod("isShoulderActive");
                } catch (Throwable ignored) {
                    isShoulderActive = null;
                }
                resolved = true;
            }
            return isShoulderActive;
        }
    }
}
