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

    /** 判断依据：AiManager 里是否存在 sendReplyPart 方法（6.2 起才有）。 */
    private static final String AI_MANAGER_CLASS =
            com.hanchen.veritycustomapi.compat.VerityStructureDetector.AI_MANAGER_RESOURCE;

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
                || mixinClassName.contains("LivingEntityPerchRideMixin")
                || mixinClassName.contains("MobVerityInteractMixin")
                || mixinClassName.contains("EntityRideAccessor")
                || mixinClassName.contains("LivingEntityRideAccessor")
                || mixinClassName.contains("VerityToolsMixin")
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
