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
    /** 快照版本戳后缀：记录快照是哪一版写下的。 */
    private static final String SNAPSHOT_META_SUFFIX = ".snapshot.meta";

    /**
     * 「完整」的判定比例：当前文件的配置键数低于快照的这个比例，就认为被截断了。
     *
     * <p>为什么不能只看哨兵键：{@code cloud.enable} 与 {@code server.port} 都在文件前部，
     * 文件被截在它们**之后**时哨兵仍然存在，判据会误以为文件完整——
     * 而真正丢失的是后面的键。2026-10-24 实测就是这样：修正前的备份只有 2479 字节、
     * 缺 20 多个键，Forge 报 "Configuration file is not correct" 后按默认值重建，
     * 于是 {@code cloud.enable} 回到默认的 false。
     *
     * <p>留 10% 余量是给「确实删了几个配置项」的版本升级用，避免误判。
     */
    private static final double COMPLETENESS_RATIO = 0.9;
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

    /**
     * 复制一份快照。
     *
     * <p>两个门槛缺一不可：
     * <ol>
     *   <li>哨兵键齐全；</li>
     *   <li><b>键数不能明显少于已有快照</b>——否则一个被截断的文件会把好快照覆盖掉，
     *       下次真出问题时就没得恢复了（这是上一版的真实缺陷）。</li>
     * </ol>
     */
    private static void snapshot(Path path, String fileName) {
        if (!hasSentinels(path, fileName)) return;
        try {
            Path snapshot = snapshotPath(path);
            int current = countKeys(path);
            if (Files.isRegularFile(snapshot)) {
                int previous = countKeys(snapshot);
                if (previous > 0 && current < previous * COMPLETENESS_RATIO) {
                    OllamaProxyMod.LOGGER.warn(
                            "{} 只有 {} 个配置键（快照有 {} 个），疑似被写坏，本次不覆盖快照",
                            fileName, current, previous);
                    return;
                }
            }
            Files.copy(path, snapshot, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(metaPath(path), OllamaProxyMod.version(), StandardCharsets.UTF_8);
        } catch (Exception error) {
            OllamaProxyMod.LOGGER.debug("写入配置快照失败: {}", error.toString());
        }
    }

    private static Path metaPath(Path path) {
        return path.resolveSibling(path.getFileName() + SNAPSHOT_META_SUFFIX);
    }

    /** 统计文件里的配置键数量；读不出来返回 0。 */
    private static int countKeys(Path path) {
        int count = 0;
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                if (TABLE_LINE.matcher(line).matches()) continue;
                if (KEY_LINE.matcher(line).find()) count++;
            }
        } catch (Exception error) {
            return 0;
        }
        return count;
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
        Path snapshot = snapshotPath(path);
        boolean sentinelsOk = hasSentinels(path, fileName);
        boolean snapshotUsable = Files.isRegularFile(snapshot) && hasSentinels(snapshot, fileName);

        // 判据一：哨兵缺失 → 一定坏了
        // 判据二：键数明显少于快照 → 截断点在哨兵之后，同样算坏
        int current = countKeys(path);
        int previous = snapshotUsable ? countKeys(snapshot) : 0;
        boolean truncated = !sentinelsOk
                || (previous > 0 && current < previous * COMPLETENESS_RATIO);

        if (!truncated) return;

        if (!snapshotUsable) {
            OllamaProxyMod.LOGGER.warn("{} 看起来被写坏了（{} 个键）且没有可用快照，将按默认值加载"
                    + "（云端开关可能变回关闭）", fileName, current);
            return;
        }

        // 快照是「上一版」写的就不能用：新版可能删掉过配置项，
        // 拿旧快照恢复会把这些键复活，Forge 又要再修正一次。
        String snapshotVersion = readMeta(path);
        String currentVersion = OllamaProxyMod.version();
        if (snapshotVersion != null && !snapshotVersion.equals(currentVersion)) {
            OllamaProxyMod.LOGGER.warn("{} 的快照来自 {}，与当前版本 {} 不一致，跳过恢复（下次保存会刷新快照）",
                    fileName, snapshotVersion, currentVersion);
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

    /** 读快照版本戳；没有或读不出来返回 null。 */
    private static String readMeta(Path path) {
        Path meta = metaPath(path);
        if (!Files.isRegularFile(meta)) return null;
        try {
            String text = Files.readString(meta, StandardCharsets.UTF_8).trim();
            return text.isEmpty() ? null : text;
        } catch (Exception error) {
            return null;
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