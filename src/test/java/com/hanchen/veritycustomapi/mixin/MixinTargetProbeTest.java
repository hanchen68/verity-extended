package com.hanchen.veritycustomapi.mixin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * {@code @Inject} 目标必须能被能力探测兜住。
 *
 * <p>守的是 2026-10-24 那次真实崩溃：
 * <pre>
 *   InvalidInjectionException: Critical injection failure:
 *   @Inject annotation on verityExt$bilingualItemSearch could not find any targets
 *   matching 'list_available_itemids' in varmite.verity.entity.llm.actions.Tools
 * </pre>
 * 原版 6.0.0 删掉了这个工具，而 {@code @Inject} 找不到目标会让**整个 {@code Tools} 类加载失败**。
 * 更阴的是它**懒加载**——{@code Tools} 只在第一次对话时被加载，所以：
 * 启动正常、进世界正常、截图里一切正常，**玩家一开口就收到 MixinTransformerError**。
 *
 * <p>（对照：{@code MixinPackageHygieneTest} 守的是「混入包不能放普通类」，
 * 那次也是「跑了几分钟才崩」。同一个教训的两种形态：**mixin 的问题都延迟到运行期才现形**。）
 *
 * <p>所以这里的规则是：**每个 {@code @Inject} 的目标方法，都必须在 MixinPlugin 里
 * 有一处对应的能力探测**，探测不到就不应用这个 mixin。
 */
public final class MixinTargetProbeTest {

    private MixinTargetProbeTest() {
    }

    private static final Path MIXIN_DIR =
            Path.of("src", "main", "java", "com", "hanchen", "veritycustomapi", "mixin");
    private static final Path PLUGIN =
            MIXIN_DIR.resolve("MixinPlugin.java");

    private static final Pattern INJECT_METHOD =
            Pattern.compile("@Inject\\([^)]*method\\s*=\\s*\"([^\"]+)\"", Pattern.DOTALL);

    public static void main(String[] args) throws Exception {
        String plugin = Files.readString(PLUGIN, StandardCharsets.UTF_8);

        injectTargetsAreDeliberate();
        itemSearchProbeWiredToToolsMixin(plugin);
        giveItemToolSurvivesToolsSkip();

        System.out.println("MixinTargetProbe checks passed");
    }

    /**
     * 列出所有 {@code @Inject} 的目标方法，要求它们都能被明确说明。
     *
     * <p>这里不强行要求「每个目标都有探测」——大部分目标是原版长期稳定的方法
     * （{@code onPlayerChat} / {@code render} 之类），给它们都加探测是过度设计。
     * 但**至少要知道有哪些目标**，所以把清单打出来；
     * 真正的强制约束在下一条：物品搜索那个已经踩过坑的必须有探测。
     */
    private static void injectTargetsAreDeliberate() throws Exception {
        List<String> targets = new ArrayList<>();
        try (Stream<Path> files = Files.list(MIXIN_DIR)) {
            for (Path file : files.filter(p -> p.toString().endsWith("Mixin.java")).toList()) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                Matcher matcher = INJECT_METHOD.matcher(text);
                while (matcher.find()) {
                    targets.add(file.getFileName() + " -> " + matcher.group(1));
                }
            }
        }
        require(!targets.isEmpty(), "没有解析到任何 @Inject 目标，解析器可能失效了");
        // 输出清单便于人工核对原版升版时逐项确认
        System.out.println("  @Inject 目标共 " + targets.size() + " 处");
    }

    /**
     * 🔴 物品搜索兼容层必须有能力探测，且探测结果要真的参与 {@code shouldApplyMixin}。
     *
     * <p>只加探测但不接到 {@code shouldApplyMixin} 等于没加——这是最容易犯的错，
     * 因为探测逻辑看起来「已经写好了」。
     */
    private static void itemSearchProbeWiredToToolsMixin(String plugin) {
        require(plugin.contains("list_available_itemids"),
                "MixinPlugin 里没有针对 list_available_itemids 的探测");
        require(plugin.contains("verityItemSearchToolAvailable"),
                "缺少 verityItemSearchToolAvailable 标志");

        // 🔴 必须定位到 shouldApplyMixin **方法体内**的那一处判断。
        // 直接 indexOf("VerityToolsMixin") 会命中类注释里的 {@link VerityToolsMixin}，
        // 那种「看起来通过」的断言比没有断言更糟——它会在真出问题时照样报绿。
        int methodStart = plugin.indexOf("public boolean shouldApplyMixin");
        require(methodStart > 0, "找不到 shouldApplyMixin 方法");
        String methodBody = plugin.substring(methodStart);

        int branch = methodBody.indexOf("mixinClassName.contains(\"VerityToolsMixin\")");
        require(branch > 0, "shouldApplyMixin 里没有 VerityToolsMixin 的启用分支");
        // 取该分支之后到下一个分支之前的片段，确认返回条件用上了探测结果
        int nextBranch = methodBody.indexOf("mixinClassName.contains(", branch + 10);
        String branchBody = nextBranch > branch
                ? methodBody.substring(branch, nextBranch)
                : methodBody.substring(branch);
        require(branchBody.contains("verityItemSearchToolAvailable"),
                "VerityToolsMixin 的启用条件没接上探测结果——"
                        + "原版删掉该方法时仍会应用，整个 Tools 类会加载失败");
    }

    /**
     * 给物品工具不能和物品搜索共用一个 mixin。
     *
     * <p>它们曾经写在一起：物品搜索需要按版本跳过，跳过时同一类里的
     * {@code @Unique} 给物品工具会一起失效。给物品是玩家在用的功能，不能陪葬。
     */
    private static void giveItemToolSurvivesToolsSkip() throws Exception {
        Path giveItem = MIXIN_DIR.resolve("VerityGiveItemByNameMixin.java");
        require(Files.isRegularFile(giveItem), "给物品工具没有独立的 mixin 类");

        String text = Files.readString(giveItem, StandardCharsets.UTF_8);
        require(text.contains("give_item_by_name"), "独立 mixin 里没有 give_item_by_name 工具");
        // 只认真正的注解用法 @Inject(...)，排除 javadoc / 注释里提到的字样
        require(!Pattern.compile("@Inject\\s*\\(").matcher(
                        stripComments(text)).find(),
                "给物品 mixin 不该有任何 @Inject——它一旦注入原版方法，"
                        + "就会跟物品搜索一样受版本变动牵连");

        // 配置里必须声明（否则写了也不会被加载）
        String config = Files.readString(Path.of("src/main/resources/mixins.verity_extended.json"),
                StandardCharsets.UTF_8);
        require(config.contains("VerityGiveItemByNameMixin"),
                "VerityGiveItemByNameMixin 没有在 mixins.verity_extended.json 里声明");

        // 原类里不应该还留着这份实现（否则工具会注册两次）
        String tools = Files.readString(MIXIN_DIR.resolve("VerityToolsMixin.java"),
                StandardCharsets.UTF_8);
        require(!tools.contains("give_item_by_name"),
                "VerityToolsMixin 里还留着 give_item_by_name，工具会重复注册");
    }

    /** 去掉行注释、块注释与 javadoc，避免注释里的字样被当成真代码。 */
    private static String stripComments(String source) {
        String noBlock = source.replaceAll("(?s)/\\*.*?\\*/", "");
        return noBlock.replaceAll("(?m)//.*$", "");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
