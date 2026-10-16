package com.hanchen.veritycustomapi.tts;

import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.tts.route.TtsRouteKind;
import com.hanchen.veritycustomapi.tts.route.TtsRouter;
import com.hanchen.veritycustomapi.voice.VoiceTranslator;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@OnlyIn(Dist.CLIENT)
public class TtsManager {

    private static final long SPEAK_DEDUPE_MS = 10000;
    private static String lastSpokenText = "";
    private static long lastSpokenAt = 0;
    private static volatile long sessionGeneration;
    private static final ExecutorService SYNTHESIS_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Verity-TTS-Synthesis");
        thread.setDaemon(true);
        return thread;
    });

    public static boolean speak(String text) {
        return speak(text, -1);
    }

    /**
     * 朗读一句台词。
     *
     * 返回值是「扩展这次接管了吗」，调用方拿它决定要不要把原版的播放拦掉。
     * 必须在收到朗读包的那一 tick 内同步给出答案，所以这里只做判断与派发，
     * 真正的合成/联网都在后台线程上。
     *
     * 三种情况下会返回 {@code true} 但不出声，都是刻意的：没有可念的内容、
     * 同一句在去重窗口内已念过、以及交给下游后下游没接管——这些时候让原版接手
     * 只会变成「念两遍」或「念不该念的东西」，保持安静更正确。
     */
    public static synchronized boolean speak(String text, int entityId) {
        return speak(text, entityId, null);
    }

    /**
     * 朗读一句台词。
     *
     * 返回值是「扩展这次接管了吗」，调用方拿它决定要不要把原版的播放拦掉。
     * 必须在收到朗读包的那一 tick 内同步给出答案，所以这里只做判断与派发，
     * 真正的合成/联网都在后台线程上。
     *
     * 三种情况下会返回 {@code true} 但不出声，都是刻意的：没有可念的内容、
     * 同一句在去重窗口内已念过、以及交给下游后下游没接管——这些时候让原版接手
     * 只会变成「念两遍」或「念不该念的东西」，保持安静更正确。
     *
     * @param displayOverride 这句台词在聊天栏/字幕里的实际阅读语言。双语模式的台词
     * 自带中文译文，阅读语言就是中文——按这个基准判断「还要不要转换」，
     * 才不会把模型已经给出的译文再拿去转一遍、白花一次调用。
     * 传 {@code null} 表示按「台词阅读语言」配置来。
     */
    public static synchronized boolean speak(String text, int entityId,
                                             com.hanchen.veritycustomapi.voice.VoiceLanguage displayOverride) {
        if (!ConfigManager.isEnableTts() || text == null || text.isBlank()) return false;
        // 「同时启用原版朗读」= 扩展整个不接管，连去重的账都不记
        if (!ConfigManager.isDisableOriginalTts()) return false;
        // 先问「这次有会出声的路线吗」；一条都没有就把这句让给原版
        if (!TtsRouter.claim()) return false;

        String normalizedText = normalizeForDedup(text);
        long now = System.currentTimeMillis();
        if (normalizedText.isEmpty()) {
            return true;
        }
        if (normalizedText.equals(lastSpokenText) && now - lastSpokenAt < SPEAK_DEDUPE_MS) {
            OllamaProxyMod.LOGGER.info("【TTS】同一句在 {}s 内已念过，跳过重复朗读: {}",
                    SPEAK_DEDUPE_MS / 1000, normalizedText);
            return true;
        }
        lastSpokenText = normalizedText;
        lastSpokenAt = now;
        // 记下去重键：万一又出现「同一句念两遍」，对照相邻两行日志就能看出两路的文本差在哪
        OllamaProxyMod.LOGGER.info("【TTS】朗读(去重键={}, entityId={})", normalizedText, entityId);

        long expectedSession = sessionGeneration;
        com.hanchen.veritycustomapi.voice.VoiceLanguage display = displayOverride != null
                ? displayOverride : ConfigManager.getVoiceDisplayLanguage();
        SYNTHESIS_EXECUTOR.execute(() -> synthesize(text, entityId, expectedSession, display));
        return true;
    }

    /**
     * 后台线程：先按配音语言把台词转换好，再交给路线调度去念。
     *
     * 转换放在这里而不是在 {@link #speak} 里，是因为它要联网、最长会等几秒——
     * 放主线程会卡住游戏，而它又不是必须同步给出的答案。
     */
    private static void synthesize(String text, int entityId, long expectedSession,
                                   com.hanchen.veritycustomapi.voice.VoiceLanguage display) {
        if (sessionGeneration != expectedSession) return;
        String spoken = text;
        // 原版路线不用我们转：转完交回去反而多花一次钱、还可能转坏原版的语气
        if (TtsRouter.configured() != TtsRouteKind.NATIVE) {
            spoken = VoiceTranslator.forSpeech(text,
                    display, ConfigManager.getVoiceSpeakLanguage());
        }
        if (sessionGeneration != expectedSession) return;
        TtsRouter.speak(spoken, entityId, expectedSession);
    }

    public static void stopSpeaking() {
        com.hanchen.veritycustomapi.compat.VerityEntityTalkAnimator.clear();
        TtsRouter.stop();
        OnlineTtsService.stopPlayback();
        AudioPlayer.stopPlayback();
    }

    /**
     * 生成「同一句话」的归一化比较键：两条朗读来源（聊天栏、服务端 TTS 包）的文本
     * 不一定逐字相同，去掉前缀、控制字符、标点、空白后再比较，避免同一句念两遍。
     */
    private static String normalizeForDedup(String text) {
        return text
                .replaceFirst("(?i)^\\s*<Verity>\\s*", "")
                .replaceFirst("(?i)^\\s*Verity\\s*[:：]?\\s*", "")
                .replaceFirst("^@\\S{1,16}\\s*[:：]\\s*", "")
                .replaceAll("\\p{C}", "")
                .replaceAll("[^\\p{IsHan}a-zA-Z0-9]", "")
                .toLowerCase(Locale.ROOT);
    }

    /** 会话开始：只预热这次真正要走的那条路线。 */
    public static void init() {
        TtsRouter.warmUp();
    }

    public static synchronized void startSession(long generation) {
        sessionGeneration = generation;
        lastSpokenText = "";
        lastSpokenAt = 0;
        resetStreamTts(generation);
        OnlineTtsService.startSession(generation);
        init();
    }

    public static void shutdown() {
        sessionGeneration++;
        resetStreamTts(sessionGeneration);
        stopSpeaking();
        TtsRouter.shutdown();
        OnlineTtsService.shutdown();
        AudioPlayer.shutdown();
    }

    private static void resetStreamTts(long generation) {
        try {
            Class<?> helper = Class.forName("com.hanchen.veritycustomapi.tts.StreamTtsHelper");
            Method method = helper.getMethod("startSession", long.class);
            method.invoke(null, generation);
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.debug("流式 TTS 会话不可用，使用普通在线 TTS");
        }
    }

}
