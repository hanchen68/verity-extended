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
 *
 * <p><b>2026-10-25 复核结论（对着 verity-6.0.0.jar 字节码逐条核对）</b>：
 * <ul>
 *   <li>6.0.0 <b>仍然没修</b>。整个 jar 里对这两个 gamerule 只有 3 处引用，全在
 *       {@code VerityEntity.tick()}：{@code doDaylightCycle} 读一次再
 *       {@code if (当前为开 && !server.isHardcore()) set(false)}，
 *       {@code doMobSpawning} 直接 {@code set(false)}。
 *       <b>整个 jar 没有任何一处 {@code set(true)}</b>，
 *       {@code ModEvents.onDeath} / {@code onDemonLeaveLevel} 也完全不碰 gamerule。
 *       所以本类仍然是必需的。</li>
 *   <li>6.2（{@code verity-2.0.jar}）已经把这块<b>重写</b>了：
 *       {@code ModEvents.enterThreatTime()} 反而会把 {@code doDaylightCycle} 打开并把时间跳到
 *       12500（黄昏），事件结束由 {@code beginThreatTimeReturn / updateThreatTimeReturn}
 *       平滑还回原时间，{@code endThreatCompletely} 收尾。
 *       因此 6.2 上本类会自然失效（它从不把规则设成 false，我们的检测条件不成立），
 *       不会与它打架——这点是刻意确认过的，不要为了「统一」去改。</li>
 * </ul>
 *
 * <p>🔴 <b>hardcore 的不对称</b>：6.0.0 给 {@code doDaylightCycle} 的关闭加了
 * {@code !server.isHardcore()} 保护，但 {@code doMobSpawning} 的关闭<b>没有</b>。
 * 以前本类在 hardcore 下整体 {@code return}，结果是「hardcore 存档生物生成被关掉后永不恢复」。
 * 现在昼夜循环仍按原版规则只在非 hardcore 恢复，生物生成不分 hardcore 都恢复。
 *
 * <p>🔴 <b>2026-10-25 行为调整（按用户要求）</b>：昼夜循环改成
 * <b>「检测到被关掉就当场恢复」</b>，不再等「恶魔被击败」那个条件。
 * 两条保护措施：
 * <ol>
 *   <li>只在本存档确实发生过 Verity 恐怖事件（{@code hasHostileDaySpawn || hasProvokedSpawn}）时动手，
 *       否则管理员手动关的 {@code doDaylightCycle} 会被我们抢回来；</li>
 *   <li>原版在永夜期间<b>每 tick 都会重新关一次</b>，所以恢复后要观察：40 tick 内又被关掉
 *       说明它正在主动维持永夜，退避到 5 秒一次，避免每 tick 对打
 *       （每次 {@code set} 都会给所有客户端同步一次 gamerule）。永夜一结束，退避清零、立即恢复。</li>
 * </ol>
 */
public final class VerityWorldStateGuard {
    /** 上一次 tick 的 gamerule 状态，用于检测由开变关的变化。 */
    private static volatile boolean lastSeenDaylight = true;
    private static volatile boolean lastSeenMobSpawning = true;
    /** 生物生成是否确认是原版为制造死寂而关闭的（只恢复自己观察到的，不抢管理员的设置）。 */
    private static volatile boolean disabledMobSpawning;
    /** 本会话是否已用推断方式兜底恢复过一次（重启后标记丢失的情况）。 */
    private static volatile boolean inferredRestoreDone;

    /** 判定「恢复后又被原版立刻关掉」的时间窗口：40 tick（2 秒）。 */
    private static final long FIGHT_WINDOW_TICKS = 40L;
    /** 判定为「原版正在维持永夜」后的重试间隔：100 tick（5 秒）。 */
    private static final long RETRY_BACKOFF_TICKS = 100L;

    /** 上一次强行恢复昼夜循环的时刻（gameTime）。 */
    private static volatile long lastDaylightRestoreAt = Long.MIN_VALUE / 2;
    /** 下一次允许强行恢复的时刻（gameTime）；对抗时退避到 5 秒一次。 */
    private static volatile long daylightRetryAt;
    /** 连续「恢复后又被立刻关掉」的次数，用来决定是否退避。 */
    private static volatile int daylightFights;

    private VerityWorldStateGuard() {
    }

    /**
     * 在 {@code VerityEntity.tick()} 开头调。
     *
     * <p>扩展的 Mixin 会取消原版 tick 的全实体扫描、提前返回，不一定走 {@code @At("RETURN")}，靠 RETURN 会漏检。
     *
     * @param tickCount 实体 tick 计数，用来降低兜底检查的频率
     */
    public static void onTickStart(Level level, int tickCount) {
        MinecraftServer server = serverOf(level);
        if (server == null) return;
        boolean daylight = rule(server, GameRules.RULE_DAYLIGHT);
        boolean spawning = rule(server, GameRules.RULE_DOMOBSPAWNING);
        // 昼夜循环：**检测到被关掉就当场恢复**，不再等「恶魔被击败」。
        // 只在本世界确实发生过 Verity 恐怖事件时才动手，避免抢走管理员手动设的值。
        if (!daylight && !server.isHardcore() && verityEventHappened(level)) {
            long now = level.getGameTime();
            if (now >= daylightRetryAt) {
                server.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(true, server);
                // 原版在永夜期间每 tick 都会重新关掉它。短时间内又被关，
                // 说明它正在主动维持永夜 —— 这时退避到 5 秒一次，避免每 tick 对打
                // （每次 set 都会给所有客户端同步一次 gamerule）。
                boolean foughtAgain = now - lastDaylightRestoreAt <= FIGHT_WINDOW_TICKS;
                daylightFights = foughtAgain ? daylightFights + 1 : 0;
                lastDaylightRestoreAt = now;
                if (daylightFights >= 2) {
                    daylightRetryAt = now + RETRY_BACKOFF_TICKS;
                    if (daylightFights == 2) {
                        OllamaProxyMod.LOGGER.info("原版正在维持永夜（反复关闭昼夜循环），"
                                + "改为每 {} 秒检查恢复一次", RETRY_BACKOFF_TICKS / 20);
                    }
                } else {
                    OllamaProxyMod.LOGGER.info("检测到昼夜循环被关闭，已立即恢复时间流逝");
                }
                daylight = true;
            }
        } else if (daylight) {
            // 时间正常流逝时把退避状态清干净，下次事件能立刻恢复
            daylightRetryAt = 0L;
            daylightFights = 0;
        }
        // 生物生成：原版**没有** hardcore 保护，hardcore 下照样关，所以照样要记下来恢复。
        // 它由 hasTriggeredDay2 一次性关掉（不会每 tick 重关），所以仍按「恶魔没了再恢复」处理。
        if (lastSeenMobSpawning && !spawning && !disabledMobSpawning) {
            disabledMobSpawning = true;
            OllamaProxyMod.LOGGER.info("检测到原版为「世界死去」关闭了生物生成（doMobSpawning），"
                    + "将在恶魔被击败后恢复");
        }
        lastSeenDaylight = daylight;
        lastSeenMobSpawning = spawning;
        if (tickCount % 40 != 0) return;
        restoreAfterRestart(level);
    }

    /**
     * 恶魔死亡或离开世界后恢复世界状态。
     *
     * <p>昼夜循环平时已经「检测到被关就立刻恢复」了，这里的作用是**清掉退避状态并再补一次**：
     * 恶魔没了说明永夜结束，原版不会再关它，这一次恢复能真正留住。
     *
     * @return 是否真的恢复了什么
     */
    public static boolean restore(Level level) {
        MinecraftServer server = serverOf(level);
        if (server == null) return false;
        boolean changed = false;
        // 永夜结束：退避状态清空，允许立刻再恢复一次
        daylightRetryAt = 0L;
        daylightFights = 0;
        if (!server.isHardcore() && !rule(server, GameRules.RULE_DAYLIGHT)) {
            server.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(true, server);
            OllamaProxyMod.LOGGER.info("恶魔已被击败，已恢复昼夜循环（时间重新开始流逝）");
            changed = true;
        }
        if (disabledMobSpawning) {
            disabledMobSpawning = false;
            // 生物生成不分 hardcore：原版在 hardcore 下也会关，也照样不恢复
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
        if (server == null) return false;
        boolean changed = false;
        if (!server.isHardcore() && lastSeenDaylight && !rule(server, GameRules.RULE_DAYLIGHT)) {
            server.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(true, server);
            daylightRetryAt = 0L;
            daylightFights = 0;
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
        if (server == null) return;
        if (disabledMobSpawning || inferredRestoreDone) return;
        if (!(level instanceof ServerLevel serverLevel)) return;
        WorldSpawnData data = WorldSpawnData.get(serverLevel);
        boolean transformedBefore = data.hasHostileDaySpawn || data.hasProvokedSpawn;
        if (!transformedBefore || data.hasLiveDemon()) return;
        inferredRestoreDone = true;
        boolean changed = false;
        // 昼夜循环：hardcore 下原版不会关它，别碰
        if (!server.isHardcore() && !rule(server, GameRules.RULE_DAYLIGHT)) {
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

    /**
     * 本世界是否真的发生过 Verity 的恐怖事件。
     *
     * <p>用它当「要不要强行恢复昼夜循环」的前置条件：只有当这个存档确实被原版冻过时间，
     * 我们才把被关掉的昼夜循环打开；否则管理员手动关的也被我们抢回来，那就成了新 bug。
     * 读的是原版的持久化标记，重启后仍然有效。
     */
    private static boolean verityEventHappened(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) return false;
        try {
            WorldSpawnData data = WorldSpawnData.get(serverLevel);
            return data.hasHostileDaySpawn || data.hasProvokedSpawn;
        } catch (Throwable error) {
            return false;
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
        disabledMobSpawning = false;
        inferredRestoreDone = false;
        lastDaylightRestoreAt = Long.MIN_VALUE / 2;
        daylightRetryAt = 0L;
        daylightFights = 0;
    }
}
