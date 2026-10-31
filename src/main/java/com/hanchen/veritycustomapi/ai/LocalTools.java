package com.hanchen.veritycustomapi.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.config.ModConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 本模组提供给模型的「本地工具」：读世界、改造世界、跑长任务。
 *
 * <p><b>为什么放在代理层而不是原版工具系统</b>：原版 Verity 的 tools 列表由它自己定义，
 * 我们没法往里加条目。但代理可以在响应里拦下 {@code tool_calls}，本地执行完再回填，
 * 模型完全感知不到区别（这条路 {@code web_search} 已经跑通了）。
 *
 * <p><b>安全边界</b>（这几条是硬约束，改代码时别放宽）：
 * <ul>
 *   <li>所有方块操作只在**玩家自己所在维度**、距离玩家 {@link #MAX_RADIUS} 格内；</li>
 *   <li>{@code harvest_area} 只认 {@link #HARVESTABLE} 里的方块（原木/矿石/作物/树叶），
 *       绝不碰石头建筑、羊毛、混凝土这类人造方块——玩家辛苦搭的东西不能被 AI 拆了；</li>
 *   <li>读类工具（scan/find/inspect/check/read）无副作用；</li>
 *   <li>任何异常都转成一句人话返回，不让模型看到堆栈。</li>
 * </ul>
 */
public final class LocalTools {

    // ===== 只读：感知世界 =====
    public static final String SCAN_AREA = "scan_area";
    public static final String FIND_BLOCK = "find_block";
    public static final String INSPECT_CONTAINER = "inspect_container";
    public static final String CHECK_INVENTORY = "check_inventory";
    public static final String READ_SIGN = "read_sign";

    // ===== 写：改造世界 =====
    public static final String HARVEST_AREA = "harvest_area";
    public static final String PLANT_CROP = "plant_crop";
    public static final String TIDY_CONTAINERS = "tidy_containers";

    // ===== 长任务 =====
    public static final String START_TASK = "start_task";
    public static final String TASK_STATUS = "task_status";
    public static final String CANCEL_TASK = "cancel_task";

    /** 单次操作的最大半径。超过这个值既卡服务端又没有意义。 */
    public static final int MAX_RADIUS = 16;

    /**
     * 允许被 {@code harvest_area} 破坏的方块。
     *
     * <p>白名单而不是黑名单：黑名单永远漏，漏一个就是玩家的房子被拆一角。
     * 这里只放「自然生成、砍了不心疼」的东西。
     */
    private static final Set<Block> HARVESTABLE = Set.of(
            Blocks.OAK_LOG, Blocks.BIRCH_LOG, Blocks.SPRUCE_LOG, Blocks.JUNGLE_LOG,
            Blocks.ACACIA_LOG, Blocks.DARK_OAK_LOG, Blocks.MANGROVE_LOG, Blocks.CHERRY_LOG,
            Blocks.OAK_LEAVES, Blocks.BIRCH_LEAVES, Blocks.SPRUCE_LEAVES, Blocks.JUNGLE_LEAVES,
            Blocks.ACACIA_LEAVES, Blocks.DARK_OAK_LEAVES, Blocks.MANGROVE_LEAVES, Blocks.CHERRY_LEAVES,
            Blocks.COAL_ORE, Blocks.IRON_ORE, Blocks.COPPER_ORE, Blocks.GOLD_ORE,
            Blocks.REDSTONE_ORE, Blocks.LAPIS_ORE, Blocks.DIAMOND_ORE, Blocks.EMERALD_ORE,
            Blocks.DEEPSLATE_COAL_ORE, Blocks.DEEPSLATE_IRON_ORE, Blocks.DEEPSLATE_COPPER_ORE,
            Blocks.DEEPSLATE_GOLD_ORE, Blocks.DEEPSLATE_REDSTONE_ORE, Blocks.DEEPSLATE_LAPIS_ORE,
            Blocks.DEEPSLATE_DIAMOND_ORE, Blocks.DEEPSLATE_EMERALD_ORE,
            Blocks.WHEAT, Blocks.CARROTS, Blocks.POTATOES, Blocks.BEETROOTS,
            Blocks.SUGAR_CANE, Blocks.MELON, Blocks.PUMPKIN, Blocks.NETHER_WART
    );

    private static final List<String> ALL_TOOLS = List.of(
            SCAN_AREA, FIND_BLOCK, INSPECT_CONTAINER, CHECK_INVENTORY, READ_SIGN,
            HARVEST_AREA, PLANT_CROP, TIDY_CONTAINERS,
            START_TASK, TASK_STATUS, CANCEL_TASK
    );

    /** 会改动世界的工具：除了总开关，还要看 {@code ai_extensions.worldEditEnabled}。 */
    private static final Set<String> WRITE_TOOLS = Set.of(HARVEST_AREA, PLANT_CROP, TIDY_CONTAINERS);

    private LocalTools() {
    }

    public static List<String> toolNames() {
        return ALL_TOOLS;
    }

    public static boolean isLocalTool(String name) {
        return name != null && ALL_TOOLS.contains(name);
    }

    /**
     * 这个工具现在能不能用。
     *
     * <p>两把闸：{@code localToolsEnabled} 关掉则全部不可用；
     * {@code worldEditEnabled} 关掉则只留只读的那 5 个。
     * 注入 schema 和执行入口都查这里，避免「工具在但一用就报错」。
     */
    public static boolean isAvailable(String name) {
        if (!isLocalTool(name)) return false;
        if (!ModConfig.localToolsEnabled()) return false;
        if (WRITE_TOOLS.contains(name)) return ModConfig.worldEditToolsEnabled();
        return true;
    }

    // ==================================================================
    // schema 注入
    // ==================================================================

    /**
     * 把这批工具塞进请求体的 {@code tools} 数组。
     *
     * <p>描述文字刻意写得「什么时候该用」而不是「这个函数做什么」——
     * 模型不会因为函数存在就调用它，必须有场景指引（web_search 当初就是这么踩过来的）。
     */
    public static void injectSchemas(JsonArray tools) {
        // 总开关关掉时一个都不注入：工具在但被拒绝，模型会反复重试并跟玩家说「我试试」，
        // 比干脆没有这个能力更糟。
        if (!ModConfig.localToolsEnabled()) return;

        tools.add(tool(SCAN_AREA,
                "看一眼周围有什么方块，按种类汇总数量。当玩家问「这附近有什么」「附近有没有矿」"
                        + "「这里适合建家吗」，或你需要判断环境时使用。",
                props()
                        .add("radius", integerProp("扫描半径（格），1~16，默认 8"))
                        .build()));
        tools.add(tool(FIND_BLOCK,
                "找离玩家最近的某一种方块，返回坐标和距离。当玩家问「最近的铁矿在哪」"
                        + "「附近有树吗」，或你要去采集某个东西前先定位时使用。",
                props()
                        .add("block", stringProp("方块 ID，例如 minecraft:iron_ore、minecraft:oak_log"))
                        .add("radius", integerProp("搜索半径（格），1~16，默认 16"))
                        .build()));
        tools.add(tool(INSPECT_CONTAINER,
                "读一个容器（箱子/木桶/熔炉/漏斗）里装了什么。当玩家问「箱子里有什么」"
                        + "「有没有铁锭」，或你准备拿材料前先看看时使用。",
                props()
                        .add("x", integerProp("容器 X 坐标；不填则用玩家脚下"))
                        .add("y", integerProp("容器 Y 坐标"))
                        .add("z", integerProp("容器 Z 坐标"))
                        .build()));
        tools.add(tool(CHECK_INVENTORY,
                "读玩家（或你自己）的背包内容，按物品汇总。当玩家问「我有多少铁」"
                        + "「背包满了吗」，或你需要知道手头材料时使用。",
                props()
                        .add("who", stringProp("player（玩家，默认）或 self（你自己）"))
                        .build()));
        tools.add(tool(READ_SIGN,
                "读一个告示牌上的文字。当玩家问「这块牌子写的什么」，或你路过告示牌想知道内容时使用。",
                props()
                        .add("x", integerProp("告示牌 X 坐标；不填则读玩家脚下"))
                        .add("y", integerProp("告示牌 Y 坐标"))
                        .add("z", integerProp("告示牌 Z 坐标"))
                        .build()));

        // ---- 会改动世界的三个工具：受 worldEditEnabled 控制 ----
        // 关掉后模型连 schema 都看不到，不会产生「答应去挖但什么都没发生」的观感。
        if (ModConfig.worldEditToolsEnabled()) {
            tools.add(tool(HARVEST_AREA,
                    "让 Verity 走过去采集自然方块（原木、矿石、作物、树叶）。"
                            + "**它会一个个走到方块旁边、转头看着方块再挖**，不是隔空删除；"
                            + "**只会破坏自然生成的方块，绝不会碰玩家的建筑**。"
                            + "当玩家说「把这些树砍了」「挖点铁矿」「你自己去挖」时使用。"
                            + "默认半径 12 格、最多 128 个；因为要走路，实际会花一些时间，"
                            + "告诉玩家「我这就过去挖」而不是「已经挖完了」。",
                    props()
                            .add("block", stringProp("只采集这种方块（方块 ID）；不填则采集范围内所有可采集方块"))
                            .add("radius", integerProp("半径（格），1~16，默认 12"))
                            .add("limit", integerProp("最多采集多少个方块，默认 128，上限 128"))
                            .build()));
            tools.add(tool(PLANT_CROP,
                    "在玩家附近的耕地上种作物，会一格格走过去种。当玩家说「帮我种点小麦」时使用。",
                    props()
                            .add("crop", stringProp("作物：wheat / carrots / potatoes / beetroots，默认 wheat"))
                            .add("radius", integerProp("半径（格），1~16，默认 6"))
                            .add("limit", integerProp("最多种多少格，默认 16"))
                            .build()));
            tools.add(tool(TIDY_CONTAINERS,
                    "把玩家背包里的物品按种类整理进附近的箱子。当玩家说「帮我整理一下箱子」时使用。",
                    props()
                            .add("radius", integerProp("半径（格），1~16，默认 8"))
                            .add("limit", integerProp("最多移动多少组，默认 32"))
                            .build()));
        }

        tools.add(tool(START_TASK,
                "**开始一个需要多步完成的任务**。当玩家交代的事情做一次做不完时（砍一片林、挖一批矿、"
                        + "种一片地、整理所有箱子），用这个把计划交给后台执行，你可以继续聊天。"
                        + "steps 里每一项是 {\"tool\":\"工具名\",\"args\":{...}}，工具名只能用本地工具。"
                        + "**每一步只处理一次工具调用（采集类单步最多 128 个方块），需要更多就多写几步。**"
                        + "提交后会立刻执行第一步并把真实结果返回给你，你可以据此告诉玩家具体做了多少。",
                props()
                        .add("goal", stringProp("给玩家看的一句话目标，例如「砍掉东边的橡树林」"))
                        .add("steps", arrayProp("步骤数组，每项 {\"tool\":\"...\",\"args\":{...}}"))
                        .build()));
        tools.add(tool(TASK_STATUS,
                "查看当前任务的进度。玩家问「做到哪了」「还要多久」时使用。", props().build()));
        tools.add(tool(CANCEL_TASK,
                "取消当前正在跑的任务。玩家说「停下」「别做了」时使用。", props().build()));
    }

    // ==================================================================
    // 执行入口
    // ==================================================================

    /**
     * 执行一个工具调用。
     *
     * @return 给模型看的自然语言结果；失败时以「失败：」开头（{@link TaskQueue} 靠这个前缀计失败数）
     */
    public static String execute(ServerPlayer player, String tool, JsonObject args) {
        if (player == null) return "失败：没有找到正在对话的玩家。";
        if (tool == null || args == null) return "失败：参数为空。";
        if (!isLocalTool(tool)) return "失败：不认识的动作 " + tool;
        // 开关关掉时即使模型从历史里点名调用，也要明确拒绝——不能「默默什么都不做」，
        // 那会让模型以为执行成功，然后跟玩家说「挖好了」。
        if (!isAvailable(tool)) return unavailableReason();

        try {
            return switch (tool) {
                case SCAN_AREA -> scanArea(player, args);
                case FIND_BLOCK -> findBlock(player, args);
                case INSPECT_CONTAINER -> inspectContainer(player, args);
                case CHECK_INVENTORY -> checkInventory(player, args);
                case READ_SIGN -> readSign(player, args);
                case HARVEST_AREA -> harvestArea(player, args);
                case PLANT_CROP -> plantCrop(player, args);
                case TIDY_CONTAINERS -> tidyContainers(player, args);
                case START_TASK -> startTask(player, args);
                case TASK_STATUS -> taskStatus(player);
                case CANCEL_TASK -> cancelTask(player);
                default -> "失败：不认识的动作 " + tool;
            };
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.warn("[本地工具] {} 执行失败: {}", tool, error.toString());
            return "失败：" + tool + " 出错了（" + error.getClass().getSimpleName() + "）。";
        }
    }

    /** 工具被开关挡下时给模型看的话：说清楚是哪一把闸，模型才能如实转述给玩家。 */
    private static String unavailableReason() {
        if (!ModConfig.localToolsEnabled()) {
            return "失败：玩家把「本地工具」总开关关掉了（配置 ai_extensions.localToolsEnabled），"
                    + "我现在看不到也动不了世界，只能陪玩家聊天。";
        }
        return "失败：玩家关掉了「允许改动世界」（配置 ai_extensions.worldEditEnabled），"
                + "我只能看，不能采集、种植或整理箱子。";
    }

    // ==================================================================
    // 只读实现
    // ==================================================================

    private static String scanArea(ServerPlayer player, JsonObject args) {
        int radius = clampRadius(args, 8);
        return onServer(player.server, () -> {
            ServerLevel level = player.serverLevel();
            BlockPos center = player.blockPosition();
            Map<String, Integer> counts = new LinkedHashMap<>();
            int total = 0;

            for (BlockPos pos : BlockPos.betweenClosed(
                    center.offset(-radius, -radius, -radius),
                    center.offset(radius, radius, radius))) {
                BlockState state = level.getBlockState(pos);
                if (state.isAir()) continue;
                String id = blockId(state.getBlock());
                counts.merge(id, 1, Integer::sum);
                total++;
            }
            if (total == 0) return "半径 " + radius + " 格内全是空气。";

            List<Map.Entry<String, Integer>> top = new ArrayList<>(counts.entrySet());
            top.sort(Map.Entry.<String, Integer>comparingByValue().reversed());

            StringBuilder sb = new StringBuilder();
            sb.append("半径 ").append(radius).append(" 格内共 ").append(total).append(" 个方块，主要种类：\n");
            int shown = 0;
            for (Map.Entry<String, Integer> entry : top) {
                if (shown++ >= 10) break;
                sb.append("- ").append(entry.getKey()).append(" × ").append(entry.getValue()).append("\n");
            }
            if (top.size() > 10) sb.append("（还有 ").append(top.size() - 10).append(" 种未列出）");
            return sb.toString().trim();
        }, "扫描失败");
    }

    private static String findBlock(ServerPlayer player, JsonObject args) {
        String raw = str(args, "block", "");
        if (raw.isBlank()) return "失败：需要指定方块 ID。";
        int radius = clampRadius(args, MAX_RADIUS);

        Block target = blockOf(raw);
        if (target == null) return "失败：不认识的方块 " + raw + "。";

        return onServer(player.server, () -> {
            ServerLevel level = player.serverLevel();
            BlockPos center = player.blockPosition();
            BlockPos best = null;
            double bestDist = Double.MAX_VALUE;

            for (BlockPos pos : BlockPos.betweenClosed(
                    center.offset(-radius, -radius, -radius),
                    center.offset(radius, radius, radius))) {
                if (!level.getBlockState(pos).is(target)) continue;
                double dist = pos.distSqr(center);
                if (dist < bestDist) {
                    bestDist = dist;
                    best = pos.immutable();
                }
            }
            if (best == null) return "半径 " + radius + " 格内没找到 " + raw + "。";
            return "最近的 " + raw + " 在 (" + best.getX() + ", " + best.getY() + ", " + best.getZ()
                    + ")，距离 " + String.format("%.1f", Math.sqrt(bestDist)) + " 格。";
        }, "查找失败");
    }

    private static String inspectContainer(ServerPlayer player, JsonObject args) {
        return onServer(player.server, () -> {
            BlockPos pos = posOrDefault(player, args);
            ServerLevel level = player.serverLevel();
            BlockEntity entity = level.getBlockEntity(pos);
            if (!(entity instanceof Container container)) {
                return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ") 不是一个容器。";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("容器 (").append(pos.getX()).append(", ").append(pos.getY()).append(", ")
                    .append(pos.getZ()).append(") 里有：\n");
            int slots = 0;
            for (int i = 0; i < container.getContainerSize(); i++) {
                ItemStack stack = container.getItem(i);
                if (stack.isEmpty()) continue;
                sb.append("- ").append(itemId(stack)).append(" × ").append(stack.getCount()).append("\n");
                if (++slots >= 20) {
                    sb.append("（还有更多，只列前 20 格）");
                    break;
                }
            }
            if (slots == 0) return "这个容器是空的。";
            return sb.toString().trim();
        }, "读取容器失败");
    }

    private static String checkInventory(ServerPlayer player, JsonObject args) {
        String who = str(args, "who", "player");
        return onServer(player.server, () -> {
            Map<String, Integer> counts = new LinkedHashMap<>();
            if ("self".equalsIgnoreCase(who)) {
                // 我们自己没有独立背包，退化成「附近容器」没有意义，直接说明
                return "我自己没有独立背包，随身物品由原版管理；要看你自己的背包请用 player。";
            }
            // 盔甲槽也要算：玩家问「我有多少铁」时，身上穿的那套同样是他的东西，
            // 漏掉会让模型给出「你只有 3 个铁」这种和实际不符的结论。
            tally(counts, player.getInventory().items);
            tally(counts, player.getInventory().armor);
            tally(counts, player.getInventory().offhand);
            if (counts.isEmpty()) return "你的背包是空的。";

            List<Map.Entry<String, Integer>> top = new ArrayList<>(counts.entrySet());
            top.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
            StringBuilder sb = new StringBuilder("你的背包里有：\n");
            int shown = 0;
            for (Map.Entry<String, Integer> entry : top) {
                if (shown++ >= 15) break;
                sb.append("- ").append(entry.getKey()).append(" × ").append(entry.getValue()).append("\n");
            }
            if (top.size() > 15) sb.append("（还有 ").append(top.size() - 15).append(" 种未列出）");
            return sb.toString().trim();
        }, "读取背包失败");
    }

    private static String readSign(ServerPlayer player, JsonObject args) {
        return onServer(player.server, () -> {
            BlockPos pos = posOrDefault(player, args);
            ServerLevel level = player.serverLevel();
            BlockEntity entity = level.getBlockEntity(pos);
            if (!(entity instanceof SignBlockEntity sign)) {
                return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ") 不是告示牌。";
            }
            StringBuilder sb = new StringBuilder("告示牌内容：\n");
            for (int i = 0; i < 4; i++) {
                String line = sign.getFrontText().getMessage(i, false).getString();
                if (!line.isBlank()) sb.append(line).append("\n");
            }
            String text = sb.toString().trim();
            return text.length() <= "告示牌内容：".length() ? "这块告示牌是空的。" : text;
        }, "读取告示牌失败");
    }

    // ==================================================================
    // 改造世界实现
    // ==================================================================

    private static String harvestArea(ServerPlayer player, JsonObject args) {
        // 默认半径放到 12、上限放到 128：模型经常只生成一步任务，
        // 半径 6 / 上限 64 那种保守值会让「砍掉这片树」只砍掉一小半。
        int radius = clampRadius(args, 12);
        int limit = clampInt(args, "limit", 128, 1, 128);
        String filterRaw = str(args, "block", "");
        Block filter = filterRaw.isBlank() ? null : blockOf(filterRaw);
        if (!filterRaw.isBlank() && filter == null) return "失败：不认识的方块 " + filterRaw + "。";

        final int startRadius = radius;
        return onServer(player.server, () -> {
            ServerLevel level = player.serverLevel();
            BlockPos center = player.blockPosition();

            int usedRadius = startRadius;
            List<BlockPos> targets = collectHarvestTargets(level, center, usedRadius, filter);

            // 🔴 指定了方块却在自己设的半径内找不到时，自动扩到最大半径再找一次。
            // 实测（2026-10-27）：模型常常先 find_block 拿到「12 格外」的坐标，
            // 然后调 harvest_area 只传 block 不传 radius（默认 6），结果一个都没采到，
            // 还回一句「没有可采集的方块」，玩家看到的就是「它根本不去执行」。
            if (targets.isEmpty() && filter != null && usedRadius < MAX_RADIUS) {
                List<BlockPos> wider = collectHarvestTargets(level, center, MAX_RADIUS, filter);
                if (!wider.isEmpty()) {
                    targets = wider;
                    usedRadius = MAX_RADIUS;
                }
            }

            if (targets.isEmpty()) {
                return "失败：半径 " + usedRadius + " 格内没有可采集的方块"
                        + (filter != null ? "（" + filterRaw + "）" : "")
                        + "。可能是被墙挡住、或者附近真的没有。";
            }

            targets.sort(Comparator.comparingDouble(p -> p.distSqr(center)));
            if (targets.size() > limit) {
                targets = new ArrayList<>(targets.subList(0, limit));
            }

            // 交给行为层：Verity 会一个个走过去、转头看着方块再挖。
            // 之前是在这里直接 destroyBlock 一片，玩家看到方块凭空消失，
            // 观感就是「用指令隔空挖」。
            String goal = filter != null ? filterRaw : "周围的可采集方块";
            return com.hanchen.veritycustomapi.behavior.VerityMiningJob.start(player, targets, goal, filter);
        }, "采集失败");
    }

    /** 收集范围内可采集的方块坐标。 */
    private static List<BlockPos> collectHarvestTargets(ServerLevel level, BlockPos center,
                                                        int radius, Block filter) {
        List<BlockPos> targets = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(
                center.offset(-radius, -radius, -radius),
                center.offset(radius, radius, radius))) {
            Block block = level.getBlockState(pos).getBlock();
            if (filter != null && block != filter) continue;
            if (!HARVESTABLE.contains(block)) continue;
            targets.add(pos.immutable());
        }
        return targets;
    }

    private static String plantCrop(ServerPlayer player, JsonObject args) {
        int radius = clampRadius(args, 6);
        int limit = clampInt(args, "limit", 16, 1, 64);
        String cropName = str(args, "crop", "wheat").toLowerCase();
        Block crop = switch (cropName) {
            case "carrots", "carrot" -> Blocks.CARROTS;
            case "potatoes", "potato" -> Blocks.POTATOES;
            case "beetroots", "beetroot" -> Blocks.BEETROOTS;
            default -> Blocks.WHEAT;
        };

        final int startRadius = radius;
        return onServer(player.server, () -> {
            ServerLevel level = player.serverLevel();
            BlockPos center = player.blockPosition();
            List<BlockPos> spots = new ArrayList<>();
            int farmland = 0;

            // 🔴 这里三个轴都要是「负半径 → 正半径」。曾经把 z 的起点误写成 +startRadius，
            // 结果 z 区间退化成一个面（[z+r, z+r]），既扫不到玩家身边的耕地，
            // 又恰好扫在范围最外侧——表现就是「明明脚下全是耕地，它却说没有耕地」。
            for (BlockPos pos : BlockPos.betweenClosed(
                    center.offset(-startRadius, -startRadius, -startRadius),
                    center.offset(startRadius, startRadius, startRadius))) {
                if (spots.size() >= limit) break;
                if (!level.getBlockState(pos).is(Blocks.FARMLAND)) continue;
                farmland++;
                BlockPos above = pos.above();
                if (!level.getBlockState(above).isAir()) continue;
                spots.add(above.immutable());
            }
            if (farmland == 0) return "失败：半径 " + startRadius + " 格内没有耕地，先锄地。";
            if (spots.isEmpty()) {
                return "失败：找到 " + farmland + " 块耕地，但上方都被占了，没地方种。";
            }
            // 和采集一样交给行为层：走过去、看着地块、再种下去
            return com.hanchen.veritycustomapi.behavior.VerityMiningJob.startPlanting(
                    player, spots, crop, "种 " + cropName);
        }, "种植失败");
    }

    private static String tidyContainers(ServerPlayer player, JsonObject args) {
        int radius = clampRadius(args, 8);
        int limit = clampInt(args, "limit", 32, 1, 64);

        return onServer(player.server, () -> {
            ServerLevel level = player.serverLevel();
            BlockPos center = player.blockPosition();

            List<Container> containers = new ArrayList<>();
            for (BlockPos pos : BlockPos.betweenClosed(
                    center.offset(-radius, -radius, -radius),
                    center.offset(radius, radius, radius))) {
                BlockEntity entity = level.getBlockEntity(pos);
                // 🔴 只认「储物箱」：箱子 / 木桶 / 潜影盒。
                // 熔炉、漏斗、发射器同样是 Container，但往里面塞东西会毁掉玩家的
                // 烧炼队列和红石分拣系统——「整理箱子」不该动这些。
                if (entity instanceof ChestBlockEntity
                        || entity instanceof BarrelBlockEntity
                        || entity instanceof ShulkerBoxBlockEntity) {
                    containers.add((Container) entity);
                }
            }
            if (containers.isEmpty()) return "半径 " + radius + " 格内没有找到容器。";

            int moved = 0;
            for (int i = 0; i < player.getInventory().items.size() && moved < limit; i++) {
                ItemStack stack = player.getInventory().items.get(i);
                if (stack.isEmpty()) continue;
                ItemStack remaining = stack.copy();
                for (Container container : containers) {
                    remaining = insertInto(container, remaining);
                    if (remaining.isEmpty()) break;
                }
                if (remaining.getCount() != stack.getCount()) {
                    player.getInventory().items.set(i, remaining.isEmpty() ? ItemStack.EMPTY : remaining);
                    moved++;
                }
            }
            if (moved == 0) return "背包里的东西要么箱子放不下，要么没有可整理的。";
            return "整理了 " + moved + " 组物品到附近的 " + containers.size() + " 个容器里。";
        }, "整理失败");
    }

    /** 把一组物品塞进容器，返回没塞完的部分。 */
    private static ItemStack insertInto(Container container, ItemStack stack) {
        ItemStack remaining = stack.copy();
        // 先叠加到已有同类物品
        for (int i = 0; i < container.getContainerSize() && !remaining.isEmpty(); i++) {
            ItemStack slot = container.getItem(i);
            if (slot.isEmpty() || !ItemStack.isSameItemSameTags(slot, remaining)) continue;
            int space = slot.getMaxStackSize() - slot.getCount();
            if (space <= 0) continue;
            int move = Math.min(space, remaining.getCount());
            slot.grow(move);
            remaining.shrink(move);
            container.setChanged();
        }
        // 再找空位
        for (int i = 0; i < container.getContainerSize() && !remaining.isEmpty(); i++) {
            if (!container.getItem(i).isEmpty()) continue;
            container.setItem(i, remaining.copy());
            remaining = ItemStack.EMPTY;
            container.setChanged();
        }
        return remaining;
    }

    /** 把一组物品槽按物品 ID 汇总进 counts。 */
    private static void tally(Map<String, Integer> counts, List<ItemStack> stacks) {
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) continue;
            counts.merge(itemId(stack), stack.getCount(), Integer::sum);
        }
    }

    // ==================================================================
    // 长任务
    // ==================================================================

    private static String startTask(ServerPlayer player, JsonObject args) {
        String goal = str(args, "goal", "未命名任务");
        if (!args.has("steps") || !args.get("steps").isJsonArray()) {
            return "失败：steps 必须是数组。";
        }
        JsonArray steps = args.getAsJsonArray("steps");
        return TaskQueue.submit(player, goal, steps);
    }

    /**
     * 查进度。
     *
     * <p>采集/种植现在跑在 {@code VerityMiningJob} 里（走过去挖），不在 TaskQueue 里，
     * 所以要两个都查——玩家问「做到哪了」时，正在走路的作业才是答案。
     */
    private static String taskStatus(ServerPlayer player) {
        String jobStatus = com.hanchen.veritycustomapi.behavior.VerityMiningJob.status(player);
        String queueStatus = TaskQueue.status(player);
        boolean hasJob = !jobStatus.startsWith("现在没有");
        boolean hasQueue = !queueStatus.startsWith("你现在没有");
        if (hasJob && hasQueue) return jobStatus + " " + queueStatus;
        if (hasJob) return jobStatus;
        return queueStatus;
    }

    /** 取消：采集作业和任务队列都要停。 */
    private static String cancelTask(ServerPlayer player) {
        String job = com.hanchen.veritycustomapi.behavior.VerityMiningJob.cancel(player);
        String queue = TaskQueue.cancel(player);
        boolean jobStopped = !job.startsWith("现在没有");
        boolean queueStopped = !queue.startsWith("你现在没有");
        if (jobStopped && queueStopped) return job + " " + queue;
        if (jobStopped) return job;
        return queue;
    }

    // ==================================================================
    // 工具方法
    // ==================================================================

    /**
     * 在服务端线程执行方块操作。
     *
     * <p>代理跑在自己的线程里，直接动方块会抛「不在主线程」。
     * 这里统一切到主线程并等待结果。
     *
     * <p>🔴 注意：如果调用方本身就在服务端线程（比如任务 tick），
     * 直接执行，**不能** {@code submit().get()}——那会自己等自己，死锁。
     */
    private static String onServer(MinecraftServer server, Supplier<String> action, String failPrefix) {
        if (server == null) return "失败：服务器还没启动。";
        try {
            if (server.isSameThread()) return action.get();
            return server.submit(action::get).get();
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.warn("[本地工具] {}: {}", failPrefix, error.toString());
            return "失败：" + failPrefix + "（" + error.getClass().getSimpleName() + "）。";
        }
    }

    private static BlockPos posOrDefault(ServerPlayer player, JsonObject args) {
        if (!args.has("x") || !args.has("y") || !args.has("z")) {
            return player.blockPosition();
        }
        return new BlockPos(num(args, "x", 0), num(args, "y", 0), num(args, "z", 0));
    }

    private static int clampRadius(JsonObject args, int fallback) {
        return clampInt(args, "radius", fallback, 1, MAX_RADIUS);
    }

    private static int clampInt(JsonObject args, String key, int fallback, int min, int max) {
        int value = num(args, key, fallback);
        return Math.max(min, Math.min(max, value));
    }

    private static int num(JsonObject args, String key, int fallback) {
        try {
            if (args.has(key) && args.get(key).isJsonPrimitive()) {
                return args.get(key).getAsInt();
            }
        } catch (Throwable ignored) {
            // 模型偶尔把数字写成字符串，当作默认值处理
        }
        return fallback;
    }

    private static String str(JsonObject args, String key, String fallback) {
        try {
            if (args.has(key) && args.get(key).isJsonPrimitive()) {
                return args.get(key).getAsString();
            }
        } catch (Throwable ignored) {
            // 同上
        }
        return fallback;
    }

    private static Block blockOf(String raw) {
        try {
            String id = raw.contains(":") ? raw : "minecraft:" + raw;
            return BuiltInRegistries.BLOCK.get(new ResourceLocation(id));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String blockId(Block block) {
        try {
            ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
            return key == null ? "unknown" : key.toString();
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static String itemId(ItemStack stack) {
        try {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
            return key == null ? "unknown" : key.toString();
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    // ===== JSON schema 小工具 =====

    private static JsonObject tool(String name, String description, JsonObject properties) {
        JsonObject parameters = new JsonObject();
        parameters.addProperty("type", "object");
        parameters.add("properties", properties);
        JsonArray required = new JsonArray();
        // 只有必填参数才放进来；这里的工具全都可以用默认值，所以 required 为空
        parameters.add("required", required);

        JsonObject function = new JsonObject();
        function.addProperty("name", name);
        function.addProperty("description", description);
        function.add("parameters", parameters);

        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("type", "function");
        wrapper.add("function", function);
        return wrapper;
    }

    private static Props props() {
        return new Props();
    }

    private static JsonObject stringProp(String description) {
        JsonObject prop = new JsonObject();
        prop.addProperty("type", "string");
        prop.addProperty("description", description);
        return prop;
    }

    private static JsonObject integerProp(String description) {
        JsonObject prop = new JsonObject();
        prop.addProperty("type", "integer");
        prop.addProperty("description", description);
        return prop;
    }

    private static JsonObject arrayProp(String description) {
        JsonObject prop = new JsonObject();
        prop.addProperty("type", "array");
        prop.addProperty("description", description);
        JsonObject items = new JsonObject();
        items.addProperty("type", "object");
        prop.add("items", items);
        return prop;
    }

    /** 链式构建 properties，省掉一堆临时变量。 */
    private static final class Props {
        private final JsonObject object = new JsonObject();

        Props add(String name, JsonObject schema) {
            object.add(name, schema);
            return this;
        }

        JsonObject build() {
            return object;
        }
    }
}
