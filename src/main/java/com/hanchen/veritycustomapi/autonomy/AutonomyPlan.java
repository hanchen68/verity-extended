package com.hanchen.veritycustomapi.autonomy;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;

/** 模型或玩家给出的有限行动计划，不包含破坏方块或操作物品的权限。 */
public final class AutonomyPlan {
    public enum Type {
        COMPANION,
        HOLD,
        SCOUT,
        REST
    }

    public enum Source {
        PLAYER,
        /** 保留旧存档兼容，只用于确定性的安全陪伴。 */
        SELF,
        MODEL
    }

    public Type type = Type.COMPANION;
    public Source source = Source.SELF;
    public double x;
    public double y;
    public double z;
    public boolean hasPos;
    public String dimension = "";
    public long createdAt;
    /** 绝对 gameTime；0 表示不自动过期。 */
    public long expiresAt;
    public String reason = "";
    public int attempts;

    public boolean expired(long now) {
        return expiresAt > 0L && now >= expiresAt;
    }

    public boolean valid() {
        return type != null && source != null && dimension != null
                && Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                && Math.abs(x) <= 30_000_000D && Math.abs(z) <= 30_000_000D
                && Math.abs(y) <= 30_000_000D && createdAt >= 0L && expiresAt >= 0L
                && (expiresAt == 0L || expiresAt >= createdAt) && attempts >= 0
                && (!(type == Type.HOLD || type == Type.SCOUT) || hasPos);
    }

    public double distanceSqrTo(double px, double py, double pz) {
        double dx = px - x;
        double dy = py - y;
        double dz = pz - z;
        return dx * dx + dy * dy + dz * dz;
    }

    public BlockPos pos() {
        return BlockPos.containing(x, y, z);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("type", type.name());
        tag.putString("source", source.name());
        tag.putDouble("x", x);
        tag.putDouble("y", y);
        tag.putDouble("z", z);
        tag.putBoolean("hasPos", hasPos);
        tag.putString("dimension", dimension);
        tag.putLong("createdAt", createdAt);
        tag.putLong("expiresAt", expiresAt);
        tag.putString("reason", reason == null ? "" : reason);
        tag.putInt("attempts", attempts);
        return tag;
    }

    /** 无效或被篡改的存档计划直接丢弃，不把 NaN/无穷传入导航。 */
    public static AutonomyPlan load(CompoundTag tag) {
        for (String key : new String[]{"x", "y", "z", "createdAt", "expiresAt", "attempts"}) {
            if (!tag.contains(key, 99) || !Double.isFinite(tag.getDouble(key))) return null;
        }
        AutonomyPlan plan = new AutonomyPlan();
        try {
            plan.type = Type.valueOf(tag.getString("type"));
            plan.source = Source.valueOf(tag.getString("source"));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        plan.x = tag.getDouble("x");
        plan.y = tag.getDouble("y");
        plan.z = tag.getDouble("z");
        plan.hasPos = tag.getBoolean("hasPos");
        plan.dimension = tag.getString("dimension");
        plan.createdAt = tag.getLong("createdAt");
        plan.expiresAt = tag.getLong("expiresAt");
        plan.reason = tag.getString("reason");
        plan.attempts = tag.getInt("attempts");
        return plan.valid() ? plan : null;
    }

    public String describe() {
        String tail = reason == null || reason.isBlank() ? "" : "（" + reason + "）";
        return switch (type) {
            case COMPANION -> "陪伴" + tail;
            case HOLD -> "守在 " + fmt() + tail;
            case SCOUT -> "前往 " + fmt() + " 侦察" + tail;
            case REST -> "原地休息" + tail;
        };
    }

    private String fmt() {
        return (int) Math.floor(x) + ", " + (int) Math.floor(y) + ", " + (int) Math.floor(z);
    }
}
