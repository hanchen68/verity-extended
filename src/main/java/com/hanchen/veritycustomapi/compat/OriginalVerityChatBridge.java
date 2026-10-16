package com.hanchen.veritycustomapi.compat;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class OriginalVerityChatBridge {
    private static final String AI_MANAGER_CLASS = "varmite.verity.entity.llm.AiManager";
    private static final String VERITY_STATE_CLASS = "varmite.verity.entity.VerityState";
    private static final String VERITY_ENTITY_CLASS = "varmite.verity.entity.verity.VerityEntity";

    private OriginalVerityChatBridge() {
    }

    public static boolean isAvailable() {
        try {
            Class.forName(AI_MANAGER_CLASS);
            Class.forName(VERITY_STATE_CLASS);
            Class.forName(VERITY_ENTITY_CLASS);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean queryAi(ServerPlayer player, String message) {
        try {
            Class<?> stateClass = Class.forName(VERITY_STATE_CLASS);
            Field field = stateClass.getField("verityEntity");
            Object entity = field.get(null);
            return queryAi(entity, player, message);
        } catch (Throwable t) {
            OllamaProxyMod.LOGGER.warn("调用原版 Verity 对话链路失败: {}", rootMessage(t));
            return false;
        }
    }

    public static boolean queryAi(Object verityEntity, ServerPlayer player, String message) {
        if (verityEntity == null || player == null || message == null || message.isBlank()) {
            return false;
        }
        try {
            Class<?> aiManagerClass = Class.forName(AI_MANAGER_CLASS);
            Class<?> verityEntityClass = Class.forName(VERITY_ENTITY_CLASS);
            Method queryAi = aiManagerClass.getMethod("queryAI", verityEntityClass, String.class, ServerPlayer.class);
            String finalMessage = resolvePlayerName(player) + " " + message.trim();
            // 新版原版靠查询里有没有「SPEECH KARMA EVALUATION」标记来判断「这是玩家亲口说的话」，
            // 缺了它所有「只在玩家明确要求时才允许」的工具（捡东西 / 收礼物等）都会被原版拒绝。
            // 旧版原版没有这个机制，decorate 会原样返回，行为不变。
            finalMessage = com.hanchen.veritycustomapi.compat.VeritySpeechContext.decorate(finalMessage);
            queryAi.invoke(null, verityEntity, finalMessage, player);
            OllamaProxyMod.LOGGER.info("已通过原版 Verity AiManager 代理对话: {}", finalMessage);
            return true;
        } catch (Throwable t) {
            OllamaProxyMod.LOGGER.warn("调用原版 Verity 对话链路失败: {}", rootMessage(t));
            return false;
        }
    }

    /** 事件汇报专用：独立线程调用，避免原版 AI 的全局串行锁把服务端主线程卡住；失败仅记日志。 */
    public static void queryAiAsync(Object verityEntity, ServerPlayer player, String message) {
        Thread thread = new Thread(() -> {
            try {
                queryAi(verityEntity, player, message);
            } catch (Throwable ignored) {
            }
        }, "Verity-Event-Report");
        thread.setDaemon(true);
        thread.start();
    }

    private static String resolvePlayerName(ServerPlayer player) {
        String name = invokeString(player, "getScoreboardName");
        if (!name.isBlank()) {
            return name;
        }
        Object profile = invokeObject(player, "getGameProfile");
        name = invokeString(profile, "getName");
        if (!name.isBlank()) {
            return name;
        }
        Object component = invokeObject(player, "getName");
        name = invokeString(component, "getString");
        return name.isBlank() ? "Player" : name;
    }

    private static Object invokeObject(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        try {
            Method method = target.getClass().getMethod(methodName);
            return method.invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String invokeString(Object target, String methodName) {
        Object value = invokeObject(target, methodName);
        return value instanceof String ? ((String) value).trim() : "";
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
