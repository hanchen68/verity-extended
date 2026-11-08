package com.hanchen.veritycustomapi.body;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import varmite.verity.entity.verity.VerityEntity;

/**
 * 她的装备栏。
 *
 * <p><b>为什么单独存</b>：原版 {@code Mob.setItemSlot} 只影响渲染和属性，
 * 不会跟着我们的 27 格背包走；读档后实体装备槽可能被重置。
 * 所以装备的权威数据存在实体 NBT 里，每次同步再写回装备槽。
 *
 * <p>这样「拿起镐」才有意义：手上真的拿着，模型渲染也看得见。
 */
public final class VerityEquipment {

    private static final String KEY = "verity_ext_equipment";
    private static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
    };

    private VerityEquipment() {
    }

    public static ItemStack get(VerityEntity verity, EquipmentSlot slot) {
        CompoundTag data = verity.getPersistentData().getCompound(KEY);
        if (!data.contains(slot.getName())) return ItemStack.EMPTY;
        ItemStack stack = ItemStack.of(data.getCompound(slot.getName()));
        return stack == null ? ItemStack.EMPTY : stack;
    }

    public static void set(VerityEntity verity, EquipmentSlot slot, ItemStack stack) {
        CompoundTag data = verity.getPersistentData().getCompound(KEY);
        if (stack == null || stack.isEmpty()) data.remove(slot.getName());
        else data.put(slot.getName(), stack.save(new CompoundTag()));
        verity.getPersistentData().put(KEY, data);
        verity.setItemSlot(slot, stack == null ? ItemStack.EMPTY : stack);
    }

    /** 读档后把 NBT 里的装备写回实体槽位；每 20 tick 调一次即可。 */
    public static void sync(VerityEntity verity) {
        for (EquipmentSlot slot : SLOTS) {
            ItemStack stored = get(verity, slot);
            ItemStack current = verity.getItemBySlot(slot);
            if (ItemStack.isSameItemSameTags(stored, current)) continue;
            verity.setItemSlot(slot, stored);
        }
    }

    /**
     * 从背包把指定物品装到槽位。
     *
     * @return 结果文本，失败以「失败」开头
     */
    public static String equipFromInventory(VerityEntity verity, EquipmentSlot slot, String itemId) {
        if (VerityInventory.count(verity, itemId) <= 0) return "失败：背包里没有 " + itemId;
        ItemStack stack = findStack(verity, itemId);
        if (stack == null) return "失败：取不出 " + itemId;
        ItemStack previous = get(verity, slot);
        VerityInventory.extract(verity, itemId, 1);
        set(verity, slot, stack.copyWithCount(1));
        if (!previous.isEmpty()) {
            String id = BuiltInRegistries.ITEM.getKey(previous.getItem()).toString();
            ItemStack leftover = VerityInventory.insert(verity, previous);
            if (!leftover.isEmpty()) {
                net.minecraft.world.level.block.Block.popResource(verity.level(), verity.blockPosition(), leftover);
                return "已装备 " + itemId + "（换下的 " + id + " 放不下，掉在脚下）";
            }
            return "已装备 " + itemId + "，换下的 " + id + " 收回背包";
        }
        return "已装备 " + itemId;
    }

    /** 手上最合适的工具：优先已装备的主手，其次背包里的。 */
    public static ItemStack bestTool(VerityEntity verity, net.minecraft.world.level.block.state.BlockState state) {
        ItemStack main = verity.getItemBySlot(EquipmentSlot.MAINHAND);
        if (!main.isEmpty() && main.isCorrectToolForDrops(state)) return main;
        ItemStack fromInventory = VerityBody.bestToolFor(verity, state);
        if (fromInventory.isEmpty()) return ItemStack.EMPTY;
        // 背包里有更合适的：自动换上，行为上就是「她换了工具」
        String id = BuiltInRegistries.ITEM.getKey(fromInventory.getItem()).toString();
        equipFromInventory(verity, EquipmentSlot.MAINHAND, id);
        ItemStack equipped = verity.getItemBySlot(EquipmentSlot.MAINHAND);
        return equipped.isEmpty() ? fromInventory : equipped;
    }

    /** 耐久消耗后写回 NBT；用坏了清空槽位。 */
    public static void damageMainHand(VerityEntity verity, int amount) {
        ItemStack main = verity.getItemBySlot(EquipmentSlot.MAINHAND);
        if (main.isEmpty() || !main.isDamageableItem()) return;
        main.setDamageValue(main.getDamageValue() + amount);
        if (main.getDamageValue() >= main.getMaxDamage()) set(verity, EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        else set(verity, EquipmentSlot.MAINHAND, main);
    }

    public static String describe(VerityEntity verity) {
        StringBuilder sb = new StringBuilder("装备：");
        boolean any = false;
        for (EquipmentSlot slot : SLOTS) {
            ItemStack stack = get(verity, slot);
            if (stack.isEmpty()) continue;
            if (any) sb.append("、");
            sb.append(slot.getName()).append("=")
                    .append(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath());
            any = true;
        }
        return any ? sb.toString() : "装备：空手";
    }

    private static ItemStack findStack(VerityEntity verity, String itemId) {
        for (ItemStack slot : VerityInventory.load(verity)) {
            if (slot.isEmpty()) continue;
            if (BuiltInRegistries.ITEM.getKey(slot.getItem()).toString().equals(itemId)) return slot;
        }
        return null;
    }
}
