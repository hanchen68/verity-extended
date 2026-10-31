package com.hanchen.veritycustomapi.behavior;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Verity 的「走过去挖」作业。
 *
 * <p><b>为什么要这个类</b>：{@code harvest_area} 原本是在一个 tick 里把范围内方块全删掉，
 * 玩家看到的是「方块凭空消失」——观感就是「用指令隔空挖」，没有角色在干活的感觉。
 * 这里改成真正的行为流：导航到方块旁边 → 转头看它 → 破坏 → 走向下一个。
 *
 * <p><b>几个刻意的取舍</b>：
 * <ul>
 *   <li><b>移速 1.5</b>（原版寻路基准是 1.0）：玩家反馈「太慢」，而 1.5 还能看清动作，
 *       再快就变成瞬移了；</li>
 *   <li><b>到达判定 3 格</b>：贴到方块旁边才挖，够近才有「伸手」的感觉，又不会被方块本身卡住；</li>
 *   <li><b>走不到就跳过</b>（{@code navigation.isDone()} 且仍超距）：悬空的、被墙挡的方块
 *       不能让作业永远卡住；</li>
 *   <li><b>每 tick 只处理一个方块</b>：不卡服务端，玩家能看着它一块块挖。</li>
 * </ul>
 */
public final class VerityMiningJob {

    /** 到达判定距离（格）。 */
    private static final double REACH = 3.0D;

    /** 寻路速度倍率。原版基准 1.0，1.5 明显更快但还看得清动作。 */
    private static final double SPEED = 1.5D;

    /** 单个作业最多多少方块。 */
    public static final int MAX_BLOCKS = 128;

    /** 作业最长运行时间。 */
    private static final long TIMEOUT_MS = 5 * 60 * 1000L;

    /** 玩家 → 当前作业。 */
    private static final Map<UUID, Job> JOBS = new LinkedHashMap<>();

    private VerityMiningJob() {
    }

    /** 一个采集作业。 */
    private static final class Job {
        /** 给日志/模型看的标识（方块 ID 或「采集」）。 */
        final String goal;
        /**
         * 给玩家看的名字，例如「小麦」「橡木原木」「周围能采的方块」。
         *
         * <p>用 {@code Component.translatable(方块翻译键)} 而不是拼字符串：
         * 服务端不知道玩家用什么语言，只有交给客户端渲染才会显示成「小麦」而不是「wheat」。
         */
        final net.minecraft.network.chat.Component subject;
        final Deque<BlockPos> pending = new ArrayDeque<>();
        BlockPos current;
        int mined;
        int skipped;
        final long startedAt = System.currentTimeMillis();
        /** 非 null 表示这是种植作业：到达后在目标位置放这个方块，而不是破坏。 */
        final net.minecraft.world.level.block.Block plantBlock;

        Job(String goal, net.minecraft.network.chat.Component subject,
            net.minecraft.world.level.block.Block plantBlock) {
            this.goal = goal;
            this.subject = subject;
            this.plantBlock = plantBlock;
        }

        boolean isPlanting() {
            return plantBlock != null;
        }

        /** 完成时的一句人话；一件事都没做成时返回 {@code null}（不发通知，别刷屏）。 */
        net.minecraft.network.chat.Component summary() {
            if (mined == 0 && skipped == 0) return null;
            net.minecraft.network.chat.MutableComponent line =
                    net.minecraft.network.chat.Component.empty().append(subject);
            if (isPlanting()) {
                // 种地按「格」数，不能像方块那样说「个」；也别写成「种 wheat完成：种了 16 个」
                // ——那是日志格式，不是人话（2026-10-31 用户截图反馈）。
                line.append("种好了，" + mined + " 格");
                if (skipped > 0) line.append("，还有 " + skipped + " 格没种上");
            } else {
                line.append("挖了 " + mined + " 块");
                if (skipped > 0) line.append("，还有 " + skipped + " 块够不着");
            }
            return line.append("。");
        }

        /** 进度短句：「已经种了 16 格」「已经挖了 12 块」。 */
        net.minecraft.network.chat.Component progress() {
            return net.minecraft.network.chat.Component.literal(
                    (isPlanting() ? "已经种了 " : "已经挖了 ") + mined + (isPlanting() ? " 格" : " 块"));
        }
    }

    /**
     * 开始一个采集作业。
     *
     * @param targets 目标方块（按调用方给的顺序；内部会按距离重排）
     * @param filter  只采这种方块；{@code null} 表示不限种类
     * @return 给模型/玩家看的一句话
     */
    public static synchronized String start(ServerPlayer player, List<BlockPos> targets, String goal,
                                            net.minecraft.world.level.block.Block filter) {
        if (player == null) return "失败：没有找到玩家。";
        if (targets == null || targets.isEmpty()) return "失败：没有可采集的方块。";

        Job existing = JOBS.get(player.getUUID());
        if (existing != null) {
            return "失败：手上还有采集没做完（" + existing.goal + "，已挖 " + existing.mined
                    + " 个，还剩 " + (existing.pending.size() + (existing.current == null ? 0 : 1))
                    + " 个）。等我挖完再说。";
        }

        net.minecraft.network.chat.Component subject = filter == null
                ? net.minecraft.network.chat.Component.literal("周围能采的方块")
                : net.minecraft.network.chat.Component.translatable(filter.getDescriptionId());
        Job job = new Job(goal == null || goal.isBlank() ? "采集" : goal.trim(), subject, null);
        BlockPos center = player.blockPosition();
        targets.stream()
                .sorted((a, b) -> Double.compare(a.distSqr(center), b.distSqr(center)))
                .limit(MAX_BLOCKS)
                .forEach(pos -> job.pending.add(pos.immutable()));

        JOBS.put(player.getUUID(), job);
        int total = job.pending.size();
        OllamaProxyMod.LOGGER.info("[采集] 开始: {} ({} 个方块) 玩家={}",
                job.goal, total, player.getName().getString());
        // 给模型看的名字用纯文本（服务端只有 en_us，取到的是 "Oak Log" 这类），
        // 不能用 goal 里那个原始 ID —— 模型会照着 ID 复述给玩家，听起来像报错。
        String readable = subject.getString();
        return "开始采集 " + readable + "，共 " + total + " 个方块。我会一个个走过去挖，挖完告诉你。";
    }

    /**
     * 开始一个种植作业。
     *
     * <p>和采集共用同一套「走过去 → 看着 → 动手」的流程，唯一区别是到达后
     * 放方块而不是破坏方块。共用是为了让玩家看到一致的行为表现，
     * 也避免再写一份几乎一样的导航状态机。
     */
    public static synchronized String startPlanting(ServerPlayer player, List<BlockPos> spots,
                                                    net.minecraft.world.level.block.Block crop, String goal) {
        if (player == null) return "失败：没有找到玩家。";
        if (spots == null || spots.isEmpty()) return "失败：没有可种的位置。";

        Job existing = JOBS.get(player.getUUID());
        if (existing != null) {
            return "失败：手上还有活没干完（" + existing.goal + "）。等我做完再说。";
        }

        Job job = new Job(goal == null || goal.isBlank() ? "种植" : goal.trim(),
                net.minecraft.network.chat.Component.translatable(crop.getDescriptionId()), crop);
        BlockPos center = player.blockPosition();
        spots.stream()
                .sorted((a, b) -> Double.compare(a.distSqr(center), b.distSqr(center)))
                .limit(MAX_BLOCKS)
                .forEach(pos -> job.pending.add(pos.immutable()));

        JOBS.put(player.getUUID(), job);
        int total = job.pending.size();
        OllamaProxyMod.LOGGER.info("[种植] 开始: {} ({} 格) 玩家={}",
                job.goal, total, player.getName().getString());
        return "开始种" + job.subject.getString() + "，共 " + total + " 格。我会一格格走过去种，种完告诉你。";
    }

    /**
     * 每 tick 推进一步。
     *
     * <p>状态机：没有 current → 取下一个并导航；有 current → 判断到没到 → 挖或继续走。
     */
    public static synchronized void tick(MinecraftServer server) {
        if (server == null || JOBS.isEmpty()) return;

        var iterator = JOBS.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Job job = entry.getValue();

            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                iterator.remove();
                continue;
            }
            if (System.currentTimeMillis() - job.startedAt > TIMEOUT_MS) {
                notify(player, net.minecraft.network.chat.Component.empty()
                        .append(job.subject).append("这边超时停下了，")
                        .append(job.progress()).append("。"));
                iterator.remove();
                continue;
            }

            PathfinderMob verity = resolveVerity(player);
            if (verity == null || !verity.isAlive()) {
                notify(player, net.minecraft.network.chat.Component.empty()
                        .append(job.subject).append("这边先停一下，我找不到自己的实体了。"));
                iterator.remove();
                continue;
            }

            try {
                if (job.current == null) {
                    job.current = job.pending.poll();
                    if (job.current == null) {
                        // 全部处理完。summary() 在「一件都没做成」时返回 null，不刷屏。
                        net.minecraft.network.chat.Component summary = job.summary();
                        if (summary != null) notify(player, summary);
                        OllamaProxyMod.LOGGER.info("[作业] 完成: {} 成功 {} 跳过 {}",
                                job.goal, job.mined, job.skipped);
                        iterator.remove();
                        continue;
                    }
                    // 导航到方块上方中心；speed 用 1.5 让它走得快一些
                    verity.getNavigation().moveTo(
                            job.current.getX() + 0.5D,
                            job.current.getY(),
                            job.current.getZ() + 0.5D,
                            SPEED);
                    continue;
                }

                ServerLevel level = player.serverLevel();
                double distSqr = verity.distanceToSqr(
                        job.current.getX() + 0.5D,
                        job.current.getY() + 0.5D,
                        job.current.getZ() + 0.5D);

                if (distSqr <= REACH * REACH) {
                    // 到了：转头看向目标，再动手
                    // 玩家反馈「像玩家一样脸朝向方块」，就是这一步——
                    // 用 LookControl 而不是直接 setYRot，这样转头是平滑的，不会瞬移。
                    verity.getLookControl().setLookAt(
                            job.current.getX() + 0.5D,
                            job.current.getY() + 0.5D,
                            job.current.getZ() + 0.5D,
                            30.0F, 30.0F);

                    if (job.isPlanting()) {
                        BlockState at = level.getBlockState(job.current);
                        if (!at.isAir()) {
                            job.current = null;
                            continue;
                        }
                        level.setBlock(job.current, job.plantBlock.defaultBlockState(), 3);
                        job.mined++;
                    } else {
                        BlockState state = level.getBlockState(job.current);
                        if (state.isAir()) {
                            // 已经被别的途径挖掉了
                            job.current = null;
                            continue;
                        }
                        if (level.destroyBlock(job.current, true)) {
                            job.mined++;
                        } else {
                            job.skipped++;
                        }
                    }
                    job.current = null;
                } else if (verity.getNavigation().isDone()) {
                    // 走不过去（悬空/被墙挡）：跳过，别让作业卡死
                    job.skipped++;
                    job.current = null;
                }
            } catch (Throwable error) {
                OllamaProxyMod.LOGGER.warn("[采集] tick 异常: {}", error.toString());
                job.skipped++;
                job.current = null;
            }
        }
    }

    /** 玩家是否有正在跑的采集。 */
    public static synchronized boolean isBusy(ServerPlayer player) {
        return player != null && JOBS.containsKey(player.getUUID());
    }

    /**
     * 这个 Verity 实体是否正在替它的主人采集。
     *
     * <p>给 {@code VerityWanderGoal} 用：导航器只有一个目标，溜达会把采集的 moveTo 覆盖掉。
     * 反查 owner 而不是把实体的 UUID 记进 Job —— 采集是按玩家组织的，
     * 玩家的 Verity 可能被重新召唤（UUID 会变），记实体 ID 会漏判。
     */
    public static synchronized boolean isBusyFor(PathfinderMob verity) {
        if (verity == null || JOBS.isEmpty()) return false;
        try {
            ServerPlayer owner = com.hanchen.veritycustomapi.network.ServerVerityRegistry
                    .resolveOwner((varmite.verity.entity.verity.VerityEntity) verity);
            if (owner != null) return JOBS.containsKey(owner.getUUID());
            // 拿不到 owner 时退化：只要有任务在跑就别溜达（偏保守，宁可不溜达）
            return !JOBS.isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 查进度。 */
    public static synchronized String status(ServerPlayer player) {
        if (player == null) return "没有找到玩家。";
        Job job = JOBS.get(player.getUUID());
        if (job == null) return "现在没有在采集。";
        int left = job.pending.size() + (job.current == null ? 0 : 1);
        return "正在采集「" + job.goal + "」：已挖 " + job.mined + " 个，还剩 " + left + " 个。";
    }

    /** 取消。 */
    public static synchronized String cancel(ServerPlayer player) {
        if (player == null) return "没有找到玩家。";
        Job job = JOBS.remove(player.getUUID());
        if (job == null) return "现在没有在采集。";
        return "已停止采集「" + job.goal + "」，挖了 " + job.mined + " 个。";
    }

    /** 玩家下线时清掉。 */
    public static synchronized void onPlayerLeave(UUID playerId) {
        JOBS.remove(playerId);
    }

    /** 服务器停止时清空。 */
    public static synchronized void clear() {
        JOBS.clear();
    }

    /** 找玩家身边的 Verity；用 PathfinderMob 接收，避免强耦合原版类型。 */
    private static PathfinderMob resolveVerity(ServerPlayer player) {
        try {
            Object verity = com.hanchen.veritycustomapi.network.ServerVerityRegistry.findForPlayer(player);
            return verity instanceof PathfinderMob mob ? mob : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void notify(ServerPlayer player, net.minecraft.network.chat.Component text) {
        try {
            // 用 Verity 自己的说话格式（`<名字> 台词`），别用 `[Verity]`：
            // 后者 TTS / HUD 气泡都识别不到，看起来像系统提示。
            com.hanchen.veritycustomapi.compat.VeritySay.to(player, text);
        } catch (Throwable ignored) {
            // 通知失败不影响作业收尾
        }
    }

    private static void notify(ServerPlayer player, String text) {
        notify(player, net.minecraft.network.chat.Component.literal(text));
    }
}
