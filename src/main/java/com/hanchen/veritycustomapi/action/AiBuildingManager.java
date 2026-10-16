package com.hanchen.veritycustomapi.action;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.compat.OriginalVerityStateAccess;
import com.hanchen.veritycustomapi.config.ModConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AiBuildingManager {
    private static final AiBuildingManager INSTANCE = new AiBuildingManager();
    private static final Gson GSON = new Gson();
    private static final int MAX_BLUEPRINT_OPERATIONS = 96;

    /** 每个玩家最多一个 BuildJob（多人模式）。单人模式仍只取 owner == player 的那一份。 */
    private final Map<UUID, BuildJob> jobsByPlayer = new ConcurrentHashMap<>();
    /** 用于斜率轮询所有 BuildJob 的临时集合。 */
    private final List<BuildJob> tickSnapshot = new ArrayList<>();
    /** 上一次保存的施工总览："每个 Job 一块记录"，用于 Undo。 */
    private final Map<String, UndoPack> lastUndoByName = new ConcurrentHashMap<>();
    /** Boss Bar 句柄：每个 Job 独立一条。 */
    private final Map<UUID, ProgressTracker> progressByPlayer = new ConcurrentHashMap<>();

    private AiBuildingManager() {
    }

    public static AiBuildingManager getInstance() {
        return INSTANCE;
    }

    public void start(MinecraftServer server, ServerPlayer player, String[] params) {
        if (!ModConfig.ENABLE_AI_BUILDING.get()) {
            notifyPlayer(player, "AI 建造已关闭，请先在配置中启用");
            return;
        }
        if (!server.isSingleplayer() || server.getPlayerCount() != 1) {
            notifyPlayer(player, "AI 建造当前仅支持单人世界");
            return;
        }
        if (jobsByPlayer.get(player.getUUID()) != null) {
            notifyPlayer(player, "已有建造任务正在运行，请先取消或等待完成");
            return;
        }

        String type = params.length > 0 ? params[0].toLowerCase(Locale.ROOT) : "house";
        int size = params.length > 1 ? parseInt(params[1], 7) : 7;
        size = Math.max(3, Math.min(ModConfig.AI_BUILDING_MAX_SIZE.get(), size));
        String mainId = params.length > 2 ? params[2] : defaultBlock(type);
        String accentId = params.length > 3 ? params[3] : defaultAccent(type);
        String roofId = params.length > 4 ? params[4] : defaultRoof(type);
        String windowId = params.length > 5 ? params[5] : "minecraft:glass";
        int floors = Math.max(1, Math.min(4, params.length > 6 ? parseInt(params[6], defaultFloors(type)) : defaultFloors(type)));
        BlockState main = resolveState(mainId);
        BlockState accent = resolveState(accentId);
        BlockState roof = resolveState(roofId);
        BlockState window = resolveState(windowId);
        if (main == null || accent == null || roof == null || window == null) {
            notifyPlayer(player, "建筑材料无效，请使用有效的 Minecraft 方块 ID");
            return;
        }
        BuildingMaterials materials = new BuildingMaterials(main, accent, roof, window);

        ServerLevel level = player.serverLevel();
        Direction facing = player.getDirection();
        BlockPos target = player.blockPosition().relative(facing, Math.max(5, size / 2 + 3));
        int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, target.getX(), target.getZ());
        BlockPos origin = new BlockPos(target.getX(), groundY, target.getZ());
        List<Placement> placements = createPlan(type, size, floors, origin, facing, materials);
        int maxBlocks = ModConfig.AI_BUILDING_MAX_BLOCKS.get();
        if (placements.isEmpty()) {
            notifyPlayer(player, "不支持的建筑类型: " + type);
            return;
        }
        if (placements.size() > maxBlocks) {
            notifyPlayer(player, "建筑需要 " + placements.size() + " 个方块，超过上限 " + maxBlocks);
            return;
        }
        try {
            validateBounds(placements, ModConfig.AI_BUILDING_MAX_SIZE.get());
        } catch (IllegalArgumentException e) {
            notifyPlayer(player, "AI 建筑校验失败: " + e.getMessage());
            return;
        }

        BuildJob job = new BuildJob(player.getUUID(), level.dimension().location(), type, placements);
        commitJob(server, player, job, type);
        notifyActionBar(player, "AI 建造已开始: " + type + "，共 " + placements.size() + " 个方块");
        OllamaProxyMod.LOGGER.info("【AI建造】开始任务 type={} size={} floors={} blocks={}", type, size, floors, placements.size());
    }

    private void commitJob(MinecraftServer server, ServerPlayer player, BuildJob job, String label) {
        jobsByPlayer.put(player.getUUID(), job);
        if (ModConfig.AI_BUILDING_GHOST_PREVIEW.get()
                && ModConfig.AI_BUILDING_GHOST_PREVIEW_TICKS.get() > 0) {
            job.ghostTicksLeft = ModConfig.AI_BUILDING_GHOST_PREVIEW_TICKS.get();
        } else {
            job.ghostTicksLeft = 0;
        }
        if (ModConfig.AI_BUILDING_BOSS_BAR_PROGRESS.get()) {
            ProgressTracker tracker = progressByPlayer.computeIfAbsent(player.getUUID(),
                    id -> new ProgressTracker());
            tracker.boss.setColor(BossEvent.BossBarColor.YELLOW);
            tracker.boss.setDarkenScreen(false);
            tracker.boss.setCreateWorldFog(false);
            tracker.boss.addPlayer(player);
            tracker.label = label;
        }
        prewarmChunks(server, player, job);
        if (ModConfig.AI_BUILDING_PERSIST.get()) persistJob(server, job);
    }

    public void startBlueprint(MinecraftServer server, ServerPlayer player, String[] params) {
        if (!validateStart(server, player)) return;
        if (params.length != 1) {
            notifyPlayer(player, "AI 蓝图参数无效");
            return;
        }
        try {
            String json = new String(Base64.getUrlDecoder().decode(params[0]), StandardCharsets.UTF_8);
            JsonObject blueprint = GSON.fromJson(json, JsonObject.class);
            String name = blueprint.has("name") ? blueprint.get("name").getAsString().trim() : "自定义建筑";
            if (name.isBlank() || name.length() > 64) name = "自定义建筑";
            JsonArray operations = blueprint.getAsJsonArray("operations");
            if (operations == null || operations.isEmpty() || operations.size() > MAX_BLUEPRINT_OPERATIONS) {
                notifyPlayer(player, "AI 蓝图操作数必须为 1-" + MAX_BLUEPRINT_OPERATIONS);
                return;
            }
            ServerLevel level = player.serverLevel();
            Direction facing = player.getDirection();
            int maxSize = ModConfig.AI_BUILDING_MAX_SIZE.get();
            BlockPos target = player.blockPosition().relative(facing, Math.max(7, maxSize / 2 + 4));
            int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, target.getX(), target.getZ());
            BlockPos origin = new BlockPos(target.getX(), groundY, target.getZ());
            Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
            OllamaProxyMod.LOGGER.info("【AI建造调试】site target={} groundY={} minBuildHeight={} maxBuildHeight={} playerY={}",
                    target, groundY, level.getMinBuildHeight(), level.getMaxBuildHeight(), player.getY());
            int skippedOperations = 0;
            boolean hasRoof = false;
            List<BlueprintShell> shells = new ArrayList<>();
            for (JsonElement element : operations) {
                if (!element.isJsonObject()) throw new IllegalArgumentException("operation");
                try {
                    JsonObject operation = element.getAsJsonObject();
                    expandBlueprintOperation(blocks, origin, facing, operation, maxSize);
                    String op = requiredString(operation, "op").toLowerCase(Locale.ROOT);
                    String block = requiredString(operation, "block").toLowerCase(Locale.ROOT);
                    if ("gable_roof".equals(op) || "hip_roof".equals(op)
                            || ("fill".equals(op) && block.contains("_stairs"))) {
                        hasRoof = true;
                    }
                    BlueprintShell shell = blueprintShell(operation, maxSize, facing);
                    if (shell != null) shells.add(shell);
                } catch (IllegalArgumentException e) {
                    if (isFatalBlueprintError(e)) throw e;
                    skippedOperations++;
                    OllamaProxyMod.LOGGER.warn("【AI建造】跳过不可恢复的蓝图操作: {}", e.getMessage());
                }
                if (blocks.size() > ModConfig.AI_BUILDING_MAX_BLOCKS.get()) {
                    notifyPlayer(player, "AI 蓝图超过最大方块数量 " + ModConfig.AI_BUILDING_MAX_BLOCKS.get());
                    return;
                }
            }
            if (!hasRoof && !shells.isEmpty()) {
                addFallbackRoofs(blocks, origin, facing, shells);
                OllamaProxyMod.LOGGER.warn("【AI建造】蓝图缺少有效屋顶，已根据 {} 个建筑外壳自动补全", shells.size());
            }
            if (blocks.size() > ModConfig.AI_BUILDING_MAX_BLOCKS.get()) {
                notifyPlayer(player, "AI 蓝图补全屋顶后超过最大方块数量 " + ModConfig.AI_BUILDING_MAX_BLOCKS.get());
                return;
            }
            if (blocks.isEmpty()) {
                notifyPlayer(player, "AI 蓝图没有生成有效方块");
                return;
            }
            normalizeDoorEntrances(blocks);
            ensureFrontWindows(blocks);
            List<Placement> placements = new ArrayList<>(blocks.size());
            blocks.forEach((pos, state) -> placements.add(new Placement(pos, state)));
            validateBounds(placements, maxSize);
            BuildJob job = new BuildJob(player.getUUID(), level.dimension().location(), name, placements);
            commitJob(server, player, job, name);
            String warning = skippedOperations == 0 ? "" : "，已跳过 " + skippedOperations + " 个无效操作";
            notifyActionBar(player, "AI 生成式建造已开始: " + name + "，共 " + placements.size() + " 个方块" + warning);
            OllamaProxyMod.LOGGER.info("【AI建造】生成式蓝图开始 name={} operations={} blocks={} skippedOperations={}",
                    name, operations.size(), placements.size(), skippedOperations);
            OllamaProxyMod.LOGGER.info("【AI建造调试】蓝图解析成功 origin={} facing={} uniqueBlocks={}", origin, facing, blocks.size());
            logSpecialBlockPositions(blocks, level);
        } catch (IllegalArgumentException e) {
            notifyPlayer(player, "AI 蓝图校验失败: " + e.getMessage());
        } catch (Exception e) {
            OllamaProxyMod.LOGGER.warn("【AI建造】蓝图解析失败", e);
            notifyPlayer(player, "AI 蓝图解析失败");
        }
    }

    public void startPlan(MinecraftServer server, ServerPlayer player, String[] params) {
        if (!validateStart(server, player)) return;
        if (params.length != 1) {
            notifyPlayer(player, "AI 建筑计划参数无效");
            return;
        }
        try {
            String json = new String(Base64.getUrlDecoder().decode(params[0]), StandardCharsets.UTF_8);
            JsonObject plan = GSON.fromJson(json, JsonObject.class);
            ServerLevel level = player.serverLevel();
            Direction facing = player.getDirection();
            int maxSize = ModConfig.AI_BUILDING_MAX_SIZE.get();
            BlockPos target = player.blockPosition().relative(facing, Math.max(7, maxSize / 2 + 4));
            int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, target.getX(), target.getZ());
            BlockPos origin = new BlockPos(target.getX(), groundY, target.getZ());
            SemanticBuildingCompiler.CompiledPlan compiled = new SemanticBuildingCompiler().compile(
                    plan, origin, facing, maxSize, ModConfig.AI_BUILDING_MAX_BLOCKS.get());
            List<Placement> placements = new ArrayList<>(compiled.blocks().size());
            compiled.blocks().forEach((pos, state) -> placements.add(new Placement(pos, state)));
            validateBounds(placements, maxSize);
            BuildJob job = new BuildJob(player.getUUID(), level.dimension().location(), compiled.name(), placements);
            commitJob(server, player, job, compiled.name());
            notifyActionBar(player, "AI 语义建筑施工已开始: " + compiled.name() + "，共 " + placements.size() + " 个方块");
            OllamaProxyMod.LOGGER.info("【AI建造】语义建筑计划开始 name={} blocks={} origin={} facing={}",
                    compiled.name(), placements.size(), origin, facing);
        } catch (IllegalArgumentException e) {
            notifyPlayer(player, "AI 建筑计划校验失败: " + e.getMessage());
            OllamaProxyMod.LOGGER.warn("【AI建造】语义建筑计划校验失败: {}", e.getMessage());
        } catch (Exception e) {
            OllamaProxyMod.LOGGER.warn("【AI建造】语义建筑计划解析失败", e);
            notifyPlayer(player, "AI 建筑计划解析失败");
        }
    }

    private boolean validateStart(MinecraftServer server, ServerPlayer player) {
        if (!ModConfig.ENABLE_AI_BUILDING.get()) {
            notifyPlayer(player, "AI 建造已关闭，请先在配置中启用");
            return false;
        }
        if (!server.isSingleplayer() || server.getPlayerCount() != 1) {
            if (!ModConfig.AI_BUILDING_ALLOW_MULTIPLAYER.get()) {
                notifyPlayer(player, "AI 建造当前仅支持单人世界；如需在多人服务器使用请开启配置 \"AI 建造 → 允许多人服务器\"");
                return false;
            }
            if (server.getPlayerList().getPlayers().size() > 1) {
                OllamaProxyMod.LOGGER.info("【AI建造】多人服务器 {} 中允许 {} 启动建造任务", server.getServerVersion(), player.getName().getString());
            }
        }
        BuildJob existing = jobsByPlayer.get(player.getUUID());
        if (existing != null && !existing.finished()) {
            notifyPlayer(player, "已有建造任务正在运行，请先取消或等待完成");
            return false;
        }
        return true;
    }

    private void expandBlueprintOperation(Map<BlockPos, BlockState> blocks, BlockPos origin, Direction facing,
                                          JsonObject operation, int maxSize) {
        String op = requiredString(operation, "op").toLowerCase(Locale.ROOT);
        String blockSpec = requiredString(operation, "block");
        BlockState state = resolveBlueprintState(blockSpec, facing);
        int[] from = requiredPosition(operation, "from", maxSize);
        int[] to = "set".equals(op) ? from : requiredPosition(operation, "to", maxSize);
        String blockPath = ForgeRegistries.BLOCKS.getKey(state.getBlock()).getPath();
        if (state.getBlock() instanceof DoorBlock && !"set".equals(op)) {
            if (from[0] == to[0] && from[2] == to[2] && Math.abs(from[1] - to[1]) <= 1) {
                putDoorAware(blocks, local(origin, facing, from[0], Math.min(from[1], to[1]), from[2]), state);
                return;
            }
            throw new IllegalArgumentException("门必须是单个坐标或两格高的竖线");
        }
        if (state.getBlock() instanceof BedBlock) {
            putBedAware(blocks, local(origin, facing, from[0], from[1], from[2]), state);
            return;
        }
        if (blockPath.endsWith("_stairs") && "fill".equals(op)) {
            addBlueprintStairRoof(blocks, origin, facing, from, to, state);
            return;
        }
        if ("gable_roof".equals(op) || "hip_roof".equals(op)) {
            addBlueprintRoof(blocks, origin, facing, from, to, state, "hip_roof".equals(op));
            return;
        }
        if ("fill".equals(op) && from[1] == to[1] && isLightBlock(blockPath)
                && Math.abs(to[0] - from[0]) * Math.abs(to[2] - from[2]) > 16) {
            addBlueprintLightGrid(blocks, origin, facing, from, to, state);
            return;
        }
        if (!"set".equals(op) && !"line".equals(op) && isNonFullPlacementBlock(blockPath)
                && !(blockPath.endsWith("_pane") && isPlanarWindow(from, to))) {
            throw new IllegalArgumentException("非完整方块只能使用set或line操作");
        }
        switch (op) {
            case "set" -> putDoorAware(blocks, local(origin, facing, from[0], from[1], from[2]), state);
            case "line" -> addBlueprintLine(blocks, origin, facing, from, to, state);
            case "fill", "hollow", "frame" -> addBlueprintBox(blocks, origin, facing, from, to, state, op);
            default -> throw new IllegalArgumentException("不支持的蓝图操作: " + op);
        }
    }

    private void addBlueprintRoof(Map<BlockPos, BlockState> blocks, BlockPos origin, Direction facing,
                                  int[] from, int[] to, BlockState state, boolean hip) {
        int minX = Math.min(from[0], to[0]) - 1;
        int maxX = Math.max(from[0], to[0]) + 1;
        int baseY = Math.min(from[1], to[1]);
        int minZ = Math.min(from[2], to[2]) - 1;
        int maxZ = Math.max(from[2], to[2]) + 1;
        boolean ridgeAlongZ = maxX - minX <= maxZ - minZ;
        BlockState backing = roofBackingState(state);
        int layers = (ridgeAlongZ ? maxX - minX : maxZ - minZ) / 2 + 1;
        for (int layer = 0; layer < layers; layer++) {
            int y = baseY + layer;
            int x0 = minX + layer;
            int x1 = maxX - layer;
            int z0 = minZ + (hip ? layer : 0);
            int z1 = maxZ - (hip ? layer : 0);
            if (x0 > x1 || z0 > z1) break;
            if (ridgeAlongZ) {
                BlockState left = withWorldFacing(state, facing.getClockWise());
                BlockState right = withWorldFacing(state, facing.getCounterClockWise());
                for (int z = z0; z <= z1; z++) {
                    putRoofColumn(blocks, origin, facing, x0, baseY, y, z, left, backing);
                    putRoofColumn(blocks, origin, facing, x1, baseY, y, z, right, backing);
                }
            } else {
                BlockState near = withWorldFacing(state, facing);
                BlockState far = withWorldFacing(state, facing.getOpposite());
                for (int x = x0; x <= x1; x++) {
                    putRoofColumn(blocks, origin, facing, x, baseY, y, z0, near, backing);
                    putRoofColumn(blocks, origin, facing, x, baseY, y, z1, far, backing);
                }
            }
        }
    }

    private static BlueprintShell blueprintShell(JsonObject operation, int maxSize, Direction facing) {
        if (!"hollow".equalsIgnoreCase(requiredString(operation, "op"))) return null;
        int[] from = requiredPosition(operation, "from", maxSize);
        int[] to = requiredPosition(operation, "to", maxSize);
        int minX = Math.min(from[0], to[0]);
        int maxX = Math.max(from[0], to[0]);
        int minY = Math.min(from[1], to[1]);
        int maxY = Math.max(from[1], to[1]);
        int minZ = Math.min(from[2], to[2]);
        int maxZ = Math.max(from[2], to[2]);
        if (maxY - minY < 3 || maxX - minX < 4 || maxZ - minZ < 4) return null;
        BlockState wall = resolveBlueprintState(requiredString(operation, "block"), facing);
        return new BlueprintShell(minX, maxX, maxY, minZ, maxZ, wall);
    }

    private void addFallbackRoofs(Map<BlockPos, BlockState> blocks, BlockPos origin, Direction facing,
                                  List<BlueprintShell> shells) {
        BlueprintShell highest = null;
        for (BlueprintShell shell : shells) {
            BlockState roof = fallbackRoofState(shell.wall(), facing);
            BlockState backing = roofBackingState(roof);
            if (backing == null) backing = shell.wall();
            int y = shell.maxY() + 1;
            for (int x = shell.minX(); x <= shell.maxX(); x++) {
                for (int z = shell.minZ(); z <= shell.maxZ(); z++) {
                    put(blocks, local(origin, facing, x, y, z), backing);
                }
            }
            if (highest == null || shell.maxY() > highest.maxY()
                    || (shell.maxY() == highest.maxY() && shell.area() > highest.area())) {
                highest = shell;
            }
        }
        if (highest != null) {
            BlockState roof = fallbackRoofState(highest.wall(), facing);
            addBlueprintRoof(blocks, origin, facing,
                    new int[]{highest.minX(), highest.maxY() + 1, highest.minZ()},
                    new int[]{highest.maxX(), highest.maxY() + 1, highest.maxZ()}, roof, false);
        }
    }

    private void addBlueprintLightGrid(Map<BlockPos, BlockState> blocks, BlockPos origin, Direction facing,
                                       int[] from, int[] to, BlockState state) {
        int minX = Math.min(from[0], to[0]);
        int maxX = Math.max(from[0], to[0]);
        int y = from[1];
        int minZ = Math.min(from[2], to[2]);
        int maxZ = Math.max(from[2], to[2]);
        for (int x = minX + 1; x < maxX; x += 4) {
            for (int z = minZ + 1; z < maxZ; z += 4) {
                put(blocks, local(origin, facing, x, y, z), state);
            }
        }
    }

    private static boolean isLightBlock(String path) {
        return path.contains("lantern") || path.contains("glowstone") || path.contains("shroomlight")
                || path.contains("froglight") || path.contains("sea_lantern") || path.contains("redstone_lamp");
    }

    private static boolean isFatalBlueprintError(IllegalArgumentException error) {
        String message = error.getMessage();
        return message != null && (message.startsWith("蓝图包含禁止方块")
                || message.startsWith("坐标超出安全范围")
                || message.startsWith("单个操作范围过大")
                || message.startsWith("坐标必须为整数"));
    }

    private static void logSpecialBlockPositions(Map<BlockPos, BlockState> blocks, ServerLevel level) {
        blocks.entrySet().stream()
                .filter(entry -> entry.getValue().getBlock() instanceof DoorBlock)
                .limit(4)
                .forEach(entry -> OllamaProxyMod.LOGGER.info("【AI建造调试】door pos={} state={} below={} belowState={} above={} aboveState={}",
                        entry.getKey(), entry.getValue(), entry.getKey().below(), level.getBlockState(entry.getKey().below()),
                        entry.getKey().above(), level.getBlockState(entry.getKey().above())));
    }

    private static void normalizeDoorEntrances(Map<BlockPos, BlockState> blocks) {
        List<Map.Entry<BlockPos, BlockState>> doors = blocks.entrySet().stream()
                .filter(entry -> entry.getValue().getBlock() instanceof DoorBlock)
                .filter(entry -> !entry.getValue().hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                        || entry.getValue().getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER)
                .toList();
        for (Map.Entry<BlockPos, BlockState> entry : doors) {
            BlockPos doorPos = entry.getKey();
            BlockState doorState = entry.getValue();
            Direction doorFacing = doorState.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                    ? doorState.getValue(BlockStateProperties.HORIZONTAL_FACING) : Direction.NORTH;
            for (Direction direction : new Direction[]{doorFacing, doorFacing.getOpposite()}) {
                for (int distance = 1; distance <= 5; distance++) {
                    BlockPos passage = doorPos.relative(direction, distance);
                    blocks.remove(passage);
                    blocks.remove(passage.above());
                    blocks.remove(passage.above(2));
                }
            }
            blocks.put(doorPos, doorState.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER));
            blocks.put(doorPos.above(), doorState.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
        }
    }

    private static void ensureFrontWindows(Map<BlockPos, BlockState> blocks) {
        List<Map.Entry<BlockPos, BlockState>> doors = blocks.entrySet().stream()
                .filter(entry -> entry.getValue().getBlock() instanceof DoorBlock)
                .filter(entry -> !entry.getValue().hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                        || entry.getValue().getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER)
                .toList();
        BlockState glass = Blocks.GLASS.defaultBlockState();
        for (Map.Entry<BlockPos, BlockState> entry : doors) {
            BlockPos doorPos = entry.getKey();
            Direction facing = entry.getValue().hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                    ? entry.getValue().getValue(BlockStateProperties.HORIZONTAL_FACING) : Direction.NORTH;
            Direction side = facing.getClockWise();
            boolean hasFrontWindow = blocks.entrySet().stream().anyMatch(candidate -> {
                BlockPos pos = candidate.getKey();
                ResourceLocation id = ForgeRegistries.BLOCKS.getKey(candidate.getValue().getBlock());
                if (id == null || !id.getPath().contains("glass")) return false;
                int forward = (pos.getX() - doorPos.getX()) * facing.getStepX()
                        + (pos.getZ() - doorPos.getZ()) * facing.getStepZ();
                int lateral = Math.abs((pos.getX() - doorPos.getX()) * side.getStepX()
                        + (pos.getZ() - doorPos.getZ()) * side.getStepZ());
                return Math.abs(forward) <= 1 && lateral <= 8;
            });
            if (hasFrontWindow) continue;
            for (int offset : new int[]{-4, -3, 3, 4}) {
                for (int height = 1; height <= 2; height++) {
                    BlockPos window = doorPos.relative(side, offset).above(height);
                    if (blocks.containsKey(window)) blocks.put(window, glass);
                }
            }
        }
    }

    private void addBlueprintLine(Map<BlockPos, BlockState> blocks, BlockPos origin, Direction facing,
                                  int[] from, int[] to, BlockState state) {
        int steps = Math.max(Math.abs(to[0] - from[0]), Math.max(Math.abs(to[1] - from[1]), Math.abs(to[2] - from[2])));
        for (int i = 0; i <= steps; i++) {
            double ratio = steps == 0 ? 0 : (double) i / steps;
            int x = (int) Math.round(from[0] + (to[0] - from[0]) * ratio);
            int y = (int) Math.round(from[1] + (to[1] - from[1]) * ratio);
            int z = (int) Math.round(from[2] + (to[2] - from[2]) * ratio);
            put(blocks, local(origin, facing, x, y, z), state);
        }
    }

    private void addBlueprintBox(Map<BlockPos, BlockState> blocks, BlockPos origin, Direction facing,
                                 int[] from, int[] to, BlockState state, String op) {
        int minX = Math.min(from[0], to[0]);
        int maxX = Math.max(from[0], to[0]);
        int minY = Math.min(from[1], to[1]);
        int maxY = Math.max(from[1], to[1]);
        int minZ = Math.min(from[2], to[2]);
        int maxZ = Math.max(from[2], to[2]);
        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume > ModConfig.AI_BUILDING_MAX_BLOCKS.get() * 2L) throw new IllegalArgumentException("单个操作范围过大");
        boolean wallShell = "fill".equals(op) && volume > 128 && maxX > minX && maxY > minY && maxZ > minZ;
        String effectiveOp = wallShell ? "hollow" : op;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    int boundaries = (x == minX || x == maxX ? 1 : 0) + (y == minY || y == maxY ? 1 : 0)
                            + (z == minZ || z == maxZ ? 1 : 0);
                    boolean sideBoundary = x == minX || x == maxX || z == minZ || z == maxZ;
                    boolean shellWall = !wallShell || sideBoundary;
                    boolean hollowSurface = minY == maxY ? boundaries >= 1 : sideBoundary;
                    if ("fill".equals(effectiveOp) || "hollow".equals(effectiveOp) && hollowSurface && shellWall
                            || "frame".equals(effectiveOp) && boundaries >= 2) {
                        put(blocks, local(origin, facing, x, y, z), state);
                    }
                }
            }
        }
    }

    private static String requiredString(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()) throw new IllegalArgumentException("缺少字段 " + key);
        String value = object.get(key).getAsString().trim();
        if (value.isBlank() || value.length() > 128) throw new IllegalArgumentException("字段无效 " + key);
        return value;
    }

    private static int[] requiredPosition(JsonObject object, String key, int maxSize) {
        if (!object.has(key) || !object.get(key).isJsonArray()) throw new IllegalArgumentException("缺少坐标 " + key);
        JsonArray array = object.getAsJsonArray(key);
        if (array.size() == 1 && array.get(0).isJsonArray()) array = array.get(0).getAsJsonArray();
        if (array.size() != 3) throw new IllegalArgumentException("坐标必须包含 x,y,z");
        int x = strictInt(array.get(0));
        int y = strictInt(array.get(1));
        int z = strictInt(array.get(2));
        if (Math.abs(x) > maxSize || y < 0 || y > maxSize || Math.abs(z) > maxSize) {
            throw new IllegalArgumentException("坐标超出安全范围");
        }
        return new int[]{x, y, z};
    }

    private static int strictInt(JsonElement element) {
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("坐标必须为整数");
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value) || value != Math.rint(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("坐标必须为整数");
        }
        return (int) value;
    }

    private static boolean isNonFullPlacementBlock(String path) {
        return path.endsWith("_door") || path.endsWith("_trapdoor") || path.endsWith("_stairs")
                || path.endsWith("_slab") || path.endsWith("_pane") || path.endsWith("_fence")
                || path.endsWith("_wall") || path.endsWith("_button") || path.endsWith("_pressure_plate");
    }

    private static boolean isPlanarWindow(int[] from, int[] to) {
        return from[0] == to[0] || from[2] == to[2];
    }

    private static BlockState resolveBlueprintState(String blockSpec, Direction facing) {
        String normalizedSpec = blockSpec.trim();
        int propertyStart = normalizedSpec.indexOf('[');
        String blockId = propertyStart >= 0 && normalizedSpec.endsWith("]")
                ? normalizedSpec.substring(0, propertyStart).trim() : normalizedSpec;
        if ("minecraft:brick_block".equals(blockId) || "brick_block".equals(blockId)) blockId = "minecraft:bricks";
        ResourceLocation id = parseBlockId(blockId);
        if (id == null) throw new IllegalArgumentException("方块 ID 无效");
        String path = id.getPath();
        if (path.contains("command_block") || path.equals("structure_block") || path.equals("structure_void")
                || path.equals("jigsaw") || path.equals("barrier") || path.equals("bedrock") || path.equals("air")) {
            throw new IllegalArgumentException("蓝图包含禁止方块: " + id);
        }
        BlockState state = resolveState(id.toString());
        if (state == null) throw new IllegalArgumentException("未知方块: " + id);
        if (propertyStart >= 0) {
            String properties = normalizedSpec.substring(propertyStart + 1, normalizedSpec.length() - 1);
            for (String entry : properties.split(",")) {
                String[] pair = entry.split("=", 2);
                if (pair.length != 2) throw new IllegalArgumentException("方块状态无效: " + blockSpec);
                String propertyName = pair[0].trim();
                if (state.getBlock() instanceof net.minecraft.world.level.block.SlabBlock && "half".equals(propertyName)) {
                    propertyName = "type";
                }
                state = setProperty(state, propertyName, pair[1].trim(), blockSpec);
            }
        }
        return applyLocalFacing(state, facing);
    }

    private void addBlueprintStairRoof(Map<BlockPos, BlockState> blocks, BlockPos origin, Direction facing,
                                       int[] from, int[] to, BlockState state) {
        int minX = Math.min(from[0], to[0]);
        int maxX = Math.max(from[0], to[0]);
        int minY = Math.min(from[1], to[1]);
        int maxY = Math.max(from[1], to[1]);
        int minZ = Math.min(from[2], to[2]);
        int maxZ = Math.max(from[2], to[2]);
        boolean ridgeAlongZ = maxX - minX <= maxZ - minZ;
        int layers = Math.min(maxY - minY + 1,
                (ridgeAlongZ ? maxX - minX : maxZ - minZ) / 2 + 1);
        for (int layer = 0; layer < layers; layer++) {
            int y = minY + layer;
            if (ridgeAlongZ) {
                int leftX = minX + layer;
                int rightX = maxX - layer;
                BlockState left = withWorldFacing(state, facing.getClockWise());
                BlockState right = withWorldFacing(state, facing.getCounterClockWise());
                for (int z = minZ; z <= maxZ; z++) {
                    put(blocks, local(origin, facing, leftX, y, z), left);
                    put(blocks, local(origin, facing, rightX, y, z), right);
                }
            } else {
                int nearZ = minZ + layer;
                int farZ = maxZ - layer;
                BlockState near = withWorldFacing(state, facing);
                BlockState far = withWorldFacing(state, facing.getOpposite());
                for (int x = minX; x <= maxX; x++) {
                    put(blocks, local(origin, facing, x, y, nearZ), near);
                    put(blocks, local(origin, facing, x, y, farZ), far);
                }
            }
        }
    }

    private static BlockState withWorldFacing(BlockState state, Direction direction) {
        Property<?> property = state.getBlock().getStateDefinition().getProperty("facing");
        return property == null ? state : setDirectionProperty(state, property, direction);
    }

    /**
     * 设置方块属性；AI 常给出方块不具备的属性（如给普通方块写 facing、给楼梯写 axis），此时只忽略该条属性并提示一次，方块本身与其余合法属性照常放置。
     */
    private static BlockState setProperty(BlockState state, String name, String value, String blockSpec) {
        Property<?> property = state.getBlock().getStateDefinition().getProperty(name);
        if (property == null) {
            reportSkippedProperty(blockSpec, "该方块没有属性 " + name);
            return state;
        }
        BlockState updated = setPropertyValue(state, property, value, blockSpec);
        return updated != null ? updated : state;
    }

    private static <T extends Comparable<T>> BlockState setPropertyValue(BlockState state, Property<T> property,
                                                                          String value, String blockSpec) {
        return property.getValue(value)
                .map(parsed -> state.setValue(property, parsed))
                .orElseGet(() -> {
                    reportSkippedProperty(blockSpec, "属性值无效 " + property.getName() + "=" + value);
                    return null;
                });
    }

    private static void reportSkippedProperty(String blockSpec, String reason) {
        if (REPORTED_ALIASES.putIfAbsent("prop:" + blockSpec + ":" + reason, Boolean.TRUE) == null) {
            OllamaProxyMod.LOGGER.info("【AI建造】忽略方块状态: {}（{}）", blockSpec, reason);
        }
    }

    private static BlockState applyLocalFacing(BlockState state, Direction facing) {
        for (Property<?> property : state.getBlock().getStateDefinition().getProperties()) {
            Object value = state.getValue(property);
            if (!(value instanceof Direction direction) || direction.getAxis() == Direction.Axis.Y) continue;
            Direction worldDirection = switch (direction) {
                case NORTH -> facing.getOpposite();
                case SOUTH -> facing;
                case EAST -> facing.getClockWise();
                case WEST -> facing.getCounterClockWise();
                default -> direction;
            };
            if (property.getValue(worldDirection.getSerializedName()).isPresent()) {
                state = setDirectionProperty(state, property, worldDirection);
            }
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState setDirectionProperty(BlockState state, Property<T> property,
                                                                                Direction direction) {
        return property.getValue(direction.getSerializedName())
                .map(value -> state.setValue(property, value))
                .orElse(state);
    }

    public void pause(ServerPlayer player) {
        BuildJob job = jobsByPlayer.get(player.getUUID());
        if (job == null || job.finished()) {
            notifyPlayer(player, "当前没有 AI 建造任务");
            return;
        }
        job.paused = true;
        stopBuilderNavigation();
        notifyPlayer(player, "AI 建造已暂停");
    }

    public void resume(ServerPlayer player) {
        BuildJob job = jobsByPlayer.get(player.getUUID());
        if (job == null || job.finished()) {
            notifyPlayer(player, "当前没有 AI 建造任务");
            return;
        }
        job.paused = false;
        notifyPlayer(player, "AI 建造已继续");
    }

    public void cancel(ServerPlayer player) {
        BuildJob job = jobsByPlayer.get(player.getUUID());
        if (job == null || job.finished()) {
            notifyPlayer(player, "当前没有 AI 建造任务");
            return;
        }
        job.cancelled = true;
        releaseBuilder(job, player);
        notifyPlayer(player, "AI 建造已取消，已放置 " + job.placed + " 个方块");
    }

    public void status(ServerPlayer player) {
        BuildJob currentJob = jobsByPlayer.get(player.getUUID());
        if (currentJob == null || currentJob.finished()) {
            notifyPlayer(player, "当前没有 AI 建造任务");
            return;
        }
        notifyPlayer(player, "AI 建造 " + currentJob.type + ": " + currentJob.index + "/" + currentJob.placements.size()
                + (currentJob.paused ? "，已暂停" : "，进行中"));
    }

    public void tick(MinecraftServer server) {
        if (jobsByPlayer.isEmpty()) return;
        tickSnapshot.clear();
        tickSnapshot.addAll(jobsByPlayer.values());
        for (BuildJob job : tickSnapshot) {
            tickOne(server, job);
        }
    }

    private void tickOne(MinecraftServer server, BuildJob job) {
        if (job.finished()) {
            finishJob(server, job);
            return;
        }
        if (job.paused) return;
        if (job.ghostTicksLeft > 0) {
            previewGhostBlocks(job);
            job.ghostTicksLeft--;
            if (job.ghostTicksLeft > 0) {
                updateBossProgress(job, null);
                return;
            }
        }
        ServerPlayer player = server.getPlayerList().getPlayer(job.owner);
        if (player == null) return;
        if (!player.serverLevel().dimension().location().equals(job.dimension)) {
            job.paused = true;
            releaseBuilder(job, player);
            notifyPlayer(player, "AI 建造因玩家切换维度已暂停");
            return;
        }

        ServerLevel level = player.serverLevel();
        PathfinderMob builder = job.verityBuilderMode ? getBuilder(level) : null;
        if (builder != null && !job.builderStarted) {
            job.builderStarted = true;
            job.restoreFollow = OriginalVerityStateAccess.isFollowingPlayer();
            OriginalVerityStateAccess.setFollowingPlayer(false);
            chooseWorkPosition(job, level, builder);
            notifyPlayer(player, "Verity 正在滚向施工位置");
        }
        if (builder != null && job.builderStarted) {
            if (!navigateBuilder(job, level, builder)) {
                updateBossProgress(job, player);
                return;
            }
            if (job.placeCooldown-- > 0) {
                updateBossProgress(job, player);
                return;
            }
            job.placeCooldown = Math.max(2, 8 / Math.max(1, ModConfig.AI_BUILDING_BLOCKS_PER_TICK.get()));
            placeNext(job, level, builder);
            if (job.index < job.placements.size() && job.index % 48 == 0) {
                chooseWorkPosition(job, level, builder);
            }
        } else {
            int budget = ModConfig.AI_BUILDING_BLOCKS_PER_TICK.get();
            for (int used = 0; used < budget && job.index < job.placements.size(); used++) {
                placeNext(job, level, null);
            }
        }

        if (shouldAbortForPlacementFailures(job)) {
            job.failed = true;
            job.paused = false;
            notifyPlayer(player, "AI 建造因施工失败率过高已停止：已处理 " + job.index
                    + " 个方块，成功 " + job.placed + "，跳过 " + job.skipped);
            OllamaProxyMod.LOGGER.warn("【AI建造】任务因失败率过高停止 type={} index={} total={} placed={} skipped={}",
                    job.type, job.index, job.placements.size(), job.placed, job.skipped);
        }

        updateBossProgress(job, player);

        if (job.finished()) {
            finishJob(server, job);
        } else if (ModConfig.AI_BUILDING_PERSIST.get()) {
            persistJob(server, job);
        }
    }

    private static boolean shouldAbortForPlacementFailures(BuildJob job) {
        int processed = job.index;
        return !job.failed && processed >= 16
                && job.skipped >= 12
                && (double) job.skipped / processed >= 0.55D;
    }

    private void finishJob(MinecraftServer server, BuildJob job) {
        ServerPlayer player = server.getPlayerList().getPlayer(job.owner);
        releaseBuilder(job, player);
        if (job.index >= job.placements.size()) {
            lastUndoByName.put(job.type, new UndoPack(job.dimension.toString(),
                    new ArrayList<>(job.placements), System.currentTimeMillis()));
            if (player != null) {
                notifyPlayer(player, "AI 建造完成: " + job.type + "，放置 " + job.placed + "，跳过 " + job.skipped);
            }
            OllamaProxyMod.LOGGER.info("【AI建造】任务完成 type={} placed={} skipped={}", job.type, job.placed, job.skipped);
            OllamaProxyMod.LOGGER.info("【AI建造调试】施工完成 type={} total={} placed={} skipped={}",
                    job.type, job.placements.size(), job.placed, job.skipped);
        }
        jobsByPlayer.remove(job.owner);
        ProgressTracker tracker = progressByPlayer.remove(job.owner);
        if (tracker != null && player != null) tracker.boss.removePlayer(player);
        if (ModConfig.AI_BUILDING_PERSIST.get()) deletePersistedJob(server, job);
    }

    private void updateBossProgress(BuildJob job, ServerPlayer player) {
        if (!ModConfig.AI_BUILDING_BOSS_BAR_PROGRESS.get()) return;
        ProgressTracker tracker = progressByPlayer.get(job.owner);
        if (tracker == null) return;
        tracker.boss.setProgress(job.finished() ? 1.0F : Math.max(0F, Math.min(1F, (float) job.index / Math.max(1, job.placements.size()))));
        tracker.boss.setName(Component.literal("[AI 建造] " + tracker.label + "  " + job.index + "/" + job.placements.size()
                + " (已放 " + job.placed + " / 跳过 " + job.skipped + ")"));
        if (player == null) {
            ServerPlayer online = serverPlayerById(job.owner);
            if (online != null) tracker.boss.addPlayer(online);
        }
    }

    private ServerPlayer serverPlayerById(UUID uuid) {
        return net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer() == null ? null
                : net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer().getPlayerList().getPlayer(uuid);
    }

    private static PathfinderMob getBuilder(ServerLevel level) {
        Object entity = OriginalVerityStateAccess.getVerityEntity();
        if (entity instanceof PathfinderMob mob && mob.isAlive() && !mob.isRemoved() && mob.level() == level) {
            return mob;
        }
        return null;
    }

    private static void sortPlacements(List<Placement> placements) {
        placements.sort(Comparator.comparingInt((Placement placement) -> placement.pos.getY())
                .thenComparingInt(AiBuildingManager::placementPriority)
                .thenComparingInt(placement -> placement.pos.getX())
                .thenComparingInt(placement -> placement.pos.getZ()));
    }

    private static int placementPriority(Placement placement) {
        BlockState state = placement.state;
        if (state.getBlock() instanceof BedBlock && state.hasProperty(BlockStateProperties.BED_PART)) {
            return state.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT ? 2 : 3;
        }
        if (state.getBlock() instanceof DoorBlock && state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            return state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? 2 : 3;
        }
        return state.canOcclude() ? 0 : 4;
    }

    private static void chooseWorkPosition(BuildJob job, ServerLevel level, PathfinderMob builder) {
        if (job.index >= job.placements.size()) return;
        Placement placement = job.placements.get(job.index);
        int dx = Integer.compare(placement.pos.getX(), job.centerX);
        int dz = Integer.compare(placement.pos.getZ(), job.centerZ);
        if (dx == 0 && dz == 0) dz = 1;
        int x = placement.pos.getX() + dx * 4;
        int z = placement.pos.getZ() + dz * 4;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        job.workPosition = new BlockPos(x, y, z);
        job.navigationStarted = false;
        job.navigationTicks = 0;
        builder.getNavigation().stop();
    }

    private static boolean navigateBuilder(BuildJob job, ServerLevel level, PathfinderMob builder) {
        if (job.workPosition == null) chooseWorkPosition(job, level, builder);
        double distance = builder.distanceToSqr(job.workPosition.getX() + 0.5, job.workPosition.getY(), job.workPosition.getZ() + 0.5);
        if (distance <= 9.0 || job.navigationTicks > 160) {
            builder.getNavigation().stop();
            job.navigationStarted = false;
            return true;
        }
        if (!job.navigationStarted || builder.getNavigation().isDone() || job.navigationTicks % 20 == 0) {
            builder.getNavigation().moveTo(job.workPosition.getX() + 0.5, job.workPosition.getY(), job.workPosition.getZ() + 0.5, 1.15);
            job.navigationStarted = true;
        }
        job.navigationTicks++;
        return false;
    }

    private void placeNext(BuildJob job, ServerLevel level, PathfinderMob builder) {
        if (job.index >= job.placements.size()) return;
        Placement placement = job.placements.get(job.index);
        if (builder != null) {
            builder.getLookControl().setLookAt(placement.pos.getX() + 0.5, placement.pos.getY() + 0.5,
                    placement.pos.getZ() + 0.5, 30.0F, 30.0F);
        }
        if (!level.isInWorldBounds(placement.pos)) {
            job.index++;
            job.skipped++;
            return;
        }
        if (!level.hasChunkAt(placement.pos)) {
            int cx = placement.pos.getX() >> 4;
            int cz = placement.pos.getZ() >> 4;
            if (!level.getChunkSource().hasChunk(cx, cz)) {
                job.index++;
                job.skipped++;
                return;
            }
        }
        job.index++;
        BlockState existing = level.getBlockState(placement.pos);
        if (!canPlaceAt(level, placement.pos, existing)) {
            job.skipped++;
            return;
        }
        if (level.setBlock(placement.pos, placement.state, Block.UPDATE_CLIENTS | Block.UPDATE_NEIGHBORS)) {
            job.placed++;
            emitVisualFx(level, placement, builder);
        } else {
            job.skipped++;
        }
    }

    private void emitVisualFx(ServerLevel level, Placement placement, PathfinderMob builder) {
        boolean particles = ModConfig.AI_BUILDING_PARTICLES.get() && builder != null;
        boolean sound = ModConfig.AI_BUILDING_SOUND.get() && builder != null;
        if (!particles && !sound) return;
        // 每 6 个方块才播一次声音，避免高频噪音刷屏
        if (sound && (placement.pos.getX() * 31L + placement.pos.getY() * 17L + placement.pos.getZ() * 13L) % 6 != 0) {
            sound = false;
        }
        if (particles) {
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, placement.state),
                    placement.pos.getX() + 0.5, placement.pos.getY() + 0.5, placement.pos.getZ() + 0.5,
                    4, 0.2, 0.2, 0.2, 0.02);
        }
        if (sound) {
            level.playSound(null, placement.pos, placement.state.getSoundType().getPlaceSound(),
                    SoundSource.BLOCKS, 0.45F, 1.0F);
        }
    }

    private static void stopBuilderNavigation() {
        Object entity = OriginalVerityStateAccess.getVerityEntity();
        if (entity instanceof PathfinderMob mob) mob.getNavigation().stop();
    }

    private void releaseBuilder(BuildJob job, ServerPlayer playerForBoss) {
        stopBuilderNavigation();
        if (job.builderStarted) OriginalVerityStateAccess.setFollowingPlayer(job.restoreFollow);
        job.builderStarted = false;
        ProgressTracker tracker = progressByPlayer.remove(job.owner);
        if (tracker != null) {
            if (playerForBoss != null) tracker.boss.removePlayer(playerForBoss);
            tracker.boss.removeAllPlayers();
        }
    }

    private static boolean canPlaceAt(ServerLevel level, BlockPos pos, BlockState existing) {
        if (existing.isAir() || existing.canBeReplaced()) return true;
        if (!ModConfig.AI_BUILDING_OVERWRITE_BLOCKS.get()) return false;
        if (level.getBlockEntity(pos) != null || existing.getDestroySpeed(level, pos) < 0) return false;
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(existing.getBlock());
        if (id == null) return false;
        String path = id.getPath();
        return !path.contains("command_block") && !path.equals("structure_block")
                && !path.equals("structure_void") && !path.equals("jigsaw")
                && !path.equals("barrier") && !path.equals("bedrock");
    }

    private static void validateBounds(List<Placement> placements, int maxSize) {
        if (placements.isEmpty()) return;
        int minX = placements.stream().mapToInt(placement -> placement.pos.getX()).min().orElseThrow();
        int maxX = placements.stream().mapToInt(placement -> placement.pos.getX()).max().orElseThrow();
        int minY = placements.stream().mapToInt(placement -> placement.pos.getY()).min().orElseThrow();
        int maxY = placements.stream().mapToInt(placement -> placement.pos.getY()).max().orElseThrow();
        int minZ = placements.stream().mapToInt(placement -> placement.pos.getZ()).min().orElseThrow();
        int maxZ = placements.stream().mapToInt(placement -> placement.pos.getZ()).max().orElseThrow();
        if ((long) maxX - minX + 1 > maxSize || (long) maxY - minY + 1 > maxSize
                || (long) maxZ - minZ + 1 > maxSize) {
            throw new IllegalArgumentException("最终建筑包围盒超出尺寸限制 " + maxSize);
        }
    }

    public void clear() {
        for (Map.Entry<UUID, BuildJob> entry : jobsByPlayer.entrySet()) {
            releaseBuilder(entry.getValue(), null);
        }
        jobsByPlayer.clear();
        progressByPlayer.clear();
    }

    private boolean ownsActiveJob(ServerPlayer player) {
        BuildJob job = jobsByPlayer.get(player.getUUID());
        if (job == null || job.finished()) {
            notifyPlayer(player, "当前没有 AI 建造任务");
            return false;
        }
        return true;
    }

    private List<Placement> createPlan(String type, int size, int floors, BlockPos origin, Direction facing,
                                       BuildingMaterials materials) {
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        switch (type) {
            case "platform" -> addPlatform(blocks, origin, size, materials.main);
            case "wall" -> addWall(blocks, origin, size, facing, materials.main, materials.accent);
            case "tower" -> addTower(blocks, origin, size, floors, facing, materials);
            case "house" -> addHouse(blocks, origin, size, floors, facing, materials, 0);
            case "cottage" -> addHouse(blocks, origin, size, Math.min(2, floors), facing, materials, 1);
            case "villa" -> addHouse(blocks, origin, size, Math.max(2, floors), facing, materials, 2);
            case "bridge" -> addBridge(blocks, origin, size, facing, materials);
            case "pyramid" -> addPyramid(blocks, origin, size, materials);
            case "temple" -> addTemple(blocks, origin, size, facing, materials);
            case "castle" -> addCastle(blocks, origin, size, facing, materials);
            default -> {
                return List.of();
            }
        }
        List<Placement> result = new ArrayList<>(blocks.size());
        blocks.forEach((pos, blockState) -> result.add(new Placement(pos, blockState)));
        return result;
    }

    private void addPlatform(Map<BlockPos, BlockState> blocks, BlockPos origin, int size, BlockState state) {
        int half = size / 2;
        for (int x = -half; x <= half; x++) {
            for (int z = -half; z <= half; z++) put(blocks, origin.offset(x, 0, z), state);
        }
    }

    private void addWall(Map<BlockPos, BlockState> blocks, BlockPos origin, int size, Direction facing,
                         BlockState state, BlockState accent) {
        int half = size / 2;
        int height = Math.max(3, Math.min(8, size / 2 + 2));
        for (int i = -half; i <= half; i++) {
            for (int y = 0; y < height; y++) {
                put(blocks, local(origin, facing, i, y, 0), y == 0 || i == -half || i == half ? accent : state);
            }
            if (i % 2 == 0) put(blocks, local(origin, facing, i, height, 0), accent);
        }
    }

    private void addTower(Map<BlockPos, BlockState> blocks, BlockPos origin, int size, int floors,
                          Direction facing, BuildingMaterials materials) {
        int width = makeOdd(Math.max(3, Math.min(9, size)));
        int half = width / 2;
        int height = Math.max(7, floors * 4 + 3);
        for (int y = 0; y < height; y++) {
            for (int x = -half; x <= half; x++) {
                for (int z = -half; z <= half; z++) {
                    boolean edge = x == -half || x == half || z == -half || z == half;
                    boolean corner = Math.abs(x) == half && Math.abs(z) == half;
                    boolean window = y > 1 && y % 4 == 2 && ((x == 0 && Math.abs(z) == half) || (z == 0 && Math.abs(x) == half));
                    boolean door = z == -half && x == 0 && y > 0 && y < 3;
                    if (y == 0 || (edge && !door)) {
                        put(blocks, local(origin, facing, x, y, z), window ? materials.window : corner ? materials.accent : materials.main);
                    }
                    if (y > 0 && y % 4 == 0) put(blocks, local(origin, facing, x, y, z), materials.accent);
                }
            }
        }
        addPyramidRoof(blocks, origin, facing, half + 1, height, materials.roof);
    }

    private void addHouse(Map<BlockPos, BlockState> blocks, BlockPos origin, int size, int floors,
                          Direction facing, BuildingMaterials materials, int style) {
        int width = makeOdd(size);
        int depth = makeOdd(Math.max(5, style == 2 ? size + 2 : size));
        int halfW = width / 2;
        int halfD = depth / 2;
        int floorHeight = 4;
        int height = floors * floorHeight;
        for (int x = -halfW; x <= halfW; x++) {
            for (int z = -halfD; z <= halfD; z++) {
                put(blocks, local(origin, facing, x, 0, z), materials.accent);
                for (int y = 1; y <= height; y++) {
                    boolean edge = x == -halfW || x == halfW || z == -halfD || z == halfD;
                    boolean corner = Math.abs(x) == halfW && Math.abs(z) == halfD;
                    boolean door = z == -halfD && x == 0 && y <= 2;
                    boolean window = edge && !corner && y % floorHeight == 2
                            && ((Math.abs(x) == halfW && Math.abs(z) <= 1) || (Math.abs(z) == halfD && Math.abs(x) == Math.max(1, halfW - 2)));
                    if (edge && !door) put(blocks, local(origin, facing, x, y, z), window ? materials.window : corner ? materials.accent : materials.main);
                }
                for (int floor = 1; floor < floors; floor++) {
                    put(blocks, local(origin, facing, x, floor * floorHeight, z), materials.accent);
                }
            }
        }
        if (style == 2) {
            addPorch(blocks, origin, facing, halfW, halfD, materials);
        }
        addGabledRoof(blocks, origin, facing, halfW, halfD, height + 1, materials.roof);
    }

    private void addPorch(Map<BlockPos, BlockState> blocks, BlockPos origin, Direction facing, int halfW, int halfD,
                          BuildingMaterials materials) {
        for (int x = -Math.min(2, halfW); x <= Math.min(2, halfW); x++) {
            for (int z = -halfD - 2; z < -halfD; z++) put(blocks, local(origin, facing, x, 0, z), materials.accent);
        }
        for (int x : new int[]{-2, 2}) {
            for (int y = 1; y <= 3; y++) put(blocks, local(origin, facing, x, y, -halfD - 2), materials.accent);
        }
        for (int x = -2; x <= 2; x++) put(blocks, local(origin, facing, x, 4, -halfD - 2), materials.roof);
    }

    private void addBridge(Map<BlockPos, BlockState> blocks, BlockPos origin, int size, Direction facing,
                           BuildingMaterials materials) {
        int length = Math.max(7, size * 2 + 1);
        for (int z = 0; z < length; z++) {
            int rise = z < 3 ? z / 2 : z >= length - 3 ? (length - 1 - z) / 2 : 1;
            for (int x = -2; x <= 2; x++) put(blocks, local(origin, facing, x, rise, z), materials.main);
            for (int x : new int[]{-2, 2}) {
                put(blocks, local(origin, facing, x, rise + 1, z), materials.accent);
                if (z % 3 == 0) put(blocks, local(origin, facing, x, rise + 2, z), materials.accent);
            }
        }
    }

    private void addPyramid(Map<BlockPos, BlockState> blocks, BlockPos origin, int size, BuildingMaterials materials) {
        int half = makeOdd(size) / 2;
        for (int y = 0; y <= half; y++) {
            int radius = half - y;
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (y == 0 || Math.abs(x) == radius || Math.abs(z) == radius) {
                        put(blocks, origin.offset(x, y, z), y == half ? materials.roof : materials.main);
                    }
                }
            }
        }
    }

    private void addTemple(Map<BlockPos, BlockState> blocks, BlockPos origin, int size, Direction facing,
                           BuildingMaterials materials) {
        int half = makeOdd(size) / 2;
        for (int level = 0; level < 3; level++) {
            int radius = half + 2 - level;
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) put(blocks, local(origin, facing, x, level, z), materials.main);
            }
        }
        int columnHeight = Math.max(5, size / 2 + 3);
        for (int x = -half; x <= half; x += Math.max(2, half)) {
            for (int z : new int[]{-half, half}) {
                for (int y = 3; y < columnHeight + 3; y++) put(blocks, local(origin, facing, x, y, z), materials.accent);
            }
        }
        for (int z = -half; z <= half; z += Math.max(2, half)) {
            for (int x : new int[]{-half, half}) {
                for (int y = 3; y < columnHeight + 3; y++) put(blocks, local(origin, facing, x, y, z), materials.accent);
            }
        }
        for (int layer = 0; layer < 3; layer++) {
            int radius = half + 1 - layer;
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) put(blocks, local(origin, facing, x, columnHeight + 3 + layer, z), materials.roof);
            }
        }
    }

    private void addCastle(Map<BlockPos, BlockState> blocks, BlockPos origin, int size, Direction facing,
                           BuildingMaterials materials) {
        int half = makeOdd(Math.max(7, size)) / 2;
        int wallHeight = Math.max(5, size / 2);
        for (int i = -half; i <= half; i++) {
            for (int y = 0; y < wallHeight; y++) {
                boolean gate = i >= -1 && i <= 1 && y < 3;
                if (!gate) put(blocks, local(origin, facing, i, y, -half), materials.main);
                put(blocks, local(origin, facing, i, y, half), materials.main);
                put(blocks, local(origin, facing, -half, y, i), materials.main);
                put(blocks, local(origin, facing, half, y, i), materials.main);
            }
            if (i % 2 == 0) {
                put(blocks, local(origin, facing, i, wallHeight, -half), materials.accent);
                put(blocks, local(origin, facing, i, wallHeight, half), materials.accent);
                put(blocks, local(origin, facing, -half, wallHeight, i), materials.accent);
                put(blocks, local(origin, facing, half, wallHeight, i), materials.accent);
            }
        }
        int towerRadius = 2;
        for (int cx : new int[]{-half, half}) {
            for (int cz : new int[]{-half, half}) addCastleTower(blocks, origin, facing, cx, cz, towerRadius, wallHeight + 4, materials);
        }
    }

    private void addCastleTower(Map<BlockPos, BlockState> blocks, BlockPos origin, Direction facing, int cx, int cz,
                                int radius, int height, BuildingMaterials materials) {
        for (int y = 0; y < height; y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    if (Math.abs(x) == radius || Math.abs(z) == radius) put(blocks, local(origin, facing, cx + x, y, cz + z), materials.accent);
                }
            }
        }
        for (int x = -radius - 1; x <= radius + 1; x++) {
            for (int z = -radius - 1; z <= radius + 1; z++) put(blocks, local(origin, facing, cx + x, height, cz + z), materials.roof);
        }
    }

    private void addGabledRoof(Map<BlockPos, BlockState> blocks, BlockPos origin, Direction facing, int halfW,
                               int halfD, int baseY, BlockState roof) {
        for (int layer = 0; layer <= halfW + 1; layer++) {
            int x = halfW + 1 - layer;
            for (int z = -halfD - 1; z <= halfD + 1; z++) {
                put(blocks, local(origin, facing, -x, baseY + layer, z), roof);
                put(blocks, local(origin, facing, x, baseY + layer, z), roof);
            }
        }
    }

    private void addPyramidRoof(Map<BlockPos, BlockState> blocks, BlockPos origin, Direction facing, int radius,
                                int baseY, BlockState roof) {
        for (int layer = 0; layer <= radius; layer++) {
            int r = radius - layer;
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    if (Math.abs(x) == r || Math.abs(z) == r) put(blocks, local(origin, facing, x, baseY + layer, z), roof);
                }
            }
        }
    }

    private static BlockPos local(BlockPos origin, Direction facing, int x, int y, int z) {
        Direction right = facing.getClockWise();
        return origin.offset(right.getStepX() * x + facing.getStepX() * z, y,
                right.getStepZ() * x + facing.getStepZ() * z);
    }

    private static void put(Map<BlockPos, BlockState> blocks, BlockPos pos, BlockState state) {
        blocks.put(pos.immutable(), state);
    }

    private static void putBedAware(Map<BlockPos, BlockState> blocks, BlockPos pos, BlockState state) {
        Direction direction = state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                ? state.getValue(BlockStateProperties.HORIZONTAL_FACING) : Direction.NORTH;
        BlockState foot = state.setValue(BlockStateProperties.BED_PART, BedPart.FOOT);
        BlockState head = state.setValue(BlockStateProperties.BED_PART, BedPart.HEAD);
        put(blocks, pos, foot);
        put(blocks, pos.relative(direction), head);
    }

    private static void putRoofColumn(Map<BlockPos, BlockState> blocks, BlockPos origin, Direction facing,
                                      int x, int baseY, int topY, int z, BlockState roof, BlockState backing) {
        if (backing != null) {
            for (int y = baseY; y < topY; y++) {
                put(blocks, local(origin, facing, x, y, z), backing);
            }
        }
        put(blocks, local(origin, facing, x, topY, z), roof);
    }

    private static BlockState roofBackingState(BlockState roof) {
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(roof.getBlock());
        if (id == null) return null;
        String path = id.getPath();
        if (!path.endsWith("_stairs")) return null;
        String base = path.substring(0, path.length() - "_stairs".length());
        String[] candidates = {base + "_planks", base + "s", base, base + "_block"};
        for (String candidate : candidates) {
            ResourceLocation candidateId = parseBlockId(id.getNamespace() + ":" + candidate);
            Block block = candidateId == null ? null : ForgeRegistries.BLOCKS.getValue(candidateId);
            if (block != null && block != Blocks.AIR) return block.defaultBlockState();
        }
        return null;
    }

    private static BlockState fallbackRoofState(BlockState wall, Direction facing) {
        ResourceLocation wallId = ForgeRegistries.BLOCKS.getKey(wall.getBlock());
        String path = wallId == null ? "" : wallId.getPath();
        String stairs = path.contains("deepslate") ? "minecraft:deepslate_tile_stairs"
                : path.contains("brick") ? "minecraft:brick_stairs"
                : path.contains("stone") || path.contains("cobble") ? "minecraft:stone_brick_stairs"
                : path.contains("quartz") || path.contains("concrete") ? "minecraft:quartz_stairs"
                : "minecraft:dark_oak_stairs";
        return withWorldFacing(resolveBlueprintState(stairs, facing), facing);
    }

    private static void putDoorAware(Map<BlockPos, BlockState> blocks, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof DoorBlock)) {
            put(blocks, pos, state);
            return;
        }
        BlockState lower = state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                ? state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER) : state;
        BlockState upper = lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
        put(blocks, pos, lower);
        put(blocks, pos.above(), upper);
    }

    private static int makeOdd(int value) {
        return value % 2 == 0 ? value - 1 : value;
    }

    private static String defaultBlock(String type) {
        return switch (type) {
            case "house", "cottage", "villa", "bridge" -> "minecraft:oak_planks";
            case "temple" -> "minecraft:quartz_bricks";
            default -> "minecraft:stone_bricks";
        };
    }

    private static String defaultAccent(String type) {
        return switch (type) {
            case "house", "cottage", "villa", "bridge" -> "minecraft:stripped_oak_log";
            case "temple" -> "minecraft:quartz_pillar";
            default -> "minecraft:polished_andesite";
        };
    }

    private static String defaultRoof(String type) {
        return switch (type) {
            case "house", "cottage", "villa" -> "minecraft:dark_oak_planks";
            case "temple" -> "minecraft:smooth_quartz";
            default -> "minecraft:deepslate_tiles";
        };
    }

    private static int defaultFloors(String type) {
        return switch (type) {
            case "villa" -> 2;
            case "tower", "castle" -> 3;
            default -> 1;
        };
    }

    /**
     * 常见「AI 幻觉名 / 旧版本名」到真实方块 ID 的别名表：模型常写出 plank、brick、dirt_block、wool 这类 1.12 时代或自造名，按候选逐个尝试。
     */
    private static final Map<String, String> BLOCK_ALIASES = Map.ofEntries(
            Map.entry("brick", "minecraft:bricks"),
            Map.entry("bricks", "minecraft:bricks"),
            Map.entry("brick_block", "minecraft:bricks"),
            Map.entry("netherbrick", "minecraft:nether_bricks"),
            Map.entry("nether_brick", "minecraft:nether_bricks"),
            Map.entry("stone_brick", "minecraft:stone_bricks"),
            Map.entry("deepslate_brick", "minecraft:deepslate_bricks"),
            Map.entry("deepslate_tile", "minecraft:deepslate_tiles"),
            Map.entry("plank", "minecraft:oak_planks"),
            Map.entry("planks", "minecraft:oak_planks"),
            Map.entry("wood_planks", "minecraft:oak_planks"),
            Map.entry("wood", "minecraft:oak_log"),
            Map.entry("log", "minecraft:oak_log"),
            Map.entry("wool", "minecraft:white_wool"),
            Map.entry("concrete", "minecraft:white_concrete"),
            Map.entry("glass_block", "minecraft:glass"),
            Map.entry("dirt_block", "minecraft:dirt"),
            Map.entry("grass", "minecraft:grass_block"),
            Map.entry("leaves", "minecraft:oak_leaves"),
            Map.entry("stairs", "minecraft:oak_stairs"),
            Map.entry("slab", "minecraft:oak_slab"),
            Map.entry("door", "minecraft:oak_door"),
            Map.entry("stone_block", "minecraft:stone"),
            Map.entry("cobblestone_block", "minecraft:cobblestone"),
            Map.entry("smooth_stone_block", "minecraft:smooth_stone"),
            Map.entry("sandstone_block", "minecraft:sandstone"),
            Map.entry("quartz_block_block", "minecraft:quartz_block"),
            Map.entry("glowstone_block", "minecraft:glowstone"),
            Map.entry("sea_lantern_block", "minecraft:sea_lantern"),
            Map.entry("lantern_block", "minecraft:lantern"),
            Map.entry("iron_bars_block", "minecraft:iron_bars"),
            Map.entry("bookshelf_block", "minecraft:bookshelf"),
            Map.entry("crafting_table_block", "minecraft:crafting_table"),
            Map.entry("furnace_block", "minecraft:furnace"),
            Map.entry("chest_block", "minecraft:chest"),
            Map.entry("hay_block_block", "minecraft:hay_block"),
            Map.entry("terracotta_block", "minecraft:terracotta")
    );

    /** 同一个别名只提示一次，避免整张蓝图刷屏。 */
    private static final Map<String, Boolean> REPORTED_ALIASES = new ConcurrentHashMap<>();

    /** 生成方块 ID 候选列表：原样 -> 补 minecraft: 前缀 -> 别名 -> 命名规律纠正；调用方取第一个能解析的。 */
    private static List<String> blockIdCandidates(String raw) {
        List<String> candidates = new ArrayList<>();
        if (raw == null) return candidates;
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) return candidates;
        if (value.startsWith("minecraft:minecraft:")) {
            value = value.substring("minecraft:".length());
        }
        addCandidate(candidates, value);
        String bare = value.startsWith("minecraft:") ? value.substring("minecraft:".length()) : value;
        addCandidate(candidates, "minecraft:" + bare);
        addCandidate(candidates, BLOCK_ALIASES.get(value));
        addCandidate(candidates, BLOCK_ALIASES.get(bare));
        if (bare.endsWith("_block")) {
            addCandidate(candidates, "minecraft:" + bare.substring(0, bare.length() - "_block".length()));
        }
        if (bare.endsWith("_plank")) addCandidate(candidates, "minecraft:" + bare + "s");
        if (bare.endsWith("_wood_planks")) {
            addCandidate(candidates, "minecraft:" + bare.replace("_wood_planks", "_planks"));
        }
        if (bare.endsWith("_wool_block")) {
            addCandidate(candidates, "minecraft:" + bare.replace("_wool_block", "_wool"));
        }
        if (bare.endsWith("_stairs_block")) {
            addCandidate(candidates, "minecraft:" + bare.replace("_stairs_block", "_stairs"));
        }
        if (bare.endsWith("_slab_block")) {
            addCandidate(candidates, "minecraft:" + bare.replace("_slab_block", "_slab"));
        }
        if (bare.endsWith("_log_block")) {
            addCandidate(candidates, "minecraft:" + bare.replace("_log_block", "_log"));
        }
        if (bare.endsWith("_leaves_block")) {
            addCandidate(candidates, "minecraft:" + bare.replace("_leaves_block", "_leaves"));
        }
        return candidates;
    }

    private static void addCandidate(List<String> candidates, String candidate) {
        if (candidate == null || candidate.isBlank() || candidates.contains(candidate)) return;
        candidates.add(candidate);
    }

    private static BlockState resolveState(String blockId) {
        List<String> candidates = blockIdCandidates(blockId);
        for (int index = 0; index < candidates.size(); index++) {
            ResourceLocation id = ResourceLocation.tryParse(candidates.get(index));
            if (id == null) continue;
            Block block = ForgeRegistries.BLOCKS.getValue(id);
            if (block == null || block == Blocks.AIR || block == Blocks.BEDROCK) continue;
            if (index > 0 && REPORTED_ALIASES.putIfAbsent(blockId + "->" + id, Boolean.TRUE) == null) {
                OllamaProxyMod.LOGGER.info("【AI建造】方块名已纠正: {} -> {}", blockId, id);
            }
            return block.defaultBlockState();
        }
        return null;
    }

    private static ResourceLocation parseBlockId(String value) {
        String normalized = value.contains(":") ? value : "minecraft:" + value;
        return ResourceLocation.tryParse(normalized);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static void notifyPlayer(ServerPlayer player, String message) {
        if (player != null) player.sendSystemMessage(Component.literal("[AI 建造] " + message));
    }

    private static void notifyActionBar(ServerPlayer player, String message) {
        if (player != null) player.displayClientMessage(Component.literal("[AI 建造] " + message), true);
    }

    private record Placement(BlockPos pos, BlockState state) {
    }

    private record BlueprintShell(int minX, int maxX, int maxY, int minZ, int maxZ, BlockState wall) {
        private int area() {
            return (maxX - minX + 1) * (maxZ - minZ + 1);
        }
    }

    private record BuildingMaterials(BlockState main, BlockState accent, BlockState roof, BlockState window) {
    }

    private static final class BuildJob {
        private final UUID owner;
        private final ResourceLocation dimension;
        private final String type;
        private final List<Placement> placements;
        private final boolean verityBuilderMode;
        private int index;
        private int placed;
        private int skipped;
        private boolean paused;
        private boolean cancelled;
        private boolean failed;
        private final int centerX;
        private final int centerZ;
        private boolean builderStarted;
        private boolean restoreFollow;
        private boolean navigationStarted;
        private int navigationTicks;
        private int placeCooldown;
        private BlockPos workPosition;
        /** 幽灵预览剩余 tick。> 0 表示仍在预览阶段，未开始放置方块。 */
        int ghostTicksLeft;
        /** previewGhostBlocks 的输出游标，避免重复处理同一格。 */
        int previewCursor;

        private BuildJob(UUID owner, ResourceLocation dimension, String type, List<Placement> placements) {
            this.owner = owner;
            this.dimension = dimension;
            this.type = type;
            this.placements = placements;
            this.verityBuilderMode = ModConfig.AI_BUILDING_VERITY_BUILDER_MODE.get();
            sortPlacements(this.placements);
            this.centerX = (placements.stream().mapToInt(placement -> placement.pos.getX()).min().orElse(0)
                    + placements.stream().mapToInt(placement -> placement.pos.getX()).max().orElse(0)) / 2;
            this.centerZ = (placements.stream().mapToInt(placement -> placement.pos.getZ()).min().orElse(0)
                    + placements.stream().mapToInt(placement -> placement.pos.getZ()).max().orElse(0)) / 2;
        }

        private BuildJob(UUID owner, ResourceLocation dimension, String type, List<Placement> placements,
                         int index, int placed, int skipped, boolean paused) {
            this(owner, dimension, type, placements);
            this.index = index;
            this.placed = placed;
            this.skipped = skipped;
            this.paused = paused;
        }

        boolean finished() {
            return cancelled || failed || index >= placements.size();
        }
    }

    /** Boss Bar + label 容器。 */
    private static final class ProgressTracker {
        final ServerBossEvent boss = new ServerBossEvent(
                Component.literal("[AI 建造]"),
                BossEvent.BossBarColor.YELLOW,
                BossEvent.BossBarOverlay.PROGRESS);
        String label = "";
    }

    /** 上次成功完成的施工任务，用于 /verity-build undo。 */
    private record UndoPack(String dimension, List<Placement> placements, long finishedAtMillis) {
    }

    /** 持久化的 BuildJob 序列化样式。placements 太多时只记录位置即可。 */
    private record JobSnapshot(UUID owner, String dimension, String type,
                               int index, int placed, int skipped,
                               boolean paused, List<long[]> positions, List<String> blocks) {
    }

    // ============================ 区块预热 ============================

    private void prewarmChunks(MinecraftServer server, ServerPlayer player, BuildJob job) {
        try {
            int chunkX0 = Integer.MAX_VALUE;
            int chunkZ0 = Integer.MAX_VALUE;
            int chunkX1 = Integer.MIN_VALUE;
            int chunkZ1 = Integer.MIN_VALUE;
            for (Placement placement : job.placements) {
                int cx = placement.pos.getX() >> 4;
                int cz = placement.pos.getZ() >> 4;
                if (cx < chunkX0) chunkX0 = cx;
                if (cz < chunkZ0) chunkZ0 = cz;
                if (cx > chunkX1) chunkX1 = cx;
                if (cz > chunkZ1) chunkZ1 = cz;
            }
            int count = 0;
            for (int cx = chunkX0; cx <= chunkX1; cx++) {
                for (int cz = chunkZ0; cz <= chunkZ1; cz++) {
                    server.getLevel(player.serverLevel().dimension()).getChunk(cx, cz,
                            net.minecraft.world.level.chunk.ChunkStatus.FULL, true);
                    count++;
                    if (count > 512) return; // 防止巨型建筑一次性把全部线程占满
                }
            }
        } catch (Throwable ignored) {
            // 预热只是优化，失败依然能施工。
        }
    }

    // ============================ 幽灵预览 ============================

    private void previewGhostBlocks(BuildJob job) {
        // 幽灵预览：仅用粒子在方块表面闪烁一次给出位置提示，无需服务端反复 setBlock。
        ServerPlayer player = serverPlayerById(job.owner);
        if (player == null) return;
        ServerLevel level = player.serverLevel();
        int particlesBudget = 32;
        int stride = Math.max(1, job.placements.size() / particlesBudget);
        for (int i = job.previewCursor; i < job.placements.size() && i < job.previewCursor + stride * 4; i++) {
            if (i % stride != 0) continue;
            Placement placement = job.placements.get(i);
            if (!level.isInWorldBounds(placement.pos)) continue;
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
                    placement.pos.getX() + 0.5, placement.pos.getY() + 0.5, placement.pos.getZ() + 0.5,
                    1, 0.0, 0.0, 0.0, 0.0);
        }
        job.previewCursor = Math.min(job.placements.size(), job.previewCursor + stride * 4);
    }

    // ============================ 蓝图本地库 ============================

    private Path blueprintDir(MinecraftServer server) {
        String configured = ModConfig.AI_BUILDING_BLUEPRINT_DIR.get();
        if (configured == null || configured.isBlank()) {
            configured = "./config/verity_extended/blueprints";
        }
        Path base = Paths.get(configured);
        if (!base.isAbsolute()) {
            String root = server == null ? System.getProperty("user.dir") : server.getServerDirectory().toString();
            base = Paths.get(root, configured).normalize();
        }
        try {
            Files.createDirectories(base);
        } catch (IOException ignored) {
        }
        return base;
    }

    public void saveCurrentBlueprint(ServerPlayer player, String name) {
        BuildJob job = jobsByPlayer.get(player.getUUID());
        if (job == null) {
            notifyPlayer(player, "当前没有 AI 建造任务可保存");
            return;
        }
        String safeName = sanitizeFileName(name);
        try {
            JsonObject save = new JsonObject();
            save.addProperty("name", job.type);
            save.addProperty("dimension", job.dimension.toString());
            JsonArray items = new JsonArray();
            for (Placement placement : job.placements) {
                JsonObject op = new JsonObject();
                op.addProperty("op", "set");
                ResourceLocation id = ForgeRegistries.BLOCKS.getKey(placement.state.getBlock());
                op.addProperty("block", id == null ? "minecraft:stone" : id.toString());
                JsonArray pos = new JsonArray();
                pos.add(placement.pos.getX());
                pos.add(placement.pos.getY());
                pos.add(placement.pos.getZ());
                op.add("pos", pos);
                items.add(op);
            }
            save.add("operations", items);
            Files.writeString(blueprintDir(player.server).resolve(safeName + ".json"),
                    GSON.toJson(save), StandardCharsets.UTF_8);
            notifyPlayer(player, "已保存蓝图: " + safeName + " (" + job.placements.size() + " 个方块)");
        } catch (IOException e) {
            notifyPlayer(player, "保存蓝图失败: " + e.getMessage());
        }
    }

    public void listBlueprints(ServerPlayer player) {
        try {
            List<Path> files = Files.list(blueprintDir(player.server))
                    .filter(p -> p.toString().endsWith(".json"))
                    .toList();
            if (files.isEmpty()) {
                notifyPlayer(player, "蓝图库为空");
                return;
            }
            StringBuilder sb = new StringBuilder("本地蓝图：\n");
            for (Path p : files) {
                sb.append("  - ").append(p.getFileName().toString()).append("\n");
            }
            player.sendSystemMessage(Component.literal("[AI 建造] " + sb));
        } catch (IOException e) {
            notifyPlayer(player, "读取蓝图库失败: " + e.getMessage());
        }
    }

    public boolean loadBlueprint(ServerPlayer player, String name) {
        Path file = blueprintDir(player.server).resolve(sanitizeFileName(name) + ".json");
        if (!Files.isRegularFile(file)) {
            notifyPlayer(player, "找不到蓝图: " + name);
            return false;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(
                    json.getBytes(StandardCharsets.UTF_8));
            startBlueprint(player.server, player, new String[]{encoded});
            return true;
        } catch (IOException e) {
            notifyPlayer(player, "读取蓝图失败: " + e.getMessage());
            return false;
        }
    }

    public boolean undoLastBuild(ServerPlayer player) {
        UndoPack pack = lastUndoByName.values().stream().findFirst().orElse(null);
        if (pack == null) {
            notifyPlayer(player, "没有可还原的施工记录");
            return false;
        }
        ServerLevel level = player.serverLevel();
        int removed = 0;
        for (Placement placement : pack.placements()) {
            if (!level.isInWorldBounds(placement.pos())) continue;
            if (!level.hasChunkAt(placement.pos())) continue;
            level.setBlock(placement.pos(), Blocks.AIR.defaultBlockState(),
                    Block.UPDATE_CLIENTS | Block.UPDATE_NEIGHBORS);
            removed++;
        }
        notifyPlayer(player, "已还原施工: 清空 " + removed + " 个方块");
        lastUndoByName.clear();
        return true;
    }

    private static String sanitizeFileName(String raw) {
        if (raw == null) return "blueprint";
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return "blueprint";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < trimmed.length() && i < 48; i++) {
            char c = trimmed.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || (c >= 0x4e00 && c <= 0x9fff)) {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.length() == 0 ? "blueprint" : sb.toString();
    }

    // ============================ 任务持久化 ============================

    private Path persistedJobPath(MinecraftServer server, BuildJob job) {
        return blueprintDir(server).resolve("__job_" + job.owner + ".json");
    }

    private void persistJob(MinecraftServer server, BuildJob job) {
        try {
            JsonObject save = new JsonObject();
            save.addProperty("owner", job.owner.toString());
            save.addProperty("dimension", job.dimension.toString());
            save.addProperty("type", job.type);
            save.addProperty("index", job.index);
            save.addProperty("placed", job.placed);
            save.addProperty("skipped", job.skipped);
            save.addProperty("paused", job.paused);
            JsonArray placements = new JsonArray();
            for (Placement placement : job.placements) {
                JsonObject item = new JsonObject();
                item.addProperty("x", placement.pos.getX());
                item.addProperty("y", placement.pos.getY());
                item.addProperty("z", placement.pos.getZ());
                ResourceLocation id = ForgeRegistries.BLOCKS.getKey(placement.state.getBlock());
                item.addProperty("block", id == null ? "minecraft:stone" : id.toString());
                placements.add(item);
            }
            save.add("placements", placements);
            Files.writeString(persistedJobPath(server, job),
                    GSON.toJson(save), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private void deletePersistedJob(MinecraftServer server, BuildJob job) {
        try {
            Files.deleteIfExists(persistedJobPath(server, job));
        } catch (IOException ignored) {
        }
    }

    public void loadPersistedJobs(MinecraftServer server) {
        if (!ModConfig.AI_BUILDING_PERSIST.get()) return;
        try {
            List<Path> files = Files.list(blueprintDir(server))
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith("__job_") && name.endsWith(".json");
                    })
                    .toList();
            for (Path file : files) {
                try {
                    String json = Files.readString(file, StandardCharsets.UTF_8);
                    JsonObject obj = GSON.fromJson(json, JsonObject.class);
                    UUID owner = UUID.fromString(obj.get("owner").getAsString());
                    String dimension = obj.get("dimension").getAsString();
                    String type = obj.get("type").getAsString();
                    int index = obj.get("index").getAsInt();
                    int placed = obj.get("placed").getAsInt();
                    int skipped = obj.get("skipped").getAsInt();
                    boolean paused = obj.get("paused").getAsBoolean();
                    List<Placement> placements = new ArrayList<>();
                    for (JsonElement element : obj.getAsJsonArray("placements")) {
                        JsonObject item = element.getAsJsonObject();
                        BlockState state = resolveState(item.get("block").getAsString());
                        if (state == null) continue;
                        placements.add(new Placement(
                                new BlockPos(item.get("x").getAsInt(),
                                        item.get("y").getAsInt(),
                                        item.get("z").getAsInt()),
                                state));
                    }
                    if (placements.isEmpty()) {
                        Files.deleteIfExists(file);
                        continue;
                    }
                    ResourceLocation dimLoc = ResourceLocation.tryParse(dimension);
                    if (dimLoc == null) dimLoc = net.minecraft.world.level.Level.OVERWORLD.location();
                    BuildJob restored = new BuildJob(owner,
                            dimLoc,
                            type, placements,
                            index, placed, skipped, paused);
                    jobsByPlayer.put(owner, restored);
                    Files.deleteIfExists(file);
                } catch (Exception inner) {
                    OllamaProxyMod.LOGGER.warn("【AI建造】读取已保存任务失败: {}", inner.toString());
                }
            }
        } catch (IOException ignored) {
        }
    }

    // ============================ 输入校验助手 ============================

    private static int parseBoundedInt(String value, int fallback, int min, int max) {
        if (value == null) return fallback;
        try {
            double d = Double.parseDouble(value);
            if (!Double.isFinite(d)) return fallback;
            int v = (int) d;
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
