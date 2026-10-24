package com.hanchen.veritycustomapi.behavior;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 「闲置四处走动」自检。
 *
 * <p>这个功能有三处写错了都不会报错、只会「看起来没效果」或者「Verity 变傻了」：
 * <ol>
 *   <li><b>优先级必须低于跟随/看向玩家。</b> Minecraft 的 goal 按占用的 flag 抢占，
 *       优先级数字越小越优先；挂到 1 或 2 会和原版的跟随抢 MOVE，
 *       表现是「跟着跟着突然跑偏」。</li>
 *   <li><b>挂 goal 必须去重。</b> {@code EntityJoinLevelEvent} 随区块加载反复触发，
 *       不去重会叠出十几个漫游 goal。</li>
 *   <li><b>说话/打架时不能溜达。</b> 否则会出现「一边说台词一边跑开」。</li>
 * </ol>
 */
public final class WanderTest {

    private WanderTest() {
    }

    private static final Path ROOT = Path.of("src", "main", "java", "com", "hanchen",
            "veritycustomapi");

    public static void main(String[] args) throws Exception {
        String handler = read("behavior/WanderEventHandler.java");
        String goal = read("behavior/VerityWanderGoal.java");

        require(handler.contains("WANDER_GOAL_PRIORITY = 3"),
                "漫游 goal 优先级不是 3：会和原版跟随/看向玩家抢 MOVE");
        require(handler.contains("hasWanderGoal"),
                "挂漫游 goal 前没有去重：区块反复加载会越挂越多");

        require(goal.contains("isTalking()"),
                "说话时没有停止漫游：会出现一边说台词一边跑开");
        require(goal.contains("getTarget() != null"),
                "有攻击目标时没有停止漫游：打架打到一半跑去散步");
        require(goal.contains("isMonstrous()"),
                "恶魔形态没有排除");
        require(goal.contains("isVehicle()"),
                "有人骑着的时候没有停止漫游：会和玩家输入抢同一个实体的位移");

        require(read("ConfigManager.java").contains("isVerityWanderEnabled"),
                "没有接配置开关");
        require(read("config/NativeOllamaProxyConfigScreen.java").contains("VERITY_WANDER_ENABLED"),
                "配置界面没有这个开关，玩家没法关掉");

        System.out.println("Wander checks passed");
    }

    private static String read(String relative) throws Exception {
        return Files.readString(ROOT.resolve(relative), StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
