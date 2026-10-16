package com.hanchen.veritycustomapi.compat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 检查本扩展有没有和原版 Verity 抢同一个资源路径。
 *
 * 原版用 {@code getResourceAsStream("/prompts/verity.xml")} 加载主提示词，
 * classpath 上出现同名资源时谁先被找到谁生效——扩展要是也带上
 * {@code prompts/verity.xml}，就可能把原版的提示词整个顶掉。
 *
 * 这个坑真踩过：扩展的 fat jar 里带着一份 15 KB 的旧提示词，而新版原版自己的
 * 已经长到 74 KB。结果新版提示词里的新规则（送礼、日记、新工具说明等）全没加载，
 * 玩家看到的是「扩展改了原版提示词，把新版功能搞坏了」，其实扩展一行提示词都没注入。
 *
 * 这类问题编译能过、单测也能过，只有玩家用起来才暴露，所以固化成自检。
 */
public final class ResourceCollisionTest {
    private ResourceCollisionTest() {
    }

    /** 原版会从 classpath 读的资源目录，扩展一个都不能带。 */
    private static final String[] FORBIDDEN_PREFIXES = {"prompts/"};

    /** 原版自己的 mixin refmap 名，扩展用 mixins.verity_extended.refmap.json 避开。 */
    private static final String[] FORBIDDEN_EXACT = {"verity.refmap.json", "verity.mixins.json"};

    private static final String OUR_RESOURCES = "build/resources/main";
    private static final String VERITY_BUILD = "src/main/java/com/hanchen/veritycustomapi/verity-6.0.0-beta.9";

    public static void main(String[] args) throws Exception {
        Path resources = Paths.get(OUR_RESOURCES);
        require(Files.isDirectory(resources),
                "未找到 " + OUR_RESOURCES + "（请先执行 processResources）");

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(resources)) {
            files.filter(Files::isRegularFile).forEach(path -> {
                String relative = resources.relativize(path).toString().replace('\\', '/');
                for (String prefix : FORBIDDEN_PREFIXES) {
                    if (relative.startsWith(prefix)) {
                        offenders.add(relative + "（原版也用 " + prefix + " 目录，会顶掉原版资源）");
                        return;
                    }
                }
                for (String exact : FORBIDDEN_EXACT) {
                    if (relative.equals(exact)) {
                        offenders.add(relative + "（原版同名文件，会顶掉原版资源）");
                        return;
                    }
                }
            });
        }

        if (!offenders.isEmpty()) {
            throw new AssertionError("扩展打进了与原版同路径的资源：\n  - "
                    + String.join("\n  - ", offenders)
                    + "\n原版用 getResourceAsStream 读这些路径，同名资源会把它顶掉。"
                    + "请把文件改名或移出 src/main/resources。");
        }

        // 反向对照：原版确实提供 prompts/verity.xml，说明这个路径真的会被抢
        Path verityPrompt = Paths.get(VERITY_BUILD, "prompts", "verity.xml");
        if (Files.isRegularFile(verityPrompt)) {
            require(readBytes(verityPrompt) > 0, "原版提示词读取失败");
            System.out.println("Resource collision checks passed（原版提示词 "
                    + readBytes(verityPrompt) + " 字节，扩展未提供同路径资源）");
        } else {
            System.out.println("Resource collision checks passed（未找到原版构建目录，仅做单向检查）");
        }
    }

    private static long readBytes(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            return in.readAllBytes().length;
        } catch (IOException error) {
            return -1L;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
