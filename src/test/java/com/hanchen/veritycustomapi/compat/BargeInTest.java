package com.hanchen.veritycustomapi.compat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 「说话打断朗读」（barge-in）自检。
 *
 * <p>这个功能有两处极易写错的地方，且都不会报错、只会「感觉没生效」：
 * <ol>
 *   <li><b>打断必须发生在 {@code isRecording} 早退之前。</b>
 *       {@code onClientTick} 一进来就是「没在录音就 return」，
 *       而按键按下的那一帧原版还没把 {@code isRecording} 置 true，
 *       把打断写在早退之后 = 永远不执行。</li>
 *   <li><b>必须同时停两条通道。</b> 原版 {@code KeybindHandler} 自己会调
 *       {@code TTSHandler.interruptSpeech()}，但扩展接管播放后声音走
 *       {@code TtsManager} 那套，只调原版那个停不掉。</li>
 * </ol>
 */
public final class BargeInTest {

    private BargeInTest() {
    }

    private static final Path INJECTOR = Path.of("src", "main", "java", "com", "hanchen",
            "veritycustomapi", "compat", "OriginalVeritySttInjector.java");

    public static void main(String[] args) throws Exception {
        String src = Files.readString(INJECTOR, StandardCharsets.UTF_8);

        int interruptCall = src.indexOf("interruptSpeaking();");
        int recordingEarlyReturn = src.indexOf("if (isRecording == null || !isRecording) return;");
        require(interruptCall > 0, "没有调用 interruptSpeaking()");
        require(recordingEarlyReturn > 0, "找不到 isRecording 早退语句");
        require(interruptCall < recordingEarlyReturn,
                "打断写在「没在录音就 return」之后：按键按下的那一帧永远走不到，功能等于没有");

        require(src.contains("TtsManager.stopSpeaking()"),
                "没有停扩展自己的朗读通道（TtsManager.stopSpeaking）");
        require(src.contains("interruptSpeech()"),
                "没有停原版的朗读通道（OriginalVerityVoiceBridge.interruptSpeech）");
        require(src.contains("bargeInArmed"),
                "没有「一次按键只打断一次」的守卫：按住不放会每帧重复调用");

        System.out.println("BargeIn checks passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
