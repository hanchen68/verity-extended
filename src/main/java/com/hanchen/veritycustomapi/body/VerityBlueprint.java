package com.hanchen.veritycustomapi.body;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.block.state.properties.Property;
import varmite.verity.entity.verity.VerityEntity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 照结构文件（.nbt）盖房子。
 *
 * <p><b>用的是原版自带的模板系统</b>：{@code data/<ns>/structures/*.nbt} 由
 * {@code StructureTemplateManager} 读取，不需要任何第三方库。
 *
 * <p>盖法是逐块的：先算总材料，材料不够直接拒绝；够就按「先低后高」逐块放，
 * 够不到就走过去再放——所以她是真的在施工，而不是把整栋楼一次性刷出来。
 */
public final class VerityBlueprint {

    private static final int MAX_BLOCKS = 512;
    private static final double REACH = 4.5D;
    private static final String RESULT_KEY = "verity_ext_blueprint_result";

    private record Target(BlockPos pos, BlockState state, String itemId) {
    }

    private static final class Job {
        final String name;
        final Deque<Target> queue = new ArrayDeque<>();
        final List<String> problems = new ArrayList<>();
        int placed;
        int skipped;
        long startedAt;

        Job(String name, long startedAt) {
            this.name = name;
            this.startedAt = startedAt;
        }
    }

    private static final Map<VerityEntity, Job> JOBS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<VerityEntity, String> RESULTS = Collections.synchronizedMap(new WeakHashMap<>());

    private VerityBlueprint() {
    }

    /** 开始照图纸施工；origin 是结构的最小角。 */
    public static String start(VerityEntity verity, String structureId, BlockPos origin) {
        if (!(verity.level() instanceof ServerLevel level)) return "失败：不在服务端";
        ResourceLocation id = ResourceLocation.tryParse(structureId);
        if (id == null) return "失败：结构 ID 不合法";

        List<Target> targets;
        try {
            targets = readStructure(level, id, origin);
        } catch (RuntimeException | java.io.IOException error) {
            return "失败：读取结构 " + structureId + " 出错（" + error.getClass().getSimpleName() + "）";
        }
        if (targets == null) return "失败：找不到结构 " + structureId
                + "（应放在 data/" + id.getNamespace() + "/structures/" + id.getPath() + ".nbt）";
        if (targets.isEmpty()) return "失败：结构里没有可放置的方块";

        // 材料清单：先算总需求，不够就不开工
        Map<String, Integer> need = new LinkedHashMap<>();
        for (Target target : targets) need.merge(target.itemId(), 1, Integer::sum);
        List<String> missing = new ArrayList<>();
        for (var entry : need.entrySet()) {
            int have = VerityInventory.count(verity, entry.getKey());
            if (have < entry.getValue())
                missing.add(entry.getKey() + " 需要 " + entry.getValue() + "，有 " + have);
        }
        if (!missing.isEmpty()) return "失败：材料不够——" + String.join("；", missing);

        // 先低后高，避免上层挡住下层
        targets.sort((a, b) -> Integer.compare(a.pos().getY(), b.pos().getY()));
        Job job = new Job(structureId, level.getGameTime());
        job.queue.addAll(targets);
        JOBS.put(verity, job);
        return "开始照 " + structureId + " 施工：共 " + targets.size() + " 块";
    }

    /**
     * 直接读资源包里的结构 NBT。
     *
     * <p>不用 StructureTemplate：1.20.1 里它的 palettes 与 StructureBlockInfo 字段都是 private，
     * 外部读不到；而结构文件格式本身是公开的（size / palette / blocks）。
     *
     * @return null 表示文件不存在
     */
    private static List<Target> readStructure(ServerLevel level, ResourceLocation id, BlockPos origin)
            throws java.io.IOException {
        ResourceLocation file = new ResourceLocation(id.getNamespace(), "structures/" + id.getPath() + ".nbt");
        var server = level.getServer();
        java.util.Optional<Resource> resource = server.getResourceManager().getResource(file);
        if (resource.isEmpty()) return null;

        CompoundTag root;
        try (var stream = resource.get().open()) {
            root = NbtIo.readCompressed(stream);
        }
        ListTag sizeTag = root.getList("size", 3);
        if (sizeTag.size() == 3) {
            long volume = (long) sizeTag.getInt(0) * sizeTag.getInt(1) * sizeTag.getInt(2);
            if (volume > MAX_BLOCKS * 4L) throw new IllegalArgumentException("structure too large");
        }
        ListTag palette = root.getList("palette", 10);
        ListTag blocks = root.getList("blocks", 10);
        List<BlockState> states = new ArrayList<>();
        for (int i = 0; i < palette.size(); i++) states.add(parseState(palette.getCompound(i)));

        List<Target> targets = new ArrayList<>();
        for (int i = 0; i < blocks.size() && targets.size() < MAX_BLOCKS; i++) {
            CompoundTag entry = blocks.getCompound(i);
            ListTag posTag = entry.getList("pos", 3);
            if (posTag.size() != 3) continue;
            int index = entry.getInt("state");
            if (index < 0 || index >= states.size()) continue;
            BlockState state = states.get(index);
            if (state == null || state.isAir()) continue;
            ItemStack asItem = new ItemStack(state.getBlock().asItem());
            if (asItem.isEmpty()) continue;   // 水/火这类没有物品形态的跳过
            targets.add(new Target(origin.offset(posTag.getInt(0), posTag.getInt(1), posTag.getInt(2)),
                    state, BuiltInRegistries.ITEM.getKey(asItem.getItem()).toString()));
        }
        return targets;
    }

    /** palette 条目转 BlockState，含属性还原。 */
    private static BlockState parseState(CompoundTag entry) {
        ResourceLocation name = ResourceLocation.tryParse(entry.getString("Name"));
        if (name == null) return null;
        Block block = BuiltInRegistries.BLOCK.get(name);
        BlockState state = block.defaultBlockState();
        if (!entry.contains("Properties")) return state;
        CompoundTag properties = entry.getCompound("Properties");
        for (String key : properties.getAllKeys()) {
            Property<?> property = block.getStateDefinition().getProperty(key);
            if (property != null) state = withProperty(state, property, properties.getString(key));
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState withProperty(BlockState state,
                                                                    Property<T> property, String value) {
        return property.getValue(value).map(parsed -> state.setValue(property, parsed)).orElse(state);
    }

    /** 每 10 tick 推进：够得到就放，够不到就走过去。 */
    public static void tick(VerityEntity verity) {
        Job job = JOBS.get(verity);
        if (job == null) return;
        if (!(verity.level() instanceof ServerLevel level)) {
            finish(verity, "失败：维度变化，施工中断");
            return;
        }
        if (level.getGameTime() - job.startedAt > 20L * 60L * 20L) {
            finish(verity, "施工超时：已放 " + job.placed + " 块，跳过 " + job.skipped + " 块");
            return;
        }
        int budget = 4;
        while (budget-- > 0 && !job.queue.isEmpty()) {
            Target target = job.queue.peek();
            if (!level.hasChunkAt(target.pos())) return;
            double distance = verity.distanceToSqr(target.pos().getX() + 0.5D,
                    target.pos().getY() + 0.5D, target.pos().getZ() + 0.5D);
            if (distance > REACH * REACH) {
                // 走过去再放；导航自己会处理台阶和绕路
                verity.getNavigation().moveTo(target.pos().getX() + 0.5D,
                        target.pos().getY(), target.pos().getZ() + 0.5D, 1.2D);
                return;
            }
            job.queue.poll();
            BlockState current = level.getBlockState(target.pos());
            if (!current.canBeReplaced()) {
                job.skipped++;
                continue;
            }
            if (VerityInventory.count(verity, target.itemId()) <= 0) {
                job.skipped++;
                job.problems.add("缺少 " + target.itemId());
                continue;
            }
            if (level.setBlock(target.pos(), target.state(), 3)) {
                VerityInventory.extract(verity, target.itemId(), 1);
                job.placed++;
            } else {
                job.skipped++;
            }
        }
        if (job.queue.isEmpty()) {
            finish(verity, "施工完成：放了 " + job.placed + " 块，跳过 " + job.skipped + " 块"
                    + (job.problems.isEmpty() ? "" : "（" + String.join("；", job.problems) + "）"));
        }
    }

    public static boolean building(VerityEntity verity) {
        return JOBS.containsKey(verity);
    }

    public static String poll(VerityEntity verity) {
        return RESULTS.remove(verity);
    }

    public static String describe(VerityEntity verity) {
        Job job = JOBS.get(verity);
        return job == null ? "" : "施工中：" + job.name + "，还剩 " + job.queue.size() + " 块";
    }

    private static void finish(VerityEntity verity, String result) {
        JOBS.remove(verity);
        RESULTS.put(verity, result);
        verity.getPersistentData().putString(RESULT_KEY, result);
    }
}
