package com.hanchen.veritycustomapi.body;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import varmite.verity.entity.verity.VerityEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 全流程自测：一条命令跑完「有手有背包」的完整链路。
 *
 * <p><b>为什么需要</b>：之前每次都要靠人进游戏、挖一块、等半分钟、看日志，
 * 一轮只能验证一件事。这里把整条链固定成脚本，逐步打印 PASS/FAIL 与实际数量，
 * 一次就能看出断在哪一环。
 *
 * <p>测试会往世界里放方块（工作台、石头、原木柱），全部在日志里标 {@code [TEST]}，
 * 结束后不自动清理——需要的话用创造模式拆掉。
 */
public final class VeritySelfTest {

    private enum Step {
        PREPARE, CRAFT_PLANKS, CRAFT_STICKS, CRAFT_TABLE, PLACE_TABLE,
        CRAFT_PICKAXE, PLACE_STONE, MINE_STONE, PLACE_TREE, FELL_TREE, DONE
    }

    private static final class Run {
        Step step = Step.PREPARE;
        int waited;
        final List<String> log = new ArrayList<>();
        int pass;
        int fail;
        BlockPos tablePos;
        BlockPos stonePos;
        BlockPos treePos;
        int logsBefore;
    }

    private static final Map<VerityEntity, Run> RUNS = Collections.synchronizedMap(new WeakHashMap<>());

    private VeritySelfTest() {
    }

    public static boolean running(VerityEntity verity) {
        return RUNS.containsKey(verity);
    }

    /** 开始自测；会先暂停自主，避免两边抢导航。 */
    public static String start(VerityEntity verity, ServerPlayer player) {
        if (!(verity.level() instanceof ServerLevel level)) return "失败：不在服务端";
        if (RUNS.containsKey(verity)) return "已经在自测中";

        VerityAutonomyPause(verity);
        Run run = new Run();
        RUNS.put(verity, run);
        VerityInventory.clear(verity);
        VerityInventory.insert(verity, new ItemStack(Items.OAK_LOG, 8));
        say(player, "§e[自测] 开始：已清空背包并放入 8 个原木（测试用）");
        OllamaProxyMod.LOGGER.info("[TEST] selftest start entity={} at {}", verity.getId(), verity.blockPosition());
        return "自测开始";
    }

    private static void VerityAutonomyPause(VerityEntity verity) {
        try {
            com.hanchen.veritycustomapi.autonomy.VerityAutonomy.stop(verity);
        } catch (RuntimeException ignored) {
            // 自测不该因为自主模块状态异常而无法开始
        }
    }

    /** 每 10 tick 推进一次。 */
    public static void tick(VerityEntity verity, ServerPlayer player) {
        Run run = RUNS.get(verity);
        if (run == null || player == null) return;
        if (!(verity.level() instanceof ServerLevel level)) return;
        run.waited += 10;
        if (run.waited > 20 * 120) {
            fail(run, player, "整体超时", "");
            finish(verity, player, run);
            return;
        }

        switch (run.step) {
            case PREPARE -> {
                int logs = VerityInventory.count(verity, "minecraft:oak_log");
                check(run, player, "准备背包", logs >= 8, "原木 " + logs + " 个");
                advance(run, Step.CRAFT_PLANKS);
            }
            case CRAFT_PLANKS -> {
                String result = VerityCrafting.craft(verity, "minecraft:oak_planks", 2);
                int planks = VerityInventory.count(verity, "minecraft:oak_planks");
                check(run, player, "合成木板", planks >= 8, result + "；木板 " + planks);
                advance(run, Step.CRAFT_STICKS);
            }
            case CRAFT_STICKS -> {
                String result = VerityCrafting.craft(verity, "minecraft:stick", 1);
                int sticks = VerityInventory.count(verity, "minecraft:stick");
                check(run, player, "合成木棍", sticks >= 4, result + "；木棍 " + sticks);
                advance(run, Step.CRAFT_TABLE);
            }
            case CRAFT_TABLE -> {
                String result = VerityCrafting.craft(verity, "minecraft:crafting_table", 1);
                int tables = VerityInventory.count(verity, "minecraft:crafting_table");
                check(run, player, "合成工作台", tables >= 1, result + "；工作台 " + tables);
                advance(run, Step.PLACE_TABLE);
            }
            case PLACE_TABLE -> {
                if (run.tablePos == null) run.tablePos = findSpot(level, verity);
                if (run.tablePos == null) {
                    fail(run, player, "放置工作台", "附近没有空位");
                    finish(verity, player, run);
                    return;
                }
                String result = VerityBody.placeBlock(verity, run.tablePos, "minecraft:crafting_table");
                boolean placed = level.getBlockState(run.tablePos).is(Blocks.CRAFTING_TABLE);
                check(run, player, "放置工作台", placed, result + " @ " + run.tablePos.toShortString());
                advance(run, Step.CRAFT_PICKAXE);
            }
            case CRAFT_PICKAXE -> {
                String result = VerityCrafting.craft(verity, "minecraft:wooden_pickaxe", 1);
                int pickaxes = VerityInventory.count(verity, "minecraft:wooden_pickaxe");
                check(run, player, "合成木镐（需工作台）", pickaxes >= 1, result + "；木镐 " + pickaxes);
                advance(run, Step.PLACE_STONE);
            }
            case PLACE_STONE -> {
                if (run.stonePos == null) run.stonePos = findSpot(level, verity);
                if (run.stonePos == null) {
                    fail(run, player, "放置石头", "附近没有空位");
                    finish(verity, player, run);
                    return;
                }
                level.setBlock(run.stonePos, Blocks.STONE.defaultBlockState(), 3);
                OllamaProxyMod.LOGGER.info("[TEST] placed stone at {}", run.stonePos);
                String started = VerityMining.start(verity, run.stonePos);
                check(run, player, "开始挖石头", !started.startsWith("失败"), started);
                advance(run, Step.MINE_STONE);
            }
            case MINE_STONE -> {
                String done = VerityMining.poll(verity);
                if (done == null && VerityMining.mining(verity)) return;   // 还在挖
                int cobble = VerityInventory.count(verity, "minecraft:cobblestone");
                check(run, player, "挖到圆石", cobble >= 1,
                        (done == null ? "无结果" : done) + "；圆石 " + cobble);
                advance(run, Step.PLACE_TREE);
            }
            case PLACE_TREE -> {
                if (run.treePos == null) run.treePos = findSpot(level, verity);
                if (run.treePos == null) {
                    fail(run, player, "放置原木柱", "附近没有空位");
                    finish(verity, player, run);
                    return;
                }
                for (int i = 0; i < 3; i++) {
                    level.setBlock(run.treePos.above(i), Blocks.OAK_LOG.defaultBlockState(), 3);
                }
                run.logsBefore = VerityInventory.count(verity, "minecraft:oak_log");
                OllamaProxyMod.LOGGER.info("[TEST] placed tree at {} height 3", run.treePos);
                String started = VerityMining.startTree(verity, run.treePos);
                check(run, player, "开始伐木", !started.startsWith("失败"), started);
                advance(run, Step.FELL_TREE);
            }
            case FELL_TREE -> {
                String done = VerityMining.poll(verity);
                if (done == null && VerityMining.mining(verity)) return;
                int logs = VerityInventory.count(verity, "minecraft:oak_log");
                check(run, player, "砍完整棵树", logs >= run.logsBefore + 3,
                        (done == null ? "无结果" : done) + "；原木 " + run.logsBefore + "→" + logs);
                advance(run, Step.DONE);
            }
            case DONE -> finish(verity, player, run);
            default -> finish(verity, player, run);
        }
    }

    /** 在她旁边找一块可替换的空位（脚下同层）。 */
    private static BlockPos findSpot(ServerLevel level, VerityEntity verity) {
        BlockPos base = verity.blockPosition();
        int[][] offsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {2, 0}, {0, 2}};
        for (int[] offset : offsets) {
            BlockPos pos = base.offset(offset[0], 0, offset[1]);
            if (!level.hasChunkAt(pos)) continue;
            if (!level.getBlockState(pos).canBeReplaced()) continue;
            if (pos.equals(verity.blockPosition())) continue;
            return pos;
        }
        return null;
    }

    private static void advance(Run run, Step next) {
        run.step = next;
        run.waited = 0;
    }

    private static void check(Run run, ServerPlayer player, String name, boolean ok, String detail) {
        if (ok) run.pass++;
        else run.fail++;
        String line = (ok ? "§a✔ " : "§c✘ ") + name + " §7" + detail;
        run.log.add((ok ? "PASS " : "FAIL ") + name + " | " + detail);
        say(player, "§e[自测] " + line);
        OllamaProxyMod.LOGGER.info("[TEST] {} {} | {}", ok ? "PASS" : "FAIL", name, detail);
    }

    private static void fail(Run run, ServerPlayer player, String name, String detail) {
        run.fail++;
        say(player, "§c[自测] ✘ " + name + " §7" + detail);
        OllamaProxyMod.LOGGER.warn("[TEST] FAIL {} | {}", name, detail);
    }

    private static void finish(VerityEntity verity, ServerPlayer player, Run run) {
        RUNS.remove(verity);
        String summary = "通过 " + run.pass + " / 失败 " + run.fail;
        say(player, (run.fail == 0 ? "§a[自测] 全部通过：" : "§e[自测] 完成：") + summary);
        OllamaProxyMod.LOGGER.info("[TEST] selftest done entity={} pass={} fail={}",
                verity.getId(), run.pass, run.fail);
        for (String line : run.log) OllamaProxyMod.LOGGER.info("[TEST] detail {}", line);
        try {
            com.hanchen.veritycustomapi.autonomy.VerityAutonomy.enable(verity, true);
        } catch (RuntimeException ignored) {
            // 自测结束后恢复自主；失败也不影响结果
        }
    }

    private static void say(ServerPlayer player, String message) {
        if (player != null) player.displayClientMessage(
                net.minecraft.network.chat.Component.literal(message), false);
    }

    /** 调试用：测试用物品 ID 是否有效。 */
    public static boolean validItem(String id) {
        return BuiltInRegistries.ITEM.containsKey(ResourceLocation.tryParse(id));
    }
}
