package com.hanchen.veritycustomapi.client;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 恐怖导演的纯判定：按钮文案识别、环境权重与触发概率、实体类型判断。
 *
 * <p>抽自 {@code VerityHorrorDirector}。这些方法**不读世界、不写状态**，
 * 只吃布尔与实体，所以能独立出来。恐怖效果本身很难复现测试，
 * 但「权重怎么算、概率上限多少」这类数值逻辑抽出来就能直接验。
 */
public final class HorrorRules {

    private HorrorRules() {
    }

    /**
     * 是否是「退出」类按钮文案。
     *
     * <p>用于假退出界面：玩家真的点退出时要放行，其余点击都拦住。
     * 中英双语都覆盖——原版中文汉化后的文案与英文原文都在这里。
     */
    public static boolean isQuitLabel(String text) {
        return text.contains("保存并退出")
                || text.contains("保存并回到标题屏幕")
                || text.contains("保存并回到")
                || text.contains("退出并保存")
                || text.contains("回到标题屏幕")
                || text.contains("Save and Quit")
                || text.contains("断开连接")
                || text.contains("Disconnect");
    }

    /**
     * 环境权重：越「孤立 + 黑暗 + 地下 + 移开视线」越容易出事。
     *
     * <p>权重不是概率，只是强度；真正概率由 {@link #ambientChance} 换算并封顶，
     * 所以这里可以放心累加，不会因为叠加而变得必触发。
     */
    public static int ambientContextWeight(boolean night, boolean underground, boolean dark,
                                           boolean alone, boolean lookedAway) {
        int weight = 0;
        if (night) weight += 2;
        if (underground) weight += 3;
        if (dark) weight += 2;
        if (alone) weight += 1;
        if (lookedAway) weight += 3;
        return weight;
    }

    /**
     * 每 tick 的触发概率。
     *
     * <p>上限 1.8% 是硬封顶：即使玩家在完全黑暗的地下独自挂机，
     * 也不会出现「一进去就被吓」的体验。频率由玩家配置，夹在 0~3 之间。
     */
    public static double ambientChance(int contextWeight, double frequency) {
        double safeFrequency = Mth.clamp(frequency, 0.0D, 3.0D);
        return Math.min(0.018D, (0.0015D + Mth.clamp(contextWeight, 0, 11) * 0.00115D)
                * safeFrequency);
    }

    /**
     * 是否「正常的 Verity」（不是恶魔变体）。
     *
     * <p>按注册名判断而不是 instanceof：扩展要兼容多个原版版本，
     * 直接引类会在版本不匹配时抛 NoClassDefFoundError。
     */
    public static boolean isNormalVerity(Entity entity) {
        if (entity == null || !entity.isAlive()) return false;
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        return id != null && "verity".equals(id.getNamespace()) && "verity".equals(id.getPath());
    }
}
