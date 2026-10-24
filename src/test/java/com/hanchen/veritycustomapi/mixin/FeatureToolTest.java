package com.hanchen.veritycustomapi.mixin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 「AI 建造 / 摄像头观察」两个功能工具的自检。
 *
 * <p>这两个能力原来靠文本标记和关键词触发，改成 {@code @Tool} 之后，
 * 一旦注册漏了、或者前置条件没拦，表现都是**静默的**：
 * 模型要么根本看不到工具（功能等于消失），要么看到却调用失败（开始胡说八道）。
 * 编译期都发现不了，所以用源码级断言钉住。
 */
public final class FeatureToolTest {

    private FeatureToolTest() {
    }

    private static final Path ROOT = Path.of("src", "main", "java", "com", "hanchen",
            "veritycustomapi");
    private static final String MIXIN = "mixin/VerityFeatureToolMixin.java";

    public static void main(String[] args) throws Exception {
        toolsAreDeclared();
        mixinIsRegistered();
        unavailableToolsAreFiltered();
        cameraToolGoesThroughDistExecutor();
        buildToolRespectsServerThread();

        System.out.println("FeatureTool checks passed");
    }

    /** 两个工具名必须真的写在 @Tool 注解里。 */
    private static void toolsAreDeclared() throws Exception {
        String src = read(MIXIN);
        require(src.contains("@Tool(name = \"build_structure\""),
                "没有声明 build_structure 工具");
        require(src.contains("@Tool(name = \"look_through_camera\""),
                "没有声明 look_through_camera 工具");
    }

    /** 混入类必须同时出现在配置清单和 shouldApplyMixin 的 Verity 分支里。 */
    private static void mixinIsRegistered() throws Exception {
        String json = Files.readString(Path.of("src", "main", "resources",
                "mixins.verity_extended.json"), StandardCharsets.UTF_8);
        require(json.contains("VerityFeatureToolMixin"),
                "VerityFeatureToolMixin 没有在 mixins.verity_extended.json 里声明——写了也不会加载");
        String plugin = read("mixin/MixinPlugin.java");
        require(plugin.contains("VerityFeatureToolMixin"),
                "MixinPlugin 没有为 VerityFeatureToolMixin 加 Verity 存在性判断");
    }

    /**
     * 功能没开时必须把工具从清单里摘掉。
     *
     * <p>不摘的后果是模型会主动说「我帮你盖好了」——玩家没开这个功能，
     * 看到的就是纯粹的编造。
     */
    private static void unavailableToolsAreFiltered() throws Exception {
        String src = read("ai/ToolAvailabilityFilter.java");
        require(src.contains("build_structure"), "ToolAvailabilityFilter 没有拦 build_structure");
        require(src.contains("look_through_camera"), "ToolAvailabilityFilter 没有拦 look_through_camera");
        require(src.contains("ENABLE_AI_BUILDING"), "没有按 AI 建造开关判断");
        require(src.contains("isCameraSkillEnabled"), "没有按「AI 主动观察」开关判断");
    }

    /**
     * 摄像头采集在客户端，工具在服务端线程——必须经 DistExecutor 转发。
     *
     * <p>直接写 {@code CameraChatVision.xxx()} 会在专用服务器上抛
     * NoClassDefFoundError（客户端类不存在），而且整个 Tools 类都会加载失败。
     */
    private static void cameraToolGoesThroughDistExecutor() throws Exception {
        String src = read(MIXIN);
        require(src.contains("DistExecutor"),
                "摄像头工具没有经 DistExecutor 转发，专用服务器上会 NoClassDefFoundError");
        require(src.contains("Dist.CLIENT"), "DistExecutor 没有限定 Dist.CLIENT");
    }

    /**
     * 建造要在服务端线程执行，且不能无条件 {@code submit().join()}。
     *
     * <p>服务端线程上再 submit 到同一个单线程执行器会把自己锁死——这是本项目
     * 反复踩过的坑，所有工具方法都必须先判断 {@code isSameThread()}。
     */
    private static void buildToolRespectsServerThread() throws Exception {
        String src = read(MIXIN);
        require(src.contains("isSameThread()"),
                "建造工具没有判断 isSameThread()，可能把服务端线程锁死");
    }

    private static String read(String relative) throws Exception {
        return Files.readString(ROOT.resolve(relative), StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
