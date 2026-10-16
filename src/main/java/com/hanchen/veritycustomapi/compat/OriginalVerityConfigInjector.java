package com.hanchen.veritycustomapi.compat;

import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.OllamaProxyServer;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * 用反射直接改原版 Verity 的 VerityConfig 值（不走 TOML 文件）。
 * AI / 语音配置在 onServerStarting 时立即生效，不用重启。
 */
public final class OriginalVerityConfigInjector {

    private static final String CONFIG_CLASS = "varmite.verity.VerityConfig";

    private OriginalVerityConfigInjector() {}

    /**
     * 每次进世界都重新注入一遍：原版 Verity 的 TOML 一重载会把 USE_TTS 恢复成 true，
     * 那样原版系统朗读（女机械音）就会和我们的在线 TTS 一起响。
     */
    public static synchronized void inject() {
        // 每项各自 try/catch，一处失败不影响其余注入；必须捕 Throwable 而非 Exception，
        // 原版改字段名/类型时反射可能抛 NoSuchFieldError 等 Error，逃出去会打断客户端启动。
        applyInjection("AI 配置", OriginalVerityConfigInjector::injectAiConfig);
        applyInjection("TTS 配置", OriginalVerityConfigInjector::injectTtsConfig);
        applyInjection("STT 配置", OriginalVerityConfigInjector::injectSttConfig);
        applyInjection("人格设定", OriginalVerityConfigInjector::injectPersonality);
        OllamaProxyMod.LOGGER.info("[ConfigInjector] 已通过反射注入原版 Verity 运行时配置");
    }

    /** 允许抛受检异常的注入任务（四个 injectXxx 都声明了 throws Exception，不能直接用 Runnable）。 */
    @FunctionalInterface
    private interface Injection {
        void run() throws Exception;
    }

    private static void applyInjection(String name, Injection injection) {
        try {
            injection.run();
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.warn("[ConfigInjector] {} 注入失败，已跳过该项（其余注入不受影响）: {}",
                    name, error.toString());
        }
    }

    public static synchronized void injectGameplayConfig(Map<String, String> values) {
        Map<String, String> fields = Map.of(
                "dayCount", "DAY_COUNT",
                "canCrash", "CAN_CRASH",
                "playVideo", "PLAY_VIDEO",
                "requireVerity", "REQUIRE_VERITY",
                "trueDarkness", "TRUE_DARKNESS",
                "killWildlife", "KILL_WILDLIFE",
                "killEntities", "KILL_ENTITIES",
                "killVillagers", "KILL_VILLAGERS",
                "showKarma", "IMMERSIVE_MODE"
        );
        values.forEach((key, value) -> {
            String field = fields.get(key);
            if (field == null) return;
            try {
                Object parsed = "dayCount".equals(key) ? Integer.parseInt(value) : Boolean.parseBoolean(value);
                Object configValue = getConfigField(field);
                if (configValue != null) {
                    configValue.getClass().getMethod("set", Object.class).invoke(configValue, parsed);
                }
            } catch (Exception e) {
                OllamaProxyMod.LOGGER.warn("[ConfigInjector] 原版玩法设置注入失败: {}", key, e);
            }
        });
        OllamaProxyMod.LOGGER.info("[ConfigInjector] 原版玩法设置已即时同步: {}", values.keySet());
    }

    /** 注入 AI 配置：指向本地代理、用扩展模型 */
    private static void injectAiConfig() throws Exception {
        int port = ConfigManager.getPort();
        setStringConfig("API_KEY", OllamaProxyServer.getLocalProxyToken());
        setStringConfig("AI_ENDPOINT", "http://127.0.0.1:" + port + "/v1/chat/completions");
        setStringConfig("AI_MODEL", ConfigManager.getTargetModel());
        setEnumConfig("AI_PROVIDER", "OPENAI");
        setBooleanConfig("AI_THINK", false);
    }

    /**
     * 注入 TTS 配置：只有「这句话交回原版念」时才开原版 TTS。
     *
     * 光看老的「禁用原版系统朗读」开关不够：路线被明确选成「原版朗读」时扩展不再接管，
     * 这时要是原版 TTS 还关着，用户选完就是一片安静。两件事其实是一回事，
     * 所以统一交给 {@link ConfigManager#shouldOriginalTtsBeEnabled()} 判。
     */
    private static void injectTtsConfig() throws Exception {
        setBooleanConfig("USE_TTS", ConfigManager.shouldOriginalTtsBeEnabled());
    }

    /** 注入 STT 配置 */
    private static void injectSttConfig() throws Exception {
        if (ConfigManager.getRecognitionMode() == ConfigManager.RecognitionMode.LOCAL) {
            setEnumConfig("STT_PROVIDER", "NATIVE");
            return;
        }
        setEnumConfig("STT_PROVIDER", "WHISPER");
        setStringConfig("STT_ENDPOINT", normalizeBaseEndpoint(ConfigManager.getSpeechApiUrl(), "audio/transcriptions"));
        setStringConfig("STT_MODEL", ConfigManager.getSpeechApiModel());
        setStringConfig("GROQ_KEY", ConfigManager.getSpeechApiKey());
    }

    /** 注入自定义人格（仅在启用自定义提示词时生效） */
    private static void injectPersonality() throws Exception {
        if (!ConfigManager.isCustomPromptEnabled()) return;
        String persona = ConfigManager.getCustomPersona();
        if (persona != null && !persona.isBlank()) {
            setStringConfig("PERSONALITY", persona);
        }
    }

    // ========== 反射工具方法 ==========

    private static void setBooleanConfig(String fieldName, boolean value) throws Exception {
        Object configValue = getConfigField(fieldName);
        if (configValue == null) return;
        configValue.getClass().getMethod("set", Object.class).invoke(configValue, value);
    }

    private static void setStringConfig(String fieldName, String value) throws Exception {
        Object configValue = getConfigField(fieldName);
        if (configValue == null || value == null) return;
        configValue.getClass().getMethod("set", Object.class).invoke(configValue, value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setEnumConfig(String fieldName, String enumName) throws Exception {
        Object configValue = getConfigField(fieldName);
        if (configValue == null) return;
        Object current = configValue.getClass().getMethod("get").invoke(configValue);
        if (current == null) return;
        Object enumValue = Enum.valueOf((Class<? extends Enum>) current.getClass(), enumName);
        configValue.getClass().getMethod("set", Object.class).invoke(configValue, enumValue);
    }

    private static Object getConfigField(String fieldName) throws Exception {
        Field field = Class.forName(CONFIG_CLASS).getField(fieldName);
        return field.get(null);
    }

    /** 将完整的 API URL 归一化为 base URL（去掉路径后缀） */
    private static String normalizeBaseEndpoint(String url, String suffix) {
        if (url == null || url.isBlank()) return "";
        String trimmed = url.trim();
        if (trimmed.endsWith("/")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        if (trimmed.endsWith(suffix)) {
            return trimmed.substring(0, trimmed.length() - suffix.length());
        }
        if (trimmed.endsWith("/v1")) return trimmed;
        return trimmed;
    }
}
