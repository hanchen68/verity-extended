package com.hanchen.veritycustomapi.config;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本模组 TOML 配置的自愈。
 *
 * Forge 在 ModLoading 阶段才真正读取 verity_extended-client.toml / verity_extended-common.toml，
 * 所以模组构造时还有机会先把它洗干净。现场见到的故障是同一个键被写了两遍
 * （Android 上进程被杀时写一半、或手工编辑过），night-config 直接抛 ParsingException，
 * 整局游戏在配置加载阶段崩掉，玩家连界面都进不去。
 *
 * 这里只做最小改动：重复表头合并、重复键保留第一次出现的值；改之前先留 .bak，
 * 出任何异常就当没动过。common 配置同样由 Forge 写、同样会崩，所以两份都处理。
 */
public final class ForgeConfigRepair {
    private static final String[] FILE_NAMES = {
            "verity_extended-client.toml",
            "verity_extended-common.toml"
    };

    /**
     * 每个文件必须存在的「哨兵键」。Forge 写 TOML 不是原子操作：Android 上进程被杀会留下半截文件，
     * 缺的键在下次加载时按默认值处理——{@code cloud.enable} 的默认值就是 false，
     * 于是「云端配置自己关掉了」。这里用哨兵判断文件是否完整，不完整就拿上一次的快照恢复。
     */
    private static final Map<String, String[][]> SENTINELS = Map.of(
            "verity_extended-common.toml", new String[][]{{"cloud", "enable"}, {"server", "port"}},
            "verity_extended-client.toml", new String[][]{{"localization", "enableVerityLocalization"}}
    );

    /** 上一次完整文件的快照后缀。 */
    private static final String SNAPSHOT_SUFFIX = ".snapshot";
    private static final Pattern TABLE_LINE = Pattern.compile("^\\s*\\[([^\\[\\]]+)]\\s*$");
    private static final Pattern KEY_LINE = Pattern.compile("^\\s*([A-Za-z0-9_.\\-]+)\\s*=");

    private ForgeConfigRepair() {
    }

    public static void repairIfNeeded() {
        for (String fileName : FILE_NAMES) {
            try {
                Path path = FMLPaths.CONFIGDIR.get().resolve(fileName);
                if (!Files.isRegularFile(path)) continue;
                dedupe(path, fileName);
                restoreIfTruncated(path, fileName);
                snapshot(path, fileName);
            } catch (Throwable error) {
                OllamaProxyMod.LOGGER.warn("配置自检失败，保持 {} 原样: {}", fileName, error.toString());
            }
        }
    }

    /**
     * 保存成功后留一份完整快照，下一次启动发现文件被写坏就拿它恢复。
     * 由 {@link CloudOverrides#saveSpec} 在所有保存路径上调用。
     */
    public static void snapshotAfterSave(net.minecraftforge.common.ForgeConfigSpec spec) {
        if (spec == null) return;
        String fileName = spec == ModConfig.CLIENT_SPEC
                ? "verity_extended-client.toml" : "verity_extended-common.toml";
        try {
            Path path = FMLPaths.CONFIGDIR.get().resolve(fileName);
            if (Files.isRegularFile(path)) snapshot(path, fileName);
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.debug("保存配置快照失败: {}", error.toString());
        }
    }

    /** 复制一份快照；只有包含全部哨兵键的文件才会被当成「完整」。 */
    private static void snapshot(Path path, String fileName) {
        if (!hasSentinels(path, fileName)) return;
        try {
            Files.copy(path, snapshotPath(path), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception error) {
            OllamaProxyMod.LOGGER.debug("写入配置快照失败: {}", error.toString());
        }
    }

    private static Path snapshotPath(Path path) {
        return path.resolveSibling(path.getFileName() + SNAPSHOT_SUFFIX);
    }

    /**
     * 文件不完整（例如写到一半被杀）时用快照恢复。
     * 判据是哨兵键是否存在——缺了它，{@code cloud.enable} 会被当成默认的 false，
     * 表现出来就是「云端配置自己关了」。
     */
    private static void restoreIfTruncated(Path path, String fileName) {
        if (hasSentinels(path, fileName)) return;
        Path snapshot = snapshotPath(path);
        if (!Files.isRegularFile(snapshot) || !hasSentinels(snapshot, fileName)) {
            OllamaProxyMod.LOGGER.warn("{} 缺少必需配置键且没有可用快照，将按默认值加载（云端开关可能变回关闭）", fileName);
            return;
        }
        try {
            Path broken = path.resolveSibling(path.getFileName() + ".broken");
            Files.copy(path, broken, StandardCopyOption.REPLACE_EXISTING);
            Files.copy(snapshot, path, StandardCopyOption.REPLACE_EXISTING);
            OllamaProxyMod.LOGGER.warn("{} 看起来被写坏了（缺少必需配置键），已用上次的快照恢复；坏文件留在 {}",
                    fileName, broken.getFileName());
        } catch (Exception error) {
            OllamaProxyMod.LOGGER.warn("{} 快照恢复失败: {}", fileName, error.toString());
        }
    }

    /** 文件里是否包含该文件声明的全部哨兵键（section.key）。 */
    private static boolean hasSentinels(Path path, String fileName) {
        String[][] sentinels = SENTINELS.get(fileName);
        if (sentinels == null || sentinels.length == 0) return true;
        Set<String> present = new HashSet<>();
        String table = "";
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                Matcher tableMatcher = TABLE_LINE.matcher(line);
                if (tableMatcher.matches()) {
                    table = tableMatcher.group(1).trim();
                    continue;
                }
                Matcher keyMatcher = KEY_LINE.matcher(line);
                if (keyMatcher.find()) present.add(table + '.' + keyMatcher.group(1));
            }
        } catch (Exception error) {
            return false;
        }
        for (String[] sentinel : sentinels) {
            if (!present.contains(sentinel[0] + '.' + sentinel[1])) return false;
        }
        return true;
    }

    private static void dedupe(Path path, String fileName) throws Exception {
        if (!Files.isRegularFile(path)) return;
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        List<String> fixed = new ArrayList<>(lines.size());
        Set<String> tables = new HashSet<>();
        Set<String> keys = new HashSet<>();
        String table = "";
        int droppedTables = 0;
        int droppedKeys = 0;
        boolean inMultiline = false;

        for (String line : lines) {
            if (inMultiline) {
                // 多行字符串内部原样保留，别在里面乱删
                fixed.add(line);
                if (line.trim().endsWith("\"\"\"")) inMultiline = false;
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.startsWith("\"\"\"") && !(trimmed.length() > 3 && trimmed.endsWith("\"\"\""))) {
                inMultiline = true;
                fixed.add(line);
                continue;
            }

            Matcher tableMatcher = TABLE_LINE.matcher(line);
            if (tableMatcher.matches()) {
                String name = tableMatcher.group(1).trim();
                table = name;
                if (!tables.add(name)) {
                    droppedTables++;
                    continue;
                }
                fixed.add(line);
                continue;
            }

            Matcher keyMatcher = KEY_LINE.matcher(line);
            if (keyMatcher.find()
                    && !trimmed.endsWith("[")
                    && !trimmed.endsWith("{")
                    && !trimmed.endsWith(",")) {
                String key = keyMatcher.group(1);
                if (!keys.add(table + '|' + key)) {
                    droppedKeys++;
                    continue;
                }
            }
            fixed.add(line);
        }

        if (droppedTables == 0 && droppedKeys == 0) return;

        Path backup = path.resolveSibling(fileName + ".bak");
        Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING);
        Files.write(path, fixed, StandardCharsets.UTF_8);
        OllamaProxyMod.LOGGER.warn("检测到 {} 有重复项，已自动修复并备份为 {}（去掉重复表头 {} 个、重复键 {} 个）",
                fileName, backup.getFileName(), droppedTables, droppedKeys);
    }
}