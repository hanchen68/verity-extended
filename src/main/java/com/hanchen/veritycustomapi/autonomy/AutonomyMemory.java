package com.hanchen.veritycustomapi.autonomy;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import varmite.verity.entity.verity.VerityEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * 自主行为的「经历记忆」：她记得哪条路走不通、哪里去过。
 *
 * <p>只存**很短**的一串事件（最多 12 条），不是聊天记录：
 * <pre>
 *   时间 | 类型 | 坐标 | 结果
 * </pre>
 * 用途就一个——**别在同一个地方反复失败**。
 *
 * <p>存在实体的 {@code getPersistentData()} 里，所以退出重进仍然有效；
 * 不做世界级数据库，避免把一个陪伴功能做成存档负担。
 */
public final class AutonomyMemory {

    /** 最多保留多少条。 */
    private static final int MAX_EVENTS = 12;
    /** 同一个点在这个时间窗内失败两次就绕开（5 分钟）。 */
    private static final long AVOID_WINDOW_TICKS = 6000L;

    public record Event(long time, String kind, double x, double y, double z, boolean success) {
    }

    private AutonomyMemory() {
    }

    public static void record(VerityEntity verity, String kind, double x, double y, double z,
                              boolean success) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return;
        List<Event> events = load(verity);
        String safeKind = kind == null ? "unknown" : kind.substring(0, Math.min(80, kind.length()));
        events.add(new Event(verity.level().getGameTime(), safeKind, x, y, z, success));
        while (events.size() > MAX_EVENTS) {
            events.remove(0);
        }
        save(verity, events);
    }

    /** 这个点最近是不是老失败（走不到）。 */
    public static boolean isBlocked(VerityEntity verity, double x, double y, double z) {
        long now = verity.level().getGameTime();
        int failures = 0;
        for (Event event : load(verity)) {
            if (event.success()) continue;
            if (now < event.time() || now - event.time() > AVOID_WINDOW_TICKS) continue;
            double dx = event.x() - x;
            double dz = event.z() - z;
            if (dx * dx + dz * dz <= 9.0D) {
                failures++;
            }
        }
        return failures >= 2;
    }

    /** 最近一次失败的原因（给状态显示用）。 */
    public static String lastFailure(VerityEntity verity) {
        List<Event> events = load(verity);
        for (int i = events.size() - 1; i >= 0; i--) {
            Event event = events.get(i);
            if (!event.success()) {
                return event.kind() + " @ " + (int) event.x() + ", " + (int) event.z();
            }
        }
        return "";
    }

    private static List<Event> load(VerityEntity verity) {
        List<Event> events = new ArrayList<>();
        CompoundTag data = verity.getPersistentData();
        if (!data.contains("verity_ext_mem")) return events;
        ListTag list = data.getList("verity_ext_mem", 10);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            events.add(new Event(
                    tag.getLong("t"),
                    tag.getString("k"),
                    tag.getDouble("x"),
                    tag.getDouble("y"),
                    tag.getDouble("z"),
                    tag.getBoolean("ok")));
        }
        return events;
    }

    private static void save(VerityEntity verity, List<Event> events) {
        ListTag list = new ListTag();
        for (Event event : events) {
            CompoundTag tag = new CompoundTag();
            tag.putLong("t", event.time());
            tag.putString("k", event.kind());
            tag.putDouble("x", event.x());
            tag.putDouble("y", event.y());
            tag.putDouble("z", event.z());
            tag.putBoolean("ok", event.success());
            list.add(tag);
        }
        verity.getPersistentData().put("verity_ext_mem", list);
    }

    /** 退出世界时不用清：这是实体自己的记忆，跟着存档走。 */
    public static void forget(VerityEntity verity) {
        verity.getPersistentData().remove("verity_ext_mem");
    }

    /** 供调试：把记忆打成一行文本。 */
    public static String describe(VerityEntity verity) {
        List<Event> events = load(verity);
        if (events.isEmpty()) return "（没有记录）";
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, events.size() - 3);
        for (int i = from; i < events.size(); i++) {
            Event event = events.get(i);
            if (i > from) sb.append("；");
            sb.append(event.success() ? "成功:" : "失败:").append(event.kind())
                    .append(" @ ").append((int) event.x()).append(",")
                    .append((int) event.y()).append(",").append((int) event.z());
        }
        return sb.toString();
    }
}
