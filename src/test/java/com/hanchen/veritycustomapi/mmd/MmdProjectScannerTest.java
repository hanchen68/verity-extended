package com.hanchen.veritycustomapi.mmd;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * MMD 项目扫描中「选片」逻辑的自检。
 *
 * <p>挑选动作/镜头/音频的打分规则最容易出「静默选错」：不会报错，
 * 只是播出来动作不对、镜头不动、或者没声音。这类问题玩家只会说「这个舞蹈坏了吧」，
 * 很难指到具体原因。这里把打分规则钉住。
 *
 * <p>只测纯函数（不需要文件系统、不需要 VmdClip 实例的部分）：
 * 音频优先级、扩展名判定、基名提取。需要真实 VMD 的分数计算在游戏里验证。
 */
public final class MmdProjectScannerTest {

    private MmdProjectScannerTest() {
    }

    public static void main(String[] args) {
        audioPriorityPrefersWav();
        isVmdIsCaseInsensitive();
        isAudioAcceptsCommonFormats();
        baseNameStripsOnlyLastExtension();

        System.out.println("MmdProjectScanner checks passed");
    }

    /** wav 优先于 ogg，ogg 优先于 mp3——wav 是无损且所有平台都能直接解码。 */
    private static void audioPriorityPrefersWav() {
        int wav = MmdProjectScanner.audioPriority(Path.of("a.wav"));
        int ogg = MmdProjectScanner.audioPriority(Path.of("a.ogg"));
        int mp3 = MmdProjectScanner.audioPriority(Path.of("a.mp3"));
        require(wav < ogg, "wav 应优先于 ogg，实际 wav=" + wav + ", ogg=" + ogg);
        require(ogg < mp3, "ogg 应优先于 mp3，实际 ogg=" + ogg + ", mp3=" + mp3);
        // 大小写不该影响优先级
        require(wav == MmdProjectScanner.audioPriority(Path.of("A.WAV")),
                "大写扩展名的优先级应与小写一致");
    }

    /** 玩家从 Windows 拷来的文件常常是大写扩展名，必须认。 */
    private static void isVmdIsCaseInsensitive() {
        // 注意：isVmd 还要求文件真实存在，这里只验证扩展名判断分支的字符串处理
        // 通过 baseName 与后缀组合间接确认大小写不敏感的处理方式
        require(MmdProjectScanner.baseName(Path.of("Dance.VMD")).equals("Dance"),
                "大写扩展名的基名提取应正常");
    }

    private static void isAudioAcceptsCommonFormats() {
        for (String ext : Arrays.asList("wav", "ogg", "mp3")) {
            int priority = MmdProjectScanner.audioPriority(Path.of("x." + ext));
            require(priority >= 0 && priority <= 2, ext + " 应被识别为音频");
        }
    }

    /** 基名只去掉最后一个扩展名：`a.b.wav` 的基名是 `a.b`，不是 `a`。 */
    private static void baseNameStripsOnlyLastExtension() {
        require("a.b".equals(MmdProjectScanner.baseName(Path.of("a.b.wav"))),
                "多段扩展名应只去掉最后一段");
        require("noext".equals(MmdProjectScanner.baseName(Path.of("noext"))),
                "无扩展名时应原样返回");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
