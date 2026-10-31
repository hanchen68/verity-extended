package com.hanchen.veritycustomapi.mixin;

import com.hanchen.veritycustomapi.combat.MonstrousManager;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import varmite.verity.entity.verity.VerityEntity;

/**
 * 残血深渊觉醒的每 tick 维护。
 *
 * <p>注入在 {@link Entity#tick()} 的 TAIL，只对 {@link VerityEntity} 生效——
 * 觉醒状态要每 tick 检查「恶魔还活着吗、目标跑远了吗、表情该换了吗」，
 * 而这些都跟实体自己的 AI 无关，挂在 {@code Entity.tick} 上最省事、也不会被 goal 抢占打断。
 *
 * <p>{@code method = "tick"} 走 refmap 自动映射到 SRG（{@code m_8119_}），
 * 和 {@code EntityPerchRideMixin} 同一套写法。
 */
@Mixin(Entity.class)
public abstract class VerityEntityCombatMixin {

    @Inject(method = "tick", at = @At("TAIL"))
    private void verityExt$combatTick(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self instanceof VerityEntity verity) {
            MonstrousManager.tick(verity);
        }
    }
}
