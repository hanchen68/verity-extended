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

    /**
     * 恐惧压力：距离、追猎状态、是否锁定玩家、是否被注视、玩家血量共同决定的一个 0~1 值。
     *
     * <p>这个值驱动镜头抖动、音效强度和暗角，是整条恐怖体验的**唯一强度来源**。
     * 抽出来的理由：原来它埋在导演类里，改一个系数要在游戏里反复体感，
     * 而且「为什么这次特别吓人」很难归因——现在可以直接对着数值看。
     *
     * <p>各项权重（按贡献从大到小）：
     * <ul>
     *   <li><b>距离</b>：96 格外为 0，线性升到脚边 0.56；</li>
     *   <li><b>进入追猎态</b>（{@code state == 1}）+0.24，是最强的一档；</li>
     *   <li><b>被锁定</b> +0.13，<b>追猎中且正在注视</b>再 +0.08；</li>
     *   <li><b>低血量</b>：状态紧张且血量低于 40% 时按缺口线性加成。</li>
     * </ul>
     *
     * <p>基准 0.06 让玩家在完全安全时仍有极轻微的存在感，不至于「什么都没有」。
     */
    public static float pressureFor(double distance, int state, int hunt, boolean targeted,
                                    boolean looking, float healthRatio) {
        float proximity = Mth.clamp((float) ((96.0D - distance) / 88.0D), 0.0F, 1.0F);
        float result = 0.06F + proximity * 0.56F;
        if (hunt > 0) result += 0.13F;
        if (state == 1) result += 0.24F;
        if (targeted) result += 0.13F;
        if (looking && hunt > 0) result += 0.08F;
        if ((state == 1 || targeted) && healthRatio < 0.4F) {
            result += (0.4F - healthRatio) * 0.45F;
        }
        return Mth.clamp(result, 0.0F, 1.0F);
    }

    /**
     * 恐怖阶段（1~4），决定用哪一档表现。
     *
     * <p>🔴 阶段与压力是**两套独立判据**，不要试图用压力反推阶段：
     * 阶段看的是「玩家还有没有退路」之类的离散条件，
     * 压力是连续强度。混用会让「远处但被锁定」这种情况表现异常。
     *
     * <p>判据按严重程度从高到低，命中即返回：
     * <ol>
     *   <li>4 阶：被锁定且已逼近 12 格内 / 追猎中且 7 格内 / 紧张状态下血量低于 25%；</li>
     *   <li>3 阶：处于追猎态或被锁定；</li>
     *   <li>2 阶：有追猎计数 / 正在被注视 / 28 格内；</li>
     *   <li>1 阶：其余。</li>
     * </ol>
     */
    public static int stageFor(double distance, int state, int hunt, boolean targeted,
                               boolean looking, float healthRatio) {
        if ((targeted && distance < 12.0D) || (state == 1 && distance < 7.0D)
                || ((state == 1 || targeted) && healthRatio < 0.25F)) return 4;
        if (state == 1 || targeted) return 3;
        if (hunt > 0 || looking || distance < 28.0D) return 2;
        return 1;
    }
}
