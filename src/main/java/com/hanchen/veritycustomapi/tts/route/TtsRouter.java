package com.hanchen.veritycustomapi.tts.route;

import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

/**
 * 路线调度：把「这句台词」交给当前最合适的那条路线。
 *
 * {@link #claim()} 和 {@link #speak()} 刻意分开。claim 同步、极便宜，只回答
 * 「扩展这次接管吗」——调用方（收到朗读包的那一侧）必须在同一 tick 内拿到答案才能
 * 决定要不要把原版的播放拦掉，所以这里绝不能碰网络、不能合成、不能阻塞。
 * speak 才是异步执行，按候选顺序一路向下试，直到有人接管。
 *
 * 要分开是因为接管与出声是两个不同的问题。接管了但最终没出声（本地模型还在加载、
 * 端点临时抽风），最坏是这一句安静；反过来「先拦掉原版、再发现没路可走」会让整句话彻底消失，
 * 那才是真事故。所以只在确定有会出声的路线时才接管。
 */
@OnlyIn(Dist.CLIENT)
public final class TtsRouter {

    private TtsRouter() {
    }

    /**
     * 这次要不要由扩展接管朗读。
     *
     * 只有能算出「至少有一条会出声的路线」时才接管；算不出来就把这句话让给原版
     * （{@link TtsRouteKind#NATIVE} 的含义就是「交还原版」，不是「静音」）。
     */
    public static boolean claim() {
        TtsRouteKind planned = plan();
        if (planned == TtsRouteKind.NATIVE) {
            OllamaProxyMod.LOGGER.debug("【TTS】无可用朗读路线（配置={}），交还原版", configured());
            return false;
        }
        return true;
    }

    /**
     * 按候选顺序朗读，返回是否有人真的接管了。
     *
     * 某个引擎返回 {@code false} 表示「这次我不参与」（例如本地模型还没加载完），
     * 继续往下一个候选走；全部走完都没人接管就返回 {@code false}，调用方静默即可。
     */
    public static boolean speak(String text, int entityId, long session) {
        if (text == null || text.isBlank()) return false;
        TtsRouteKind configured = configured();
        for (TtsRouteKind candidate : TtsRoutePlanner.fallbackOrder(configured)) {
            TtsEngine engine = TtsEngines.of(candidate);
            if (engine == null || !isUsable(engine)) continue;
            try {
                if (engine.speak(text, entityId, session)) {
                    logRoute(configured, candidate);
                    return true;
                }
            } catch (Throwable error) {
                // 一条路线炸了不该带走整句话：记一笔继续降级
                OllamaProxyMod.LOGGER.warn("TTS 路线 {} 朗读失败，继续尝试后续路线: {}",
                        candidate.displayName(), error.getMessage());
            }
        }
        OllamaProxyMod.LOGGER.debug("【TTS】所有候选路线都未接管（配置={}），本次不朗读", configured.displayName());
        return false;
    }

    /** 会话开始：只预热这次真正要用的那条路线，别把没用到的引擎也拉起来。 */
    public static void warmUp() {
        TtsRouteKind planned = plan();
        TtsEngine engine = TtsEngines.of(planned);
        if (engine == null) return;
        try {
            engine.warmUp();
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.warn("TTS 路线 {} 预热失败: {}", planned.displayName(), error.getMessage());
        }
    }

    /** 立刻停掉所有已创建的引擎。 */
    public static void stop() {
        for (TtsEngine engine : TtsEngines.createdInstances()) {
            try {
                engine.stop();
            } catch (Throwable error) {
                OllamaProxyMod.LOGGER.debug("停止 TTS 引擎 {} 失败: {}", engine.id(), error.getMessage());
            }
        }
    }

    /** 释放所有已创建的引擎。 */
    public static void shutdown() {
        for (TtsEngine engine : TtsEngines.createdInstances()) {
            try {
                engine.shutdown();
            } catch (Throwable error) {
                OllamaProxyMod.LOGGER.debug("释放 TTS 引擎 {} 失败: {}", engine.id(), error.getMessage());
            }
        }
    }

    /**
     * 现在实际会走哪条路线。
     *
     * 按候选顺序逐个探测、命中即停，不做全量扫描。这不只是省事：探测本地引擎会真的去加载
     * ONNX 原生库，而绝大多数玩家用的是网络端点——不能因为「顺路问一句」就把几十兆原生库拉起来。
     */
    public static TtsRouteKind plan() {
        for (TtsRouteKind candidate : TtsRoutePlanner.fallbackOrder(configured())) {
            if (isUsableKind(candidate)) return candidate;
        }
        return TtsRouteKind.NATIVE;
    }

    /**
     * 现在可用的路线集合，按枚举顺序。
     *
     * 注意这是全量探测：会把每条路线的引擎都实例化并问一次可用性
     * （包括真的去加载本地引擎的原生库）。只给诊断/界面用，别放进朗读路径。
     */
    public static List<TtsRouteKind> usableKinds() {
        List<TtsRouteKind> usable = new ArrayList<>(4);
        for (TtsRouteKind kind : TtsEngines.registeredKinds()) {
            if (isUsableKind(kind)) usable.add(kind);
        }
        return usable;
    }

    /** 配置里写的路线（已把「同时启用原版朗读」并进来）。 */
    public static TtsRouteKind configured() {
        try {
            return ConfigManager.getTtsRouteKind();
        } catch (Throwable error) {
            return TtsRouteKind.NATIVE;
        }
    }

    private static boolean isUsableKind(TtsRouteKind kind) {
        TtsEngine engine = TtsEngines.of(kind);
        return engine != null && isUsable(engine);
    }

    private static boolean isUsable(TtsEngine engine) {
        try {
            return engine.isAvailable();
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.debug("TTS 引擎 {} 可用性探测失败，视为不可用: {}",
                    engine.id(), error.getMessage());
            return false;
        }
    }

    private static void logRoute(TtsRouteKind configured, TtsRouteKind actual) {
        if (configured == actual) {
            OllamaProxyMod.LOGGER.debug("【TTS】路线={}", actual.displayName());
        } else {
            OllamaProxyMod.LOGGER.info("【TTS】配置路线 {} 不可用，已降级到 {}",
                    configured.displayName(), actual.displayName());
        }
    }
}
