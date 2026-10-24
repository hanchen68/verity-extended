package com.hanchen.veritycustomapi.ride;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.network.HeadPerchNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import varmite.verity.entity.verity.VerityEntity;

/**
 * 骑乘键（默认 R）的客户端处理。
 *
 * <p><b>为什么要独立成键</b>：骑乘原来用「潜行 + 空手右键」，而 PatPat 的抚摸手势
 * **默认就是潜行右键**。我们为了拦住原版「空手右键把 Verity 收进物品」，
 * 必须在 {@code PlayerInteractEvent.EntityInteract} 的 HIGHEST 优先级取消那个事件——
 * 一旦取消，PatPat 就永远收不到抚摸请求。两个功能抢同一个手势，只能有一个生效。
 *
 * <p>所以骑乘改成按 R：
 * <ul>
 *   <li>已经骑着 → 请求下来；</li>
 *   <li>没骑 → 看准星指向的那只 Verity，在交互距离内就请求骑上去。</li>
 * </ul>
 * 抚摸还给 PatPat，右键还给原版（普通右键仍然是「把 Verity 收进物品」）。
 */
@OnlyIn(Dist.CLIENT)
public final class RideKeyHandler {

    private RideKeyHandler() {
    }

    /** 交互距离的平方：原版玩家触及距离约 3 格，留一点余量。 */
    private static final double REACH_SQR = 4.0D * 4.0D;

    public static void onRideKey(Minecraft minecraft) {
        Player player = minecraft.player;
        if (player == null) return;

        // 已经骑在 Verity 身上：这个键就是「下来」
        if (player.getVehicle() instanceof VerityEntity) {
            HeadPerchNetwork.sendDismount();
            return;
        }
        // 骑在别的东西上时不插手
        if (player.getVehicle() != null) return;

        VerityEntity target = lookedAtVerity(minecraft, player);
        if (target == null) {
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal("§7【骑乘】先把准星对准 Verity"), true);
            return;
        }
        if (target.distanceToSqr(player) > REACH_SQR) {
            player.displayClientMessage(
                    net.minecraft.network.chat.Component.literal("§7【骑乘】离得太远了"), true);
            return;
        }
        OllamaProxyMod.LOGGER.debug("[骑乘键] 请求骑乘 Verity id={}", target.getId());
        HeadPerchNetwork.sendRideRequest(target.getId());
    }

    /** 准星指向的 Verity；没指着实体就返回 {@code null}。 */
    private static VerityEntity lookedAtVerity(Minecraft minecraft, Player player) {
        HitResult hit = minecraft.hitResult;
        if (hit instanceof EntityHitResult entityHit) {
            Entity entity = entityHit.getEntity();
            if (entity instanceof VerityEntity verity && verity.isAlive()) {
                return verity;
            }
        }
        return null;
    }
}
