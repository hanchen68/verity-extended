package com.hanchen.veritycustomapi.autonomy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hanchen.veritycustomapi.ai.LocalTools;
import com.hanchen.veritycustomapi.ai.TaskQueue;
import com.hanchen.veritycustomapi.behavior.VerityMiningJob;
import com.hanchen.veritycustomapi.network.ServerVerityRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import varmite.verity.entity.verity.VerityEntity;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 自主决策的主线程感知与有限工具边界，不接受任意命令或建造操作。 */
public final class AutonomyActions {
    private static final String GRANT_KEY = "verity_ext_autonomy_world_grant";
    private static final String OWN_JOB_KEY = "verity_ext_autonomy_owned_job";
    private static final String OUTCOME_KEY = "verity_ext_autonomy_outcome";
    private static final int MAX_OBSERVATION_LENGTH = 3000;
    private static final Set<String> READ_TOOLS = Set.of(LocalTools.SCAN_AREA, LocalTools.FIND_BLOCK,
            LocalTools.INSPECT_CONTAINER, LocalTools.CHECK_INVENTORY, LocalTools.READ_SIGN);
    private static final Set<String> WRITE_TOOLS = Set.of(LocalTools.HARVEST_AREA,
            LocalTools.PLANT_CROP, LocalTools.TIDY_CONTAINERS);
    private static final int[][] CANDIDATE_OFFSETS = {
            {4, 0}, {-4, 0}, {0, 4}, {0, -4}, {4, 4}, {-4, -4}, {4, -4}, {-4, 4}
    };

    private AutonomyActions() {
    }

    /** 只读取已加载世界与背包，不调用模型、网络或提交任务。 */
    public static JsonObject observe(ServerPlayer player, VerityEntity verity) {
        JsonObject result = new JsonObject();
        String rejection = rejection(player, verity);
        if (rejection != null) {
            result.addProperty("error", rejection);
            return result;
        }
        ServerLevel level = player.serverLevel();
        result.addProperty("dimension", shorten(level.dimension().location().toString(), 160));
        result.addProperty("game_time", level.getGameTime());
        result.addProperty("time_of_day", level.getDayTime() % 24000L);
        result.addProperty("nearby_threat", VerityAutonomy.nearestThreat(verity) != null);
        result.addProperty("selfInventory", com.hanchen.veritycustomapi.body.VerityInventory.summary(verity));
        result.addProperty("inventoryOwner", "selfInventory是Verity自己的背包；玩家背包另列，两者不能混用");
        result.addProperty("playerActivity", com.hanchen.veritycustomapi.body.CompanionActivity.describe(player));
        result.addProperty("equipment", com.hanchen.veritycustomapi.body.VerityEquipment.describe(verity));
        result.addProperty("hunger", com.hanchen.veritycustomapi.body.VerityVitals.hunger(verity));
        result.addProperty("sleeping", com.hanchen.veritycustomapi.body.VeritySleep.sleeping(verity));
        result.addProperty("night", com.hanchen.veritycustomapi.body.VeritySleep.isNight(verity));
        result.addProperty("smelting", com.hanchen.veritycustomapi.body.VeritySmelting.describe(verity));
        result.addProperty("building", com.hanchen.veritycustomapi.body.VerityBlueprint.describe(verity));
        result.addProperty("canCraftNow", com.hanchen.veritycustomapi.body.VerityCrafting.availableFor(verity));
        result.add("player_position", position(player.blockPosition()));
        result.add("position", position(verity.blockPosition()));

        VerityAutonomy.State current = VerityAutonomy.state(verity);
        JsonObject state = new JsonObject();
        boolean commandedWait = AutonomyPermissions.commandedWait(current.suspended,
                current.waitUntil - level.getGameTime(), current.plan != null
                && current.plan.source == AutonomyPlan.Source.PLAYER && current.plan.type == AutonomyPlan.Type.HOLD);
        state.addProperty("intent", current.intent == VerityAutonomy.Intent.WAIT && !commandedWait
                ? "IDLE_READY_TO_ACT" : current.intent.name());
        state.addProperty("playerCommandedWait", commandedWait);
        state.addProperty("waitMeaning", commandedWait ? "玩家明确要求等待，遵守指令"
                : "没有玩家等待指令。空闲不代表必须留在原地，可立即自主选择行动");
        state.addProperty("suspended", current.suspended);
        state.addProperty("health", verity.getHealth());
        state.addProperty("player_health", player.getHealth());
        boolean miningBusy = VerityMiningJob.isBusy(player);
        boolean queueBusy = TaskQueue.isBusy(player);
        state.addProperty("busy", miningBusy || queueBusy);
        state.addProperty("mining_busy", miningBusy);
        state.addProperty("queue_busy", queueBusy);
        state.addProperty("mining_status", shorten(VerityMiningJob.status(player), 300));
        state.addProperty("world_write_authorized", authorized(player, verity));
        state.addProperty("wait_ticks", Math.max(0L, current.waitUntil - level.getGameTime()));
        if (current.plan != null) state.addProperty("plan", current.plan.type.name());
        result.add("state", state);
        result.addProperty("lastOutcome", shorten(verity.getPersistentData()
                .getString(OUTCOME_KEY), 400));
        result.addProperty("scan", scan(level, verity.blockPosition()));
        result.addProperty("inventory", inventory(player));
        result.add("candidates", candidates(verity));

        // 背包物品 ID 也可能来自模组；限制序列化后的总长，而非只限制单个字段。
        if (result.toString().length() > MAX_OBSERVATION_LENGTH) {
            result.addProperty("inventory", "物品摘要超出预算，已省略。");
        }
        if (result.toString().length() > MAX_OBSERVATION_LENGTH) {
            result.addProperty("scan", "环境摘要超出预算，已省略。");
        }
        JsonArray points = result.getAsJsonArray("candidates");
        while (result.toString().length() > MAX_OBSERVATION_LENGTH && !points.isEmpty()) {
            points.remove(points.size() - 1);
        }
        if (result.toString().length() > MAX_OBSERVATION_LENGTH) {
            state.addProperty("mining_status", "状态过长，请以 mining_busy 判断是否仍在执行。");
            result.addProperty("lastOutcome", "执行反馈超出预算，已省略，不能据此推断完成。");
        }
        return result;
    }

    /** 返回本次观察专用的候选点；模型只能选择其 ID，不能自行构造坐标。 */
    public static JsonArray candidates(VerityEntity verity) {
        JsonArray result = new JsonArray();
        if (!onMainThread(verity) || !verity.isAlive() || verity.isRemoved()) return result;
        ServerLevel level = (ServerLevel) verity.level();
        if (!Double.isFinite(verity.getX()) || !Double.isFinite(verity.getY())
                || !Double.isFinite(verity.getZ()) || Math.abs(verity.getX()) > 29_999_990D
                || Math.abs(verity.getZ()) > 29_999_990D) return result;
        BlockPos origin = verity.blockPosition();
        double followRange = verity.getAttributeValue(Attributes.FOLLOW_RANGE);
        if (!Double.isFinite(followRange) || followRange > 64.0D) return result;
        // 原版寻路会建立比目的地更大的区块缓存，先保证该缓存范围已经加载。
        int pathRadius = (int) Math.ceil(Math.max(0.0D, followRange)) + 8;
        if (!loadedArea(level, origin, pathRadius)) return result;
        for (int[] offset : CANDIDATE_OFFSETS) {
            if (result.size() >= 6) break;
            for (int dy = 3; dy >= -3; dy--) {
                BlockPos target = origin.offset(offset[0], dy, offset[1]);
                if (!safePosition(verity, level, target)) continue;
                Path path = verity.getNavigation().createPath(target, 0);
                if (path == null || !path.canReach() || !safePath(verity, level, path)) continue;
                JsonObject point = position(target);
                point.addProperty("id", "p" + result.size());
                result.add(point);
                break;
            }
        }
        return result;
    }

    /** 只能由主线程在明确的玩家授权入口调用，时长固定夹在 1～600 秒。 */
    public static void grant(ServerPlayer player, VerityEntity verity, int seconds) {
        if (rejection(player, verity) != null) return;
        int duration = Math.max(1, Math.min(600, seconds));
        long now = verity.level().getGameTime();
        CompoundTag grant = new CompoundTag();
        grant.putUUID("player", player.getUUID());
        grant.putString("dimension", verity.level().dimension().location().toString());
        grant.putLong("issued_at", now);
        grant.putLong("expires_at", now + duration * 20L);
        verity.getPersistentData().put(GRANT_KEY, grant);
    }

    public static void revoke(ServerPlayer player, VerityEntity verity) {
        if (rejection(player, verity) == null) revoke(verity);
    }

    /** 实体自身停止自主行为时撤销，无须再查找已经离线的拥有者。 */
    public static void revoke(VerityEntity verity) {
        if (onMainThread(verity)) verity.getPersistentData().remove(GRANT_KEY);
    }

    /** Enabling autonomy grants standing permission for existing tools; legacy timers are ignored. */
    public static boolean authorized(ServerPlayer player, VerityEntity verity) {
        if (rejection(player, verity) != null) return false;
        VerityAutonomy.State state = VerityAutonomy.state(verity);
        return AutonomyPermissions.canEdit(state.enabled, state.suspended,
                com.hanchen.veritycustomapi.config.ModConfig.worldEditToolsEnabled());
    }

    public static boolean isBusy(ServerPlayer player) {
        return VerityMiningJob.isBusy(player) || TaskQueue.isBusy(player);
    }

    /** 独立于模型决策轮询，作业消失只说明结束，不证明完成或成功。 */
    public static void poll(VerityEntity verity) {
        if (!onMainThread(verity)) return;
        CompoundTag data = verity.getPersistentData();
        if (!data.contains(OWN_JOB_KEY)) return;
        CompoundTag marker = data.getCompound(OWN_JOB_KEY);
        if (!marker.hasUUID("player") || !marker.hasUUID("job")) {
            data.remove(OWN_JOB_KEY);
            return;
        }
        ServerPlayer player = verity.getServer().getPlayerList().getPlayer(marker.getUUID("player"));
        UUID actual = player == null ? null : VerityMiningJob.activeJobId(player);
        if (marker.getUUID("job").equals(actual)) return;
        data.remove(OWN_JOB_KEY);
        var terminal = VerityMiningJob.result(marker.getUUID("job"));
        String outcome = terminal == null ? "作业终态丢失或重新加载，最终结果未知，不可声称成功" : terminal.toString();
        data.putString(OUTCOME_KEY, outcome);
        AutonomyAgenda.feedback(verity, terminal != null && "SUCCESS".equals(terminal.get("status").getAsString()), outcome);
    }

    /** 只取消本实体记录的同一个自主作业，不触碰玩家后来开始的手动作业。 */
    public static String cancelOwnedJob(ServerPlayer player, VerityEntity verity) {
        if (!onMainThread(verity) || player == null || player.getServer() != verity.getServer()) {
            return "失败：取消自主作业必须在所属服务端主线程执行。";
        }
        CompoundTag data = verity.getPersistentData();
        CompoundTag marker = data.getCompound(OWN_JOB_KEY);
        if (!marker.hasUUID("player") || !marker.hasUUID("job")) return "没有本实体启动的自主作业。";
        if (!marker.getUUID("player").equals(player.getUUID()) || !ServerVerityRegistry.owns(player, verity)) {
            return "失败：无权取消该自主作业。";
        }
        boolean cancelled = VerityMiningJob.isBusy(player)
                && VerityMiningJob.cancelIfCurrent(player, marker.getUUID("job"));
        data.remove(OWN_JOB_KEY);
        String outcome = cancelled
                ? "本自主采集或种植作业已取消；已经执行的部分不会回滚，结果以实际世界为准。"
                : "本自主作业已结束或被替换，最终结果未验证；未取消玩家的其他任务。";
        data.putString(OUTCOME_KEY, outcome);
        return outcome;
    }

    /** 执行一次白名单工具；采集、种植返回的只是启动结果，不是完成证明。 */
    public static String execute(ServerPlayer player, VerityEntity verity, String tool, JsonObject args) {
        String rejection = rejection(player, verity);
        if (rejection != null) return "失败：" + rejection;
        if (tool == null || (!READ_TOOLS.contains(tool) && !WRITE_TOOLS.contains(tool))) {
            return "失败：自主行为不支持该工具；禁止任意命令、建造或后台任务提交。";
        }
        if (args == null) return "失败：工具参数不能为空。";
        boolean write = WRITE_TOOLS.contains(tool);
        if (write && !authorized(player, verity)) return "失败：自主已关闭或暂停，或世界工具总开关关闭。";
        if (write && isBusy(player)) return "失败：已有采集、种植或任务队列在执行，不能抢占。";
        if (write && ServerVerityRegistry.findForPlayer(player) != verity) {
            return "失败：当前工具执行实体与获授权实体不一致，未开始操作。";
        }
        JsonObject checked = args.deepCopy();
        String boundary = validateBoundary(player, tool, checked);
        if (boundary != null) return "失败：" + boundary;
        String output = LocalTools.execute(player, tool, checked);
        if (output == null || output.isBlank()) return "失败：工具没有返回执行结果。";
        output = shorten(output, 2400);
        if (output.startsWith("失败")) return output;
        if (LocalTools.HARVEST_AREA.equals(tool) || LocalTools.PLANT_CROP.equals(tool)) {
            UUID jobId = VerityMiningJob.activeJobId(player);
            if (!VerityMiningJob.isBusy(player) || jobId == null) {
                return "失败：没有检测到实际采集或种植作业启动。工具反馈：" + output;
            }
            CompoundTag marker = new CompoundTag();
            marker.putUUID("player", player.getUUID());
            marker.putUUID("job", jobId);
            marker.putString("tool", tool);
            verity.getPersistentData().put(OWN_JOB_KEY, marker);
            return output + " 注意：这仅表示作业已开始，不代表采集或种植已经完成；完成情况以后续作业状态为准。";
        }
        return output;
    }

    private static String rejection(ServerPlayer player, VerityEntity verity) {
        if (player == null || verity == null) return "玩家或 Verity 不存在。";
        if (!onMainThread(verity) || player.getServer() != verity.getServer()) return "必须在所属服务端主线程操作。";
        if (!player.isAlive() || player.isSpectator() || !verity.isAlive() || verity.isRemoved()) return "玩家或 Verity 当前不可操作。";
        if (player.serverLevel() != verity.level()) return "玩家与 Verity 不在同一维度。";
        if (!ServerVerityRegistry.owns(player, verity)) return "仅 Verity 的拥有者可以使用自主工具。";
        if (player.distanceToSqr(verity) > 32.0D * 32.0D) return "玩家距离 Verity 太远。";
        return null;
    }

    private static boolean onMainThread(VerityEntity verity) {
        return verity != null && verity.level() instanceof ServerLevel level
                && level.getServer().isSameThread();
    }

    private static String validateBoundary(ServerPlayer player, String tool, JsonObject args) {
        ServerLevel level = player.serverLevel();
        if (LocalTools.CHECK_INVENTORY.equals(tool)) return null;
        if (LocalTools.INSPECT_CONTAINER.equals(tool) || LocalTools.READ_SIGN.equals(tool)) {
            BlockPos target = player.blockPosition();
            if (args.has("x") || args.has("y") || args.has("z")) {
                try {
                    double x = args.get("x").getAsDouble();
                    double y = args.get("y").getAsDouble();
                    double z = args.get("z").getAsDouble();
                    if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                            || Math.abs(x) > 30_000_000D || Math.abs(z) > 30_000_000D
                            || x != Math.floor(x) || y != Math.floor(y) || z != Math.floor(z)
                            || y < level.getMinBuildHeight() || y >= level.getMaxBuildHeight()) {
                        return "读取坐标必须是世界范围内的有限整数。";
                    }
                    target = new BlockPos((int) x, (int) y, (int) z);
                    args.addProperty("x", target.getX());
                    args.addProperty("y", target.getY());
                    args.addProperty("z", target.getZ());
                } catch (RuntimeException ignored) {
                    return "读取坐标必须同时提供合法的 x、y、z。";
                }
            }
            if (target.distSqr(player.blockPosition()) > LocalTools.MAX_RADIUS * LocalTools.MAX_RADIUS
                    || !level.hasChunkAt(target) || !level.getWorldBorder().isWithinBounds(target)) {
                return "读取位置超出附近已加载世界的安全范围。";
            }
            return null;
        }
        int fallback = switch (tool) {
            case LocalTools.FIND_BLOCK -> 16;
            case LocalTools.HARVEST_AREA -> 12;
            case LocalTools.PLANT_CROP -> 6;
            default -> 8;
        };
        int radius = fallback;
        if (args.has("radius")) {
            try {
                JsonElement value = args.get("radius");
                double raw = value.getAsDouble();
                if (!Double.isFinite(raw)) return "半径不是有限数字。";
                radius = (int) Math.max(1.0D, Math.min(LocalTools.MAX_RADIUS, raw));
            } catch (RuntimeException ignored) {
                return "半径必须是数字。";
            }
        }
        args.addProperty("radius", radius);
        // 采集指定资源时原执行器可能自动扩到 16 格，因此按其最大搜索区间检查。
        int requiredRadius = LocalTools.HARVEST_AREA.equals(tool) ? LocalTools.MAX_RADIUS : radius;
        if (!loadedArea(level, player.blockPosition(), requiredRadius)) return "操作范围存在未加载区块，未开始操作。";
        BlockPos center = player.blockPosition();
        if (!level.getWorldBorder().isWithinBounds(center.offset(-requiredRadius, 0, -requiredRadius))
                || !level.getWorldBorder().isWithinBounds(center.offset(requiredRadius, 0, requiredRadius))) {
            return "操作范围超出世界边界。";
        }
        return null;
    }

    private static boolean loadedArea(ServerLevel level, BlockPos center, int radius) {
        for (int x = (center.getX() - radius) >> 4; x <= (center.getX() + radius) >> 4; x++) {
            for (int z = (center.getZ() - radius) >> 4; z <= (center.getZ() + radius) >> 4; z++) {
                if (!level.hasChunkAt(new BlockPos(x << 4, center.getY(), z << 4))) return false;
            }
        }
        return true;
    }

    private static boolean safePosition(VerityEntity verity, ServerLevel level, BlockPos pos) {
        if (level.isOutsideBuildHeight(pos) || level.isOutsideBuildHeight(pos.above(2))
                || !level.hasChunkAt(pos) || !level.getWorldBorder().isWithinBounds(pos)) return false;
        BlockState ground = level.getBlockState(pos.below());
        if (!ground.isFaceSturdy(level, pos.below(), Direction.UP) || dangerous(ground)) return false;
        for (BlockPos nearby : BlockPos.betweenClosed(pos.offset(-1, 0, -1), pos.offset(1, 2, 1))) {
            if (!level.hasChunkAt(nearby) || dangerous(level.getBlockState(nearby))) return false;
        }
        AABB box = verity.getBoundingBox().move(pos.getX() + 0.5D - verity.getX(),
                pos.getY() - verity.getY(), pos.getZ() + 0.5D - verity.getZ());
        return level.noCollision(verity, box);
    }

    private static boolean safePath(VerityEntity verity, ServerLevel level, Path path) {
        if (path.getNodeCount() > 40) return false;
        for (int i = 0; i < path.getNodeCount(); i++) {
            Node node = path.getNode(i);
            if (!safePosition(verity, level, new BlockPos(node.x, node.y, node.z))) return false;
        }
        return true;
    }

    private static boolean dangerous(BlockState state) {
        return !state.getFluidState().isEmpty() || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.CACTUS) || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CAMPFIRE)
                || state.is(Blocks.SOUL_CAMPFIRE) || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.WITHER_ROSE);
    }

    private static JsonObject position(BlockPos pos) {
        JsonObject result = new JsonObject();
        result.addProperty("x", pos.getX());
        result.addProperty("y", pos.getY());
        result.addProperty("z", pos.getZ());
        return result;
    }

    private static String scan(ServerLevel level, BlockPos center) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-5, -3, -5), center.offset(5, 3, 5))) {
            if (!level.hasChunkAt(pos) || level.isOutsideBuildHeight(pos)) continue;
            BlockState block = level.getBlockState(pos);
            if (!block.isAir()) counts.merge(BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString(), 1, Integer::sum);
        }
        return summarize(counts, 10, 800);
    }

    private static String inventory(ServerPlayer player) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty()) counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        }
        return summarize(counts, 12, 800);
    }

    private static String summarize(Map<String, Integer> counts, int limit, int maxLength) {
        StringBuilder result = new StringBuilder();
        counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .limit(limit).forEach(entry -> {
                    if (result.length() > 0) result.append("; ");
                    result.append(entry.getKey()).append(" x").append(entry.getValue());
                });
        return shorten(result.length() == 0 ? "无" : result.toString(), maxLength);
    }

    private static String shorten(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 1) + "…";
    }
}
