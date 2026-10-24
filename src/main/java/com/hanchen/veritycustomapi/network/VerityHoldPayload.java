package com.hanchen.veritycustomapi.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

/**
 * 服务端 -> 客户端：让某只 Verity 在手上举着某个物品若干 tick。
 *
 * 档期一到客户端自己放下，不需要再发一次“放下”的包；重复施法只会刷新这只 Verity 的记录。
 */
public final class VerityHoldPayload {
    private final int entityId;
    private final ItemStack stack;
    private final int durationTicks;

    public VerityHoldPayload(int entityId, ItemStack stack, int durationTicks) {
        this.entityId = entityId;
        this.stack = stack == null ? ItemStack.EMPTY : stack;
        this.durationTicks = durationTicks;
    }

    public int entityId() {
        return entityId;
    }

    public ItemStack stack() {
        return stack;
    }

    public int durationTicks() {
        return durationTicks;
    }

    public static void write(VerityHoldPayload payload, FriendlyByteBuf buffer) {
        buffer.writeVarInt(payload.entityId);
        buffer.writeItem(payload.stack);
        buffer.writeVarInt(Math.max(1, payload.durationTicks));
    }

    public static VerityHoldPayload read(FriendlyByteBuf buffer) {
        return new VerityHoldPayload(buffer.readVarInt(), buffer.readItem(), buffer.readVarInt());
    }
}