package com.hanchen.veritycustomapi.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 服务端 → 客户端：一只 Verity 的自主状态快照。
 *
 * <p>为什么需要这个包：菜单里的自主开关原来是两个独立按钮，点完只能去聊天栏看结果，
 * 界面本身不知道当前到底是开还是关、同伴在做什么、为什么停住。玩家因此会反复点、重复扣额度。
 * 状态由服务端给出（只有服务端知道 enabled/intent/阻塞原因），客户端只负责显示。
 */
public final class AutonomyStatePayload {

    private final int entityId;
    private final boolean enabled;
    private final boolean suspended;
    private final String intent;
    private final String goal;
    private final String note;

    public AutonomyStatePayload(int entityId, boolean enabled, boolean suspended,
                               String intent, String goal, String note) {
        this.entityId = entityId;
        this.enabled = enabled;
        this.suspended = suspended;
        this.intent = safe(intent);
        this.goal = safe(goal);
        this.note = safe(note);
    }

    public int entityId() { return entityId; }
    public boolean enabled() { return enabled; }
    public boolean suspended() { return suspended; }
    public String intent() { return intent; }
    public String goal() { return goal; }
    public String note() { return note; }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public static void write(AutonomyStatePayload payload, FriendlyByteBuf buffer) {
        buffer.writeVarInt(payload.entityId);
        buffer.writeBoolean(payload.enabled);
        buffer.writeBoolean(payload.suspended);
        buffer.writeUtf(payload.intent, 64);
        buffer.writeUtf(payload.goal, 200);
        buffer.writeUtf(payload.note, 200);
    }

    public static AutonomyStatePayload read(FriendlyByteBuf buffer) {
        return new AutonomyStatePayload(buffer.readVarInt(), buffer.readBoolean(), buffer.readBoolean(),
                buffer.readUtf(64), buffer.readUtf(200), buffer.readUtf(200));
    }
}
