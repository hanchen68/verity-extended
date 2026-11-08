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

    /** 6.2 起独立出来的回复发送方法。 */
    public static final String NEW_REPLY_METHOD = "sendReplyPart";

    /**
     * 6.0.0 正式版里「发 TTS + 广播聊天」的那个 lambda 的<b>描述符</b>。
     *
     * <p>🔴 用描述符而不是方法名：lambda 编号由编译器按出现顺序生成，
     * 同一大版本的不同构建编号就不一样——实测 6.0.0 是 {@code lambda$queryAI$7}，
     * 6.0.0-beta.9 却是 {@code lambda$queryAI$4}，而且两者<b>参数签名完全不同</b>。
     * 写死名字意味着换个构建就静默失效（这正是 2026-11-07 那个
     * 「服务器里 @ 它不会 @ 回那个人」的根因）。
     *
     * <p>Mixin 的方法选择器支持「只写描述符」，因此注入点也不再依赖编号。
     */
    public static final String REPLY_LAMBDA_DESC_600 =
            "(ZLnet/minecraft/server/level/ServerLevel;Lnet/minecraft/server/level/ServerPlayer;"
                    + "Ljava/lang/String;Lnet/minecraft/server/MinecraftServer;"
                    + "Lvarmite/verity/entity/verity/VerityEntity;)V";

    /** 6.0.0-beta.9 / 早期 6.1 的回复 lambda 描述符（参数顺序不同）。 */
    public static final String REPLY_LAMBDA_DESC_BETA9 =
            "(Lvarmite/verity/entity/verity/VerityEntity;Ljava/lang/String;"
                    + "Lnet/minecraft/server/level/ServerPlayer;"
                    + "Lnet/minecraft/server/MinecraftServer;)V";

    /** 广播系统消息的 SRG 名；旧版回复 lambda 里一定引用它。 */
    public static final String BROADCAST_METHOD_SRG = "m_240416_";

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

    /**
     * 旧版回复 lambda 是哪种签名。
     *
     * <p>用描述符判断，不看 lambda 编号：编号随构建变化（6.0.0 是 $7、beta.9 是 $4），
     * 而且两者参数顺序完全不同，必须用不同的 Mixin 处理器。
     *
     * @return 6.0.0 形态 / beta.9 形态 / 都没有
     */
    public enum ReplyLambdaShape {
        /** 6.0.0 正式版：{@code (Z, ServerLevel, ServerPlayer, String, MinecraftServer, VerityEntity)} */
        V600,
        /** 6.0.0-beta.9 / 早期 6.1：{@code (VerityEntity, String, ServerPlayer, MinecraftServer)} */
        V_BETA9,
        /** 没有旧版 lambda（6.2+ 走 sendReplyPart，或上游又重构了） */
        NONE
    }

    /** 判定 AiManager 里的旧版回复 lambda 形态。 */
    public static ReplyLambdaShape replyLambdaShape(byte[] aiManagerBytes) {
        if (aiManagerBytes == null) return ReplyLambdaShape.NONE;
        // 必须同时存在广播调用：只有描述符、没有广播说明不是发送点
        if (!containsBytes(aiManagerBytes, BROADCAST_METHOD_SRG)) return ReplyLambdaShape.NONE;
        if (containsBytes(aiManagerBytes, REPLY_LAMBDA_DESC_600)) return ReplyLambdaShape.V600;
        if (containsBytes(aiManagerBytes, REPLY_LAMBDA_DESC_BETA9)) return ReplyLambdaShape.V_BETA9;
        return ReplyLambdaShape.NONE;
    }

    /**
     * AiManager 里是否还有「旧版回复入口」。
     *
     * <p>🔴 不能只查方法名。{@code lambda$queryAI$4} 在 6.0.0 里确实存在，
     * 但它只是一个 {@code aload_0; areturn} 的取值 lambda——按名字判定会把
     * 「没有回复逻辑」误判成「有旧版入口」，Mixin 注入空方法后静默失效，
     * 表现就是「服务器里 @ 它不会 @ 回那个人」。
     */
    public static boolean hasLegacyReplyMethod(byte[] aiManagerBytes) {
        return replyLambdaShape(aiManagerBytes) != ReplyLambdaShape.NONE;
    }

    /** 纯字节包含判断，独立出来便于测试。 */
    static boolean containsBytes(byte[] haystack, String needleText) {
        if (haystack == null || needleText == null || needleText.isEmpty()) return false;
        byte[] needle = needleText.getBytes(StandardCharsets.UTF_8);
        if (needle.length > haystack.length) return false;
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return true;
        }
        return false;
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
        vbmMoodSystem = null;
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

    /**
     * VBM（Verity Behavior Model）预览版的主事件类。
     *
     * <p>只有「6.0.0 整活 demo」那种构建才有：`varmite/verity/vbm/*` 一整套
     * 心情带（MoodBand）/ 压力（StressService）/ 好感服务（KarmaService）/
     * 压扁与充气（FlatService）/ 捡拾送礼（GiftService）。
     */
    public static final String VBM_RESOURCE = "varmite/verity/vbm/VbmEvents.class";

    private static volatile Boolean vbmMoodSystem;

    /**
     * 是否装了带 VBM 的预览版。
     *
     * <p>true 时扩展要让出重叠能力：送礼交给原版 GiftService（它会自己捡地上的东西），
     * 压扁状态下不参与站头/骑乘（视觉上是一张饼，挂上去很怪）。
     * 心情与好感的提示词注入原版已经自己做（{@code MoodPrompts.directive}），扩展不能重复注入。
     */
    public static boolean hasVbmMoodSystem() {
        Boolean cached = vbmMoodSystem;
        if (cached != null) return cached;
        boolean detected = hasResource(
                VerityStructureDetector.class.getClassLoader(), VBM_RESOURCE);
        vbmMoodSystem = detected;
        return detected;
    }
}
