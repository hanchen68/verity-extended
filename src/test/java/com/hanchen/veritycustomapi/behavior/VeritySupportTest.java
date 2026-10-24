package com.hanchen.veritycustomapi.behavior;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 「低血量护主」自检。
 *
 * <p>这条功能最容易出问题的地方是**边界**：没有边界的话它就不是「护主」，
 * 而是「自动回血外挂」——玩家血一掉就补，战斗难度直接归零。
 * 所以下面这几条约束都是产品语义，不能随手放宽：
 * <ul>
 *   <li>只在 50% 以下触发；</li>
 *   <li>30 秒最多一次；</li>
 *   <li>Verity 必须在附近、活着、不是恶魔形态；</li>
 *   <li>不消耗物品和额度。</li>
 * </ul>
 */
public final class VeritySupportTest {

    private VeritySupportTest() {
    }

    private static final Path ROOT = Path.of("src", "main", "java", "com", "hanchen",
            "veritycustomapi");

    public static void main(String[] args) throws Exception {
        String src = read("behavior/VeritySupportHandler.java");

        require(src.contains("HEALTH_THRESHOLD = 0.5F"), "阈值不是 50%：会变成自动回血");
        require(src.contains("COOLDOWN_MS = 30_000L"), "缺少 30 秒冷却");
        require(src.contains("MAX_DISTANCE_SQR"), "没有距离限制：能隔空奶");
        require(src.contains("isMonstrous()"), "恶魔形态没有排除");
        require(src.contains("isAlive()"), "没有检查 Verity 是否活着");
        require(src.contains("LAST_HELP_TAG"), "冷却时间戳没有持久化，换维度会重置");
        require(!src.contains("shrink(") && !src.contains("consume"),
                "护主不该消耗物品");

        require(read("ConfigManager.java").contains("isVerityHelpEnabled"), "没有接配置开关");
        require(read("config/NativeOllamaProxyConfigScreen.java").contains("VERITY_HELP_ENABLED"),
                "配置界面没有这个开关，玩家没法关掉");

        System.out.println("VeritySupport checks passed");
    }

    private static String read(String relative) throws Exception {
        return Files.readString(ROOT.resolve(relative), StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
