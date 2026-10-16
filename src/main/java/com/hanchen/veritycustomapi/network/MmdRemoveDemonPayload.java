package com.hanchen.veritycustomapi.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端「按 id 列表删恶魔」请求的 payload。
 * 单机直连调用，联机走 SimpleChannel。
 */
public final class MmdRemoveDemonPayload {
    private final List<Integer> entityIds;

    public MmdRemoveDemonPayload(List<Integer> entityIds) {
        this.entityIds = entityIds == null ? List.of() : List.copyOf(entityIds);
    }

    public List<Integer> entityIds() { return entityIds; }

    public static void write(MmdRemoveDemonPayload payload, FriendlyByteBuf buf) {
        buf.writeInt(payload.entityIds.size());
        for (int id : payload.entityIds) {
            buf.writeInt(id);
        }
    }

    public static MmdRemoveDemonPayload read(FriendlyByteBuf buf) {
        int n = buf.readInt();
        List<Integer> ids = new ArrayList<>(n);
        for (int i = 0; i < n; i++) ids.add(buf.readInt());
        return new MmdRemoveDemonPayload(ids);
    }
}