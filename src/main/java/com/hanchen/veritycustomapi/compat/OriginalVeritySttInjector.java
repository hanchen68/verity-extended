package com.hanchen.veritycustomapi.compat;

import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.NetworkSecurity;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.stt.ApiSpeechRecognizer;
import com.hanchen.veritycustomapi.stt.SpeechRecognizer;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.common.MinecraftForge;

import javax.sound.sampled.AudioFormat;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 劫持原版 Verity 的 STT 链路：用 HIGHEST 优先级注册 ClientTickEvent，
 * 抢在原版 KeybindHandler（NORMAL）之前拦下 PUSH_TO_TALK 的松开事件，
 * 改用 ApiSpeechRecognizer 替代原版 AiAPI.transcribeAudio() 转录，再发聊天消息。
 */
@OnlyIn(Dist.CLIENT)
public final class OriginalVeritySttInjector {

    private static volatile boolean injected = false;
    private static volatile SpeechRecognizer ourRecognizer;
    private static final AtomicBoolean recognitionInFlight = new AtomicBoolean(false);
    private static volatile Class<?> keybindHandlerClass;
    private static volatile Field isRecordingField;
    private static volatile Field recorderField;
    private static volatile Object recorderInstance;

    // 缓存的反射对象（避免 per-tick Class.forName/getMethod）
    private static volatile Class<?> keyRegClass;
    private static volatile Field ptkField;
    private static volatile Method isDownMethod;
    private static volatile Method stopRecordingMethod;
    private static volatile Method getFormatMethod;
    private static volatile long sessionGeneration;

    private OriginalVeritySttInjector() {}

    /**
     * 启动注入：反射访问原版 KeybindHandler 的静态字段，
     * 并以高优先级注册我们自己的 tick 监听器。
     */
    public static synchronized void inject() {
        if (injected) return;
        if (RuntimePlatform.isAndroidRuntime()) {
            OllamaProxyMod.LOGGER.info("[SttInjector] Android/FCL 使用扩展录音链，跳过原版录音器注入");
            return;
        }

        try {
            keybindHandlerClass = Class.forName("varmite.verity.client.KeybindHandler");
            isRecordingField = keybindHandlerClass.getDeclaredField("isRecording");
            isRecordingField.setAccessible(true);
            recorderField = keybindHandlerClass.getDeclaredField("RECORDER");
            recorderField.setAccessible(true);
            recorderInstance = recorderField.get(null);

            // 缓存所有反射对象
            keyRegClass = Class.forName("varmite.verity.client.KeybindRegistry");
            ptkField = keyRegClass.getDeclaredField("PUSH_TO_TALK");
            ptkField.setAccessible(true);

            stopRecordingMethod = recorderInstance.getClass().getMethod("stopRecording");
            getFormatMethod = recorderInstance.getClass().getMethod("getAudioFormat");

            // isDown() 在 KeyMapping 上，不是在 MicrophoneRecorder 上
            Object pushToTalk = ptkField.get(null);
            if (pushToTalk != null) {
                try {
                    isDownMethod = pushToTalk.getClass().getMethod("isDown");
                } catch (NoSuchMethodException e) {
                    isDownMethod = pushToTalk.getClass().getMethod("m_90857_");
                }
            }

            MinecraftForge.EVENT_BUS.register(OriginalVeritySttInjector.class);
            injected = true;
            OllamaProxyMod.LOGGER.info("[SttInjector] 已注入原版 Verity STT 链路，使用扩展 STT 引擎");
        } catch (Exception e) {
            OllamaProxyMod.LOGGER.warn("[SttInjector] 注入失败，STT 将使用原版: {}", e.getMessage());
        }
    }

    /** SttInjector 是否已激活（劫持了 Verity 的 PUSH_TO_TALK 按键） */
    public static boolean isActive() {
        return injected;
    }

    public static synchronized void startSession(long generation) {
        sessionGeneration = generation;
        recognitionInFlight.set(false);
    }

    public static synchronized void endSession(long generation) {
        sessionGeneration = generation;
        recognitionInFlight.set(false);
        SpeechRecognizer recognizer = ourRecognizer;
        ourRecognizer = null;
        if (recognizer != null) {
            recognizer.shutdown();
        }
        try {
            Boolean isRecording = isRecordingField == null ? null : (Boolean) isRecordingField.get(null);
            if (Boolean.TRUE.equals(isRecording)) {
                isRecordingField.set(null, false);
                if (stopRecordingMethod != null && recorderInstance != null) {
                    stopRecordingMethod.invoke(recorderInstance);
                }
            }
        } catch (Exception e) {
            OllamaProxyMod.LOGGER.warn("[SttInjector] 结束会话录音失败: {}", e.getMessage());
        }
    }

    /**
     * HIGHEST 优先级，在原版 KeybindHandler (NORMAL) 之前执行。
     * 当检测到 PUSH_TO_TALK 键释放时，截获录音并用我们自己的识别器转录。
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!injected) return;

        try {
            Boolean isRecording = (Boolean) isRecordingField.get(null);
            if (isRecording == null || !isRecording) return;

            // 使用缓存的 field/method 检测按键
            Object pushToTalk = ptkField.get(null);
            if (pushToTalk == null) return;

            Boolean isKeyDown = (Boolean) isDownMethod.invoke(pushToTalk);
            if (isKeyDown == null || isKeyDown) return;

            // 按键释放，原版录音已在进行中 -> 截获
            isRecordingField.set(null, false);

            byte[] pcmData = (byte[]) stopRecordingMethod.invoke(recorderInstance);
            if (pcmData == null || pcmData.length == 0) return;

            AudioFormat format = (AudioFormat) getFormatMethod.invoke(recorderInstance);

            OllamaProxyMod.LOGGER.info("[SttInjector] 截获 PUSH_TO_TALK 录音 {} bytes，使用扩展 STT", pcmData.length);

            // 延迟初始化我们的识别器
            if (ourRecognizer == null || !ourRecognizer.isReady()) {
                initRecognizer();
            }

            if (ourRecognizer != null && ourRecognizer.isReady()) {
                if (!recognitionInFlight.compareAndSet(false, true)) {
                    OllamaProxyMod.LOGGER.warn("[SttInjector] 上一次 STT 请求仍在处理中，本次录音不再并发提交");
                    return;
                }
                byte[] audioCopy = pcmData.clone();
                long expectedSession = sessionGeneration;
                SpeechRecognizer recognizer = ourRecognizer;
                CompletableFuture.supplyAsync(() -> {
                    try {
                        return recognizer.recognize(audioCopy);
                    } catch (Exception e) {
                        OllamaProxyMod.LOGGER.error("[SttInjector] STT 识别失败", e);
                        Minecraft.getInstance().execute(() -> {
                            if (sessionGeneration == expectedSession && Minecraft.getInstance().player != null) {
                                Minecraft.getInstance().player.sendSystemMessage(
                                        net.minecraft.network.chat.Component.literal("§c[语音输入] 云端语音服务暂时不可用，请稍后重试"));
                            }
                        });
                        return "";
                    } finally {
                        if (sessionGeneration == expectedSession) {
                            recognitionInFlight.set(false);
                        }
                    }
                }).thenAccept(text -> {
                    if (sessionGeneration == expectedSession && text != null && !text.trim().isEmpty()) {
                        String safeText = text.trim();
                        if ("thank you.".equalsIgnoreCase(safeText)) return;
                        if (safeText.length() > 256) safeText = safeText.substring(0, 256);
                        String finalText = safeText;
                        Minecraft.getInstance().execute(() -> {
                            if (sessionGeneration == expectedSession && Minecraft.getInstance().player != null) {
                                // 统一走 VerityChatSender：服务端装了本 mod 就发 /v 指令，
                                // 否则普通聊天会被服务端的 requireCommand 拦掉（语音等于没反应）
                                com.hanchen.veritycustomapi.client.VerityChatSender.sendToVerity(finalText);
                            }
                        });
                    }
                });
            } else {
                OllamaProxyMod.LOGGER.warn("[SttInjector] 扩展 STT 引擎不可用，请检查 STT 配置");
            }
        } catch (Exception e) {
            OllamaProxyMod.LOGGER.warn("[SttInjector] tick 处理异常: {}", e.getMessage());
        }
    }

    private static volatile boolean injecting = false;

    public static synchronized void onConfigSaved() {
        sessionGeneration++;
        recognitionInFlight.set(false);
        SpeechRecognizer recognizer = ourRecognizer;
        ourRecognizer = null;
        if (recognizer != null) {
            recognizer.shutdown();
        }
    }

    private static void initRecognizer() {
        if (injecting) return; // 防止并发重复初始化
        injecting = true;
        try {
            initRecognizerInternal();
        } finally {
            injecting = false;
        }
    }

    private static void initRecognizerInternal() {
        ConfigManager.RecognitionMode mode = ConfigManager.getRecognitionMode();
        SpeechRecognizer candidate = null;

        // 1. 本地 Vosk 优先：用户选本地模式时不因 API 已配置而走在线
        if (mode == ConfigManager.RecognitionMode.LOCAL) {
            try {
                Class<?> voskClass = Class.forName("com.hanchen.veritycustomapi.stt.LocalVoskRecognizer");
                candidate = (SpeechRecognizer) voskClass
                        .getConstructor(String.class)
                        .newInstance(ConfigManager.getVoskModelPath());
                if (candidate.isReady()) {
                    replaceRecognizer(candidate);
                    OllamaProxyMod.LOGGER.info("[SttInjector] 扩展 STT 本地 Vosk 引擎已就绪");
                    return;
                }
                candidate.shutdown();
                candidate = null;
            } catch (Throwable e) {
                // 用 Throwable 而不是 Exception：缺 JNA 或原生库架构不匹配时抛的是
                // NoClassDefFoundError / UnsatisfiedLinkError 这类 Error，只捕 Exception
                // 会让它逃出去把整个 STT 初始化线程干掉，而不是回退到在线 API。
                if (candidate != null) candidate.shutdown();
                OllamaProxyMod.LOGGER.warn("[SttInjector] 本地 Vosk 识别器初始化失败（{}），尝试回退 API: {}",
                        e.getClass().getSimpleName(), e.getMessage());
            }
        }

        // 2. 尝试 API 模式（在线模式优先，本地失败时回退）
        String apiUrl = ConfigManager.getSpeechApiUrl();
        String apiKey = ConfigManager.getSpeechApiKey();
        if (apiUrl != null && !apiUrl.isBlank() && apiKey != null && !apiKey.isBlank()) {
            try {
                String model = ConfigManager.getSpeechApiModel();
                String authStr = ConfigManager.getSpeechAuthType().toLowerCase();
                ApiSpeechRecognizer.AuthType authType = ApiSpeechRecognizer.AuthType.BEARER;
                if (authStr.contains("api_key_header")) authType = ApiSpeechRecognizer.AuthType.API_KEY_HEADER;
                else if (authStr.contains("api_key_param")) authType = ApiSpeechRecognizer.AuthType.API_KEY_PARAM;
                else if (authStr.contains("custom")) authType = ApiSpeechRecognizer.AuthType.CUSTOM_HEADER;

                String formatStr = ConfigManager.getSpeechRequestFormat().toLowerCase();
                ApiSpeechRecognizer.RequestFormat format = ApiSpeechRecognizer.RequestFormat.MULTIPART_FORM_DATA;
                if (formatStr.contains("json")) format = ApiSpeechRecognizer.RequestFormat.JSON;
                else if (formatStr.contains("url")) format = ApiSpeechRecognizer.RequestFormat.URL_ENCODED;

                candidate = new ApiSpeechRecognizer(apiUrl, apiKey, model,
                        format, authType, ConfigManager.getSpeechResponseField(), null);
                replaceRecognizer(candidate);
                OllamaProxyMod.LOGGER.info("[SttInjector] 扩展 STT API 引擎已就绪: endpoint={}, model={}",
                        NetworkSecurity.sanitizedEndpoint(apiUrl), model);
                return;
            } catch (Exception e) {
                OllamaProxyMod.LOGGER.error("[SttInjector] STT API 引擎初始化失败", e);
            }
        }

        OllamaProxyMod.LOGGER.warn("[SttInjector] STT 未配置 — API模式需填URL/Key，本地模式需安装Vosk模型");
    }

    private static void replaceRecognizer(SpeechRecognizer replacement) {
        SpeechRecognizer previous = ourRecognizer;
        ourRecognizer = replacement;
        if (previous != null && previous != replacement) previous.shutdown();
    }
}
