package com.hanchen.veritycustomapi.combat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 护主 / 帮忙打怪的守卫自检。
 *
 * <p>这个功能在 4.5.2 上线时连踩四个坑，每个都只能靠「进游戏被怪打」才发现：
 * <ol>
 *   <li>{@code Can't find attribute minecraft:generic.attack_damage} 崩溃 ——
 *       Verity 的实体属性里没有 ATTACK_DAMAGE，直接调 {@code doHurtTarget} 必炸；</li>
 *   <li>追击方向写反 —— 「近了才追、远了停」，结果 Verity 一路后退；</li>
 *   <li>goal 优先级 2 抢不过跟随 goal —— 站在原地看着主人挨打；</li>
 *   <li>{@code EntityJoinLevelEvent} 反复触发导致 goal 越挂越多。</li>
 * </ol>
 * 这些都是纯逻辑错误，编译期发现不了，所以用源码级断言钉住。
 */
public final class CombatAssistTest {

    private CombatAssistTest() {
    }

    private static final Path ROOT = Path.of("src", "main", "java", "com", "hanchen",
            "veritycustomapi");

    public static void main(String[] args) throws Exception {
        goalPriorityIsZero();
        neverCallsDoHurtTarget();
        goalRegistrationIsDeduped();
        targetFilterExcludesFriendly();
        commandIsRegistered();

        System.out.println("CombatAssist checks passed");
    }

    /**
     * 战斗 goal 的优先级必须是 0。
     *
     * <p>Verity 自己的 FollowPlacerGoal 是 1、LookAtPlayerGoal 是 2。
     * 挂在 2 会跟 LookAtPlayerGoal 抢 LOOK，而且 FollowPlacerGoal 在跑时 MOVE 已被占住，
     * 战斗 goal 根本起不来 —— 表现就是「站原地挨打不还手」。
     */
    private static void goalPriorityIsZero() throws Exception {
        String src = read("combat/CombatEventHandler.java");
        require(src.contains("COMBAT_GOAL_PRIORITY = 0"),
                "战斗 goal 优先级不是 0：会被 FollowPlacerGoal 占住 MOVE，Verity 不会还手");
    }

    /**
     * 不能调 {@code doHurtTarget()}。
     *
     * <p>它第一行就读 ATTACK_DAMAGE，1.20.1 里还会读 ATTACK_KNOCKBACK；
     * Verity 的 createAttributes 两个都没注册，属性补不上时直接抛
     * IllegalArgumentException，整个服务端线程都会炸。
     */
    private static void neverCallsDoHurtTarget() throws Exception {
        String src = read("combat/FightTargetGoal.java");
        require(!stripComments(src).contains("doHurtTarget"),
                "FightTargetGoal 调了 doHurtTarget()——Verity 没有 ATTACK_DAMAGE 属性，会崩");
        require(stripComments(src).contains("target.hurt("),
                "伤害没有走 target.hurt(mobAttack)，缺少不依赖属性的施加伤害路径");
    }

    /** {@code EntityJoinLevelEvent} 会随区块加载反复触发，必须去重。 */
    private static void goalRegistrationIsDeduped() throws Exception {
        String src = read("combat/CombatEventHandler.java");
        require(src.contains("hasFightGoal"),
                "挂战斗 goal 前没有去重：区块反复加载会把 goal 越挂越多");
    }

    /** 目标筛选：不打玩家、不打已驯服宠物、不打另一只 Verity。 */
    private static void targetFilterExcludesFriendly() throws Exception {
        String src = read("combat/CombatAuraManager.java");
        require(src.contains("instanceof Player"),
                "目标筛选没有排除玩家");
        require(src.contains("TamableAnimal") && src.contains("isTame"),
                "目标筛选没有排除已驯服的宠物");
        require(src.contains("instanceof VerityEntity"),
                "目标筛选没有排除另一只 Verity（会互相打起来）");
    }

    /** 命令必须注册，否则玩家没法开关。 */
    private static void commandIsRegistered() throws Exception {
        String src = read("network/VerityChatCommand.java");
        require(src.contains("Commands.literal(\"verity-combat\")"),
                "没有注册 /verity-combat 指令");
        require(src.contains("combatAssistEnabled"),
                "/verity-combat 没有接到 CombatAuraManager.combatAssistEnabled");
    }

    private static String read(String relative) throws Exception {
        return Files.readString(ROOT.resolve(relative), StandardCharsets.UTF_8);
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
