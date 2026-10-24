package com.hanchen.veritycustomapi.compat;

import com.hanchen.veritycustomapi.util.Silent;

import java.nio.charset.StandardCharsets;

/**
 * 运行时探测原版 Verity 的内部结构。
 * 不用 Class.forName，改成扫 class 常量池：Mixin 插件加载阶段去加载原版类会触发静态初始化链、
 * 把游戏带崩；方法名在常量池里是 UTF-8 明文，扫字节够准。
 */
public final class VerityStructureDetector {

    private VerityStructureDetector() {
    }

    /** 原版 AiManager 的 class 资源路径。 */
    public static final String AI_MANAGER_RESOURCE = "varmite/verity/entity/llm/AiManager.class";

    /** 6.2 起独立出来的回复发送方法；6.0/6.1 直接写在 lambda$queryAI$4 里。 */
    public static final String NEW_REPLY_METHOD = "sendReplyPart";

    /** 6.0/6.1 的回复发送入口（编译器产出的 lambda 名，序号可能随重构变化）。 */
    public static final String LEGACY_REPLY_METHOD = "lambda$queryAI$4";

    /** 6.2 起原版自带整套抚摸支持；存在时扩展的兼容层必须让位，否则一次抚摸响应两次。 */
    public static final String BUILTIN_PAT_RESOURCE = "varmite/verity/pat/PatQuips.class";

    /** 原版 AI 工具集。 */
    public static final String TOOLS_RESOURCE = "varmite/verity/entity/llm/actions/Tools.class";

    /** 6.2 的金附魔书机制：一次给多本附魔书时每本附魔随机，会出现"一本对、其余不对"。 */
    public static final String GOLDEN_BOOK_METHOD = "goldenMakeBook";

    /** 6.2 新增的 run_command 工具；give_item 造的是无 NBT 物品，带数据物品只能走它。 */
    public static final String RUN_COMMAND_METHOD = "run_command";

    /** 6.2 自带的记忆提炼入口（每 5 轮提炼 + 每轮附长期备忘）；存在时扩展的记忆增强全部让位。 */
    public static final String MEMORY_REFINER_RESOURCE = "varmite/verity/entity/llm/MemoryRefiner.class";

    /** 原版记忆提炼请求（prompts/memory_refine.xml）的根标签，只出现在该内部调用里。 */
    public static final String MEMORY_REFINE_ROOT = "<memory_refinement>";

    /** 原版 Verity 实体的 class 资源路径。 */
    public static final String VERITY_ENTITY_RESOURCE =
            "varmite/verity/entity/verity/VerityEntity.class";

    /**
     * 「把 Verity 扛在肩上」只有部分构建才有：它的状态查询方法名只出现在那些构建的实体类里。
     * 有它时扩展的站头/骑乘就得避让——扛肩不占乘客位，两套定位会同时生效、互相打架。
     */
    public static final String SHOULDER_METHOD = "isShoulderActive";

    /** 原版日记类内部请求（日记/书页/回信）的根标签；代理对这类请求不做注入也不加工回复。 */
    public static final String SYSTEM_PROMPT_ROOT = "<system_prompt>";

    /** 只看最新一条 user 消息判断是否内部提示词调用；玩家对话的 system 里同样含该标签。 */
    public static boolean isInternalPromptRequest(String latestUserText) {
        if (latestUserText == null || latestUserText.isBlank()) return false;
        String text = latestUserText.strip();
        return text.startsWith(MEMORY_REFINE_ROOT) || text.startsWith(SYSTEM_PROMPT_ROOT);
    }

    /** class 资源是否存在（只查资源，不加载类，避免触发原版静态初始化）。 */
    public static boolean hasResource(ClassLoader loader, String resource) {
        if (loader == null || resource == null) return false;
        try {
            return loader.getResource(resource) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** class 字节里是否定义了指定方法；classBytes 为 null 返回 false。 */
    public static boolean hasMethod(byte[] classBytes, String methodName) {
        if (classBytes == null || methodName == null || methodName.isEmpty()) return false;
        byte[] needle = methodName.getBytes(StandardCharsets.UTF_8);
        if (needle.length > classBytes.length) return false;
        outer:
        for (int i = 0; i + needle.length <= classBytes.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (classBytes[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    /** AiManager 是否为「新版回复结构」（6.2+）。 */
    public static boolean isNewReplyStructure(byte[] aiManagerBytes) {
        return hasMethod(aiManagerBytes, NEW_REPLY_METHOD);
    }

    /** AiManager 里是否还有旧版回复入口；新旧都没有说明上游又重构了，私聊适配会静默失效。 */
    public static boolean hasLegacyReplyMethod(byte[] aiManagerBytes) {
        return hasMethod(aiManagerBytes, LEGACY_REPLY_METHOD);
    }

    // 以下判定会被运行期多处查询，结果缓存一次。

    private static volatile Boolean builtinPatSupport;

    /** 原版是否自带抚摸支持（6.2+）；true 时扩展必须关闭自己的兼容层。 */
    public static boolean hasBuiltinPatSupport() {
        Boolean cached = builtinPatSupport;
        if (cached != null) return cached;
        boolean detected = hasResource(
                VerityStructureDetector.class.getClassLoader(), BUILTIN_PAT_RESOURCE);
        builtinPatSupport = detected;
        return detected;
    }

    /** 仅供自检使用：重置缓存，便于按不同构建重复验证。 */
    public static void resetCacheForTesting() {
        builtinPatSupport = null;
        goldenBookMechanic = null;
        runCommandTool = null;
        refinedMemorySystem = null;
        shoulderMountSystem = null;
    }

    private static volatile Boolean runCommandTool;

    /** 原版工具集里是否有 run_command（6.2+）；有则代理引导带数据物品走 /give。 */
    public static boolean hasRunCommandTool() {
        Boolean cached = runCommandTool;
        if (cached != null) return cached;
        boolean detected = false;
        try (java.io.InputStream in = VerityStructureDetector.class.getClassLoader()
                .getResourceAsStream(TOOLS_RESOURCE)) {
            if (in != null) detected = hasMethod(in.readAllBytes(), RUN_COMMAND_METHOD);
        } catch (Throwable ignored) { Silent.ignore("VerityStructureDetector", ignored); }
        runCommandTool = detected;
        return detected;
    }

    private static volatile Boolean goldenBookMechanic;

    /** 原版是否带金附魔书机制（6.2+）；有则把附魔书数量压到 1 本，旧版保持原样。 */
    public static boolean hasGoldenBookMechanic() {
        Boolean cached = goldenBookMechanic;
        if (cached != null) return cached;
        boolean detected = false;
        try (java.io.InputStream in = VerityStructureDetector.class.getClassLoader()
                .getResourceAsStream(TOOLS_RESOURCE)) {
            if (in != null) detected = hasMethod(in.readAllBytes(), GOLDEN_BOOK_METHOD);
        } catch (Throwable ignored) { Silent.ignore("VerityStructureDetector", ignored); }
        goldenBookMechanic = detected;
        return detected;
    }

    private static volatile Boolean refinedMemorySystem;

    /** 原版是否自带记忆提炼与每轮长期备忘（6.2+）；true 时扩展的记忆增强整体让位。 */
    public static boolean hasRefinedMemorySystem() {
        Boolean cached = refinedMemorySystem;
        if (cached != null) return cached;
        boolean detected = hasResource(
                VerityStructureDetector.class.getClassLoader(), MEMORY_REFINER_RESOURCE);
        refinedMemorySystem = detected;
        return detected;
    }

    private static volatile Boolean shoulderMountSystem;

    /**
     * 原版是否带「把 Verity 扛在肩上」这套机制；true 时扩展的站头/骑乘必须避让。
     * 该机制不占用乘客位，判定不出来就会出现两套定位同时生效。
     */
    public static boolean hasShoulderMountSystem() {
        Boolean cached = shoulderMountSystem;
        if (cached != null) return cached;
        boolean detected = false;
        try (java.io.InputStream in = VerityStructureDetector.class.getClassLoader()
                .getResourceAsStream(VERITY_ENTITY_RESOURCE)) {
            if (in != null) detected = hasMethod(in.readAllBytes(), SHOULDER_METHOD);
        } catch (Throwable ignored) { Silent.ignore("VerityStructureDetector", ignored); }
        shoulderMountSystem = detected;
        return detected;
    }
}
