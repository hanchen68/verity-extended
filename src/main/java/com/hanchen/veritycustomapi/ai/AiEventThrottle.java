package com.hanchen.veritycustomapi.ai;

import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 事件反馈限流器：同一 key 在 cooldownTicks 内只放行一次，窗口内其余事件合并计数。
 * key 由调用方拼，建议 事件名:实体或玩家 id，防止连点/高频 tick 把 AI 对话刷爆。
 */
public final class AiEventThrottle {
    /** value[0]=上次放行 tick，value[1]=窗口内被合并的事件数。 */
    /**
     * 上限：key 由调用方拼「事件名:实体id」，实体 id 每存档重新计数，
     * 长时间不重启会不断累积旧 key。超过上限就整体清空——
     * 代价是冷却状态丢一次，换来的是不会无限占内存。
     */
    private static final int MAX_TRACKED_KEYS = 512;

    private static final Map<String, long[]> LAST_AT = new ConcurrentHashMap<>();

    private AiEventThrottle() {
    }

    /** true 表示本次放行（并记录时间），false 表示还在冷却中（本次事件已计入合并）。 */
    public static boolean tryAcquire(String key, long nowTicks, long cooldownTicks) {
        if (cooldownTicks <= 0) {
            return true;
        }
        long[] slot = LAST_AT.get(key);
        if (slot != null && nowTicks - slot[0] < cooldownTicks) {
            slot[1]++;
            return false;
        }
        if (LAST_AT.size() >= MAX_TRACKED_KEYS) {
            // 简单粗暴地清空：冷却状态丢一次不影响正确性，只可能让某次事件早放行。
            LAST_AT.clear();
        }
        LAST_AT.put(key, new long[]{nowTicks, 0L});
        return true;
    }

    public static boolean tryAcquire(Level level, String event, long entityId, long cooldownTicks) {
        return level != null && tryAcquire(keyOf(event, entityId), level.getGameTime(), cooldownTicks);
    }

    /** 放行后紧接着调用：取回冷却期内被合并的事件数并拼成"（期间还发生了 N 次）"，N=0 返回空串。 */
    public static String mergedSuffix(Level level, String event, long entityId) {
        long merged = pendingMerged(keyOf(event, entityId));
        return merged > 0 ? "（期间还发生了 " + merged + " 次）" : "";
    }

    private static String keyOf(String event, long entityId) {
        return event + ":" + entityId;
    }

    /** 自该 key 上次放行以来被合并的事件数；取走即清零。放行后紧接着调用，把积压拼进本次反馈。 */
    public static long pendingMerged(String key) {
        long[] slot = LAST_AT.get(key);
        if (slot == null) {
            return 0L;
        }
        long pending = slot[1];
        slot[1] = 0L;
        return pending;
    }

    public static void reset() {
        LAST_AT.clear();
    }
}
