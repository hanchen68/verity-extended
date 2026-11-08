package com.hanchen.veritycustomapi.body;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import varmite.verity.entity.verity.VerityEntity;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 用熔炉烧东西。
 *
 * <p>和玩家一样：先把原料放进上格、燃料放下格，等烧完再把成品拿走。
 * 燃料按「能烧几件」估算，不够会补；烧完/超时都会给出真实结果。
 *
 * <p>只认 {@link AbstractFurnaceBlockEntity}（熔炉/高炉/烟熏炉），
 * 不碰箱子——那是储物，不是加工。
 */
public final class VeritySmelting {

    private static final String RESULT_KEY = "verity_ext_smelt_result";
    private static final long TIMEOUT_TICKS = 20L * 60L * 5L;
    /** 燃料优先顺序：耐烧的在前。 */
    private static final List<String> FUELS = List.of(
            "minecraft:coal", "minecraft:charcoal", "minecraft:coal_block",
            "minecraft:oak_planks", "minecraft:spruce_planks", "minecraft:birch_planks",
            "minecraft:oak_log", "minecraft:stick");

    private static final class Job {
        final BlockPos pos;
        final String inputId;
        int delivered;
        long startedAt;

        Job(BlockPos pos, String inputId, int delivered, long startedAt) {
            this.pos = pos;
            this.inputId = inputId;
            this.delivered = delivered;
            this.startedAt = startedAt;
        }
    }

    private static final Map<VerityEntity, Job> JOBS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<VerityEntity, String> RESULTS = Collections.synchronizedMap(new WeakHashMap<>());

    private VeritySmelting() {
    }

    /** 开始烧；返回一句话（失败以「失败」开头）。 */
    public static String start(VerityEntity verity, String inputId, int count) {
        if (!(verity.level() instanceof ServerLevel level)) return "失败：不在服务端";
        Container furnace = findFurnace(verity, level);
        if (furnace == null) return "失败：附近 4 格内没有熔炉";
        int want = Math.max(1, Math.min(16, count));
        int have = VerityInventory.count(verity, inputId);
        if (have <= 0) return "失败：背包里没有 " + inputId;
        int deliver = Math.min(want, have);

        ItemStack input = furnace.getItem(0);
        String expected = input.isEmpty() ? inputId
                : BuiltInRegistries.ITEM.getKey(input.getItem()).toString();
        if (!expected.equals(inputId))
            return "失败：熔炉上格已经有 " + expected + "，先等它烧完";

        ItemStack stack = takeFromInventory(verity, inputId, deliver);
        if (stack.isEmpty()) return "失败：取不出 " + inputId;
        if (!input.isEmpty()) stack.grow(input.getCount());
        furnace.setItem(0, stack);

        int fuelAdded = ensureFuel(verity, furnace, deliver);
        if (fuelAdded <= 0 && furnace.getItem(1).isEmpty())
            return "失败：没有燃料（需要煤、木炭或木板）";
        furnace.setChanged();

        JOBS.put(verity, new Job(furnacePos(level, furnace), inputId, deliver, level.getGameTime()));
        return "开始烧 " + deliver + " 个 " + inputId + (fuelAdded > 0 ? "（补了燃料）" : "（用现有燃料）");
    }

    /** 每 20 tick 推进：把成品收回背包；烧完就结束。 */
    public static void tick(VerityEntity verity) {
        Job job = JOBS.get(verity);
        if (job == null) return;
        if (!(verity.level() instanceof ServerLevel level)) {
            finish(verity, "失败：维度变化，熔炼中断");
            return;
        }
        BlockEntity entity = level.getBlockEntity(job.pos);
        if (!(entity instanceof Container furnace)) {
            finish(verity, "失败：熔炉不见了，熔炼中断");
            return;
        }
        ItemStack output = furnace.getItem(2);
        int collected = 0;
        if (!output.isEmpty()) {
            ItemStack leftover = VerityInventory.insert(verity, output.copy());
            collected = output.getCount() - leftover.getCount();
            if (leftover.isEmpty()) furnace.setItem(2, ItemStack.EMPTY);
            else furnace.setItem(2, leftover);
            furnace.setChanged();
        }
        boolean inputEmpty = furnace.getItem(0).isEmpty();
        boolean outputEmpty = furnace.getItem(2).isEmpty();
        if (inputEmpty && outputEmpty) {
            finish(verity, "烧好了：收进背包 " + job.delivered + " 个成品（原料 " + job.inputId + "）");
            return;
        }
        if (level.getGameTime() - job.startedAt > TIMEOUT_TICKS) {
            finish(verity, "熔炼超时，已收回 " + collected + " 个成品；剩余仍在熔炉里");
        }
    }

    public static boolean smelting(VerityEntity verity) {
        return JOBS.containsKey(verity);
    }

    public static String poll(VerityEntity verity) {
        return RESULTS.remove(verity);
    }

    public static String describe(VerityEntity verity) {
        Job job = JOBS.get(verity);
        return job == null ? "" : "熔炼中：" + job.inputId;
    }

    private static void finish(VerityEntity verity, String result) {
        JOBS.remove(verity);
        RESULTS.put(verity, result);
        verity.getPersistentData().putString(RESULT_KEY, result);
    }

    private static Container findFurnace(VerityEntity verity, ServerLevel level) {
        BlockPos origin = verity.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-4, -2, -4), origin.offset(4, 2, 4))) {
            if (!level.hasChunkAt(pos)) continue;
            BlockEntity entity = level.getBlockEntity(pos);
            if (entity instanceof AbstractFurnaceBlockEntity furnace) return furnace;
        }
        return null;
    }

    private static BlockPos furnacePos(ServerLevel level, Container furnace) {
        return furnace instanceof BlockEntity blockEntity ? blockEntity.getBlockPos() : BlockPos.ZERO;
    }

    /** 保证下格有足够燃料；返回补进去的燃料件数。 */
    private static int ensureFuel(VerityEntity verity, Container furnace, int smeltCount) {
        ItemStack current = furnace.getItem(1);
        // 一件煤能烧 8 件；现有燃料够就不补
        if (!current.isEmpty() && current.getCount() * 8 >= smeltCount) return 0;
        for (String fuelId : FUELS) {
            if (VerityInventory.count(verity, fuelId) <= 0) continue;
            int need = Math.max(1, (smeltCount - current.getCount() * 8 + 7) / 8);
            ItemStack fuel = takeFromInventory(verity, fuelId, Math.min(need, 8));
            if (fuel.isEmpty()) continue;
            if (!current.isEmpty()) fuel.grow(current.getCount());
            furnace.setItem(1, fuel);
            furnace.setChanged();
            return fuel.getCount();
        }
        return 0;
    }

    private static ItemStack takeFromInventory(VerityEntity verity, String itemId, int count) {
        int available = VerityInventory.count(verity, itemId);
        if (available <= 0) return ItemStack.EMPTY;
        int take = Math.min(count, available);
        var item = BuiltInRegistries.ITEM.get(new net.minecraft.resources.ResourceLocation(itemId));
        ItemStack stack = new ItemStack(item, take);
        VerityInventory.extract(verity, itemId, take);
        return stack;
    }
}
