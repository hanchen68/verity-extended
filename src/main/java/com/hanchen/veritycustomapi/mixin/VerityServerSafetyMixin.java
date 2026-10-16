package com.hanchen.veritycustomapi.mixin;

import com.hanchen.veritycustomapi.compat.VerityWorldStateGuard;
import com.hanchen.veritycustomapi.config.ModConfig;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import varmite.verity.entity.demon.VerityDemonEntity;
import varmite.verity.entity.verity.VerityEntity;

@Mixin(VerityEntity.class)
public abstract class VerityServerSafetyMixin {
    @Inject(method = "transformIntoDemon", at = @At("RETURN"), require = 0, remap = false)
    private void verityExtended$limitDemonHealth(net.minecraft.world.entity.player.Player player,
                                                 boolean provoked,
                                                 CallbackInfoReturnable<Boolean> callback) {
        if (!callback.getReturnValueZ() || !(player.level() instanceof ServerLevel level)) return;
        int limit = ModConfig.SERVER_VERITY_DEMON_MAX_HEALTH.get();
        for (VerityDemonEntity demon : level.getEntitiesOfClass(VerityDemonEntity.class,
                ((VerityEntity) (Object) this).getBoundingBox().inflate(256.0D))) {
            AttributeInstance maxHealth = demon.getAttribute(Attributes.MAX_HEALTH);
            if (maxHealth == null) continue;
            double capped = Math.min(maxHealth.getBaseValue(), limit);
            if (maxHealth.getBaseValue() != capped) maxHealth.setBaseValue(capped);
            if (demon.getHealth() > capped) demon.setHealth((float) capped);
        }
    }

    /** 每 tick 开头跟踪昼夜循环，识别「原版为变身关掉 doDaylightCycle」，每 40 tick 做一次重启后标记丢失的兜底恢复。见 {@link VerityWorldStateGuard}。 */
    @Inject(method = "m_8119_", at = @At("HEAD"), require = 0, remap = false)
    private void verityExtended$trackDaylightCycle(CallbackInfo callback) {
        VerityEntity self = (VerityEntity) (Object) this;
        VerityWorldStateGuard.onTickStart(self.level(), self.tickCount);
    }

    // 注入点 remap = false 表示原样使用，Minecraft 成员名是 SRG，故必须写 SRG 名（可查 build/createSrgToMcp/output.srg）：
    // VerityEntity.tick -> m_8119_，ServerLevel.getAllEntities -> m_8583_；写 official 名会因 require = 0 静默失效。
    @Inject(method = "m_8119_", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;m_8583_()Ljava/lang/Iterable;"),
            cancellable = true, require = 0, remap = false)
    private void verityExtended$skipGlobalEntityPurge(CallbackInfo callback) {
        if (!ModConfig.SERVER_VERITY_ALLOW_GLOBAL_ENTITY_PURGE.get()) callback.cancel();
    }
}
