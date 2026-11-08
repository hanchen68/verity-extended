package com.hanchen.veritycustomapi.autonomy;

import com.hanchen.veritycustomapi.network.AutonomyStatePayload;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端侧的自主状态缓存（按实体 id 索引）。
 *
 * <p>只有服务端知道「自主开没开、正在做什么、为什么停下」，客户端原本一概不知：
 * 菜单里只能摆「开启 / 关闭」两个按钮，点完去聊天栏看结果。这里接收服务端下行快照，
 * 让菜单能如实显示状态，而不是让玩家盲点。
 *
 * <p>单人游戏里服务端和客户端同 JVM，这张表会被服务端 tick 直接写入；多人时靠数据包。
 */
public final class ClientAutonomyState {

    /** 超过这个时间没收到更新，就当作「未知」，避免显示一份过期状态骗人。 */
    private static final long STALE_MS = 8000L;

    private static final Map<Integer, Snapshot> STATES = new ConcurrentHashMap<>();

    private ClientAutonomyState() {
    }

    public record Snapshot(boolean enabled, boolean suspended, String intent, String goal, String note, long at) {
    }

    public static void accept(AutonomyStatePayload payload) {
        if (payload == null || payload.entityId() < 0) return;
        STATES.put(payload.entityId(), new Snapshot(payload.enabled(), payload.suspended(),
                payload.intent(), payload.goal(), payload.note(), System.currentTimeMillis()));
    }

    /** @return 最近一次快照；没有或已过期返回 {@code null}，调用方应显示「状态未知」。 */
    public static Snapshot get(int entityId) {
        Snapshot snapshot = STATES.get(entityId);
        if (snapshot == null) return null;
        if (System.currentTimeMillis() - snapshot.at() > STALE_MS) return null;
        return snapshot;
    }

    /** 断开连接/切换存档必须清掉，否则会把上一个世界里的状态显示到新世界里。 */
    public static void clear() {
        STATES.clear();
    }

    public static void forget(int entityId) {
        STATES.remove(entityId);
    }
}
