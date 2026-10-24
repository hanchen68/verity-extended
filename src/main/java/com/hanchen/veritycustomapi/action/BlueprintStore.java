package com.hanchen.veritycustomapi.action;

import com.hanchen.veritycustomapi.config.ModConfig;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 蓝图文件的落盘与读取。
 *
 * <p>抽自 {@code AiBuildingManager}。抽出的动机是**安全边界**而不是行数：
 * 蓝图名来自玩家输入（{@code /verity-build save <名字>}），会被直接当文件名用。
 * 这部分逻辑混在 1800 行的建造管理器里时，没人会注意到「这里其实是一道路径穿越防线」，
 * 改起来很容易顺手把 {@link #sanitizeFileName} 的字符白名单放宽。
 *
 * <p>🔴 两条必须保留的约束：
 * <ol>
 *   <li><b>文件名白名单</b>：只允许字母、数字、下划线、连字符和中文。
 *       放宽到「允许点」就会多出 {@code ..} 这种组合，配合子目录写法可以写到蓝图目录之外。</li>
 *   <li><b>目录先建</b>：读不到目录不等于「蓝图库为空」，而是配置错误，
 *       两者必须区分开——否则玩家会以为蓝图丢了，其实是目录配错了。</li>
 * </ol>
 */
final class BlueprintStore {

    private BlueprintStore() {
    }

    /** 蓝图目录：绝对路径直接用；相对路径按服务器目录解析。 */
    static Path directory(Object serverRoot) {
        String configured = ModConfig.AI_BUILDING_BLUEPRINT_DIR.get();
        if (configured == null || configured.isBlank()) {
            configured = "./config/verity_extended/blueprints";
        }
        Path base = Paths.get(configured);
        if (!base.isAbsolute()) {
            String root = serverRoot == null
                    ? System.getProperty("user.dir") : serverRoot.toString();
            base = Paths.get(root, configured).normalize();
        }
        try {
            Files.createDirectories(base);
        } catch (IOException ignored) {
            // 目录建不出来时后续读写会各自报错，这里不再重复喊
        }
        return base;
    }

    /**
     * 把玩家输入的蓝图名压成安全文件名。
     *
     * <p>只保留字母/数字/下划线/连字符/中文，其余一律换成下划线。
     * **刻意不允许点**：一旦允许，{@code ..} 就能组合出来。
     * 长度截到 48，避免超长名字在 Windows 上创建失败。
     */
    static String sanitizeFileName(String raw) {
        if (raw == null) return "blueprint";
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return "blueprint";
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < trimmed.length() && i < 48; i++) {
            char c = trimmed.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-'
                    || (c >= 0x4e00 && c <= 0x9fff)) {
                builder.append(c);
            } else {
                builder.append('_');
            }
        }
        return builder.length() == 0 ? "blueprint" : builder.toString();
    }

    /** 列出全部蓝图文件（按名字排序，便于玩家查找）。 */
    static List<Path> list(Path directory) throws IOException {
        try (var stream = Files.list(directory)) {
            return stream.filter(path -> path.toString().endsWith(".json"))
                    .sorted()
                    .toList();
        }
    }

    static void write(Path directory, String safeName, String json) throws IOException {
        Files.writeString(directory.resolve(safeName + ".json"), json, StandardCharsets.UTF_8);
    }

    static String read(Path directory, String safeName) throws IOException {
        return Files.readString(directory.resolve(safeName + ".json"), StandardCharsets.UTF_8);
    }

    static Path fileFor(Path directory, String safeName) {
        return directory.resolve(safeName + ".json");
    }
}
