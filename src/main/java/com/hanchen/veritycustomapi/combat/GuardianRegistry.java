package com.hanchen.veritycustomapi.combat;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端侧的「哪些实体是我们召唤的替身」登记表（按实体 id）。
 *
 * <p>为什么要单独同步：客户端拿不到 Forge 的 {@code getPersistentData()}（服务端数据不同步），
 * 靠自定义名判断又容易受名字被改/同步时序影响。所以召唤时服务端直接广播一条
 * {@code GuardianSyncPayload}，客户端记在这张表里，恐怖系统 / 音效屏蔽据此判断。
 */
public final class GuardianRegistry {

    private static final Set<Integer> GUARDIANS = ConcurrentHashMap.newKeySet();

    private GuardianRegistry() {
    }

    public static void set(int entityId, boolean guardian) {
        if (entityId < 0) return;
        if (guardian) {
            GUARDIANS.add(entityId);
        } else {
            GUARDIANS.remove(entityId);
        }
    }

    public static boolean contains(int entityId) {
        return GUARDIANS.contains(entityId);
    }

    /** 退出世界时清空：实体 id 每个存档从 0 开始。 */
    public static void clear() {
        GUARDIANS.clear();
    }
}
