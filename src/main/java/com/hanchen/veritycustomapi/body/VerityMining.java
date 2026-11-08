package com.hanchen.veritycustomapi.body;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import varmite.verity.entity.verity.VerityEntity;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 有耗时的挖掘。
 *
 * <p><b>为什么需要</b>：瞬间破坏看起来像开创造，而且让「工具好不好」失去意义。
 * 这里按原版公式算需要多少 tick：硬度 × 30 ÷ 工具速度（需要正确工具时），
 * 不需要工具的方块按 ×100 折算。
 *
 * <p>挖掘过程每 tick 推进；完成后调用 {@link VerityBody#breakBlock} 真正破坏并收掉落，
 * 掉落逻辑只有一处，避免两套代码算出不同结果。
 */
public final class VerityMining {

    private static final class Job {
        BlockPos pos;
        int totalTicks;
        int elapsed;
        /** 砍树用：同一棵树的其余原木，按从上到下排好。 */
        final java.util.Deque<BlockPos> queue = new java.util.ArrayDeque<>();
        int felled;

        Job(BlockPos pos, int totalTicks) {
            this.pos = pos;
            this.totalTicks = totalTicks;
        }
    }

    private static final Map<VerityEntity, Job> JOBS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<VerityEntity, String> RESULTS = Collections.synchronizedMap(new WeakHashMap<>());

    private VerityMining() {
    }

    /** 开始挖；返回给人看的一句话（失败以「失败」开头）。 */
    public static String start(VerityEntity verity, BlockPos pos) {
        if (!(verity.level() instanceof ServerLevel level)) return "失败：不在服务端";
        if (!level.hasChunkAt(pos)) return "失败：目标区块未加载";
        if (verity.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) > 25.0D)
            return "失败：够不到（超过 5 格）";

        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return "失败：那里已经是空气";
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0.0F) return "失败：这个方块挖不动";

        ItemStack tool = VerityBody.bestToolFor(verity, state);
        if (state.requiresCorrectToolForDrops() && tool.isEmpty())
            return "失败：挖 " + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + " 需要正确工具";

        // 已经挖着同一个方块就继续，不要重置进度
        Job existing = JOBS.get(verity);
        if (existing != null && existing.pos.equals(pos)) return "还在挖 " + pos.toShortString();

        if (hardness == 0.0F) {
            String result = VerityBody.breakBlock(verity, pos);
            RESULTS.put(verity, result);
            return "已瞬间破坏：" + result;
        }
        float speed = tool.isEmpty() ? 1.0F : tool.getDestroySpeed(state);
        int ticks = expectedTicks(hardness, speed);
        JOBS.put(verity, new Job(pos, Math.min(ticks, 20 * 30)));
        VerityBody.lookAt(verity, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
        return "开始挖 " + BuiltInRegistries.BLOCK.getKey(state.getBlock())
                + "，预计 " + (ticks / 20.0D) + " 秒";
    }

    /**
     * 砍整棵树：从目标原木开始找相连的原木（最多 64 块），从上往下挖。
     *
     * <p>算法借自成熟假玩家模组的伐木作业：先连通搜索、再按高度降序，
     * 这样不会因为挖掉下面的木头让上面的悬空掉落。
     */
    public static String startTree(VerityEntity verity, BlockPos origin) {
        if (!(verity.level() instanceof ServerLevel level)) return "失败：不在服务端";
        BlockState originState = level.getBlockState(origin);
        if (!originState.is(net.minecraft.tags.BlockTags.LOGS)) return "失败：那里不是原木";
        if (verity.distanceToSqr(origin.getX() + 0.5D, origin.getY() + 0.5D, origin.getZ() + 0.5D) > 25.0D)
            return "失败：够不到（超过 5 格）";

        List<BlockPos> logs = connectedLogs(level, origin, 64);
        if (logs.isEmpty()) return "失败：没有找到相连的原木";
        logs.sort((a, b) -> Integer.compare(b.getY(), a.getY()));

        Job job = new Job(logs.get(0), 1);
        for (int i = 1; i < logs.size(); i++) job.queue.add(logs.get(i));
        job.totalTicks = ticksFor(verity, level, job.pos);
        JOBS.put(verity, job);
        VerityBody.lookAt(verity, job.pos.getX() + 0.5D, job.pos.getY() + 0.5D, job.pos.getZ() + 0.5D);
        return "开始砍树：共 " + logs.size() + " 块原木";
    }

    /** 连通搜索同种原木（不跨树种），限制数量防止把整片森林算进来。 */
    private static List<BlockPos> connectedLogs(ServerLevel level, BlockPos origin, int limit) {
        List<BlockPos> found = new java.util.ArrayList<>();
        java.util.Set<BlockPos> seen = new java.util.HashSet<>();
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        var target = level.getBlockState(origin).getBlock();
        queue.add(origin.immutable());
        seen.add(origin.immutable());
        while (!queue.isEmpty() && found.size() < limit) {
            BlockPos pos = queue.poll();
            found.add(pos);
            for (var dir : net.minecraft.core.Direction.values()) {
                BlockPos next = pos.relative(dir);
                if (seen.contains(next) || !level.hasChunkAt(next)) continue;
                if (!level.getBlockState(next).is(target)) continue;
                seen.add(next.immutable());
                queue.add(next.immutable());
            }
        }
        return found;
    }

    /** 单块需要多少 tick（和 start 同一套公式）。 */
    private static int ticksFor(VerityEntity verity, ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness <= 0.0F) return 1;
        ItemStack tool = VerityBody.bestToolFor(verity, state);
        float speed = tool.isEmpty() ? 1.0F : tool.getDestroySpeed(state);
        return expectedTicks(hardness, speed);
    }

    /**
     * 挖一个方块需要多少 tick（原版公式）。
     *
     * <p>{@code 硬度 × 30 ÷ 工具速度}：空手挖木头（硬度 2）是 60 tick = 3 秒，
     * 木镐挖石头（硬度 1.5、速度 2）是 23 tick ≈ 1.1 秒。
     */
    public static int expectedTicks(float hardness, float speed) {
        if (hardness <= 0.0F) return 1;
        return Math.max(1, Math.min(
                (int) Math.ceil(hardness * 30.0F / Math.max(0.1F, speed)), 20 * 30));
    }

    /** 每 tick 推进所有正在挖的实体；JOBS 通常为空，开销可忽略。 */
    public static void tickAll(MinecraftServer server) {
        if (server == null || JOBS.isEmpty()) return;
        for (var entry : new java.util.ArrayList<>(JOBS.entrySet())) {
            VerityEntity verity = entry.getKey();
            Job job = entry.getValue();
            if (verity == null || verity.isRemoved() || !verity.isAlive()) {
                JOBS.remove(verity);
                continue;
            }
            if (++job.elapsed < job.totalTicks) continue;
            String result = VerityBody.breakBlock(verity, job.pos);
            job.felled++;
            if (!job.queue.isEmpty()) {
                // 队列里还有原木：接着挖下一块，不结束作业
                job.pos = job.queue.poll();
                job.elapsed = 0;
                job.totalTicks = ticksFor(verity, (ServerLevel) verity.level(), job.pos);
                VerityBody.lookAt(verity, job.pos.getX() + 0.5D, job.pos.getY() + 0.5D, job.pos.getZ() + 0.5D);
                continue;
            }
            JOBS.remove(verity);
            RESULTS.put(verity, job.felled > 1 ? "砍完整棵树：" + result + "（共 " + job.felled + " 块）" : result);
        }
    }

    /** 取走完成结果；仍在挖时返回 null。 */
    public static String poll(VerityEntity verity) {
        return RESULTS.remove(verity);
    }

    public static boolean mining(VerityEntity verity) {
        return JOBS.containsKey(verity);
    }

    public static void cancel(VerityEntity verity) {
        JOBS.remove(verity);
        RESULTS.remove(verity);
    }

    public static String describe(VerityEntity verity) {
        Job job = JOBS.get(verity);
        if (job == null) return "";
        int percent = (int) (100.0F * job.elapsed / Math.max(1, job.totalTicks));
        return "挖掘中 " + percent + "%" + (job.queue.isEmpty() ? "" : "，还剩 " + job.queue.size() + " 块");
    }
}
