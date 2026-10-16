package com.hanchen.veritycustomapi.tts.route;

/**
 * 一条朗读路线背后的引擎。
 *
 * 引擎只负责「把这段文字念出来」，不关心为什么选了它、也不关心降级逻辑
 * ——那些都在 {@link TtsRouter} 里。新增引擎 = 实现本接口 + 在 {@link TtsEngines} 注册。
 *
 * {@link #speak} 的返回值是「这次朗读我接管了吗」：返回 {@code false} 表示本引擎这次不出声，
 * 调用方应继续尝试后面的候选。所以 {@link TtsRouteKind#NATIVE} 的实现恒返回 {@code false}。
 */
public interface TtsEngine {

    /** 本引擎属于哪条路线。 */
    TtsRouteKind kind();

    /** 稳定标识，用于日志与自检；不要跟随显示名变化。 */
    String id();

    /** 给玩家看的名字。 */
    String displayName();

    /**
     * 现在能不能用。允许做懒探测，但必须把结果缓存住：
     * 本方法会在朗读路径上被调用。
     */
    boolean isAvailable();

    /** 会话开始时的预热（加载模型、建连接池等）。默认什么都不做。 */
    default void warmUp() {
    }

    /**
     * 朗读一段文字。
     *
     * @return true 表示这次朗读由本引擎接管（无论最终是否真的出声）；false 表示本引擎不参与
     */
    boolean speak(String text, int entityId, long session);

    /** 立刻停掉本引擎正在播放/合成的内容。 */
    default void stop() {
    }

    /** 释放资源。 */
    default void shutdown() {
    }
}
