package com.hanchen.veritycustomapi.compat;

import com.hanchen.veritycustomapi.OllamaProxyMod;

import java.lang.reflect.Field;

/**
 * 读原版「AI 请求是不是正在处理中」，好让扩展的主动感知在它忙的时候让路。
 * 原版 6.2 起给 AI 请求加了全局单飞锁（{@code AiManager.requestInFlight} 之类，6.0 没这成员），
 * 后台 LLM 请求和玩家消息共用这把锁；取不到就一律返回 false，旧版行为不变。
 */
public final class OriginalVerityAiLock {

    private OriginalVerityAiLock() {
    }

    private static final String AI_MANAGER_CLASS = "varmite.verity.entity.llm.AiManager";
    private static final String REQUEST_IN_FLIGHT_FIELD = "requestInFlight";

    private static volatile boolean resolved;
    private static volatile Field requestInFlight;

    /** 原版是否有 AI 请求正在处理中；无法判定时返回 false。 */
    public static boolean isRequestInFlight() {
        Field field = resolve();
        if (field == null) return false;
        try {
            return field.getBoolean(null);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 是否检测到了原版的单飞锁（用于日志与自检）。 */
    public static boolean isSupported() {
        return resolve() != null;
    }

    private static Field resolve() {
        if (resolved) return requestInFlight;
        synchronized (OriginalVerityAiLock.class) {
            if (resolved) return requestInFlight;
            try {
                // initialize = false：不要在解析阶段触发原版的静态初始化链。
                Class<?> aiManager = Class.forName(AI_MANAGER_CLASS, false,
                        OriginalVerityAiLock.class.getClassLoader());
                Field field = aiManager.getDeclaredField(REQUEST_IN_FLIGHT_FIELD);
                field.setAccessible(true);
                requestInFlight = field;
                OllamaProxyMod.LOGGER.info(
                        "检测到原版 AI 单飞锁（{}.{}），主动感知将在其忙碌时主动让路",
                        AI_MANAGER_CLASS, REQUEST_IN_FLIGHT_FIELD);
            } catch (Throwable ignored) {
                // 旧版原版没有这个字段：保持「不支持」，行为完全不变
            } finally {
                resolved = true;
            }
            return requestInFlight;
        }
    }
}
