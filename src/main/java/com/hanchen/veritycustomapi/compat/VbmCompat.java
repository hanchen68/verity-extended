package com.hanchen.veritycustomapi.compat;

import varmite.verity.entity.verity.VerityEntity;

import java.lang.reflect.Method;

/**
 * VBM（Verity Behavior Model）预览版的专属读取器。
 *
 * <p>这套东西只有「6.0.0 整活 demo」那种构建才有，编译期依赖的正式版里没有，
 * 所以和 {@link VerityShoulderCarry} 一样：先靠 {@link VerityStructureDetector#hasVbmMoodSystem()}
 * 做能力探测，探测为 false 时压根不碰反射，免得在不支持的构建上抛 {@code NoSuchMethodError}。
 *
 * <p>目前只读一个状态：<b>压扁</b>（{@code VerityEntity.isFlat()}，被重物砸中后摊成一张饼）。
 * 压扁时她既不该被挂到玩家头上，也不该被骑——视觉上就是一张饼，挂上去很怪。
 */
public final class VbmCompat {

    private static volatile Method isFlat;
    private static volatile boolean resolved;

    private VbmCompat() {
    }

    /** Verity 此刻是否处于压扁状态；不支持 VBM 的构建恒为 false。 */
    public static boolean isFlat(VerityEntity verity) {
        if (verity == null || !VerityStructureDetector.hasVbmMoodSystem()) return false;
        Method method = resolve(verity.getClass());
        if (method == null) return false;
        try {
            Object value = method.invoke(verity);
            return value instanceof Boolean flat && flat;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Method resolve(Class<?> type) {
        if (resolved) return isFlat;
        synchronized (VbmCompat.class) {
            if (resolved) return isFlat;
            try {
                Method method = type.getMethod("isFlat");
                method.setAccessible(true);
                isFlat = method;
            } catch (Throwable ignored) {
                isFlat = null;
            }
            resolved = true;
        }
        return isFlat;
    }

    /** 仅供自检使用：清掉反射缓存。 */
    public static void resetCacheForTesting() {
        isFlat = null;
        resolved = false;
    }
}
