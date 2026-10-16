package com.hanchen.veritycustomapi.ride;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import varmite.verity.entity.verity.VerityEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 上骑后的「潜行免疫」窗口。
 *
 * 骑乘手势是「潜行 + 空手右键」，而原版判断要不要下车的依据恰好就是「有没有按着潜行」——
 * {@code Player.rideTick()} 每 tick 检查 {@code wantsToStopRiding()}（实现就是
 * {@code isShiftKeyDown()}），成立就 {@code stopRiding()}。玩家用潜行骑上去时手还按在潜行上，
 * 于是下一 tick 就被判成要下车，表现为「刚骑上去就掉下来」。
 *
 * 上骑后只要潜行没松开，就不算下车请求；另外给一个极短的固定窗口兜住「秒按秒放又按」。
 * 松开之后潜行恢复正常的「下车」语义，不改变玩家原来的习惯。
 */
public final class RideGrace {

    /** 上骑后无论如何都免疫的时长（tick），0.5 秒。 */
    private static final int MIN_GRACE_TICKS = 10;

    private static final class Entry {
        private long mountedAtTick;
        private boolean waitingForRelease;
    }

    private static final Map<UUID, Entry> GRACE = new ConcurrentHashMap<>();

    private RideGrace() {
    }

    /** 成功上骑时登记。由骑乘入口调用。 */
    public static void onMounted(ServerPlayer player) {
        if (player == null) {
            return;
        }
        Entry entry = new Entry();
        entry.mountedAtTick = gameTime(player);
        entry.waitingForRelease = true;
        GRACE.put(player.getUUID(), entry);
    }

    /**
     * 这次「想下车」要不要被拦下。
     *
     * @return true 表示拦下（不让他因为潜行被踢下车）
     */
    public static boolean blocksSneakDismount(Player player) {
        if (player == null) {
            return false;
        }
        // 只对我们自己的载具生效，别去干扰原版坐船/坐矿车的手感
        if (!(player.getVehicle() instanceof VerityEntity)) {
            return false;
        }
        Entry entry = GRACE.get(player.getUUID());
        if (entry == null) {
            return false;
        }
        if (!player.isShiftKeyDown()) {
            // 潜行已经松开：从现在起潜行重新算作正常的「下车」请求
            entry.waitingForRelease = false;
        }
        if (gameTime(player) - entry.mountedAtTick < MIN_GRACE_TICKS) {
            return true;
        }
        if (entry.waitingForRelease) {
            return true;
        }
        GRACE.remove(player.getUUID());
        return false;
    }

    /** 玩家不再骑着我们的 Verity 时顺手清掉，避免长期挂着。 */
    public static void forget(Player player) {
        if (player != null) {
            GRACE.remove(player.getUUID());
        }
    }

    /** 服务器停止时清理，避免跨存档残留。 */
    public static void clear() {
        GRACE.clear();
    }

    private static long gameTime(Player player) {
        try {
            return player.level().getGameTime();
        } catch (Throwable error) {
            return System.currentTimeMillis() / 50L;
        }
    }
}
