package com.hanchen.veritycustomapi.network;

import java.util.concurrent.ConcurrentLinkedQueue;

import com.hanchen.veritycustomapi.mmd.MmdPlaybackManager;

/**
 * 客户端简易队列。收到 S2C 生成回执时只把实体 id 入队，真正启动播放留到客户端 tick 里做，
 * 免得在主线程里同步跑网络回调把线程卡死。
 */
public final class ClientMmdAckQueue {
    private static final ConcurrentLinkedQueue<Integer> pendingEntityIds = new ConcurrentLinkedQueue<>();

    public static void accept(MmdSpawnDemonPayload payload) {
        pendingEntityIds.add(payload.entityId());
    }

    /**
     * 客户端 tick 里调用：把排队的实体和上次没启动成的项目名配对，开始播放。
     */
    public static void flushIfReady() {
        Integer id;
        while ((id = pendingEntityIds.poll()) != null) {
            MmdPlaybackManager manager = MmdPlaybackManager.get();
            String pendingProject = manager.getPendingProject();
            if (pendingProject != null) {
                manager.tryStartForSingleDemon(id, pendingProject);
            }
        }
    }

    private ClientMmdAckQueue() {}
}