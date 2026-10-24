package com.hanchen.veritycustomapi.mixin;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.compat.VerityLocalization;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Mixin 插件：检测原版 Verity 是否存在，不存在则跳过皮肤相关 Mixin，避免 NoClassDefFoundError；
 * 同时在类加载阶段把原模组 class 里硬编码的英文文本替换成中文（见 {@link VerityLocalization}）。
 */
public class MixinPlugin implements IMixinConfigPlugin {

    /**
     * 汉化开关。Forge 配置系统在 Mixin 阶段还没加载，所以直接读客户端配置文件。
     * 客户端配置文件名由 Forge 按 modId 生成，即 verity_extended-client.toml。
     */
    private static final Pattern LOCALIZATION_SWITCH =
            Pattern.compile("(?m)^\\s*enableVerityLocalization\\s*=\\s*(true|false)");

    private boolean verityAvailable;
    private boolean patPatAvailable;
    private boolean localizationActive;

    /** 原版回复发送逻辑是否为新版结构（6.2 起）。6.0.x/6.1.x 写在 lambda$queryAI$4，6.2 起抽成 sendReplyPart 且参数顺序变了，两个 Mixin 靠此标记二选一。 */
    private boolean verityNewReplyStructure;

    /**
     * 原版是否还有 {@code Tools.list_available_itemids} 工具（6.0.0 起已删除）。
     *
     * <p>🔴 这个标志是 2026-10-24 一次真实崩溃换来的：{@link VerityToolsMixin} 用
     * {@code @Inject(method = "list_available_itemids", ...)} 接管原版物品搜索，
     * 而 6.0.0 把这个工具整个删掉了（只留下 {@code give_item}）。
     * 目标方法找不到时 Mixin 会抛 {@code InvalidInjectionException}，
     * **整个 {@code Tools} 类加载失败**——而 {@code Tools} 是在第一次对话时才被加载的，
     * 所以启动阶段完全正常，玩家一开口就收到
     * {@code MixinTransformerError: An unexpected critical error was encountered}。
     *
     * <p>教训：{@code @Inject} 的目标方法必须先用字节扫描确认存在，
     * 再决定要不要应用这个 mixin。指望 {@code require = 0} 兜底是不行的——
     * 这里的失败是「找不到目标」而不是「参数不匹配」，整类加载直接崩。
     */
    private boolean verityItemSearchToolAvailable;

    /** 判断依据：AiManager 里是否存在 sendReplyPart 方法（6.2 起才有）。 */
    private static final String AI_MANAGER_CLASS =
            com.hanchen.veritycustomapi.compat.VerityStructureDetector.AI_MANAGER_RESOURCE;

    /** 原版物品搜索工具的方法名（6.0.0 起已不存在）。 */
    private static final String ITEM_SEARCH_METHOD = "list_available_itemids";

    @Override
    public void onLoad(String mixinPackage) {
        // 仅检查 VerityEntity.class 资源是否存在，不通过 Class.forName 加载，
        // 避免过早触发 Minecraft Entity -> Registries -> Bootstrap 初始化链。
        ClassLoader loader = getClass().getClassLoader();
        String classPath = "varmite/verity/entity/verity/VerityEntity.class";
        verityAvailable = loader.getResource(classPath) != null;
        patPatAvailable = loader.getResource("net/lopymine/patpat/client/render/PatPatClientRenderer.class") != null;
        OllamaProxyMod.LOGGER.info("[MixinPlugin] PatPat 兼容目标{}", patPatAvailable ? "已检测到" : "未检测到");
        if (verityAvailable) {
            OllamaProxyMod.LOGGER.info("[MixinPlugin] 检测到原版 Verity 模组，将启用皮肤 Mixin");
            verityNewReplyStructure = detectNewReplyStructure(loader);
            boolean legacyReply = detectLegacyReplyStructure(loader);
            OllamaProxyMod.LOGGER.info("[MixinPlugin] 原版回复发送结构：{}",
                    verityNewReplyStructure ? "新版（AiManager.sendReplyPart，6.2+）"
                            : legacyReply ? "旧版（AiManager.lambda$queryAI$4，6.0/6.1）"
                            : "未知（两代入口都没有，私聊适配将失效）");
            if (!verityNewReplyStructure && !legacyReply) {
                OllamaProxyMod.LOGGER.warn("[MixinPlugin] sendReplyPart 与 lambda$queryAI$4 都不在 AiManager 里，"
                        + "私聊/广播适配会静默失效；这通常是 Verity 又重构了回复链路，需要重新适配");
            }
            VerityLocalization.setEnabled(readLocalizationEnabled());
            VerityLocalization.load();
            localizationActive = VerityLocalization.isEnabled();
            OllamaProxyMod.LOGGER.info("[MixinPlugin] 原版界面汉化{}", localizationActive ? "已启用" : "已关闭");
            verityItemSearchToolAvailable = detectItemSearchTool(loader);
            if (!verityItemSearchToolAvailable) {
                OllamaProxyMod.LOGGER.info("[MixinPlugin] 原版没有 {} 工具（6.0.0 起已删除），"
                        + "双语物品搜索兼容层已跳过；给物品功能改由 give_item_by_name 提供",
                        ITEM_SEARCH_METHOD);
            }
        } else {
            OllamaProxyMod.LOGGER.info("[MixinPlugin] 未检测到原版 Verity 模组，皮肤 Mixin 已禁用");
        }
    }

    /** 检测 AiManager 的回复发送结构；直接读 class 资源做字节级查找（见 VerityStructureDetector），不用 Class.forName 以免过早触发原版静态初始化，读取失败按旧版处理。 */
    private static boolean detectNewReplyStructure(ClassLoader loader) {
        try (java.io.InputStream in = loader.getResourceAsStream(AI_MANAGER_CLASS)) {
            if (in == null) return false;
            return com.hanchen.veritycustomapi.compat.VerityStructureDetector
                    .isNewReplyStructure(in.readAllBytes());
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.warn("[MixinPlugin] 检测原版回复结构失败，按旧版处理: {}", error.toString());
            return false;
        }
    }

    /** 旧版 lambda 入口是否还在；读不到就按不存在处理。 */
    private static boolean detectLegacyReplyStructure(ClassLoader loader) {
        try (java.io.InputStream in = loader.getResourceAsStream(AI_MANAGER_CLASS)) {
            if (in == null) return false;
            return com.hanchen.veritycustomapi.compat.VerityStructureDetector
                    .hasLegacyReplyMethod(in.readAllBytes());
        } catch (Throwable error) {
            return false;
        }
    }

    /** 读 Tools 的 class 字节，确认物品搜索工具还在；读不到按「不存在」处理（宁可少一个兼容层，也不能让整类崩掉）。 */
    private static boolean detectItemSearchTool(ClassLoader loader) {
        try (java.io.InputStream in = loader.getResourceAsStream(
                com.hanchen.veritycustomapi.compat.VerityStructureDetector.TOOLS_RESOURCE)) {
            if (in == null) return false;
            return com.hanchen.veritycustomapi.compat.VerityStructureDetector
                    .hasMethod(in.readAllBytes(), ITEM_SEARCH_METHOD);
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.warn("[MixinPlugin] 检测原版物品搜索工具失败，按不存在处理: {}", error.toString());
            return false;
        }
    }

    /** 读取汉化开关：优先系统属性，其次客户端配置文件，都没有时默认开启。 */
    private static boolean readLocalizationEnabled() {
        String property = System.getProperty("verity_extended.localization");
        if (property != null) {
            return !"false".equalsIgnoreCase(property.trim());
        }
        Path[] candidates = {
                Paths.get("config", "verity_extended-client.toml"),
                Paths.get("run", "config", "verity_extended-client.toml")
        };
        for (Path candidate : candidates) {
            try {
                if (!Files.isRegularFile(candidate)) continue;
                String text = Files.readString(candidate, StandardCharsets.UTF_8);
                Matcher matcher = LOCALIZATION_SWITCH.matcher(text);
                if (matcher.find()) {
                    return Boolean.parseBoolean(matcher.group(1));
                }
            } catch (Throwable ignored) {
                // 读取失败按默认开启处理
            }
        }
        return true;
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // 新旧回复发送入口只能启用一个：都对准 AiManager 但目标方法与参数不同，一起启用会让签名不匹配的那个直接注入报错（require = 0 只对「找不到注入点」静默跳过）。
        // V2 的判断必须写在前面，避免被子串匹配吃掉。
        if (mixinClassName.contains("AiManagerPrivateChatV2Mixin")) {
            return verityAvailable && verityNewReplyStructure;
        }
        if (mixinClassName.contains("AiManagerPrivateChatMixin")) {
            return verityAvailable && !verityNewReplyStructure;
        }
        // PatPat Hook 只有在两个目标 Mod 都存在时才应用。
        if (mixinClassName.contains("PatPatClientRendererMixin")) {
            return verityAvailable && patPatAvailable;
        }
        // 🔴 物品搜索兼容层：6.0.0 删掉了 list_available_itemids，@Inject 找不到目标会让
        // 整个 Tools 类加载失败（第一次对话才触发，表现为开局正常、一说话就炸）。
        // 目标方法不存在时必须整个跳过——注意这会让同一 mixin 里的 @Unique give_item_by_name
        // 一起失效，所以下面 VerityGiveItemByNameMixin 单独承担「按名字给物品」。
        if (mixinClassName.contains("VerityToolsMixin")) {
            return verityAvailable && verityItemSearchToolAvailable;
        }
        // 汉化占位 Mixin 同样需要原版 Verity 存在
        if (mixinClassName.contains("VerityLocalizationMixin")) {
            return verityAvailable;
        }
        // Verity 相关 Mixin 需要 Verity 存在
        if (mixinClassName.contains("VerityEntitySkinMixin")
                || mixinClassName.contains("VerityEntityTextureMixin")
                || mixinClassName.contains("VerityItemRendererSkinMixin")
                || mixinClassName.contains("PlayTtsClientHandlerMixin")
                || mixinClassName.contains("VerityTalkingStateMixin")
                || mixinClassName.contains("SphereRendererTalkingStateMixin")
                || mixinClassName.contains("VerityDemonTransformationMixin")
                || mixinClassName.contains("VerityServerSafetyMixin")
                || mixinClassName.contains("VerityWorldStateRestoreMixin")
                || mixinClassName.contains("ServerVerityChatMixin")
                || mixinClassName.contains("AiManagerPrivateChatMixin")
                || mixinClassName.contains("VerityDemonMmdMixin")
                || mixinClassName.contains("VerityDemonBuiltinPmxMixin")
                || mixinClassName.contains("VerityEntityBuiltinPmxMixin")
                || mixinClassName.contains("VerityDemonRenderRotationMixin")
                || mixinClassName.contains("EntityPerchRideMixin")
                || mixinClassName.contains("MobRideControlMixin")
                || mixinClassName.contains("LivingEntityPerchRideMixin")
                || mixinClassName.contains("LivingEntityRiddenControlMixin")
                || mixinClassName.contains("MobVerityInteractMixin")
                || mixinClassName.contains("EntityRideAccessor")
                || mixinClassName.contains("LivingEntityRideAccessor")
                || mixinClassName.contains("VerityToolsMixin")
                || mixinClassName.contains("VerityGiveItemByNameMixin")
                || mixinClassName.contains("VerityFeatureToolMixin")
                || mixinClassName.contains("VerityKarmaCapMixin")
                || mixinClassName.contains("AiApiMemoryIsolationMixin")) {
            return verityAvailable;
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if (!localizationActive || !mixinClassName.contains("VerityLocalizationMixin")) return;
        int replaced = VerityLocalization.apply(targetClass);
        if (replaced > 0) {
            OllamaProxyMod.LOGGER.info("[汉化] {} 已替换 {} 条界面文本", targetClassName, replaced);
        }
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
