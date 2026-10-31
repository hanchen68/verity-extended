package com.hanchen.veritycustomapi.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 服务端 → 客户端：同步「某只 Verity 正站在谁头上」。
 *
 * <p>没有这条包，专用服务器上的客户端只能靠实体位置同步跟随（3 tick 插值），
 * 玩家跑动时头上的 Verity 会明显滞后。有了它，客户端可以像单人游戏那样每 tick 直接摆位。
 *
 * <p>{@code carrierId < 0} 表示「已经下来了」。
 */
public final class PerchSyncPayload {

    private final int verityId;
    private final int carrierId;

    public PerchSyncPayload(int verityId, int carrierId) {
        this.verityId = verityId;
        this.carrierId = carrierId;
    }

    public int verityId() {
        return verityId;
    }

    public int carrierId() {
        return carrierId;
    }

    public static void write(PerchSyncPayload payload, FriendlyByteBuf buffer) {
        buffer.writeVarInt(payload.verityId);
        // VarInt 不认负数：整体 +1 编码，读回来再 -1
        buffer.writeVarInt(payload.carrierId + 1);
    }

    public static PerchSyncPayload read(FriendlyByteBuf buffer) {
        return new PerchSyncPayload(buffer.readVarInt(), buffer.readVarInt() - 1);
    }
}
