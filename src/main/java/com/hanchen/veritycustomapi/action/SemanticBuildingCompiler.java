package com.hanchen.veritycustomapi.action;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SemanticBuildingCompiler {
    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final org.apache.logging.log4j.Logger LOGGER =
            com.hanchen.veritycustomapi.OllamaProxyMod.LOGGER;
    /** 单层房间数量上限，超出只取前若干个而不是整张计划作废。 */
    private static final int MAX_ROOMS_PER_FLOOR = 20;
    /** 楼层数量上限。 */
    private static final int MAX_FLOORS = 6;

    public CompiledPlan compile(JsonObject plan, BlockPos origin, Direction facing, int maxSize, int maxBlocks) {
        int version = integer(plan, "schemaVersion");
        if (version != 1) throw new IllegalArgumentException("不支持的建筑计划版本");
        String name = string(plan, "name", 64);
        List<Point> outline = polygon(plan.getAsJsonArray("footprint"), maxSize, "footprint");
        validateOrthogonal(outline);
        Set<Cell> footprint = rasterize(outline);
        if (footprint.size() < 25) throw new IllegalArgumentException("建筑轮廓面积过小");
        if (footprint.size() >= 144 && isSimpleRectangle(outline)) {
            throw new IllegalArgumentException("中大型建筑不能使用单一长方形轮廓");
        }
        Palette palette = palette(plan.getAsJsonObject("palette"));
        // 以下几步做「软修复」：模型漏字段或写出越界值时能补就补、能夹就夹，不因可修正的小问题作废整张计划。
        JsonObject roof = plan.getAsJsonObject("roof");
        if (roof == null) {
            roof = new JsonObject();
            roof.addProperty("style", "pitched");
            LOGGER.warn("【AI建造】计划缺少屋顶设计，已按双坡屋顶自动补全（屋面材料取配色）");
        }
        String roofStyle = roof.has("style") ? string(roof, "style", 16).toLowerCase(Locale.ROOT) : "pitched";
        if (!"pitched".equals(roofStyle) && !"terraced".equals(roofStyle) && !"flat".equals(roofStyle)) {
            LOGGER.warn("【AI建造】屋顶样式 '{}' 不受支持，已回退为 pitched", roofStyle);
            roofStyle = "pitched";
            roof.addProperty("style", roofStyle);
        }
        JsonArray floors = plan.getAsJsonArray("floors");
        if (floors == null || floors.isEmpty()) {
            JsonObject fallbackFloor = new JsonObject();
            fallbackFloor.addProperty("height", 4);
            fallbackFloor.add("rooms", new JsonArray());
            floors = new JsonArray();
            floors.add(fallbackFloor);
            LOGGER.warn("【AI建造】计划缺少楼层定义，已补一层 4 格高的空楼层（外墙与屋顶仍会成型）");
        } else if (floors.size() > MAX_FLOORS) {
            LOGGER.warn("【AI建造】楼层数 {} 超过上限 {}，已截断", floors.size(), MAX_FLOORS);
            JsonArray trimmed = new JsonArray();
            for (int i = 0; i < MAX_FLOORS; i++) trimmed.add(floors.get(i));
            floors = trimmed;
        }
        Entrance entrance = entrance(plan.getAsJsonObject("entrance"), footprint, facing, palette.door());
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        int floorY = 0;
        List<Room> rooms = new ArrayList<>();
        for (int index = 0; index < floors.size(); index++) {
            JsonObject floor = floors.get(index).getAsJsonObject();
            int height = integer(floor, "height");
            if (height < 4 || height > 6) {
                int clamped = Math.max(4, Math.min(6, height <= 0 ? 4 : height));
                LOGGER.warn("【AI建造】第 {} 层高度 {} 超出 4-6，已修正为 {}", index + 1, height, clamped);
                height = clamped;
            }
            compileStorey(blocks, origin, facing, footprint, floorY, height, palette, index == 0, entrance);
            List<Room> floorRooms = readRooms(floor.getAsJsonArray("rooms"), footprint, floorY, height, maxSize);
            compileRoomWalls(blocks, origin, facing, floorRooms, footprint, palette);
            rooms.addAll(floorRooms);
            floorY += height + 1;
            checkLimit(blocks, maxBlocks);
        }
        compileRoomSemantics(blocks, origin, facing, rooms, palette);
        finalizeEntrance(blocks, origin, facing, entrance, footprint, 0, palette.trim());
        int roofRise = "flat".equals(roofStyle)
                ? 0 : inwardDepth(expandFootprint(footprint)).values().stream().mapToInt(Integer::intValue).max().orElse(1) - 1;
        if (floorY + roofRise > maxSize) throw new IllegalArgumentException("建筑总高度超出尺寸限制");
        compileRoof(blocks, origin, facing, footprint, floorY, roof, palette);
        checkLimit(blocks, maxBlocks);
        validateBounds(blocks, maxSize);
        return new CompiledPlan(name, blocks);
    }

    private void compileStorey(Map<BlockPos, BlockState> blocks, BlockPos origin, Direction facing,
                               Set<Cell> footprint, int floorY, int height, Palette palette,
                               boolean groundFloor, Entrance entrance) {
        Set<Cell> boundary = boundary(footprint);
        for (Cell cell : footprint) {
            if (groundFloor) put(blocks, origin, facing, cell, floorY - 1, palette.foundation());
            put(blocks, origin, facing, cell, floorY, palette.floor());
            put(blocks, origin, facing, cell, floorY + height, palette.floor());
        }
        for (Cell cell : boundary) {
            boolean corner = exposedSides(cell, footprint) >= 2;
            for (int y = floorY + 1; y <= floorY + height; y++) {
                BlockState state = corner || y == floorY + height ? palette.trim() : palette.wall();
                put(blocks, origin, facing, cell, y, state);
            }
            if (!corner && windowPosition(cell, footprint) && height >= 4) {
                put(blocks, origin, facing, cell, floorY + 2, palette.window());
                put(blocks, origin, facing, cell, floorY + 3, palette.window());
            }
        }
    }

    private void compileRoomSemantics(Map<BlockPos, BlockState> blocks, BlockPos origin, Direction facing,
                                      List<Room> rooms, Palette palette) {
        for (Room room : rooms) {
            List<Cell> interior = new ArrayList<>(room.cells());
            interior.removeIf(cell -> exposedSides(cell, room.cells()) > 0);
            if (interior.isEmpty()) continue;
            interior.sort((a, b) -> Integer.compare(a.x() * 31 + a.z(), b.x() * 31 + b.z()));
            String type = room.type();
            if (type.contains("bed") || type.contains("卧") || type.contains("guest")) {
                placeBedroom(blocks, origin, facing, interior, room.floorY() + 1, type);
            } else if (containsAny(type, "library", "study", "reading", "stacks", "archive", "书", "阅览", "档案")) {
                placeLibrary(blocks, origin, facing, interior, room.floorY() + 1, type);
            } else if (containsAny(type, "kitchen", "厨")) {
                placeKitchen(blocks, origin, facing, interior, room.floorY() + 1, type);
            } else if (containsAny(type, "dining", "restaurant", "bistro", "cafe", "餐")) {
                placeTables(blocks, origin, facing, interior, room.floorY() + 1, 8);
            } else if (containsAny(type, "living", "lounge", "family", "客厅", "起居")) {
                placeLivingRoom(blocks, origin, facing, interior, room.floorY() + 1, type);
            } else if (containsAny(type, "bath", "wash", "toilet", "浴", "卫生")) {
                placeBathroom(blocks, origin, facing, interior, room.floorY() + 1, type);
            } else if (containsAny(type, "gallery", "exhibition", "museum", "display", "展", "画廊", "博物")) {
                placeGallery(blocks, origin, facing, interior, room.floorY() + 1, type);
            } else if (containsAny(type, "lobby", "foyer", "reception", "门厅", "大厅", "前台")) {
                placeReception(blocks, origin, facing, interior, room.floorY() + 1, type);
            } else if (containsAny(type, "office", "meeting", "conference", "办公室", "会议")) {
                placeOffice(blocks, origin, facing, interior, room.floorY() + 1, type);
            } else if (containsAny(type, "workshop", "forge", "craft", "工坊", "工作间", "锻造")) {
                placeWorkshop(blocks, origin, facing, interior, room.floorY() + 1, type);
            } else if (containsAny(type, "storage", "pantry", "warehouse", "储藏", "仓库")) {
                placeStorage(blocks, origin, facing, interior, room.floorY() + 1, type);
            }
            int lightY = room.floorY() + room.height();
            List<Cell> lights = lightCells(interior);
            for (Cell light : lights) put(blocks, origin, facing, light, lightY, Blocks.SHROOMLIGHT.defaultBlockState());
        }
    }

    private static List<Cell> lightCells(List<Cell> interior) {
        if (interior.isEmpty()) return List.of();
        double centerX = interior.stream().mapToInt(Cell::x).average().orElse(0);
        double centerZ = interior.stream().mapToInt(Cell::z).average().orElse(0);
        Cell center = interior.stream()
                .min((a, b) -> Double.compare(
                        Math.abs(a.x() - centerX) + Math.abs(a.z() - centerZ),
                        Math.abs(b.x() - centerX) + Math.abs(b.z() - centerZ)
                ))
                .orElse(interior.get(0));
        return List.of(center);
    }

    private static boolean containsAny(String value, String... markers) {
        for (String marker : markers) if (value.contains(marker)) return true;
        return false;
    }

    private static void placeBedroom(Map<BlockPos, BlockState> blocks, BlockPos origin, Direction facing,
                                     List<Cell> cells, int y, String type) {
        Set<Cell> available = new HashSet<>(cells);
        Cell center = geometricCenter(cells);
        placeBed(blocks, origin, facing, center, y);
        putOffset(blocks, origin, facing, available, center, -2, 0, y, Blocks.BARREL.defaultBlockState());
        putOffset(blocks, origin, facing, available, center, 2, 0, y, Blocks.BOOKSHELF.defaultBlockState());
        if (variant(type, center, 3) == 0) {
            putOffset(blocks, origin, facing, available, center, -2, 0, y + 1, Blocks.FLOWER_POT.defaultBlockState());
        } else {
            putOffset(blocks, origin, facing, available, center, 2, 0, y + 1, Blocks.LANTERN.defaultBlockState());
        }
    }

    private static void placeLibrary(Map<BlockPos, BlockState> blocks, BlockPos origin, Direction facing,
                                     List<Cell> cells, int y, String type) {
        Set<Cell> available = new HashSet<>(cells);
        Cell center = geometricCenter(cells);
        placeSparse(blocks, origin, facing, cells, y, Blocks.BOOKSHELF.defaultBlockState(), 5);
        put(blocks, origin, facing, center, y, Blocks.LECTERN.defaultBlockState());
        addSeat(blocks, origin, facing, available, center, Direction.SOUTH, y);
        if (cells.size() >= 20) {
            putOffset(blocks, origin, facing, available, center, 2, 0, y, Blocks.CHISELED_BOOKSHELF.defaultBlockState());
            putOffset(blocks, origin, facing, available, center, -2, 0, y, Blocks.CARTOGRAPHY_TABLE.defaultBlockState());
        }
    }

    private static void placeKitchen(Map<BlockPos, BlockState> blocks, BlockPos origin, Direction facing,
                                     List<Cell> cells, int y, String type) {
        Set<Cell> available = new HashSet<>(cells);
        Cell center = geometricCenter(cells);
        put(blocks, origin, facing, center, y, Blocks.CRAFTING_TABLE.defaultBlockState());
        putOffset(blocks, origin, facing, available, center, -2, 0, y, Blocks.BARREL.defaultBlockState());
        putOffset(blocks, origin, facing, available, center, 2, 0, y, Blocks.FURNACE.defaultBlockState());
        putOffset(blocks, origin, facing, available, center, 0, 2, y, Blocks.SMOKER.defaultBlockState());
        if (cells.size() >= 20) {
            putOffset(blocks, origin, facing, available, center, 0, -2, y, Blocks.CAULDRON.defaultBlockState());
        }
    }

    private static void placeLivingRoom(Map<BlockPos, BlockState> blocks, BlockPos origin, Direction facing,
                                        List<Cell> cells, int y, String type) {
        Set<Cell> available = new HashSet<>(cells);
        Cell center = geometricCenter(cells);
        placeSeating(blocks, origin, facing, cells, y);
        int variant = variant(type, center, 3);
        BlockState accent = variant == 0 ? Blocks.BOOKSHELF.defaultBlockState()
                : variant == 1 ? Blocks.JUKEBOX.defaultBlockState() : Blocks.CHEST.defaultBlockState();
        putOffset(blocks, origin, facing, available, center, -3, 0, y, accent);
        if (cells.size() >= 24) {
            putOffset(blocks, origin, facing, available, center, 3, 0, y, Blocks.FLOWER_POT.defaultBlockState());
        }
    }

    private static void placeBathroom(Map<BlockPos, BlockState> blocks, BlockPos origin, Direction facing,
                                      List<Cell> cells, int y, String type) {
        Set<Cell> available = new HashSet<>(cells);
        Cell center = geometricCenter(cells);
        put(blocks, origin, facing, center, y, Blocks.CAULDRON.defaultBlockState());
        putOffset(blocks, origin, facing, available, center, 2, 0, y, Blocks.QUARTZ_STAIRS.defaultBlockState());
        putOffset(blocks, origin, facing, available, center, -2, 0, y, Blocks.BARREL.defaultBlockState());
        if (cells.size() >= 16) {
            putOffset(blocks, origin, facing, available, center, 0, 2, y, Blocks.WHITE_CARPET.defaultBlockState());
        }
    }

    private static void placeGallery(Map<BlockPos, BlockState> blocks, BlockPos origin, Direction facing,
                                     List<Cell> cells, int y, String type) {
        Set<Cell> available = new HashSet<>(cells);
        Cell center = geometricCenter(cells);
        put(blocks, origin, facing, center, y, Blocks.SMOOTH_STONE.defaultBlockState());
        put(blocks, origin, facing, center, y + 1, Blocks.FLOWER_POT.defaultBlockState());
        putOffset(blocks, origin, facing, available, center, 4, 0, y, Blocks.QUARTZ_PILLAR.defaultBlockState());
        putOffset(blocks, origin, facing, available, center, -4, 0, y, Blocks.CUT_COPPER.defaultBlockState());
        putOffset(blocks, origin, facing, available, center, 0, 4, y, Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
    }

    private static void placeTables(Map<BlockPos, BlockState> blocks, BlockPos origin, Direction facing,
                                    List<Cell> cells, int y, int spacing) {
        Set<Cell> available = new HashSet<>(cells);
        Cell center = geometricCenter(cells);
        int minX = cells.stream().mapToInt(Cell::x).min().orElse(center.x());
        int maxX = cells.stream().mapToInt(Cell::x).max().orElse(center.x());
        int minZ = cells.stream().mapToInt(Cell::z).min().orElse(center.z());
        int maxZ = cells.stream().mapToInt(Cell::z).max().orElse(center.z());
        boolean longX = maxX - minX >= maxZ - minZ;
        List<Cell> tables = new ArrayList<>();
        tables.add(center);
        if (cells.size() >= 30) {
            Cell second = new Cell(center.x() + (longX ? 4 : 0), center.z() + (longX ? 0 : 4));
            if (available.contains(second)) tables.add(second);
        }
        for (Cell table : tables) {
            put(blocks, origin, facing, table, y, Blocks.OAK_FENCE.defaultBlockState());
            put(blocks, origin, facing, table, y + 1, Blocks.OAK_PRESSURE_PLATE.defaultBlockState());
            Direction first = longX ? Direction.NORTH : Direction.WEST;
            addSeat(blocks, origin, facing, available, table, first, y);
            addSeat(blocks, origin, facing, available, table, first.getOpposite(), y);
        }
    }

    private static void placeSeating(Map<BlockPos, BlockState> blocks, BlockPos origin, Direction facing,
                                     List<Cell> cells, int y) {
        Set<Cell> available = new HashSet<>(cells);
        Cell center = geometricCenter(cells);
        put(blocks, origin, facing, center, y, Blocks.OAK_SLAB.defaultBlockState());
        addSeat(blocks, origin, facing, available, center, Direction.NORTH, y);
        addSeat(blocks, origin, facing, available, center, Direction.SOUTH, y);
        if (cells.size() >= 20) addSeat(blocks, origin, facing, available, center, Direction.EAST, y);
    }

    private static void placeReception(Map<BlockPos, BlockState> blocks, BlockPos origin, Direction facing,
                                       List<Cell> cells, int y, String type) {
        Set<Cell> available = new HashSet<>(cells);
        Cell center = geometricCenter(cells);
        put(blocks, origin, facing, center, y, Blocks.BARREL.defaultBlockState());
        if (cells.size() > 6) {
            addSeat(blocks, origin, facing, available, center, Direction.SOUTH, y);
            putOffset(blocks, origin, facing, available, center, 2, 0, y, Blocks.LECTERN.defaultBlockState());
        }
        if (cells.size() >= 20) {
            putOffset(blocks, origin, facing, available, center, -2, 0, y, Blocks.POTTED_AZALEA.defaultBlockState());
        }
    }

    private static void placeOffice(Map<BlockPos, BlockState> blocks, BlockPos origin, Direction facing,
                                    List<Cell> cells, int y, String type) {
        Set<Cell> available = new HashSet<>(cells);
        Cell center = geometricCenter(cells);
        put(blocks, origin, facing, center, y, Blocks.CARTOGRAPHY_TABLE.defaultBlockState());
        addSeat(blocks, origin, facing, available, center, Direction.SOUTH, y);
        putOffset(blocks, origin, facing, available, center, -2, 0, y, Blocks.BOOKSHELF.defaultBlockState());
        putOffset(blocks, origin, facing, available, center, 2, 0, y, Blocks.BARREL.defaultBlockState());
        if (containsAny(type, "meeting", "conference", "会议")) {
            addSeat(blocks, origin, facing, available, center, Direction.NORTH, y);
            addSeat(blocks, origin, facing, available, center, Direction.EAST, y);
        }
    }

    private static void placeWorkshop(Map<BlockPos, BlockState> blocks, BlockPos origin, Direction facing,
                                      List<Cell> cells, int y, String type) {
        Set<Cell> available = new HashSet<>(cells);
        Cell center = geometricCenter(cells);
        put(blocks, origin, facing, center, y, Blocks.CRAFTING_TABLE.defaultBlockState());
        putOffset(blocks, origin, facing, available, center, -2, 0, y, Blocks.ANVIL.defaultBlockState());
        putOffset(blocks, origin, facing, available, center, 2, 0, y, Blocks.SMITHING_TABLE.defaultBlockState());
        putOffset(blocks, origin, facing, available, center, 0, 2, y, Blocks.BLAST_FURNACE.defaultBlockState());
        putOffset(blocks, origin, facing, available, center, 0, -2, y, Blocks.BARREL.defaultBlockState());
    }

    private static void placeStorage(Map<BlockPos, BlockState> blocks, BlockPos origin, Direction facing,
                                     List<Cell> cells, int y, String type) {
        Set<Cell> available = new HashSet<>(cells);
        Cell center = geometricCenter(cells);
        putOffset(blocks, origin, facing, available, center, -2, 0, y, Blocks.BARREL.defaultBlockState());
        putOffset(blocks, origin, facing, available, center, 2, 0, y, Blocks.CHEST.defaultBlockState());
        putOffset(blocks, origin, facing, available, center, 0, 2, y, Blocks.BARREL.defaultBlockState());
        if (cells.size() >= 20) {
            putOffset(blocks, origin, facing, available, center, 0, -2, y, Blocks.COMPOSTER.defaultBlockState());
        }
    }

    private static Cell geometricCenter(List<Cell> cells) {
        double centerX = cells.stream().mapToInt(Cell::x).average().orElse(0);
        double centerZ = cells.stream().mapToInt(Cell::z).average().orElse(0);
        return cells.stream()
                .min((a, b) -> Double.compare(
                        Math.abs(a.x() - centerX) + Math.abs(a.z() - centerZ),
                        Math.abs(b.x() - centerX) + Math.abs(b.z() - centerZ)
                ))
                .orElse(cells.get(0));
    }

    private static void addSeat(Map<BlockPos, BlockState> blocks, BlockPos origin, Direction facing,
                                Set<Cell> available, Cell center, Direction direction, int y) {
        Cell seat = new Cell(center.x() + direction.getStepX() * 2, center.z() + direction.getStepZ() * 2);
        if (!available.contains(seat)) return;
        BlockState state = applyFacing(orient(Blocks.OAK_STAIRS.defaultBlockState(), direction), facing);
        put(blocks, origin, facing, seat, y, state);
    }

    private static void putOffset(Map<BlockPos, BlockState> blocks, BlockPos origin, Direction facing,
                                  Set<Cell> available, Cell center, int offsetX, int offsetZ,
                                  int y, BlockState state) {
        Cell target = new Cell(center.x() + offsetX, center.z() + offsetZ);
        if (available.contains(target)) put(blocks, origin, facing, target, y, state);
    }

    private static int variant(String type, Cell center, int count) {
        return Math.floorMod(type.hashCode() * 31 + center.x() * 17 + center.z(), count);
    }

    private static void finalizeEntrance(Map<BlockPos, BlockState> blocks, BlockPos origin, Direction facing,
                                         Entrance entrance, Set<Cell> footprint, int floorY, BlockState trim) {
        Direction inward = entrance.localFacing().getOpposite();
        for (int distance = 0; distance <= 4; distance++) {
            Cell passage = new Cell(entrance.x() + inward.getStepX() * distance,
                    entrance.z() + inward.getStepZ() * distance);
            if (!footprint.contains(passage)) break;
            for (int y = floorY + 1; y <= floorY + 3; y++) blocks.remove(local(origin, facing, passage.x(), y, passage.z()));
        }
        Cell door = new Cell(entrance.x(), entrance.z());
        addDoorFrame(blocks, origin, facing, door, inward, floorY, footprint, trim);
        BlockPos lowerPos = local(origin, facing, door.x(), floorY + 1, door.z());
        BlockState lower = entrance.block()
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER);
        blocks.put(lowerPos, lower);
        blocks.put(lowerPos.above(), lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
    }

    private static void addDoorFrame(Map<BlockPos, BlockState> blocks, BlockPos origin, Direction facing,
                                     Cell door, Direction inward, int floorY, Set<Cell> footprint, BlockState trim) {
        Direction side = inward.getClockWise();
        for (int sign : new int[]{-1, 1}) {
            Cell post = new Cell(door.x() + side.getStepX() * sign, door.z() + side.getStepZ() * sign);
            if (!footprint.contains(post)) continue;
            for (int y = floorY + 1; y <= floorY + 3; y++) {
                put(blocks, origin, facing, post, y, trim);
            }
        }
        put(blocks, origin, facing, door, floorY + 3, trim);
    }

    private void compileRoof(Map<BlockPos, BlockState> blocks, BlockPos origin, Direction facing,
                             Set<Cell> footprint, int roofBaseY, JsonObject roof, Palette palette) {
        String style = roof.get("style").getAsString().toLowerCase(Locale.ROOT);
        BlockState roofState = roof.has("block") ? block(roof.get("block").getAsString()) : palette.roof();
        Set<Cell> expanded = expandFootprint(footprint);
        if ("flat".equals(style)) {
            for (Cell cell : expanded) put(blocks, origin, facing, cell, roofBaseY, roofState);
            return;
        }
        Map<Cell, Integer> depth = inwardDepth(expanded);
        boolean terraced = "terraced".equals(style);
        for (Map.Entry<Cell, Integer> entry : depth.entrySet()) {
            int rise = terraced ? (entry.getValue() + 1) / 2 : entry.getValue();
            int y = roofBaseY + Math.max(0, rise - 1);
            BlockState surface = orientRoofSurface(roofState, entry.getKey(), entry.getValue(), depth, facing);
            put(blocks, origin, facing, entry.getKey(), y, surface);
        }
    }

    private static BlockState orientRoofSurface(BlockState state, Cell cell, int cellDepth,
                                                Map<Cell, Integer> depths, Direction worldFacing) {
        if (!state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) return state;
        Direction localInward = null;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            Cell next = new Cell(cell.x() + direction.getStepX(), cell.z() + direction.getStepZ());
            if (depths.getOrDefault(next, 0) > cellDepth) {
                localInward = direction;
                break;
            }
        }
        return localInward == null ? state : applyFacing(orient(state, localInward), worldFacing);
    }

    private static Map<Cell, Integer> inwardDepth(Set<Cell> cells) {
        Map<Cell, Integer> result = new LinkedHashMap<>();
        Set<Cell> remaining = new HashSet<>(cells);
        int depth = 1;
        while (!remaining.isEmpty()) {
            Set<Cell> edge = boundary(remaining);
            if (edge.isEmpty()) break;
            for (Cell cell : edge) result.put(cell, depth);
            remaining.removeAll(edge);
            depth++;
        }
        return result;
    }

    private static Set<Cell> expandFootprint(Set<Cell> footprint) {
        Set<Cell> expanded = new HashSet<>(footprint);
        for (Cell cell : footprint) for (int[] d : CARDINALS) expanded.add(new Cell(cell.x() + d[0], cell.z() + d[1]));
        return expanded;
    }

    /**
     * 读取单层房间；房间超出轮廓则裁剪、与已有房间重叠则裁掉重叠部分、裁空则丢弃该房间，单个房间有问题不影响整张计划。
     */
    private static List<Room> readRooms(JsonArray array, Set<Cell> footprint, int floorY, int height, int maxSize) {
        List<Room> rooms = new ArrayList<>();
        if (array == null || array.isEmpty()) return rooms;
        if (array.size() > MAX_ROOMS_PER_FLOOR) {
            LOGGER.warn("【AI建造】单层房间数量 {} 超过上限 {}，只取前 {} 个",
                    array.size(), MAX_ROOMS_PER_FLOOR, MAX_ROOMS_PER_FLOOR);
        }
        Set<Cell> occupied = new HashSet<>();
        int index = 0;
        for (JsonElement element : array) {
            if (rooms.size() >= MAX_ROOMS_PER_FLOOR) break;
            index++;
            JsonObject object = element.getAsJsonObject();
            String type = string(object, "type", 48).toLowerCase(Locale.ROOT);
            Set<Cell> cells;
            try {
                List<Point> points = polygon(object.getAsJsonArray("polygon"), maxSize, "room polygon");
                validateOrthogonal(points);
                cells = rasterize(points);
            } catch (RuntimeException error) {
                LOGGER.warn("【AI建造】第 {} 个房间 '{}' 的多边形无效（{}），已忽略这个房间", index, type,
                        error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
                continue;
            }
            Set<Cell> clipped = new HashSet<>(cells);
            clipped.retainAll(footprint);
            if (clipped.isEmpty()) {
                LOGGER.warn("【AI建造】第 {} 个房间 '{}' 完全落在建筑轮廓外，已忽略", index, type);
                continue;
            }
            if (clipped.size() < cells.size()) {
                LOGGER.warn("【AI建造】第 {} 个房间 '{}' 超出轮廓，已裁剪到 {} 格（原 {} 格）",
                        index, type, clipped.size(), cells.size());
            }
            Set<Cell> overlap = new HashSet<>(clipped);
            overlap.retainAll(occupied);
            if (!overlap.isEmpty()) {
                clipped.removeAll(overlap);
                if (clipped.isEmpty()) {
                    LOGGER.warn("【AI建造】第 {} 个房间 '{}' 与已有房间完全重叠，已忽略", index, type);
                    continue;
                }
                LOGGER.warn("【AI建造】第 {} 个房间 '{}' 与已有房间重叠，已裁掉 {} 格", index, type, overlap.size());
            }
            occupied.addAll(clipped);
            rooms.add(new Room(type, clipped, floorY, height));
        }
        return rooms;
    }

    /**
     * 读取入口；不在外墙时吸附到最近墙格，朝向与反向都指向室内（凹角处）时自动挑一个真正朝外的方向。
     * 仅当轮廓没有任何外墙、或门材料不是门方块时报错。
     */
    private static Entrance entrance(JsonObject object, Set<Cell> footprint, Direction worldFacing, BlockState door) {
        if (object == null) throw new IllegalArgumentException("缺少入口设计");
        if (!(door.getBlock() instanceof DoorBlock)) throw new IllegalArgumentException("door 材料必须是门方块");
        int x = integer(object, "x");
        int z = integer(object, "z");
        Set<Cell> boundary = boundary(footprint);
        Cell cell = new Cell(x, z);
        if (!boundary.contains(cell)) {
            Cell nearest = null;
            long best = Long.MAX_VALUE;
            for (Cell candidate : boundary) {
                long dx = candidate.x() - x;
                long dz = candidate.z() - z;
                long distance = dx * dx + dz * dz;
                if (distance < best) {
                    best = distance;
                    nearest = candidate;
                }
            }
            if (nearest == null) throw new IllegalArgumentException("建筑轮廓没有可用外墙，无法放置入口");
            LOGGER.warn("【AI建造】入口 ({},{}) 不在外墙，已吸附到最近的墙格 ({},{})",
                    x, z, nearest.x(), nearest.z());
            x = nearest.x();
            z = nearest.z();
        }
        Direction localFacing = direction(string(object, "facing", 8));
        Cell outside = new Cell(x + localFacing.getStepX(), z + localFacing.getStepZ());
        if (footprint.contains(outside)) {
            localFacing = localFacing.getOpposite();
            outside = new Cell(x + localFacing.getStepX(), z + localFacing.getStepZ());
        }
        if (footprint.contains(outside)) {
            Direction outward = null;
            for (Direction candidate : Direction.Plane.HORIZONTAL) {
                Cell probe = new Cell(x + candidate.getStepX(), z + candidate.getStepZ());
                if (!footprint.contains(probe)) {
                    outward = candidate;
                    break;
                }
            }
            if (outward == null) throw new IllegalArgumentException("无法确定入口朝外方向");
            LOGGER.warn("【AI建造】入口 ({},{}) 两侧都在室内，已改用朝外方向 {}", x, z, outward);
            localFacing = outward;
        }
        return new Entrance(x, z, localFacing, applyFacing(orient(door, localFacing.getOpposite()), worldFacing));
    }

    private static void compileRoomWalls(Map<BlockPos, BlockState> blocks, BlockPos origin, Direction facing,
                                         List<Room> rooms, Set<Cell> footprint, Palette palette) {
        Map<Cell, Integer> owners = new HashMap<>();
        for (int i = 0; i < rooms.size(); i++) for (Cell cell : rooms.get(i).cells()) owners.put(cell, i);
        Map<String, List<Cell>> sharedWalls = new LinkedHashMap<>();
        for (Map.Entry<Cell, Integer> entry : owners.entrySet()) {
            Cell cell = entry.getKey();
            for (int[] d : new int[][]{{1, 0}, {0, 1}}) {
                Cell neighbor = new Cell(cell.x() + d[0], cell.z() + d[1]);
                Integer other = owners.get(neighbor);
                if (other == null || other.equals(entry.getValue())) continue;
                int low = Math.min(entry.getValue(), other);
                int high = Math.max(entry.getValue(), other);
                sharedWalls.computeIfAbsent(low + ":" + high, ignored -> new ArrayList<>()).add(neighbor);
            }
        }
        for (Map.Entry<String, List<Cell>> entry : sharedWalls.entrySet()) {
            String[] pair = entry.getKey().split(":");
            Room room = rooms.get(Integer.parseInt(pair[0]));
            List<Cell> wall = entry.getValue();
            wall.sort((a, b) -> Integer.compare(a.x() * 257 + a.z(), b.x() * 257 + b.z()));
            Cell opening = wall.get(wall.size() / 2);
            for (Cell cell : wall) {
                if (cell.equals(opening)) continue;
                for (int y = room.floorY() + 1; y <= room.floorY() + room.height() - 1; y++) {
                    put(blocks, origin, facing, cell, y, palette.wall());
                }
            }
        }
    }

    private static Palette palette(JsonObject object) {
        if (object == null) throw new IllegalArgumentException("缺少建筑材料方案");
        BlockState window = block(field(object, "window"));
        if (window.getBlock() instanceof IronBarsBlock) window = Blocks.GLASS.defaultBlockState();
        return new Palette(block(field(object, "foundation")), block(field(object, "wall")),
                block(field(object, "trim")), block(field(object, "floor")), block(field(object, "roof")),
                window, block(field(object, "door")));
    }

    private static String field(JsonObject object, String key) {
        return string(object, key, 128);
    }

    private static List<Point> polygon(JsonArray array, int maxSize, String name) {
        if (array == null || array.size() < 4 || array.size() > 32) throw new IllegalArgumentException(name + " 顶点数量必须为 4-32");
        List<Point> points = new ArrayList<>();
        for (JsonElement element : array) {
            JsonArray pair = element.getAsJsonArray();
            if (pair.size() != 2) throw new IllegalArgumentException(name + " 坐标必须是 [x,z]");
            int x = exactInt(pair.get(0));
            int z = exactInt(pair.get(1));
            if (Math.abs(x) > maxSize || Math.abs(z) > maxSize) throw new IllegalArgumentException(name + " 超出尺寸限制");
            points.add(new Point(x, z));
        }
        if (points.get(0).equals(points.get(points.size() - 1))) points.remove(points.size() - 1);
        return points;
    }

    private static void validateOrthogonal(List<Point> points) {
        if (points.size() < 4) throw new IllegalArgumentException("轮廓顶点不足");
        for (int i = 0; i < points.size(); i++) {
            Point a = points.get(i);
            Point b = points.get((i + 1) % points.size());
            if (a.equals(b) || a.x() != b.x() && a.z() != b.z()) throw new IllegalArgumentException("轮廓必须是无重复点的正交多边形");
        }
        for (int i = 0; i < points.size(); i++) {
            Point a1 = points.get(i);
            Point a2 = points.get((i + 1) % points.size());
            for (int j = i + 1; j < points.size(); j++) {
                if (j == i || j == (i + 1) % points.size() || (j + 1) % points.size() == i) continue;
                if (segmentsIntersect(a1, a2, points.get(j), points.get((j + 1) % points.size()))) {
                    throw new IllegalArgumentException("轮廓不能自相交");
                }
            }
        }
    }

    private static boolean segmentsIntersect(Point a, Point b, Point c, Point d) {
        if (a.x() == b.x() && c.z() == d.z()) {
            return between(a.x(), c.x(), d.x()) && between(c.z(), a.z(), b.z());
        }
        if (a.z() == b.z() && c.x() == d.x()) {
            return between(c.x(), a.x(), b.x()) && between(a.z(), c.z(), d.z());
        }
        return false;
    }

    private static boolean between(int value, int a, int b) {
        return value >= Math.min(a, b) && value <= Math.max(a, b);
    }

    private static Set<Cell> rasterize(List<Point> polygon) {
        int minX = polygon.stream().mapToInt(Point::x).min().orElseThrow();
        int maxX = polygon.stream().mapToInt(Point::x).max().orElseThrow();
        int minZ = polygon.stream().mapToInt(Point::z).min().orElseThrow();
        int maxZ = polygon.stream().mapToInt(Point::z).max().orElseThrow();
        Set<Cell> cells = new HashSet<>();
        for (int x = minX; x < maxX; x++) {
            for (int z = minZ; z < maxZ; z++) if (inside(x + 0.5, z + 0.5, polygon)) cells.add(new Cell(x, z));
        }
        return cells;
    }

    private static boolean inside(double x, double z, List<Point> polygon) {
        boolean inside = false;
        for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
            Point a = polygon.get(i);
            Point b = polygon.get(j);
            if ((a.z() > z) != (b.z() > z) && x < (double) (b.x() - a.x()) * (z - a.z()) / (b.z() - a.z()) + a.x()) inside = !inside;
        }
        return inside;
    }

    private static boolean isSimpleRectangle(List<Point> points) {
        if (points.size() != 4) return false;
        int minX = points.stream().mapToInt(Point::x).min().orElse(0);
        int maxX = points.stream().mapToInt(Point::x).max().orElse(0);
        int minZ = points.stream().mapToInt(Point::z).min().orElse(0);
        int maxZ = points.stream().mapToInt(Point::z).max().orElse(0);
        return points.stream().allMatch(point ->
                (point.x() == minX || point.x() == maxX) && (point.z() == minZ || point.z() == maxZ));
    }

    private static Set<Cell> boundary(Set<Cell> cells) {
        Set<Cell> result = new HashSet<>();
        for (Cell cell : cells) if (exposedSides(cell, cells) > 0) result.add(cell);
        return result;
    }

    private static int exposedSides(Cell cell, Set<Cell> cells) {
        int count = 0;
        for (int[] d : CARDINALS) if (!cells.contains(new Cell(cell.x() + d[0], cell.z() + d[1]))) count++;
        return count;
    }

    private static boolean windowPosition(Cell cell, Set<Cell> footprint) {
        if (exposedSides(cell, footprint) != 1) return false;
        boolean exposedX = !footprint.contains(new Cell(cell.x() - 1, cell.z()))
                || !footprint.contains(new Cell(cell.x() + 1, cell.z()));
        int tangent = exposedX ? cell.z() : cell.x();
        int position = Math.floorMod(tangent, 6);
        return position == 2 || position == 3;
    }

    private static void placeSparse(Map<BlockPos, BlockState> blocks, BlockPos origin, Direction facing,
                                    List<Cell> cells, int y, BlockState state, int spacing) {
        for (int i = spacing / 2; i < cells.size(); i += spacing) put(blocks, origin, facing, cells.get(i), y, state);
    }

    private static void placeCenter(Map<BlockPos, BlockState> blocks, BlockPos origin, Direction facing,
                                    List<Cell> cells, int y, BlockState state) {
        put(blocks, origin, facing, cells.get(cells.size() / 2), y, state);
    }

    private static void placeBed(Map<BlockPos, BlockState> blocks, BlockPos origin, Direction facing, Cell cell, int y) {
        BlockState state = Blocks.RED_BED.defaultBlockState();
        Direction local = Direction.SOUTH;
        BlockPos footPos = local(origin, facing, cell.x(), y, cell.z());
        BlockState foot = applyFacing(orient(state, local), facing).setValue(BlockStateProperties.BED_PART, BedPart.FOOT);
        Direction world = foot.getValue(BlockStateProperties.HORIZONTAL_FACING);
        blocks.put(footPos, foot);
        blocks.put(footPos.relative(world), foot.setValue(BlockStateProperties.BED_PART, BedPart.HEAD));
    }

    private static BlockState orient(BlockState state, Direction direction) {
        return state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                ? state.setValue(BlockStateProperties.HORIZONTAL_FACING, direction) : state;
    }

    private static BlockState applyFacing(BlockState state, Direction facing) {
        if (!state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) return state;
        Direction local = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        Direction world = switch (local) {
            case NORTH -> facing.getOpposite();
            case SOUTH -> facing;
            case EAST -> facing.getClockWise();
            case WEST -> facing.getCounterClockWise();
            default -> local;
        };
        return state.setValue(BlockStateProperties.HORIZONTAL_FACING, world);
    }

    private static Direction direction(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "east" -> Direction.EAST;
            case "west" -> Direction.WEST;
            default -> throw new IllegalArgumentException("入口朝向无效");
        };
    }

    private static BlockState block(String value) {
        ResourceLocation id = ResourceLocation.tryParse(value.contains(":") ? value : "minecraft:" + value);
        Block block = id == null ? null : ForgeRegistries.BLOCKS.getValue(id);
        if (block == null || block == Blocks.AIR || block == Blocks.BEDROCK
                || block == Blocks.BARRIER || block == Blocks.COMMAND_BLOCK || block == Blocks.STRUCTURE_BLOCK
                || block == Blocks.JIGSAW) throw new IllegalArgumentException("建筑材料无效: " + value);
        return block.defaultBlockState();
    }

    private static int integer(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) throw new IllegalArgumentException("缺少字段 " + key);
        return exactInt(object.get(key));
    }

    private static int exactInt(JsonElement element) {
        double value = element.getAsDouble();
        if (!Double.isFinite(value) || value != Math.rint(value)) throw new IllegalArgumentException("坐标必须为整数");
        return (int) value;
    }

    private static String string(JsonObject object, String key, int maxLength) {
        if (object == null || !object.has(key) || !object.get(key).isJsonPrimitive()) throw new IllegalArgumentException("缺少字段 " + key);
        String value = object.get(key).getAsString().trim();
        if (value.isEmpty() || value.length() > maxLength) throw new IllegalArgumentException("字段无效 " + key);
        return value;
    }

    private static void checkLimit(Map<BlockPos, BlockState> blocks, int maxBlocks) {
        if (blocks.size() > maxBlocks) throw new IllegalArgumentException("建筑计划超过最大方块数量 " + maxBlocks);
    }

    private static void validateBounds(Map<BlockPos, BlockState> blocks, int maxSize) {
        if (blocks.isEmpty()) return;
        int minX = blocks.keySet().stream().mapToInt(BlockPos::getX).min().orElseThrow();
        int maxX = blocks.keySet().stream().mapToInt(BlockPos::getX).max().orElseThrow();
        int minY = blocks.keySet().stream().mapToInt(BlockPos::getY).min().orElseThrow();
        int maxY = blocks.keySet().stream().mapToInt(BlockPos::getY).max().orElseThrow();
        int minZ = blocks.keySet().stream().mapToInt(BlockPos::getZ).min().orElseThrow();
        int maxZ = blocks.keySet().stream().mapToInt(BlockPos::getZ).max().orElseThrow();
        if ((long) maxX - minX + 1 > maxSize || (long) maxY - minY + 1 > maxSize
                || (long) maxZ - minZ + 1 > maxSize) {
            throw new IllegalArgumentException("最终建筑包围盒超出尺寸限制 " + maxSize);
        }
    }

    private static void put(Map<BlockPos, BlockState> blocks, BlockPos origin, Direction facing,
                            Cell cell, int y, BlockState state) {
        blocks.put(local(origin, facing, cell.x(), y, cell.z()), state);
    }

    private static BlockPos local(BlockPos origin, Direction facing, int x, int y, int z) {
        Direction right = facing.getClockWise();
        return origin.offset(right.getStepX() * x + facing.getStepX() * z, y,
                right.getStepZ() * x + facing.getStepZ() * z);
    }

    public record CompiledPlan(String name, Map<BlockPos, BlockState> blocks) {
    }

    private record Point(int x, int z) {
    }

    private record Cell(int x, int z) {
    }

    private record Room(String type, Set<Cell> cells, int floorY, int height) {
    }

    private record Entrance(int x, int z, Direction localFacing, BlockState block) {
    }

    private record Palette(BlockState foundation, BlockState wall, BlockState trim, BlockState floor,
                           BlockState roof, BlockState window, BlockState door) {
    }
}
