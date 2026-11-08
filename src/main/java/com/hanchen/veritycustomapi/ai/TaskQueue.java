package com.hanchen.veritycustomapi.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 跨 tick 的持续任务队列。
 *
 * <p><b>解决什么问题</b>：在此之前，模型一次回复只能触发一个动作，做完就结束。
 * 玩家说「把这片树林砍了」，它只能砍一棵。有了这个队列，模型可以把一句话拆成
 * 若干步骤交进来，由服务端每 tick 推进一步，玩家该干嘛干嘛。
 *
 * <p><b>为什么每 tick 只推进一步</b>：采集一片区域可能有上百个方块，
 * 一次性做完会把服务端卡住（主线程阻塞）。一步一个动作，玩家完全感觉不到停顿。
 *
 * <p><b>边界</b>：
 * <ul>
 *   <li>每个玩家同时只允许一个任务——多任务会让「进度汇报」和「取消」都变得含糊；</li>
 *   <li>步骤总数上限 {@link #MAX_STEPS}，防止模型生成一个几万步的计划；</li>
 *   <li>玩家下线/死亡自动取消；服务器停止清空。</li>
 * </ul>
 */
public final class TaskQueue {

    /** 单任务最多步骤数。超过说明模型理解错了需求，直接拒绝比跑半小时更好。 */
    public static final int MAX_STEPS = 200;

    /** 单任务最多运行时长（毫秒）。防止某一步反复失败导致任务永远挂着。 */
    private static final long MAX_RUNTIME_MS = 10 * 60 * 1000L;

    private static final Map<UUID, Task> TASKS = new LinkedHashMap<>();

    private TaskQueue() {
    }

    /** 一个待执行步骤：工具名 + 参数。 */
    public record Step(String tool, JsonObject args) {
    }

    /** 任务运行状态。 */
    public enum State { RUNNING, DONE, FAILED, CANCELLED }

    /** 一个玩家名下的任务。 */
    public static final class Task {
        final UUID id = UUID.randomUUID();
        final UUID playerId;
        final String goal;
        final Deque<Step> pending = new ArrayDeque<>();
        final int total;
        final long startedAt = System.currentTimeMillis();
        int done;
        int failed;
        State state = State.RUNNING;
        String lastError = "";

        Task(UUID playerId, String goal, int total) {
            this.playerId = playerId;
            this.goal = goal;
            this.total = total;
        }

        public UUID id() { return id; }
        public String goal() { return goal; }
        public int total() { return total; }
        public int done() { return done; }
        public int failed() { return failed; }
        public State state() { return state; }
        public String lastError() { return lastError; }
    }

    /**
     * 提交一个任务。
     *
     * @param goal  任务目标（给玩家看的一句话）
     * @param steps 步骤数组，每项形如 {@code {"tool":"harvest_area","args":{...}}}
     * @return 给模型看的执行结果文本
     */
    public static String submit(ServerPlayer player, String goal, JsonArray steps) {
        if (player == null) return "没有找到正在对话的玩家，任务无法提交。";
        MinecraftServer server = player.getServer();
        if (server == null || server.isStopped()) return "失败：服务器不可用，任务无法提交。";
        if (server.isSameThread()) return submitOnServer(player, goal, steps);
        // 不能持有 TaskQueue 锁等待主线程；主线程 tick/isBusy 也需要同一把锁。
        JsonArray snapshot = steps == null ? null : steps.deepCopy();
        CompletableFuture<String> pending = server.submit(() -> submitOnServer(player, goal, snapshot));
        try {
            return pending.get(15, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            pending.cancel(false);
            Thread.currentThread().interrupt();
            return "失败：等待任务提交被中断；请先查询任务状态，不要重复提交。";
        } catch (TimeoutException timeout) {
            pending.cancel(false);
            return "失败：等待任务提交超时；若已经开始执行，请先查询任务状态，不要重复提交。";
        } catch (ExecutionException failure) {
            OllamaProxyMod.LOGGER.warn("[任务] 主线程提交失败: {}", failure.getCause().toString());
            return "失败：任务提交执行出错；请先查询任务状态。";
        }
    }

    private static synchronized String submitOnServer(ServerPlayer player, String goal, JsonArray steps) {
        if (player == null) return "没有找到正在对话的玩家，任务无法提交。";
        MinecraftServer server = player.getServer();
        if (server == null || !server.isSameThread() || server.isStopped()) return "失败：任务只能在运行中的服务端主线程提交。";
        if (!player.isAlive() || server.getPlayerList().getPlayer(player.getUUID()) != player) return "失败：玩家已下线或死亡，任务没有提交。";
        if (steps == null || steps.isEmpty()) return "步骤为空，任务没有提交。";
        if (steps.size() > MAX_STEPS) {
            return "步骤太多了（" + steps.size() + " 步，上限 " + MAX_STEPS + "），请拆成几个更小的任务。";
        }

        Task existing = TASKS.get(player.getUUID());
        if (existing != null && existing.state == State.RUNNING) {
            return "你手上还有一个任务没做完（" + existing.goal + "，进度 "
                    + existing.done + "/" + existing.total + "）。先等它做完，或者让我取消它。";
        }

        Task task = new Task(player.getUUID(), goal == null ? "未命名任务" : goal.trim(), steps.size());
        for (JsonElement element : steps) {
            if (!element.isJsonObject()) continue;
            JsonObject step = element.getAsJsonObject();
            String tool = step.has("tool") && step.get("tool").isJsonPrimitive()
                    ? step.get("tool").getAsString() : "";
            JsonObject args = step.has("args") && step.get("args").isJsonObject()
                    ? step.getAsJsonObject("args") : new JsonObject();
            if (tool.isBlank() || !LocalTools.isLocalTool(tool)) {
                return "步骤里有一个不认识的动作：" + (tool.isBlank() ? "(空)" : tool)
                        + "。可用的动作有：" + String.join("、", LocalTools.toolNames()) + "。";
            }
            // 开关挡下的动作不能进队列：否则任务会「提交成功、每步都失败」，
            // 玩家只看到一串失败提示，却不知道为什么。
            if (!LocalTools.isAvailable(tool)) {
                return "步骤里的动作 " + tool + " 被开关禁用了（设置 → AI 扩展 → 允许改动世界），"
                        + "这个任务没有提交。";
            }
            task.pending.add(new Step(tool, args));
        }
        if (task.pending.isEmpty()) return "步骤全部无效，任务没有提交。";

        TASKS.put(player.getUUID(), task);
        OllamaProxyMod.LOGGER.info("[任务] 提交: {} ({} 步) 玩家={}",
                task.goal, task.total, player.getName().getString());

        // 🔴 立刻把第一步做掉，把真实结果一起返回给模型。
        // 实测（2026-10-27）：只回「任务已开始」时，模型会说「我这就去砍」，
        // 而任务在同一秒内就结束了——玩家看到的是一句空话 + 一条「已做完」的系统消息，
        // 观感就是「它根本没去执行」。同步做掉第一步，模型才有具体的东西可说。
        String firstResult = "";
        Step first = task.pending.poll();
        if (first != null) {
            try {
                firstResult = LocalTools.execute(player, first.tool(), first.args());
                task.done++;
                if (firstResult != null && firstResult.startsWith("失败")) {
                    task.failed++;
                    task.lastError = firstResult;
                }
                OllamaProxyMod.LOGGER.info("[任务] 首步 {} -> {}", first.tool(),
                        firstResult == null ? "(null)" : firstResult);
            } catch (Throwable error) {
                task.failed++;
                task.lastError = error.toString();
                OllamaProxyMod.LOGGER.warn("[任务] 首步失败 {}: {}", first.tool(), error.toString());
                firstResult = "失败：" + error.getClass().getSimpleName();
            }
        }

        if (task.pending.isEmpty()) {
            // 只有一步：这里直接收尾，让模型看到最终状态
            task.state = task.failed == 0 ? State.DONE : State.FAILED;
            return (task.failed == 0 ? "做完了" : "做完了但有失败") + "：" + task.goal
                    + "。结果：" + firstResult;
        }

        return "任务已开始：" + task.goal + "，共 " + task.total + " 步。"
                + "第一步已经做完：" + firstResult + " "
                + "剩下的 " + (task.total - task.done) + " 步我在后台继续做，做完告诉你。";
    }

    /** 判断当前玩家是否仍有未结束的步骤，供自主行为避免抢占任务。 */
    public static synchronized boolean isBusy(ServerPlayer player) {
        if (player == null) return false;
        Task task = TASKS.get(player.getUUID());
        return task != null && task.state == State.RUNNING;
    }

    /** 查询进度。 */
    public static synchronized String status(ServerPlayer player) {
        if (player == null) return "没有找到正在对话的玩家。";
        Task task = TASKS.get(player.getUUID());
        if (task == null) return "你现在没有正在进行的任务。";

        String stateText = switch (task.state) {
            case RUNNING -> "进行中";
            case DONE -> "已完成";
            case FAILED -> "失败";
            case CANCELLED -> "已取消";
        };
        StringBuilder sb = new StringBuilder();
        sb.append("任务「").append(task.goal).append("」").append(stateText)
                .append("：").append(task.done).append("/").append(task.total).append(" 步");
        if (task.failed > 0) sb.append("，其中 ").append(task.failed).append(" 步失败");
        if (!task.lastError.isBlank()) sb.append("；最近一次失败：").append(task.lastError);
        return sb.toString();
    }

    /** 取消当前任务。 */
    public static synchronized String cancel(ServerPlayer player) {
        if (player == null) return "没有找到正在对话的玩家。";
        Task task = TASKS.get(player.getUUID());
        if (task == null || task.state != State.RUNNING) return "你现在没有正在进行的任务。";
        task.state = State.CANCELLED;
        task.pending.clear();
        OllamaProxyMod.LOGGER.info("[任务] 取消: {} ({}/{})", task.goal, task.done, task.total);
        return "已取消任务「" + task.goal + "」，做了 " + task.done + " 步。";
    }

    /**
     * 每 tick 推进一步。
     *
     * <p>由服务端 tick 事件调用。只处理 RUNNING 状态的任务，且每个任务每 tick 最多一步。
     */
    public static synchronized void tick(MinecraftServer server) {
        if (server == null || TASKS.isEmpty()) return;

        for (Task task : TASKS.values()) {
            if (task.state != State.RUNNING) continue;

            if (System.currentTimeMillis() - task.startedAt > MAX_RUNTIME_MS) {
                task.state = State.FAILED;
                task.lastError = "超过最长运行时间";
                notify(server, task, "任务「" + task.goal + "」跑太久了，先停下。已完成 "
                        + task.done + "/" + task.total + " 步。");
                continue;
            }

            Step step = task.pending.poll();
            if (step == null) {
                task.state = task.failed == 0 ? State.DONE : State.FAILED;
                String summary = task.failed == 0
                        ? "任务「" + task.goal + "」做完了，共 " + task.total + " 步。"
                        : "任务「" + task.goal + "」结束：" + (task.total - task.failed)
                          + " 步成功、" + task.failed + " 步失败。";
                notify(server, task, summary);
                OllamaProxyMod.LOGGER.info("[任务] 结束: {} ({}/{}, 失败 {})",
                        task.goal, task.done, task.total, task.failed);
                continue;
            }

            ServerPlayer player = server.getPlayerList().getPlayer(task.playerId);
            if (player == null) {
                task.state = State.CANCELLED;
                task.lastError = "玩家已下线";
                continue;
            }

            // 🔴 harvest_area 现在会把方块交给 VerityMiningJob 异步地走过去挖。
            // 如果上一个采集作业还没跑完，就把这一步放回队首等下一 tick——
            // 否则「砍三片林」这类多步任务会在第一个作业还没挖完时就开始第二步，
            // 而 VerityMiningJob 会直接拒绝（同一玩家只能有一个作业），任务就假成功了。
            if (com.hanchen.veritycustomapi.behavior.VerityMiningJob.isBusy(player)) {
                task.pending.addFirst(step);
                continue;
            }

            try {
                String result = LocalTools.execute(player, step.tool(), step.args());
                task.done++;
                if (result != null && result.startsWith("失败")) {
                    task.failed++;
                    task.lastError = result;
                }
                // 任务里的每一步都要留痕：排查「AI 说要去砍但什么都没发生」时，
                // 靠的就是这一行——没有它只能猜是「没执行」还是「执行了没采到」。
                OllamaProxyMod.LOGGER.info("[任务] 步骤 {}/{} {} -> {}", task.done, task.total,
                        step.tool(),
                        result == null ? "(null)" : (result.length() > 100
                                ? result.substring(0, 100) + "…" : result));
            } catch (Throwable error) {
                task.failed++;
                task.lastError = error.toString();
                OllamaProxyMod.LOGGER.warn("[任务] 步骤失败 {}: {}", step.tool(), error.toString());
            }
        }
    }

    /** 玩家下线时取消他的任务。 */
    public static synchronized void onPlayerLeave(UUID playerId) {
        Task task = TASKS.get(playerId);
        if (task != null && task.state == State.RUNNING) {
            task.state = State.CANCELLED;
            task.pending.clear();
        }
    }

    /** 服务器停止时清空。 */
    public static synchronized void clear() {
        TASKS.clear();
    }

    /** 给玩家发一条系统消息；玩家已下线就只写日志。 */
    private static void notify(MinecraftServer server, Task task, String text) {
        try {
            ServerPlayer player = server.getPlayerList().getPlayer(task.playerId);
            if (player != null) {
                // 统一走 Verity 的说话格式（`<名字> 台词`），TTS 与 HUD 气泡才认得出。
                com.hanchen.veritycustomapi.compat.VeritySay.to(player, text);
            }
        } catch (Throwable ignored) {
            // 通知失败不影响任务收尾
        }
    }
}
