package com.hanchen.veritycustomapi.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 配置保存守卫的自检。
 *
 * <p>守的是 2026-10-24 那次真实事故：`verity_extended-common.toml` 从 122 个键
 * 被写成 20 个，而写出来的恰好是「云端覆盖过的那批键」——说明保存那一刻内存树里
 * 只有被 `set()` 过的节点。玩家看到的是「所有设置突然变回默认」。
 *
 * <p>这里的判据是**和保存前的自己比**，不依赖任何外部参照物，
 * 所以不管树为什么不全，文件都不会比保存前更差。这几条把该行为钉住。
 */
public final class ForgeConfigRepairTest {

    private ForgeConfigRepairTest() {
    }

    public static void main(String[] args) throws Exception {
        restoresWhenFileShrinks();
        keepsFileWhenShrinkIsSmall();
        toleratesMissingBackup();
        toleratesEmptyBefore();
        ignoresUnreadableBackup();

        System.out.println("ForgeConfigRepair checks passed");
    }

    /** 保存后只剩一小撮键 → 必须换回保存前的版本。这是那次事故的复现场景。 */
    private static void restoresWhenFileShrinks() throws Exception {
        Path dir = Files.createTempDirectory("cfg-guard-");
        Path file = dir.resolve("common.toml");
        Path backup = dir.resolve("common.toml.pre-save");

        Files.writeString(file, toml(40), StandardCharsets.UTF_8);
        Files.copy(file, backup);
        int before = 40;

        // 模拟「保存后只剩 4 个键」
        Files.writeString(file, toml(4), StandardCharsets.UTF_8);

        boolean restored = ForgeConfigRepair.restoreIfShrunk(file, backup, before, "common.toml");
        require(restored, "键数骤降应当触发回滚");
        require(countKeys(file) == 40, "回滚后应恢复成 40 个键，实际=" + countKeys(file));
    }

    /** 只少了几个键是正常的（版本升级删配置项），不能因此回滚。 */
    private static void keepsFileWhenShrinkIsSmall() throws Exception {
        Path dir = Files.createTempDirectory("cfg-guard-");
        Path file = dir.resolve("common.toml");
        Path backup = dir.resolve("common.toml.pre-save");

        Files.writeString(file, toml(40), StandardCharsets.UTF_8);
        Files.copy(file, backup);

        // 40 → 37，掉了 7.5%，在 10% 余量内
        Files.writeString(file, toml(37), StandardCharsets.UTF_8);
        boolean restored = ForgeConfigRepair.restoreIfShrunk(file, backup, 40, "common.toml");
        require(!restored, "小幅减少不应回滚");
        require(countKeys(file) == 37, "文件应保持保存后的样子");
    }

    private static void toleratesMissingBackup() throws Exception {
        Path dir = Files.createTempDirectory("cfg-guard-");
        Path file = dir.resolve("common.toml");
        Files.writeString(file, toml(4), StandardCharsets.UTF_8);
        boolean restored = ForgeConfigRepair.restoreIfShrunk(
                file, dir.resolve("nope.pre-save"), 40, "common.toml");
        require(!restored, "没有备份时不应声称回滚");
    }

    private static void toleratesEmptyBefore() throws Exception {
        Path dir = Files.createTempDirectory("cfg-guard-");
        Path file = dir.resolve("common.toml");
        Path backup = dir.resolve("common.toml.pre-save");
        Files.writeString(file, toml(4), StandardCharsets.UTF_8);
        Files.copy(file, backup);
        // keysBefore=0 表示保存前文件不存在/读不出来，此时无从比较，必须不动
        boolean restored = ForgeConfigRepair.restoreIfShrunk(file, backup, 0, "common.toml");
        require(!restored, "保存前键数为 0 时不应回滚");
    }

    private static void ignoresUnreadableBackup() throws Exception {
        Path dir = Files.createTempDirectory("cfg-guard-");
        Path file = dir.resolve("common.toml");
        Files.writeString(file, toml(40), StandardCharsets.UTF_8);
        // 备份路径指向一个目录：copy 会失败，必须走「回滚失败」分支而不是抛出去
        Path notAFile = Files.createDirectory(dir.resolve("dir.pre-save"));
        boolean restored = ForgeConfigRepair.restoreIfShrunk(file, notAFile, 40, "common.toml");
        require(!restored, "备份不可读时不应声称回滚");
    }

    /** 生成一个含 n 个键的合法 TOML。 */
    private static String toml(int keys) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < keys; i++) {
            if (i % 10 == 0) builder.append("\n[section").append(i / 10).append("]\n");
            builder.append("\tkey").append(i).append(" = ").append(i).append('\n');
        }
        return builder.toString();
    }

    private static int countKeys(Path path) throws Exception {
        int count = 0;
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("[")) continue;
            if (trimmed.contains("=")) count++;
        }
        return count;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
