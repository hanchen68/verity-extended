package com.hanchen.veritycustomapi.tts.route;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 引擎注册表：路线 -> 引擎实例。
 *
 * 实例按需创建（第一次用到某条路线才 new），这样专用服务器上完全不会碰到客户端专属的引擎类。
 * 要加一条新路线，只需在这里多注册一个 {@link Supplier}。
 */
public final class TtsEngines {

    private static final Map<TtsRouteKind, Supplier<TtsEngine>> FACTORIES = new EnumMap<>(TtsRouteKind.class);
    private static final Map<TtsRouteKind, TtsEngine> INSTANCES = new EnumMap<>(TtsRouteKind.class);

    static {
        register(TtsRouteKind.NATIVE, com.hanchen.veritycustomapi.tts.route.NativeTtsEngine::new);
        register(TtsRouteKind.LOCAL, com.hanchen.veritycustomapi.tts.route.LocalTtsEngine::new);
        register(TtsRouteKind.ENDPOINT, com.hanchen.veritycustomapi.tts.route.EndpointTtsEngine::new);
    }

    private TtsEngines() {
    }

    /** 注册（或替换）某条路线背后的引擎。 */
    public static synchronized void register(TtsRouteKind kind, Supplier<TtsEngine> factory) {
        if (kind == null || factory == null) return;
        FACTORIES.put(kind, factory);
        INSTANCES.remove(kind);
    }

    /** 取某条路线的引擎；没有注册则返回 null。 */
    public static synchronized TtsEngine of(TtsRouteKind kind) {
        if (kind == null) return null;
        TtsEngine cached = INSTANCES.get(kind);
        if (cached != null) return cached;
        Supplier<TtsEngine> factory = FACTORIES.get(kind);
        if (factory == null) return null;
        try {
            TtsEngine created = factory.get();
            if (created != null) INSTANCES.put(kind, created);
            return created;
        } catch (Throwable error) {
            return null;
        }
    }

    /** 已注册的路线，按枚举声明顺序。 */
    public static synchronized List<TtsRouteKind> registeredKinds() {
        List<TtsRouteKind> kinds = new ArrayList<>();
        for (TtsRouteKind kind : TtsRouteKind.values()) {
            if (FACTORIES.containsKey(kind)) kinds.add(kind);
        }
        return kinds;
    }

    /**
     * 已经创建出来的引擎实例（快照）。
     *
     * 只返回已创建的：没被用到过的路线不该因为「要停一下」而被实例化
     * ——那会在专用服务器上凭空加载客户端专属的引擎类。
     */
    public static synchronized List<TtsEngine> createdInstances() {
        return List.copyOf(INSTANCES.values());
    }

    /** 仅供自检使用：清掉实例缓存，但保留注册表。 */
    public static synchronized void resetInstancesForTesting() {
        INSTANCES.clear();
    }
}
