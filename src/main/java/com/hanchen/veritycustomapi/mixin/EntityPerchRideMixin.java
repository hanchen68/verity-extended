package com.hanchen.veritycustomapi.mixin;

import com.hanchen.veritycustomapi.compat.VerityShoulderCarry;
import com.hanchen.veritycustomapi.ride.HeadPerchManager;
import com.hanchen.veritycustomapi.ride.HeadPerchState;
import com.hanchen.veritycustomapi.ride.RideManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import varmite.verity.entity.verity.VerityEntity;

import java.util.UUID;

/**
 * 站头 + 骑乘的实体级 Hook。全部注入在 {@link Entity} 自己声明的方法上，
 * 注入体内用 instanceof 判断 VerityEntity / Player 再干活，不污染普通实体。
 *
 * 两条关系的做法刻意不同：
 *
 * - 骑乘（玩家坐 Verity 身上）走原版乘客系统——乘客是玩家，原版会无条件 tick，
 * 没有额外限制，是最省事也最稳的做法；
 * - 站头（Verity 站玩家头上）不走乘客系统：把玩家当载具要一路对抗原版
 * （乘客不在 tick 列表就被强制 {@code stopRiding}，而且乘客照样会被 tick，
 * Verity 自己的 {@code aiStep -> travel} 会与定位打架）。改成「状态 + 每帧自己定位」，
 * 只记 Verity 挂在谁身上，位置与重力全由我们控制。
 *
 */
@Mixin(Entity.class)
public abstract class EntityPerchRideMixin {
    @Unique
    private UUID verityExt$lastRider;

    @Unique
    private int verityExt$riderMissingTicks;

    /** 允许 Verity 站到玩家头上、允许玩家骑到 Verity 身上；其余保持原版判定。 */
    @Inject(method = "canAddPassenger", at = @At("HEAD"), cancellable = true)
    private void verityExt$allowVerityPassenger(Entity passenger, CallbackInfoReturnable<Boolean> cir) {
        Entity self = (Entity) (Object) this;
        if (self instanceof Player && passenger instanceof VerityEntity
                && self.getPassengers().isEmpty()) {
            cir.setReturnValue(true);
            return;
        }
        if (self instanceof VerityEntity verity && passenger instanceof Player
                && !verity.isMonstrous() && verity.getVehicle() == null) {
            cir.setReturnValue(true);
        }
    }

    /** 玩家骑在 Verity 身上时由玩家操控。 */
    @Inject(method = "getControllingPassenger", at = @At("HEAD"), cancellable = true)
    private void verityExt$playerInControl(CallbackInfoReturnable<LivingEntity> cir) {
        Entity self = (Entity) (Object) this;
        if (self instanceof VerityEntity verity && verity.getFirstPassenger() instanceof Player player) {
            cir.setReturnValue(player);
        }
    }

    /** 骑乘时乘客的定位统一走 positionRider，避免与乘客自己的 tick 互相覆盖。 */
    @Inject(method = "positionRider", at = @At("HEAD"), cancellable = true)
    private void verityExt$ridePosition(Entity passenger, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self instanceof VerityEntity verity && passenger instanceof Player rider) {
            rider.moveTo(verity.getX(), verity.getY() + verity.getBbHeight() + 0.12D, verity.getZ());
            ci.cancel();
        }
    }

    /** 站头的每帧定位 + 骑乘的跟随与下骑检测。 */
    @Inject(method = "tick", at = @At("TAIL"))
    private void verityExt$perchAndRideTick(CallbackInfo ci) {
        Entity selfEntity = (Entity) (Object) this;
        if (!(selfEntity instanceof VerityEntity self)) return;

        // Verity 若被扛到肩上（部分构建的机制），两套定位会同时写 Verity 的坐标：让出我们的坐骑关系。
        // 让位时顺带清掉追踪标记，避免下一 tick 把这次被动让位当成「玩家让 Verity 下来了」报给 AI。
        if (VerityShoulderCarry.isCarried(self)) {
            verityExt$yieldToShoulderCarry(self);
            return;
        }

        // 站头：状态驱动，位置完全跟随承载者；处理完就不再走骑乘分支
        if (verityExt$perchTick(self)) {
            return;
        }

        Entity passenger = self.getFirstPassenger();
        if (passenger instanceof Player rider) {
            verityExt$riderMissingTicks = 0;
            if (self.isMonstrous()) {
                rider.stopRiding();
                return;
            }
            rider.setDeltaMovement(self.getDeltaMovement());
            if (rider instanceof ServerPlayer) {
                verityExt$lastRider = rider.getUUID();
            }
        } else if (verityExt$lastRider != null && ++verityExt$riderMissingTicks >= 3
                && self.level() instanceof ServerLevel level) {
            Float originalStep = com.hanchen.veritycustomapi.ride.VerityStepStore.take(self.getId());
            if (originalStep != null) {
                ((EntityRideAccessor) self).verityExt$setMaxUpStep(originalStep);
            }
            Entity old = level.getEntity(verityExt$lastRider);
            if (old instanceof ServerPlayer rider) {
                RideManager.onDismounted(self, rider);
            }
            verityExt$lastRider = null;
            verityExt$riderMissingTicks = 0;
        }
    }

    /**
     * 站头每帧维护：把 Verity 摆到承载者头顶。
     *
     * 只在服务端做——客户端靠原版实体位置同步跟随（我们的 travel 掐断保证 Verity 不会在
     * 客户端自己掉下去）。承载者消失、死亡或换维度时静默解除。
     *
     * 每 10 秒打一条位置对比日志：服务端位置明明是对的、可客户端就是看不到人时，
     * 这条能直接分出是定位问题还是同步问题，不用再猜。
     *
     * @return true 表示 Verity 此刻处于站头状态，调用方不要再处理骑乘分支
     */
    @Unique
    private boolean verityExt$perchTick(VerityEntity self) {
        UUID carrierId = HeadPerchState.carrierOf(self.getUUID());
        if (carrierId == null) {
            return false;
        }
        // 客户端也摆一次位置。单人游戏下客户端与服务端共享同一份状态，客户端自己定位可以保证
        // 「站在头上」是客户端的最终判定，不必依赖同步包的时序（实测只靠同步时，服务端位置对、
        // 但客户端画面上人不在头上）。专用服务器上客户端拿不到状态，自然走原版实体同步。
        // 旋转现在连 ?RotO 一起复制，所以客户端摆位不会再抽搐。
        if (self.level().isClientSide) {
            Player carrier = self.level().getPlayerByUUID(carrierId);
            if (carrier != null) {
                HeadPerchManager.placeOnHead(self, carrier);
                if (self.level().getGameTime() % 200 == 0) {
                    com.hanchen.veritycustomapi.OllamaProxyMod.LOGGER.info(
                            "【站头·客户端】Verity=({}, {}, {}) 差Y={}",
                            fmt(self.getX()), fmt(self.getY()), fmt(self.getZ()),
                            fmt(self.getY() - carrier.getY()));
                }
            }
            return true;
        }
        if (!(self.level() instanceof ServerLevel level)) {
            return false;
        }
        if (level.getServer() == null) {
            return false;
        }
        ServerPlayer carrier = level.getServer().getPlayerList().getPlayer(carrierId);
        if (carrier == null || !carrier.isAlive()
                || !carrier.level().dimension().equals(level.dimension())) {
            HeadPerchManager.releaseQuietly(self);
            return false;
        }
        HeadPerchManager.placeOnHead(self, carrier);
        long tick = self.level().getGameTime();
        // 位置对比：左侧是 Verity，右侧是承载者。功能已稳定，10 秒一条足够排查用，不再刷屏。
        if (tick % 200 == 0) {
            com.hanchen.veritycustomapi.OllamaProxyMod.LOGGER.info(
                    "【站头】位置 Verity=({}, {}, {}) 玩家=({}, {}, {}) 差Y={} noPhysics={}",
                    fmt(self.getX()), fmt(self.getY()), fmt(self.getZ()),
                    fmt(carrier.getX()), fmt(carrier.getY()), fmt(carrier.getZ()),
                    fmt(self.getY() - carrier.getY()), self.noPhysics);
        }
        return true;
    }

    /** log4j 的 {} 不支持 {:.2f} 这类格式，必须自己格式化好再传，否则参数会错位。 */
    @Unique
    private static String fmt(double value) {
        return String.format("%.2f", value);
    }

    /** Verity 被扛到肩上时让出坐骑关系：解除乘客/载具，恢复上台阶高度，且不向 AI 报告。 */
    @Unique
    private void verityExt$yieldToShoulderCarry(VerityEntity self) {
        boolean released = false;
        if (self.getVehicle() instanceof Player) {
            self.stopRiding();
            released = true;
        }
        if (self.getFirstPassenger() instanceof Player rider) {
            rider.stopRiding();
            released = true;
        }
        if (released || verityExt$lastRider != null) {
            Float originalStep = com.hanchen.veritycustomapi.ride.VerityStepStore.take(self.getId());
            if (originalStep != null) {
                ((EntityRideAccessor) self).verityExt$setMaxUpStep(originalStep);
            }
        }
        verityExt$lastRider = null;
    }
}
