package com.hanchen.veritycustomapi.mixin;

import com.hanchen.veritycustomapi.client.SecondSightManager;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「第二视角」的镜头接管点。
 *
 * <p>原版 {@code Camera.setup(...)} 每次渲染都会按玩家重新算位置，所以必须在它的
 * <b>RETURN</b> 处覆盖，否则下一帧就被改回去。{@code require = 0}：签名变了只是视角失效，
 * 不能让整个客户端类加载失败（渲染类崩在注册阶段 = 进不去游戏）。
 */
@Mixin(Camera.class)
public abstract class CameraSecondSightMixin {
    /**
     * 镜头位置字段。开发环境用官方名 {@code position}，运行时 Mixin 自动重映射到 SRG。
     * 走字段而不是 {@code setPosition(...)}：1.20.1 那个方法是 protected，外部类调不到。
     */
    @Shadow
    private Vec3 position;

    @Inject(method = "m_90575_", at = @At("RETURN"), require = 0, remap = false)
    private void verityExtended$secondSight(BlockGetter level, Entity entity, boolean detached,
                                            boolean thirdPersonReverse, float partialTick,
                                            CallbackInfo callback) {
        try {
            // detached = 原版已经在第三人称。此时它自己已经后退过镜头，
            // 我们必须把「额外后退」让出来，否则镜头会被推远两倍。
            Vec3 target = SecondSightManager.sampleCameraPosition(partialTick, detached);
            if (target != null) {
                this.position = target;
            }
        } catch (Throwable ignored) {
            // 视角接管失败就退回原版镜头
            SecondSightManager.stop();
        }
    }
}
