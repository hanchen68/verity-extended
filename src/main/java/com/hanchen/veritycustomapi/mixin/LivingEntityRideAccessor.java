package com.hanchen.veritycustomapi.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 暴露骑乘操控需要的 LivingEntity 输入字段与跳跃。 */
@Mixin(LivingEntity.class)
public interface LivingEntityRideAccessor {
    @Accessor("xxa")
    float verityExt$xxa();

    @Accessor("zza")
    float verityExt$zza();

    @Accessor("jumping")
    boolean verityExt$jumping();

    @Accessor("jumping")
    void verityExt$setJumping(boolean jumping);

    @Invoker("jumpFromGround")
    void verityExt$jumpFromGround();

    @Invoker("getWaterSlowDown")
    float verityExt$waterSlowDown();
}
