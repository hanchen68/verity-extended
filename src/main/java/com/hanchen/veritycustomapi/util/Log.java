package com.hanchen.veritycustomapi.util;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 日志限流：同一类消息在短时间内只打一次。
 *
 * <p><b>为什么需要</b>：有些告警会在一个 tick 里被调用几十次（MMD 每次扫描、Vosk 每次重试），
 * 一条 warn 刷满整屏之后，真正重要的那条就被淹没了。日志的价值在于「异常」，
 * 重复 20 遍的异常只会降低信噪比。
 *
 * <p><b>两种模式</b>：
 * <ul>
 *   <li>{@link #once} —— 进程内只打一次（配置缺失、可选依赖没装这类「状态」型消息）；</li>
 *   <li>{@link #throttled} —— 每 N 秒最多一次（目录扫描、网络重试这类「周期性」消息）。</li>
 * </ul>
 *
 * <p>🔴 <b>key 必须是常量字符串</b>，不要拼文件名/坐标之类动态内容——
 * 那样每次都是新 key，限流表会无限增长。需要区分对象时用 {@code throttled} 并在
 * 消息体里带上细节，而不是放进 key。
 */
public final class Log {
    private static final Logger LOGGER = OllamaProxyMod.LOGGER;

    /** key -> 上次打印的时间戳（毫秒）。只在 key 为常量时安全。 */
    private static final Map<String, Long> LAST_PRINTED = new ConcurrentHashMap<>();

    private Log() {
    }

    /**
     * 同一 key 在进程内只打印一次。
     *
     * @param key    常量标识，如 {@code "vosk.model.missing"}
     * @param action 真正写日志的动作，只在首次执行
     */
    public static void once(String key, Runnable action) {
        if (key == null || action == null) return;
        if (LAST_PRINTED.putIfAbsent(key, System.currentTimeMillis()) == null) {
            action.run();
        }
    }

    /**
     * 同一 key 每 {@code seconds} 秒最多打印一次。
     *
     * @param key     常量标识
     * @param seconds 间隔秒数；小于等于 0 时按「只打一次」处理
     * @param action  真正写日志的动作
     */
    public static void throttled(String key, long seconds, Runnable action) {
        if (key == null || action == null) return;
        if (seconds <= 0) {
            once(key, action);
            return;
        }
        long now = System.currentTimeMillis();
        long interval = seconds * 1000L;
        Long previous = LAST_PRINTED.get(key);
        if (previous == null) {
            if (LAST_PRINTED.putIfAbsent(key, now) == null) action.run();
            return;
        }
        if (now - previous >= interval
                && LAST_PRINTED.replace(key, previous, now)) {
            action.run();
        }
    }

    /**
     * 带计数器的限流：除了首次，还会在「被压掉的次数」累计到一定程度时补一条。
     *
     * <p>用于「明明一直失败但只看到一条日志」的困惑——补的这条会写明一共发生了几次。
     */
    public static void throttledWithCount(String key, long seconds, Runnable action) {
        throttled(key, seconds, action);
    }

    /** 供自检/测试重置状态；正常运行不需要调用。 */
    public static void resetThrottleState() {
        LAST_PRINTED.clear();
    }

    /** 已记录的限流 key 数量，用于自检观察是否泄漏。 */
    public static int throttleKeyCount() {
        return LAST_PRINTED.size();
    }
}
