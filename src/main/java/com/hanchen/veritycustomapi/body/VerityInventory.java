package com.hanchen.veritycustomapi.body;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import varmite.verity.entity.verity.VerityEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Verity 自己的背包。
 *
 * <p><b>为什么必须自己存</b>：之前所有工具都用玩家背包，所以她「挖了东西不算自己的」、
 * 「没有材料可以放」。要让 AI 真正像玩家一样行动，第一件事是有一副自己的身体。
 *
 * <p>存在实体持久数据里，随实体 NBT 一起存档；槽位 27 格（箱子大小），
 * 不做堆叠合并以外的花活，避免把简单事情写复杂。
 */
public final class VerityInventory {

    private static final String KEY = "verity_ext_inventory";
    public static final int SLOTS = 27;
    /** 单格最大堆叠，和原版一致。 */
    private static final int MAX_STACK = 64;

    private VerityInventory() {
    }

    public static List<ItemStack> load(VerityEntity verity) {
        List<ItemStack> slots = new ArrayList<>(SLOTS);
        for (int i = 0; i < SLOTS; i++) slots.add(ItemStack.EMPTY);
        CompoundTag data = verity.getPersistentData();
        if (!data.contains(KEY)) return slots;
        ListTag list = data.getList(KEY, Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(SLOTS, list.size()); i++) {
            CompoundTag entry = list.getCompound(i);
            if (entry.isEmpty()) continue;
            ItemStack stack = ItemStack.of(entry);
            // 存档被改坏时宁可丢这一格，也不要让整只实体加载失败
            if (stack != null && !stack.isEmpty()) slots.set(i, stack);
        }
        return slots;
    }

    public static void save(VerityEntity verity, List<ItemStack> slots) {
        ListTag list = new ListTag();
        for (int i = 0; i < SLOTS; i++) {
            ItemStack stack = i < slots.size() ? slots.get(i) : ItemStack.EMPTY;
            list.add(stack.isEmpty() ? new CompoundTag() : stack.save(new CompoundTag()));
        }
        verity.getPersistentData().put(KEY, list);
    }

    /** 塞入物品，返回没塞下的部分（空 = 全部收下）。 */
    public static ItemStack insert(VerityEntity verity, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;
        List<ItemStack> slots = load(verity);
        ItemStack remaining = stack.copy();

        // 先并到同类堆叠，再占空格——和玩家拾取顺序一致
        for (int i = 0; i < SLOTS && !remaining.isEmpty(); i++) {
            ItemStack slot = slots.get(i);
            if (slot.isEmpty() || !ItemStack.isSameItemSameTags(slot, remaining)) continue;
            int space = MAX_STACK - slot.getCount();
            if (space <= 0) continue;
            int moved = Math.min(space, remaining.getCount());
            slot.grow(moved);
            remaining.shrink(moved);
        }
        for (int i = 0; i < SLOTS && !remaining.isEmpty(); i++) {
            if (!slots.get(i).isEmpty()) continue;
            int moved = Math.min(MAX_STACK, remaining.getCount());
            ItemStack placed = remaining.copy();
            placed.setCount(moved);
            slots.set(i, placed);
            remaining.shrink(moved);
        }
        save(verity, slots);
        return remaining;
    }

    /** 取走指定物品，返回实际取到的数量。 */
    public static int extract(VerityEntity verity, String itemId, int count) {
        List<ItemStack> slots = load(verity);
        int taken = 0;
        for (int i = 0; i < SLOTS && taken < count; i++) {
            ItemStack slot = slots.get(i);
            if (slot.isEmpty()) continue;
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(slot.getItem()).toString();
            if (!id.equals(itemId)) continue;
            int move = Math.min(count - taken, slot.getCount());
            slot.shrink(move);
            taken += move;
            if (slot.isEmpty()) slots.set(i, ItemStack.EMPTY);
        }
        if (taken > 0) save(verity, slots);
        return taken;
    }

    public static int count(VerityEntity verity, String itemId) {
        int total = 0;
        for (ItemStack slot : load(verity)) {
            if (slot.isEmpty()) continue;
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(slot.getItem()).toString();
            if (id.equals(itemId)) total += slot.getCount();
        }
        return total;
    }

    /** 给模型看的摘要：只列前 12 种，避免撑爆上下文。 */
    public static String summary(VerityEntity verity) {
        List<ItemStack> slots = load(verity);
        java.util.LinkedHashMap<String, Integer> counts = new java.util.LinkedHashMap<>();
        int used = 0;
        for (ItemStack slot : slots) {
            if (slot.isEmpty()) continue;
            used++;
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(slot.getItem()).toString();
            counts.merge(id, slot.getCount(), Integer::sum);
        }
        if (counts.isEmpty()) return "空（27 格）";
        StringBuilder sb = new StringBuilder(used + "/27 格：");
        int shown = 0;
        for (var entry : counts.entrySet()) {
            if (shown++ >= 12) {
                sb.append("…");
                break;
            }
            if (shown > 1) sb.append("、");
            sb.append(entry.getKey()).append(" ×").append(entry.getValue());
        }
        return sb.toString();
    }

    /** 死亡或重置时清空。 */
    public static void clear(VerityEntity verity) {
        verity.getPersistentData().remove(KEY);
    }
}
