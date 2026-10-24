package com.hanchen.veritycustomapi.compat;

import com.hanchen.veritycustomapi.util.Silent;

import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.sound.sampled.AudioFormat;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

@OnlyIn(Dist.CLIENT)
public final class OriginalVerityVoiceBridge {
    private static final String AI_API_CLASS = "varmite.verity.entity.llm.AiAPI";
    private static final String CONFIG_CLASS = "varmite.verity.VerityConfig";
    private static final String VERITY_ENTITY_CLASS = "varmite.verity.entity.verity.VerityEntity";
    private static final String MICROPHONE_RECORDER_CLASS = "varmite.verity.client.audio.MicrophoneRecorder";
    private static final String KEYBIND_HANDLER_CLASS = "varmite.verity.client.KeybindHandler";

    private OriginalVerityVoiceBridge() {
    }

    public static boolean isAvailable() {
        try {
            Class.forName(AI_API_CLASS);
            Class.forName(CONFIG_CLASS);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isVoiceInputAvailable() {
        try {
            Class.forName(MICROPHONE_RECORDER_CLASS);
            Class.forName(KEYBIND_HANDLER_CLASS);
            return isAvailable();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static OriginalVoiceInputSession createVoiceInputSession() {
        try {
            configureOriginalStt();
            Method getRecorder = Class.forName(KEYBIND_HANDLER_CLASS).getMethod("getRecorder");
            Object recorder = getRecorder.invoke(null);
            if (recorder == null) {
                OllamaProxyMod.LOGGER.warn("原版 Verity KeybindHandler.getRecorder() 返回空录音器");
                return null;
            }
            OllamaProxyMod.LOGGER.info("已接入原版 Verity 语音录音器: {}", recorder.getClass().getName());
            return new OriginalVoiceInputSession(recorder);
        } catch (Throwable t) {
            OllamaProxyMod.LOGGER.warn("创建原版 Verity 语音输入代理失败: {}", rootMessage(t));
            return null;
        }
    }

    public static void interruptSpeech() {
        try {
            Class<?> aiApi = Class.forName(AI_API_CLASS);
            aiApi.getMethod("interruptSpeech").invoke(null);
        } catch (Throwable ignored) { Silent.ignore("OriginalVerityVoiceBridge", ignored); }
    }

    public static String transcribeAudio(byte[] pcmData, AudioFormat format) {
        if (pcmData == null || pcmData.length == 0 || format == null) {
            return "";
        }
        try {
            configureOriginalStt();
            Class<?> aiApi = Class.forName(AI_API_CLASS);
            Method transcribe = aiApi.getMethod("transcribeAudio", byte[].class, AudioFormat.class);
            Object result = transcribe.invoke(null, pcmData, format);
            return result instanceof String ? ((String) result).trim() : "";
        } catch (Throwable t) {
            OllamaProxyMod.LOGGER.warn("调用原版 Verity STT 失败，回退扩展识别链路: {}", rootMessage(t));
            return "";
        }
    }

    public static boolean speak(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        try {
            configureOriginalTts();
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) {
                return false;
            }
            Class<?> aiApi = Class.forName(AI_API_CLASS);
            Class<?> verityEntityClass = Class.forName(VERITY_ENTITY_CLASS);
            Method playTts = aiApi.getMethod("playTTS", String.class, Player.class, verityEntityClass);
            playTts.invoke(null, text, mc.player, null);
            return true;
        } catch (Throwable t) {
            OllamaProxyMod.LOGGER.warn("调用原版 Verity TTS 失败，回退扩展 TTS 链路: {}", rootMessage(t));
            return false;
        }
    }

    public static final class OriginalVoiceInputSession {
        private final Object recorder;

        private OriginalVoiceInputSession(Object recorder) {
            this.recorder = recorder;
        }

        public boolean start() {
            try {
                setOriginalKeybindRecording(true);
                interruptSpeech();
                recorder.getClass().getMethod("startRecording").invoke(recorder);
                Object recording = recorder.getClass().getMethod("isRecording").invoke(recorder);
                boolean active = Boolean.TRUE.equals(recording);
                OllamaProxyMod.LOGGER.info("原版 Verity MicrophoneRecorder.startRecording 已调用，录音状态: {}", active);
                return active;
            } catch (Throwable t) {
                setOriginalKeybindRecording(false);
                OllamaProxyMod.LOGGER.warn("原版 Verity 录音启动失败: {}", rootMessage(t));
                return false;
            }
        }

        public void stopAndSubmitToOriginalChain() {
            try {
                setOriginalKeybindRecording(false);
                Method stopRecording = recorder.getClass().getMethod("stopRecording");
                byte[] recordedAudio = (byte[]) stopRecording.invoke(recorder);
                int audioLength = recordedAudio == null ? 0 : recordedAudio.length;
                OllamaProxyMod.LOGGER.info("原版 Verity MicrophoneRecorder.stopRecording 已调用，音频字节: {}", audioLength);
                if (audioLength == 0) {
                    return;
                }
                Method getAudioFormat = recorder.getClass().getMethod("getAudioFormat");
                AudioFormat format = (AudioFormat) getAudioFormat.invoke(recorder);
                Class<?> aiApi = Class.forName(AI_API_CLASS);
                Method transcribe = aiApi.getMethod("transcribeAudio", byte[].class, AudioFormat.class);
                Method accept = Class.forName(KEYBIND_HANDLER_CLASS).getDeclaredMethod("accept", String.class);
                accept.setAccessible(true);
                CompletableFuture.supplyAsync(() -> {
                    try {
                        OllamaProxyMod.LOGGER.info("正在调用原版 Verity AiAPI.transcribeAudio");
                        Object result = transcribe.invoke(null, recordedAudio, format);
                        String text = result instanceof String ? ((String) result).trim() : "";
                        OllamaProxyMod.LOGGER.info("原版 Verity AiAPI.transcribeAudio 已返回，文本长度: {}", text.length());
                        return text;
                    } catch (Throwable t) {
                        OllamaProxyMod.LOGGER.warn("原版 Verity 语音识别调用失败: {}", rootMessage(t));
                        return "";
                    }
                }).thenAccept(text -> {
                    try {
                        OllamaProxyMod.LOGGER.info("正在调用原版 Verity KeybindHandler.accept 提交语音文本");
                        accept.invoke(null, text);
                        OllamaProxyMod.LOGGER.info("原版 Verity KeybindHandler.accept 已调用完成");
                    } catch (Throwable t) {
                        OllamaProxyMod.LOGGER.warn("原版 Verity 语音结果提交失败: {}", rootMessage(t));
                    }
                });
            } catch (Throwable t) {
                OllamaProxyMod.LOGGER.warn("原版 Verity 语音输入链路提交失败: {}", rootMessage(t));
            }
        }
    }

    private static void setOriginalKeybindRecording(boolean recording) {
        try {
            Field field = Class.forName(KEYBIND_HANDLER_CLASS).getDeclaredField("isRecording");
            field.setAccessible(true);
            field.setBoolean(null, recording);
        } catch (Throwable t) {
            OllamaProxyMod.LOGGER.warn("同步原版 Verity KeybindHandler.isRecording 失败: {}", rootMessage(t));
        }
    }

    private static void configureOriginalStt() throws Exception {
        if (ConfigManager.getRecognitionMode() == ConfigManager.RecognitionMode.LOCAL) {
            setEnumConfig("STT_PROVIDER", "NATIVE");
            return;
        }
        setEnumConfig("STT_PROVIDER", "WHISPER");
        setStringConfig("STT_ENDPOINT", normalizeBaseEndpoint(ConfigManager.getSpeechApiUrl(), "audio/transcriptions"));
        setStringConfig("STT_MODEL", ConfigManager.getSpeechApiModel());
        setStringConfig("GROQ_KEY", ConfigManager.getSpeechApiKey());
    }

    private static void configureOriginalTts() throws Exception {
        setBooleanConfig("USE_TTS", true);
        if (ConfigManager.getTtsMode() == ConfigManager.TtsMode.LOCAL) {
            setEnumConfig("TTS_PROVIDER", "LOCAL");
            return;
        }
        setEnumConfig("TTS_PROVIDER", "KOKORO");
        setStringConfig("TTS_ENDPOINT", normalizeBaseEndpoint(ConfigManager.getTtsApiUrl(), "audio/speech"));
        setStringConfig("KOKORO_MODEL", ConfigManager.getTtsApiModel());
        setStringConfig("GROQ_KEY", ConfigManager.getTtsApiKey());
    }

    private static void setBooleanConfig(String fieldName, boolean value) throws Exception {
        setConfigValue(fieldName, value);
    }

    private static void setStringConfig(String fieldName, String value) throws Exception {
        setConfigValue(fieldName, value == null ? "" : value);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setEnumConfig(String fieldName, String enumName) throws Exception {
        Field field = Class.forName(CONFIG_CLASS).getField(fieldName);
        Object configValue = field.get(null);
        Object current = getConfigValue(configValue);
        if (current == null) {
            return;
        }
        Object enumValue = Enum.valueOf((Class<? extends Enum>) current.getClass().asSubclass(Enum.class), enumName);
        invokeSet(configValue, enumValue);
    }

    private static void setConfigValue(String fieldName, Object value) throws Exception {
        Field field = Class.forName(CONFIG_CLASS).getField(fieldName);
        Object configValue = field.get(null);
        invokeSet(configValue, value);
    }

    private static Object getConfigValue(Object configValue) throws Exception {
        Method get = configValue.getClass().getMethod("get");
        return get.invoke(configValue);
    }

    private static void invokeSet(Object configValue, Object value) throws Exception {
        Method set = configValue.getClass().getMethod("set", Object.class);
        set.invoke(configValue, value);
    }

    private static String normalizeBaseEndpoint(String url, String suffix) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String normalized = url.trim();
        String cleanSuffix = "/" + suffix;
        if (normalized.endsWith(cleanSuffix)) {
            return normalized.substring(0, normalized.length() - cleanSuffix.length() + 1);
        }
        if (!normalized.endsWith("/")) {
            normalized += "/";
        }
        return normalized;
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
