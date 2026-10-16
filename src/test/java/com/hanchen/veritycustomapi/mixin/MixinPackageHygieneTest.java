package com.hanchen.veritycustomapi.mixin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 混入包卫生检查。
 *
 * Mixin 规定「混入包（{@code mixins.verity_extended.json} 里声明的 {@code package}）
 * 下的类一律当 Mixin 处理」。往这个包里塞一个普通工具类，只要有任何代码引用它，
 * Mixin 就会抛：
 *
 * IllegalClassLoadError: xxx.SomeHelper is in a defined mixin package com.hanchen.veritycustomapi.mixin.*
 *
 * 这个异常在运行期第一次加载该类时才抛，编译和单测都发现不了 ——
 * 实测就是「游戏跑了几分钟，Verity 一开口整个崩掉」。
 * 这个坑踩过一次（把共用逻辑类放进了混入包），所以固化成自检。
 */
public final class MixinPackageHygieneTest {
    private MixinPackageHygieneTest() {
    }

    private static final String CONFIG = "src/main/resources/mixins.verity_extended.json";
    private static final String CLASSES_ROOT = "build/classes/java/main";

    public static void main(String[] args) throws Exception {
        String config = Files.readString(Paths.get(CONFIG));
        String mixinPackage = firstGroup(config, "\"package\"\\s*:\\s*\"([^\"]+)\"");
        require(mixinPackage != null && !mixinPackage.isBlank(),
                "未能从 " + CONFIG + " 解析出 mixin package");

        // 配置里列出的所有 Mixin 类名，混在 "mixins" 和 "client" 两个数组里
        Set<String> declared = new LinkedHashSet<>();
        for (String section : new String[]{"mixins", "client"}) {
            Matcher matcher = Pattern.compile("\"" + section + "\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL)
                    .matcher(config);
            if (matcher.find()) {
                for (String name : matcher.group(1).split(",")) {
                    String trimmed = name.trim().replace("\"", "");
                    if (!trimmed.isEmpty()) declared.add(trimmed);
                }
            }
        }
        // 配置插件本身不是 Mixin，但按 Mixin 的加载方式可以放在该包里
        declared.add(firstGroup(config, "\"plugin\"\\s*:\\s*\"([^\"]+)\""));
        declared.remove(null);
        require(!declared.isEmpty(), "未能解析出任何 Mixin 声明");

        Path packageDir = Paths.get(CLASSES_ROOT, mixinPackage.replace('.', '/'));
        require(Files.isDirectory(packageDir),
                "未找到混入包的编译产物：" + packageDir + "（请先执行 compileJava）");

        Set<String> simpleNames = declared.stream()
                .map(full -> full.substring(full.lastIndexOf('.') + 1))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<String> offenders;
        try (Stream<Path> files = Files.walk(packageDir)) {
            offenders = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".class"))
                    .map(p -> p.getFileName().toString())
                    .map(name -> name.substring(0, name.length() - ".class".length()))
                    // 内部类（Outer$Inner）跟着它的外部类走
                    .map(name -> name.contains("$") ? name.substring(0, name.indexOf('$')) : name)
                    .distinct()
                    .filter(name -> !simpleNames.contains(name))
                    .sorted()
                    .collect(Collectors.toList());
        }

        if (!offenders.isEmpty()) {
            throw new AssertionError("混入包 " + mixinPackage + " 下存在非 Mixin 类："
                    + String.join(", ", offenders)
                    + " —— Mixin 会把它们当作 Mixin 处理，运行期加载时抛 IllegalClassLoadError。"
                    + "请把这些类移到其它包（例如 com.hanchen.veritycustomapi.compat）。");
        }

        System.out.println("Mixin package hygiene checks passed（" + simpleNames.size() + " 个已声明类）");
    }

    private static String firstGroup(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
