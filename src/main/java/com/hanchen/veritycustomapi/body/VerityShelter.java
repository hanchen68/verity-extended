package com.hanchen.veritycustomapi.body;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import varmite.verity.entity.verity.VerityEntity;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 搭一个小庇护所。
 *
 * <p>尺寸 3×3、高 3、带屋顶，正面留一个门洞——刚好是「下雨了先躲一下」的量级，
 * 不是建筑作品。所有方块都在手能够到的 5 格内，所以她站在原地就能盖完。
 *
 * <p>材料不足会在开始前直接拒绝，不会盖到一半停住。
 */
public final class VerityShelter {

    private static final int PER_LAYER = 8;
    private static final int HEIGHT = 3;
    private static final Map<VerityEntity, Deque<BlockPos>> QUEUES =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<VerityEntity, String> MATERIAL = new WeakHashMap<>();

    private VerityShelter() {
    }

    /** 在自己周围盖；返回结果文本。 */
    public static String build(VerityEntity verity, String itemId) {
        if (!(verity.level() instanceof ServerLevel level)) return "失败：不在服务端";
        String material = itemId == null || itemId.isBlank() ? "minecraft:cobblestone" : itemId;
        ItemStack probe = firstStack(verity, material);
        if (probe == null) return "失败：背包里没有 " + material;
        if (!(probe.getItem() instanceof BlockItem)) return "失败：" + material + " 不是方块";

        BlockPos center = verity.blockPosition();
        Deque<BlockPos> plan = new ArrayDeque<>();
        for (int y = 0; y < HEIGHT; y++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;      // 中间留空，人站里面
                    if (y == 0 && dz == 1 && dx == 0) continue; // 正面门洞
                    plan.add(center.offset(dx, y, dz));
                }
            }
        }
        // 屋顶
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                plan.add(center.offset(dx, HEIGHT, dz));
            }
        }
        int needed = plan.size();
        int have = VerityInventory.count(verity, material);
        if (have < needed) return "失败：需要 " + needed + " 块 " + material + "，只有 " + have;

        QUEUES.put(verity, plan);
        MATERIAL.put(verity, material);
        return "开始盖庇护所：需要 " + needed + " 块 " + material;
    }

    /** 每 10 tick 放几块，避免一次改太多方块卡服。 */
    public static void tick(VerityEntity verity) {
        Deque<BlockPos> queue = QUEUES.get(verity);
        if (queue == null) return;
        String material = MATERIAL.getOrDefault(verity, "minecraft:cobblestone");
        if (!(verity.level() instanceof ServerLevel level)) {
            QUEUES.remove(verity);
            return;
        }
        int placed = 0;
        while (!queue.isEmpty() && placed < 4) {
            BlockPos pos = queue.poll();
            if (VerityInventory.count(verity, material) <= 0) break;
            BlockState current = level.getBlockState(pos);
            if (!current.canBeReplaced()) continue;
            if (verity.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) > 25.0D) continue;
            ItemStack stack = firstStack(verity, material);
            if (stack == null || !(stack.getItem() instanceof BlockItem blockItem)) break;
            if (level.setBlock(pos, blockItem.getBlock().defaultBlockState(), 3)) {
                VerityInventory.extract(verity, material, 1);
                placed++;
            }
        }
        if (queue.isEmpty()) {
            QUEUES.remove(verity);
            MATERIAL.remove(verity);
            verity.getPersistentData().putString("verity_ext_shelter_result", "庇护所盖好了");
            return;
        }
        // 材料耗尽必须收尾：否则 building() 永远为真，整条自主链路会被这一步锁死
        if (VerityInventory.count(verity, material) <= 0) {
            int left = queue.size();
            QUEUES.remove(verity);
            MATERIAL.remove(verity);
            verity.getPersistentData().putString("verity_ext_shelter_result",
                    "材料用完了，庇护所没盖完（还差 " + left + " 块）");
        }
    }

    public static boolean building(VerityEntity verity) {
        return QUEUES.containsKey(verity);
    }

    public static String describe(VerityEntity verity) {
        Deque<BlockPos> queue = QUEUES.get(verity);
        return queue == null ? "" : "盖房中，还剩 " + queue.size() + " 块";
    }

    /** 材料建议：有圆石用圆石，否则木板。 */
    public static String suggestMaterial(VerityEntity verity) {
        List<String> candidates = List.of("minecraft:cobblestone", "minecraft:oak_planks",
                "minecraft:dirt", "minecraft:oak_log");
        for (String id : candidates) {
            if (VerityInventory.count(verity, id) >= 33) return id;
        }
        return "";
    }

    private static ItemStack firstStack(VerityEntity verity, String itemId) {
        for (ItemStack slot : VerityInventory.load(verity)) {
            if (slot.isEmpty()) continue;
            if (net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(slot.getItem()).toString().equals(itemId)) return slot;
        }
        return null;
    }
}
