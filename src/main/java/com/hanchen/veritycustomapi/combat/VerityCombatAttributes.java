package com.hanchen.veritycustomapi.combat;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.event.entity.EntityAttributeModificationEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import varmite.verity.entity.ModEntities;
import varmite.verity.entity.verity.VerityEntity;

/**
 * 给 {@code VerityEntity} 补上近战需要的两个原版属性。
 *
 * <p><b>为什么需要</b>：{@code VerityEntity.createAttributes()} 用的是
 * {@code LivingEntity.createLivingAttributes()}，只带 MAX_HEALTH；
 * 它自己又手动加了 MOVEMENT_SPEED / FOLLOW_RANGE，但**没有 ATTACK_DAMAGE**。
 * 原版 {@code Mob#doHurtTarget} 第一行就读 ATTACK_DAMAGE，
 * 1.20.1 里紧接着还会读 ATTACK_KNOCKBACK，缺任何一个都会抛
 * {@code IllegalArgumentException: Can't find attribute minecraft:generic.attack_damage}。
 *
 * <p><b>为什么用 {@code EntityAttributeModificationEvent} 而不是 Mixin</b>：
 * 这是 Forge 官方给「已经注册过属性的实体类型」追加属性的入口。
 * 它在 {@code EntityAttributeCreationEvent} 之后触发，直接改 AttributeSupplier，
 * 所有新建的 VerityEntity 都会带上，不需要碰第三方类的字节码。
 *
 * <p><b>兜底</b>：万一事件没触发（Forge 版本变动等），
 * {@link FightTargetGoal#hurtTarget} 走的是 {@code target.hurt(mobAttack)}，
 * 不读这两个属性，功能仍然可用、不会崩。
 */
@Mod.EventBusSubscriber(modid = OllamaProxyMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class VerityCombatAttributes {
    private VerityCombatAttributes() {}

    /** 0.0 即可：不额外加击退，属性存在只是为了满足 doHurtTarget 的读取。 */
    private static final double ATTACK_KNOCKBACK = 0.0D;

    @SubscribeEvent
    public static void onEntityAttributeModification(EntityAttributeModificationEvent event) {
        EntityType<VerityEntity> type = ModEntities.VERITY_ENTITY.get();
        if (!event.has(type, Attributes.ATTACK_DAMAGE)) {
            event.add(type, Attributes.ATTACK_DAMAGE, CombatAuraManager.ATTACK_DAMAGE);
        }
        if (!event.has(type, Attributes.ATTACK_KNOCKBACK)) {
            event.add(type, Attributes.ATTACK_KNOCKBACK, ATTACK_KNOCKBACK);
        }
    }
}
