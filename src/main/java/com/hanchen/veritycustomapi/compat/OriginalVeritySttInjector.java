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

    /**
     * 原版 6.0.0 新增的按键释放缓冲计数（{@code KeybindHandler.releasePaddingTicks}）。
     *
     * <p>6.0.0 的录音状态机在松开 PTT 后不立刻停录，而是先数满 5 个 tick 再停
     * （避免手指抖动截断尾音）。我们截获录音时**必须把它一起清零**：
     * 只清 {@code isRecording} 而留着这个计数器的话，原版下一轮
     * 「松开 → 计数 → 停录」的判断会从一个非零值继续，
     * 表现是下一次说话的开头被吞掉、或者松开后迟迟不停录。
     *
     * <p>6.0.0 之前的版本没有这个字段，取不到就是 null——此时跳过清零即可，
     * 属于正常的新旧版本差异，不该报错。
     */
    private static volatile Field releasePaddingField;

    /** 本次按键是否已经掐过朗读：按住不放时不要每帧都调一次打断。 */
    private static boolean bargeInArmed;

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
            // 6.0.0 起才有；老版本取不到是正常的，清零时跳过即可
            releasePaddingField = resolveReleasePaddingField();

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

    /**
     * 取 {@code KeybindHandler.releasePaddingTicks}（6.0.0 新增）。
     *
     * @return 字段；版本没有这个字段时返回 {@code null}
     */
    private static Field resolveReleasePaddingField() {
        try {
            Field found = keybindHandlerClass.getDeclaredField("releasePaddingTicks");
            found.setAccessible(true);
            return found;
        } catch (Throwable missing) {
            // 6.0.0 之前没有这个字段，属于正常版本差异，静默跳过即可
            return null;
        }
    }

    /**
     * 结束原版录音状态：清 {@code isRecording} **并清释放缓冲计数**。
     *
     * <p>🔴 这两件事必须成对做。6.0.0 的 {@code KeybindHandler.onClientTick} 逻辑是：
     * <pre>
     *   按键按住 → releasePaddingTicks = 0, isRecording = true, startRecording()
     *   按键松开且 isRecording → releasePaddingTicks++；未满 5 次直接 return
     *   满 5 次 → isRecording = false, releasePaddingTicks = 0, stopRecording()
     * </pre>
     * 我们截获录音时抢在它前面把 {@code isRecording} 置成 false 并 stopRecording。
     * 如果不同时清 {@code releasePaddingTicks}，它停在「已经数到 3」这类中间值上，
     * 下一次按键松开时会**少数几个 tick 就停录**，表现为尾音被截断；
     * 更糟的是若残留值已达 5，原版会在我们没按的时候直接走停录分支。
     *
     * <p>这就是「功能看起来能用、但偶发吞字」的那类问题——不会报错，只会让人
     * 觉得识别不准。所以必须显式成对清理，而不是只清 isRecording。
     */
    private static void clearRecordingState() {
        try {
            if (isRecordingField != null) isRecordingField.set(null, false);
            if (releasePaddingField != null) releasePaddingField.setInt(null, 0);
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.debug("[SttInjector] 清理录音状态失败: {}", error.toString());
        }
    }

    /**
     * 掐掉正在播放的朗读（抢话 / barge-in）。
     *
     * <p>要停三条通道，缺一条都会「按了键但声音还在」：
     * <ol>
     *   <li>{@code TtsManager.stopSpeaking()} —— 扩展自己的朗读路线
     *       （TtsRouter / OnlineTtsService / AudioPlayer，后者内部还会 stopAll 原版声音通道）；</li>
     *   <li>{@code OriginalVerityVoiceBridge.interruptSpeech()} —— 原版 AI 自己的朗读；</li>
     * </ol>
     * 原版 {@code KeybindHandler} 按下 PTT 时只调了第 2 条，所以扩展接管播放之后
     * 光靠它是不够的——玩家开口了 Verity 还在念。
     */
    private static void interruptSpeaking() {
        try {
            com.hanchen.veritycustomapi.tts.TtsManager.stopSpeaking();
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.debug("[SttInjector] 停止扩展朗读失败: {}", error.toString());
        }
        try {
            OriginalVerityVoiceBridge.interruptSpeech();
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.debug("[SttInjector] 停止原版朗读失败: {}", error.toString());
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
                // 必须连 releasePaddingTicks 一起清，理由见 clearRecordingState
                clearRecordingState();
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
            // 使用缓存的 field/method 检测按键
            Object pushToTalk = ptkField.get(null);
            if (pushToTalk == null) return;
            Boolean isRecording = (Boolean) isRecordingField.get(null);
            Boolean isKeyDown = (Boolean) isDownMethod.invoke(pushToTalk);
            boolean down = Boolean.TRUE.equals(isKeyDown);

            // 抢话打断：按下语音键的那一刻就把正在念的 TTS 掐掉。
            // 原版 KeybindHandler 自己会调 TTSHandler.interruptSpeech()，但那只管原版那条播放通道；
            // 扩展接管后的播放走 TtsRouter / OnlineTtsService / VanillaAudioSink，
            // 必须另外调 TtsManager.stopSpeaking()，否则玩家开口了 Verity 还在念。
            // 判据用「按下 且 还没进入录音状态」= 这次按下的第一帧，配合 bargeInArmed 保证一次按键只掐一次。
            if (down && !Boolean.TRUE.equals(isRecording)) {
                if (!bargeInArmed) {
                    bargeInArmed = true;
                    interruptSpeaking();
                }
            } else if (!down) {
                bargeInArmed = false;
            }

            if (isRecording == null || !isRecording) return;

            if (isKeyDown == null || isKeyDown) return;

            // 按键释放，原版录音已在进行中 -> 截获。
            // 🔴 只清 isRecording 不够：6.0.0 多了 releasePaddingTicks，
            // 不清会让原版下一次松开时提前停录（吞尾音）。见 clearRecordingState。
            clearRecordingState();

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
