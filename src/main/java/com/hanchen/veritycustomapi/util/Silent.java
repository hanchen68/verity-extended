package com.hanchen.veritycustomapi.util;

import com.hanchen.veritycustomapi.OllamaProxyMod;

/**
 * 统一的「可控降级」出口。
 *
 * 项目里原来有大量 `catch (Exception ignored) {}`：降级本身是对的（缺类、缺字段、可选依赖不在场），
 * 但连一点痕迹都不留，线上出问题时无法判断到底是「没走到」还是「走到了但抛了」。
 *
 * 这里只做一件事：把异常记到 DEBUG，带上位置标签，行为上仍然是「吞掉、继续」。
 * 需要更高可见度的场景（例如第三方适配初始化失败）不要用这个，直接 WARN。
 */
public final class Silent {

    private Silent() {
    }

    /**
     * @param where 位置标签，形如 {@code CrossSavePursuit:142}
     * @param error 被吞掉的异常
     */
    public static void ignore(String where, Throwable error) {
        if (!OllamaProxyMod.LOGGER.isDebugEnabled()) {
            // 关闭 debug 时连字符串都不拼，避免高频路径（每 tick/每帧）产生垃圾
            return;
        }
        OllamaProxyMod.LOGGER.debug("[降级] {}: {}", where, error.toString());
    }
}
