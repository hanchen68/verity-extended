package com.hanchen.veritycustomapi.stt;

import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.compat.OriginalVerityVoiceBridge;
import com.hanchen.veritycustomapi.compat.RuntimePlatform;
import com.hanchen.veritycustomapi.tts.TtsManager;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@OnlyIn(Dist.CLIENT)
public class SpeechRecognitionManager {
    public static final SpeechRecognitionManager INSTANCE = new SpeechRecognitionManager();
    private volatile VoiceRecognitionService recognitionService;
    private volatile AndroidSystemSpeechRecognizer androidSystemSpeech;
    private OriginalVerityVoiceBridge.OriginalVoiceInputSession originalVoiceInputSession;
    private final AtomicBoolean isInitialized = new AtomicBoolean(false);
    private final AtomicInteger initializationGeneration = new AtomicInteger(0);
    private final Object initLock = new Object();
    private volatile long sessionGeneration;
    private volatile int activeInitializationGeneration = -1;

    private SpeechRecognitionManager() {}

    /**
     * 异步初始化语音识别服务，不阻塞游戏主线程
     */
    public synchronized void init() {
        if (isInitialized.get() && recognitionService != null) return;
        int generation = initializationGeneration.get();
        if (activeInitializationGeneration == generation) return;
        activeInitializationGeneration = generation;
        Thread initThread = new Thread(() -> {
            VoiceRecognitionService candidate = null;
            try {
                candidate = new VoiceRecognitionService();
                synchronized (initLock) {
                    if (initializationGeneration.get() != generation) {
                        candidate.shutdown();
                        return;
                    }
                    recognitionService = candidate;
                    isInitialized.set(candidate.isReady());
                }
                if (candidate.isReady()) {
                    OllamaProxyMod.LOGGER.debug("语音识别模块初始化完成");
                } else {
                    OllamaProxyMod.LOGGER.warn("语音识别模块未就绪: {}", candidate.getUnavailableReason());
                }
            } catch (Exception e) {
                synchronized (initLock) {
                    if (initializationGeneration.get() == generation) {
                        OllamaProxyMod.LOGGER.error("语音识别模块初始化失败", e);
                        recognitionService = null;
                        isInitialized.set(false);
                    } else if (candidate != null) {
                        candidate.shutdown();
                    }
                }
            } finally {
                synchronized (initLock) {
                    if (activeInitializationGeneration == generation) {
                        activeInitializationGeneration = -1;
                    }
                }
            }
        }, "Speech-Init-Thread");
        initThread.setDaemon(true);
        initThread.start();
    }

    /**
     * 获取内部语音识别服务实例（用于注入代理服务器）
     */
    public VoiceRecognitionService getRecognitionService() {
        return recognitionService;
    }

    /**
     * 按下语音键：先掐掉正在念的 TTS，再开录
     */
    public void onKeyPressed() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        TtsManager.stopSpeaking();
        OriginalVerityVoiceBridge.interruptSpeech();
        VoiceRecognitionService service = recognitionService;
        if (!isInitialized.get() || service == null) {
            // 手机端在线 STT 未配置时，直接回退到系统语音识别，避免“完全不能用”。
            if (RuntimePlatform.isAndroidRuntime() && startAndroidSystemSpeech(mc)) {
                return;
            }
            String reason = service != null
                    ? service.getUnavailableReason()
                    : "语音识别模块尚未初始化完成，请稍后再试";
            showVoiceHint(mc, "§c【语音错误】" + reason);
            return;
        }
        boolean success = service.startListening();
        if (!success) {
            if (RuntimePlatform.isAndroidRuntime() && startAndroidSystemSpeech(mc)) {
                return;
            }
            if (RuntimePlatform.isAndroidRuntime()) {
                showVoiceHint(mc, "§c【语音错误】启动录音失败，请在手机系统/FCL 中授予麦克风权限");
            } else {
                showVoiceHint(mc, "§c【语音错误】启动录音失败，请检查系统音频输入设备或调整采样率");
            }
        }
    }

    private boolean startAndroidSystemSpeech(Minecraft mc) {
        AndroidSystemSpeechRecognizer recognizer = androidSystemSpeech;
        if (recognizer == null) {
            recognizer = new AndroidSystemSpeechRecognizer(
                    text -> mc.execute(() -> handleRecognitionResult(mc, text)),
                    error -> mc.execute(() -> showVoiceHint(mc, "§c【语音错误】" + error)));
            androidSystemSpeech = recognizer;
        }
        OllamaProxyMod.LOGGER.info("在线 STT 未就绪，回退 Android 系统语音识别");
        boolean started = recognizer.start();
        if (started) {
            showVoiceHint(mc, "§a【语音】正在听，松开按键结束");
        }
        return started;
    }

    /**
     * 按键松开：异步结束录音并发送识别结果到聊天（不阻塞 Render 线程）
     */
    public void onKeyReleased() {
        AndroidSystemSpeechRecognizer systemSpeech = androidSystemSpeech;
        if (systemSpeech != null && systemSpeech.isActive()) {
            systemSpeech.stop();
            return;
        }
        if (!isInitialized.get() || recognitionService == null) return;
        final Minecraft mc = Minecraft.getInstance();
        final long expectedSession = sessionGeneration;
        CompletableFuture.supplyAsync(recognitionService::stopListening)
                .thenAccept(result -> {
                    if (sessionGeneration == expectedSession && mc.player != null) {
                        mc.execute(() -> handleRecognitionResult(mc, result));
                    }
                })
                .exceptionally(e -> {
                    OllamaProxyMod.LOGGER.error("语音识别异步处理失败", e);
                    if (sessionGeneration == expectedSession && mc.player != null) {
                        mc.execute(() -> showVoiceHint(mc, "§c【语音错误】识别失败，请重试"));
                    }
                    return null;
                });
    }


    private void stopOriginalVoiceInput() {
        OriginalVerityVoiceBridge.OriginalVoiceInputSession session;
        synchronized (this) {
            session = originalVoiceInputSession;
            originalVoiceInputSession = null;
        }
        if (session == null) {
            return;
        }
        session.stopAndSubmitToOriginalChain();
    }

    private static void handleRecognitionResult(Minecraft mc, String result) {
        if (mc.player == null) return;
        String cleaned = normalizeRecognitionResult(result);
        if (cleaned != null) {
            // 统一走 VerityChatSender：服务端装了本 mod 就发 /v 指令
            // （旧实现发的是普通聊天 "/v …"，会被服务端的 requireCommand 拦掉，等于语音没反应）
            com.hanchen.veritycustomapi.client.VerityChatSender.sendToVerity(cleaned);
            showVoiceHint(mc, "§a【语音→Verity】" + cleaned);
        } else {
            showVoiceHint(mc, "§7【提示】未检测到有效语音输入");
        }
    }

    private static String normalizeRecognitionResult(String result) {
        if (result == null) {
            return null;
        }
        String cleaned = result.trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        cleaned = cleaned.replaceAll("\\s+", " ");
        if (cleaned.length() > 256) {
            cleaned = cleaned.substring(0, 256);
        }
        if (cleaned.length() < 2) {
            return null;
        }
        if (cleaned.matches("(?i)^(嗯+|啊+|呃+|诶+|哦+|噢+|额+)$")) {
            return null;
        }
        if (cleaned.matches("(?i)^(hello|hi|test|testing|one two three|thank you\\.?|thanks)$")) {
            return null;
        }
        return cleaned;
    }

    private static void showVoiceHint(Minecraft mc, String message) {
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal(message), true);
        }
    }

    /**
     * 配置保存后重载，实现热更新
     */
    public void onConfigSaved() {
        shutdown();
        init();
    }

    public void startSession(long generation) {
        sessionGeneration = generation;
        init();
    }

    /**
     * 释放所有资源
     */
    public synchronized void shutdown() {
        sessionGeneration++;
        initializationGeneration.incrementAndGet();
        activeInitializationGeneration = -1;
        VoiceRecognitionService service;
        synchronized (initLock) {
            service = recognitionService;
            recognitionService = null;
            isInitialized.set(false);
        }
        AndroidSystemSpeechRecognizer systemSpeech = androidSystemSpeech;
        androidSystemSpeech = null;
        if (systemSpeech != null) {
            systemSpeech.cancel();
        }
        if (service != null) {
            service.shutdown();
        }
    }

    public boolean isReady() {
        return isInitialized.get() && recognitionService != null && recognitionService.isReady();
    }
}
