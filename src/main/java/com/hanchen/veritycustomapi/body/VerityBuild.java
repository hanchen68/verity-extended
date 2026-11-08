package com.hanchen.veritycustomapi.body;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import varmite.verity.entity.verity.VerityEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 小规模建造：按给定方块清单，从低到高逐块放置。
 *
 * <p>放置顺序借自成熟建造作业的做法：<b>先低后高</b>，否则上面的方块没有支撑、
 * 或者被自己的施工顺序挡住。目标位置被占用就跳过，不覆盖已有方块。
 *
 * <p>限制 8 块/次：这是「搭一小段墙、铺一格桥」的量级，不是蓝图工程。
 * 真正的建筑规划（结构文件、材料清单）还没做，不假装有。
 */
public final class VerityBuild {

    private static final int MAX_BLOCKS = 8;
    private static final double REACH = 5.0D;

    private VerityBuild() {
    }

    /** blocks 每项 {x,y,z,item}。 */
    public static String build(VerityEntity verity, JsonArray blocks) {
        if (!(verity.level() instanceof ServerLevel level)) return "失败：不在服务端";
        if (blocks == null || blocks.isEmpty()) return "失败：没有给出要放的方块";
        if (blocks.size() > MAX_BLOCKS) return "失败：一次最多放 " + MAX_BLOCKS + " 块";

        List<BlockPos> targets = new ArrayList<>();
        List<String> items = new ArrayList<>();
        for (var element : blocks) {
            if (!element.isJsonObject()) continue;
            JsonObject entry = element.getAsJsonObject();
            try {
                int x = entry.get("x").getAsInt();
                int y = entry.get("y").getAsInt();
                int z = entry.get("z").getAsInt();
                String item = entry.get("item").getAsString();
                targets.add(new BlockPos(x, y, z));
                items.add(item);
            } catch (RuntimeException ignored) {
                return "失败：方块条目格式不对（需要 x/y/z/item）";
            }
        }
        if (targets.isEmpty()) return "失败：没有合法的方块条目";

        // 先低后高：高处的方块需要下方先存在
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) order.add(i);
        order.sort(Comparator.comparingInt(i -> targets.get(i).getY()));

        int placed = 0;
        List<String> problems = new ArrayList<>();
        for (int index : order) {
            BlockPos pos = targets.get(index);
            String item = items.get(index);
            if (verity.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) > REACH * REACH) {
                problems.add(pos.toShortString() + " 够不到");
                continue;
            }
            if (!level.hasChunkAt(pos)) {
                problems.add(pos.toShortString() + " 未加载");
                continue;
            }
            if (pos.equals(verity.blockPosition()) || pos.equals(verity.blockPosition().above())) {
                problems.add(pos.toShortString() + " 会卡住自己");
                continue;
            }
            ItemStack stack = firstStack(verity, item);
            if (stack == null) {
                problems.add("没有 " + item);
                continue;
            }
            if (!(stack.getItem() instanceof BlockItem blockItem)) {
                problems.add(item + " 不是方块");
                continue;
            }
            BlockState current = level.getBlockState(pos);
            if (!current.canBeReplaced()) {
                problems.add(pos.toShortString() + " 已被占用");
                continue;
            }
            if (!level.setBlock(pos, blockItem.getBlock().defaultBlockState(), 3)) {
                problems.add(pos.toShortString() + " 放置失败");
                continue;
            }
            VerityInventory.extract(verity, item, 1);
            placed++;
        }
        if (placed == 0) return "失败：一块都没放上（" + String.join("；", problems) + "）";
        return "放置 " + placed + " 块" + (problems.isEmpty() ? "" : "，跳过 " + problems.size() + " 处（"
                + String.join("；", problems) + "）");
    }

    private static ItemStack firstStack(VerityEntity verity, String itemId) {
        for (ItemStack slot : VerityInventory.load(verity)) {
            if (slot.isEmpty()) continue;
            if (BuiltInRegistries.ITEM.getKey(slot.getItem()).toString().equals(itemId)) return slot;
        }
        return null;
    }
}
