package com.hanchen.veritycustomapi.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** 骑乘时需要的 Entity 成员：上台阶高度与受控移动。 */
@Mixin(Entity.class)
public interface EntityRideAccessor {
    @Accessor("maxUpStep")
    void verityExt$setMaxUpStep(float value);

    @Accessor("maxUpStep")
    float verityExt$getMaxUpStep();

    @Invoker("moveRelative")
    void verityExt$moveRelative(float amount, Vec3 movement);
}
