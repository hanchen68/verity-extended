package com.hanchen.veritycustomapi.tts.route;

import java.util.ArrayList;
import java.util.List;

/**
 * 路线决策：从「配置想用哪条」和「哪几条现在真能用」算出「这次走哪条」。
 *
 * 刻意不依赖任何游戏类，这样降级规则可以离线自检——降级错了的表现是「没声音」，
 * 光看日志很难定位。
 */
public final class TtsRoutePlanner {

    private TtsRoutePlanner() {
    }

    /**
     * 从配置路线出发的候选顺序。
     *
     * {@link TtsRouteKind#NATIVE} 是例外：它是「我不想让扩展出声」的明确表态，
     * 不能被自动降级绕过，所以候选只有它自己。
     *
     * 降级只能往「不更贵」的方向走。网络端点是要花钱的，本地引擎与原版都不花钱，
     * 所以选了本地就绝不会偷偷改走端点——用户选了离线，就不能在他不知情时产生调用。
     * 反过来选了端点，退回本地是白捡的，允许。
     *
     * 每条链最后一定落在原版上（它永远可用，且只是把这句话交回原版朗读），
     * 所以不会出现无路可走。
     */
    public static List<TtsRouteKind> fallbackOrder(TtsRouteKind configured) {
        if (configured == TtsRouteKind.NATIVE) {
            return List.of(TtsRouteKind.NATIVE);
        }
        List<TtsRouteKind> order = new ArrayList<>(3);
        addIfAbsent(order, configured);
        if (configured == TtsRouteKind.ENDPOINT) {
            // 端点不可用时退回本地：免费、也不改变「扩展来念」这件事
            addIfAbsent(order, TtsRouteKind.LOCAL);
        }
        addIfAbsent(order, TtsRouteKind.NATIVE);
        return List.copyOf(order);
    }

    /**
     * 算出这次实际走哪条路线。
     *
     * @param configured 配置的路线；为 null 时按端点处理
     * @param usable     现在可用的路线集合（判定由调用方提供）
     * @return 候选顺序里第一个可用的；全都不可用时返回 {@link TtsRouteKind#NATIVE}
     */
    public static TtsRouteKind plan(TtsRouteKind configured, List<TtsRouteKind> usable) {
        for (TtsRouteKind candidate : fallbackOrder(configured)) {
            if (usable != null && usable.contains(candidate)) return candidate;
        }
        return TtsRouteKind.NATIVE;
    }

    /**
     * 把「配置里写的路线」规范化：认不出来的值返回 {@code fallback}。
     *
     * @param raw      配置原文，可能是 {@code auto} 或历史写法
     * @param fallback 认不出来时的默认路线
     */
    public static TtsRouteKind normalize(String raw, TtsRouteKind fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        String trimmed = raw.trim();
        if ("auto".equalsIgnoreCase(trimmed) || "默认".equals(trimmed)) return fallback;
        TtsRouteKind parsed = TtsRouteKind.parse(trimmed);
        return parsed == null ? fallback : parsed;
    }

    private static void addIfAbsent(List<TtsRouteKind> order, TtsRouteKind kind) {
        if (kind != null && !order.contains(kind)) order.add(kind);
    }
}
