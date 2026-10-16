package com.hanchen.veritycustomapi.compat;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import varmite.verity.event.WorldSpawnData;

/**
 * 修原版「Verity 变恶魔后世界永久停在死寂状态」这个坑。
 * 原版制造死寂时会关掉 doDaylightCycle 和 doMobSpawning，但恶魔被击败后不恢复；
 * 这里只在确认是原版改的时才恢复——判定方式是跨 tick 对比 gamerule 由开变关。
 */
public final class VerityWorldStateGuard {
    /** 上一次 tick 的 gamerule 状态，用于检测由开变关的变化。 */
    private static volatile boolean lastSeenDaylight = true;
    private static volatile boolean lastSeenMobSpawning = true;
    /** 是否确认这两个 gamerule 是原版为制造死寂而关闭的。 */
    private static volatile boolean disabledDaylight;
    private static volatile boolean disabledMobSpawning;
    /** 本会话是否已用推断方式兜底恢复过一次（重启后标记丢失的情况）。 */
    private static volatile boolean inferredRestoreDone;

    private VerityWorldStateGuard() {
    }

    /**
     * 在 {@code VerityEntity.tick()} 开头调。用「上一 tick 开、这一 tick 关」判断是不是原版刚改了 gamerule。
     * 扩展的 Mixin 会取消原版 tick 的全实体扫描、提前返回，不一定走 {@code @At("RETURN")}，靠 RETURN 会漏检。
     *
     * @param tickCount 实体 tick 计数，用来降低兜底检查的频率
     */
    public static void onTickStart(Level level, int tickCount) {
        MinecraftServer server = serverOf(level);
        if (server == null) return;
        boolean daylight = rule(server, GameRules.RULE_DAYLIGHT);
        boolean spawning = rule(server, GameRules.RULE_DOMOBSPAWNING);
        if (!server.isHardcore()) {
            if (lastSeenDaylight && !daylight && !disabledDaylight) {
                disabledDaylight = true;
                OllamaProxyMod.LOGGER.info("检测到原版为变身关闭了昼夜循环（doDaylightCycle），"
                        + "将在恶魔被击败后恢复");
            }
            if (lastSeenMobSpawning && !spawning && !disabledMobSpawning) {
                disabledMobSpawning = true;
                OllamaProxyMod.LOGGER.info("检测到原版为「世界死去」关闭了生物生成（doMobSpawning），"
                        + "将在恶魔被击败后恢复");
            }
        }
        lastSeenDaylight = daylight;
        lastSeenMobSpawning = spawning;
        if (tickCount % 40 != 0) return;
        restoreAfterRestart(level);
    }

    /**
     * 恶魔死亡或离开世界后恢复世界状态。
     *
     * @return 是否真的恢复了什么
     */
    public static boolean restore(Level level) {
        MinecraftServer server = serverOf(level);
        if (server == null || server.isHardcore()) return false;
        boolean changed = false;
        if (disabledDaylight) {
            disabledDaylight = false;
            if (!rule(server, GameRules.RULE_DAYLIGHT)) {
                server.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(true, server);
                OllamaProxyMod.LOGGER.info("恶魔已被击败，已恢复昼夜循环（时间重新开始流逝）");
                changed = true;
            }
        }
        if (disabledMobSpawning) {
            disabledMobSpawning = false;
            if (!rule(server, GameRules.RULE_DOMOBSPAWNING)) {
                server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(true, server);
                OllamaProxyMod.LOGGER.info("恶魔已被击败，已恢复生物生成（世界重新开始复苏）");
                changed = true;
            }
        }
        return changed;
    }

    /**
     * 变身被扩展层取消时调用（恶魔友好模式 / 关闭变身开关）。
     * 原版先关昼夜循环再调 transformIntoDemon，变身被取消时时间已被冻住且等不到恶魔死亡，
     * 这里当场恢复时间；第 2 天事件关掉的生物生成一并恢复。
     *
     * @return 是否真的恢复了什么
     */
    public static boolean restoreIfJustDisabled(Level level) {
        MinecraftServer server = serverOf(level);
        if (server == null || server.isHardcore()) return false;
        boolean changed = false;
        if (lastSeenDaylight && !rule(server, GameRules.RULE_DAYLIGHT)) {
            server.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(true, server);
            disabledDaylight = false;
            OllamaProxyMod.LOGGER.info("变身已被配置取消，但原版此前已关闭昼夜循环，已当场恢复时间流逝");
            changed = true;
        }
        boolean spawningOff = !rule(server, GameRules.RULE_DOMOBSPAWNING);
        if (spawningOff && (disabledMobSpawning || lastSeenMobSpawning)) {
            server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(true, server);
            disabledMobSpawning = false;
            OllamaProxyMod.LOGGER.info("变身已被配置取消，已恢复生物生成（敌对剧情整体关闭）");
            changed = true;
        }
        return changed;
    }

    /**
     * 兜底：服务器在死寂期间重启会导致内存标记丢失。
     * 若「gamerule 为关 + 历史上发生过变身 + 当前无存活恶魔」，判定为原版所关，只补救一次，
     * 避免与管理员之后手动改的 gamerule 冲突。
     */
    private static void restoreAfterRestart(Level level) {
        MinecraftServer server = serverOf(level);
        if (server == null || server.isHardcore()) return;
        if (disabledDaylight || disabledMobSpawning || inferredRestoreDone) return;
        if (!(level instanceof ServerLevel serverLevel)) return;
        WorldSpawnData data = WorldSpawnData.get(serverLevel);
        boolean transformedBefore = data.hasHostileDaySpawn || data.hasProvokedSpawn;
        if (!transformedBefore || data.hasLiveDemon()) return;
        inferredRestoreDone = true;
        boolean changed = false;
        if (!rule(server, GameRules.RULE_DAYLIGHT)) {
            server.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(true, server);
            changed = true;
        }
        if (!rule(server, GameRules.RULE_DOMOBSPAWNING)) {
            server.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(true, server);
            changed = true;
        }
        if (changed) {
            OllamaProxyMod.LOGGER.info("检测到世界仍停在死寂状态且恶魔已不存在"
                    + "（可能是服务器重启后丢失了标记），已恢复昼夜循环与生物生成");
        }
    }

    private static boolean rule(MinecraftServer server, GameRules.Key<GameRules.BooleanValue> key) {
        return server.getGameRules().getRule(key).get();
    }

    private static MinecraftServer serverOf(Level level) {
        return level instanceof ServerLevel serverLevel ? serverLevel.getServer() : null;
    }

    /**
     * 服务器停止时清空内存标记。
     * 标记为 static，不清理会跨存档残留：A 存档的恶魔状态会强行打开 B 存档的 doDaylightCycle，
     * 并让重启兜底在后续存档中失效。
     */
    public static void reset() {
        lastSeenDaylight = true;
        lastSeenMobSpawning = true;
        disabledDaylight = false;
        disabledMobSpawning = false;
        inferredRestoreDone = false;
    }
}
