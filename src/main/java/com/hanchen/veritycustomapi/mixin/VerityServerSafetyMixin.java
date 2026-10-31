package com.hanchen.veritycustomapi.mixin;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.compat.VerityWorldStateGuard;
import com.hanchen.veritycustomapi.config.ModConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import varmite.verity.entity.demon.VerityDemonEntity;
import varmite.verity.entity.verity.VerityEntity;

@Mixin(VerityEntity.class)
public abstract class VerityServerSafetyMixin {
    /** 「已拦下关昼夜循环」只记一次日志，避免刷屏。 */
    @org.spongepowered.asm.mixin.Unique
    private static boolean daylightBlockLogged;

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

    /**
     * 从源头拦掉原版关昼夜循环的调用。
     *
     * <p>反编译 {@code VerityEntity.tick()}（6.0.0，SRG 约 343 行）：
     * <pre>
     * if (dayTime / 24000 &gt;= VerityConfig.DAY_COUNT &amp;&amp; verityKarma &lt; 9000) {
     *     if (gamerule(RULE_DAYLIGHT).get() &amp;&amp; !server.isHardcore()) {
     *         gamerule(RULE_DAYLIGHT).set(false, server);     // ← 每 tick 都关一次
     *     }
     *     transformIntoDemon(...);
     * }
     * </pre>
     * 这是原版的「世界末日」设计：好感不到 9000 就一直永夜，而且整个 jar 里
     * <b>没有任何一处把它设回 true</b>。我们之前靠 tick 里对打恢复，结果是
     * 每 tick 开→关→开，日志刷屏、每次还同步一遍 gamerule 给所有客户端，而且永远修不好。
     *
     * <p>现在直接在调用点拦下：<b>原版可以变身、可以关生物生成，但关不掉昼夜循环</b>。
     * 用规则实例做身份比对（同一个 tick 里还会关 {@code doMobSpawning}，那条不能拦）。
     */
    @Redirect(method = "m_8119_",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/GameRules$BooleanValue;m_46246_(ZLnet/minecraft/server/MinecraftServer;)V"),
            require = 0, remap = false)
    private void verityExtended$keepDaylightCycle(GameRules.BooleanValue rule, boolean value,
                                                  MinecraftServer server) {
        if (!value && server != null
                && rule == server.getGameRules().getRule(GameRules.RULE_DAYLIGHT)) {
            if (!daylightBlockLogged) {
                daylightBlockLogged = true;
                OllamaProxyMod.LOGGER.info("已拦下原版关闭昼夜循环的调用：世界继续昼夜交替"
                        + "（变身与生物生成不受影响）");
            }
            return;
        }
        rule.set(value, server);
    }

    // 注入点 remap = false 表示原样使用，Minecraft 成员名是 SRG，故必须写 SRG 名（可查 build/createSrgToMcp/output.srg）：
    // VerityEntity.tick -> m_8119_，ServerLevel.getAllEntities -> m_8583_；写 official 名会因 require = 0 静默失效。
    @Inject(method = "m_8119_", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;m_8583_()Ljava/lang/Iterable;"),
            cancellable = true, require = 0, remap = false)
    private void verityExtended$skipGlobalEntityPurge(CallbackInfo callback) {
        if (!ModConfig.SERVER_VERITY_ALLOW_GLOBAL_ENTITY_PURGE.get()) callback.cancel();
    }
}
