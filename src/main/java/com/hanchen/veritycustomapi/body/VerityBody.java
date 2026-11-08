package com.hanchen.veritycustomapi.body;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import varmite.verity.entity.verity.VerityEntity;

import java.util.List;

/**
 * Verity 的「手」：看、挖、放、捡。
 *
 * <p>和 {@link com.hanchen.veritycustomapi.autonomy.AutonomyActions} 的区别：
 * 那层是「语义命令」（采集一片区域，围绕玩家），这层是**玩家真正做的动作**——
 * 对准一个方块、挖掉它、把掉落收进自己包里、再放到另一个位置。
 *
 * <p>全部只在服务端主线程调用；距离、区块、方块合法性都在这里校验，
 * 不依赖调用方自觉。
 */
public final class VerityBody {

    /** 手能够到多远（格）。原版玩家约 4.5，这里给一点余量。 */
    private static final double REACH = 5.0D;
    /** 拾取半径。 */
    private static final double PICKUP_RADIUS = 3.0D;

    private VerityBody() {
    }

    public static void lookAt(VerityEntity verity, double x, double y, double z) {
        verity.getLookControl().setLookAt(x, y, z, 40.0F, 40.0F);
    }

    /**
     * 挖掉一个方块，掉落进自己的背包。
     *
     * @return 结果文本；失败时以「失败」开头，调用方据此判定
     */
    public static String breakBlock(VerityEntity verity, BlockPos pos) {
        if (!(verity.level() instanceof ServerLevel level)) return "失败：不在服务端";
        if (!level.hasChunkAt(pos)) return "失败：目标区块未加载";
        if (verity.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) > REACH * REACH)
            return "失败：够不到（超过 " + (int) REACH + " 格）";

        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return "失败：那里已经是空气";
        if (state.getDestroySpeed(level, pos) < 0.0F) return "失败：这个方块挖不动（如基岩）";

        // 工具决定能不能拿到掉落：没有镐挖石头就是白挖，和玩家一致
        ItemStack tool = bestToolFor(verity, state);
        boolean needsTool = state.requiresCorrectToolForDrops();
        if (needsTool && tool.isEmpty())
            return "失败：挖 " + BuiltInRegistries.BLOCK.getKey(state.getBlock())
                    + " 需要正确工具，背包里没有（先用木头做把镐）";

        // 先算掉落，再破坏：顺序反了就拿不到 BlockEntity 里的内容
        List<ItemStack> drops = Block.getDrops(state, level, pos, level.getBlockEntity(pos), verity, tool);
        if (!level.destroyBlock(pos, false)) return "失败：破坏方块没有成功";

        if (!tool.isEmpty()) damageTool(verity, tool);
        int stored = 0;
        for (ItemStack drop : drops) {
            if (drop.isEmpty()) continue;
            ItemStack leftover = VerityInventory.insert(verity, drop);
            stored += drop.getCount() - leftover.getCount();
            if (!leftover.isEmpty()) {
                // 背包满了就掉在地上，和玩家一样，不能凭空吞掉
                Block.popResource(level, pos, leftover);
            }
        }
        lookAt(verity, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
        return "已挖掉 " + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + "，"
                + (drops.isEmpty() ? "没有掉落物" : "收进背包 " + stored + " 件");
    }

    /** 背包里对当前方块最合适的工具（没有就空）。 */
    static ItemStack bestToolFor(VerityEntity verity, BlockState state) {
        ItemStack best = ItemStack.EMPTY;
        for (ItemStack slot : VerityInventory.load(verity)) {
            if (slot.isEmpty() || !slot.isCorrectToolForDrops(state)) continue;
            if (best.isEmpty() || slot.getDestroySpeed(state) > best.getDestroySpeed(state)) best = slot;
        }
        return best;
    }

    /** 工具耐久 -1，用坏了就从背包移除。 */
    private static void damageTool(VerityEntity verity, ItemStack tool) {
        if (!tool.isDamageableItem()) return;
        tool.setDamageValue(tool.getDamageValue() + 1);
        List<ItemStack> slots = VerityInventory.load(verity);
        for (int i = 0; i < slots.size(); i++) {
            ItemStack slot = slots.get(i);
            if (slot.isEmpty() || !ItemStack.isSameItem(slot, tool)) continue;
            if (slot.getDamageValue() != tool.getDamageValue() - 1) continue;
            if (slot.getDamageValue() >= slot.getMaxDamage()) slots.set(i, ItemStack.EMPTY);
            break;
        }
        VerityInventory.save(verity, slots);
    }

    /** 从背包放一个方块。 */
    public static String placeBlock(VerityEntity verity, BlockPos pos, String itemId) {
        if (!(verity.level() instanceof ServerLevel level)) return "失败：不在服务端";
        if (!level.hasChunkAt(pos)) return "失败：目标区块未加载";
        if (verity.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) > REACH * REACH)
            return "失败：够不到（超过 " + (int) REACH + " 格）";
        if (VerityInventory.count(verity, itemId) <= 0) return "失败：背包里没有 " + itemId;

        ItemStack probe = findStack(verity, itemId);
        if (probe == null || !(probe.getItem() instanceof BlockItem blockItem))
            return "失败：" + itemId + " 不是可放置的方块";
        BlockState target = blockItem.getBlock().defaultBlockState();
        if (!level.getBlockState(pos).canBeReplaced()) return "失败：目标位置被占用";

        if (!level.setBlock(pos, target, 3)) return "失败：放置没有成功";
        VerityInventory.extract(verity, itemId, 1);
        lookAt(verity, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
        return "已放置 " + itemId + " 到 " + pos.toShortString();
    }

    /** 拾取脚下附近的掉落物，返回收进背包的数量。 */
    public static int pickupNearby(VerityEntity verity) {
        if (!(verity.level() instanceof ServerLevel level)) return 0;
        int taken = 0;
        for (ItemEntity drop : level.getEntitiesOfClass(ItemEntity.class,
                verity.getBoundingBox().inflate(PICKUP_RADIUS))) {
            if (drop.isRemoved() || drop.getItem().isEmpty()) continue;
            ItemStack stack = drop.getItem().copy();
            ItemStack leftover = VerityInventory.insert(verity, stack);
            int moved = stack.getCount() - leftover.getCount();
            if (moved <= 0) continue;
            taken += moved;
            if (leftover.isEmpty()) drop.discard();
            else drop.setItem(leftover);
        }
        return taken;
    }

    private static ItemStack findStack(VerityEntity verity, String itemId) {
        for (ItemStack slot : VerityInventory.load(verity)) {
            if (slot.isEmpty()) continue;
            if (BuiltInRegistries.ITEM.getKey(slot.getItem()).toString().equals(itemId)) return slot;
        }
        return null;
    }

    /** 打最近的敌对生物（4 格内）。 */
    public static String attackNearest(VerityEntity verity) {
        if (!(verity.level() instanceof ServerLevel level)) return "失败：不在服务端";
        net.minecraft.world.entity.LivingEntity target = null;
        double best = 16.0D;
        for (var monster : level.getEntitiesOfClass(net.minecraft.world.entity.monster.Monster.class,
                verity.getBoundingBox().inflate(4.0D))) {
            if (monster == null || !monster.isAlive()) continue;
            double distance = verity.distanceToSqr(monster);
            if (distance < best) {
                best = distance;
                target = monster;
            }
        }
        if (target == null) return "附近没有敌人";
        lookAt(verity, target.getX(), target.getY() + target.getBbHeight() * 0.5D, target.getZ());
        boolean hit = target.hurt(verity.damageSources().mobAttack(verity), 3.0F);
        return hit ? "攻击了 " + target.getName().getString() : "没打中 " + target.getName().getString();
    }

    /** 把手里的东西交给玩家；装不下就掉在玩家脚下。 */
    public static String giveTo(VerityEntity verity, net.minecraft.server.level.ServerPlayer player,
                                String itemId, int count) {
        if (player == null) return "失败：玩家不在";
        if (verity.distanceToSqr(player) > 16.0D) return "失败：离你太远，先走近";
        int available = VerityInventory.count(verity, itemId);
        if (available <= 0) return "失败：我这里没有 " + itemId;
        int give = Math.max(1, Math.min(count <= 0 ? 1 : count, available));
        int taken = VerityInventory.extract(verity, itemId, give);
        if (taken <= 0) return "失败：没取出来";

        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                .get(new net.minecraft.resources.ResourceLocation(itemId));
        ItemStack stack = new ItemStack(item, taken);
        if (!player.getInventory().add(stack)) {
            net.minecraft.world.level.block.Block.popResource(player.serverLevel(),
                    player.blockPosition(), stack);
            return "给你 " + taken + " 个 " + itemId + "（你背包满了，掉在脚下）";
        }
        return "给你 " + taken + " 个 " + itemId;
    }

    /** 给状态显示用的一行身体诊断。 */
    public static String describe(VerityEntity verity) {
        return "背包：" + VerityInventory.summary(verity);
    }

    /** 供外部拼 JSON 时复用。 */
    public static JsonObject position(VerityEntity verity) {
        JsonObject object = new JsonObject();
        object.addProperty("x", verity.getX());
        object.addProperty("y", verity.getY());
        object.addProperty("z", verity.getZ());
        return object;
    }
}
