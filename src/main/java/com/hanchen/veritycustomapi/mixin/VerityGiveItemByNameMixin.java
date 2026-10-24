package com.hanchen.veritycustomapi.mixin;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.config.ModConfig;
import com.hanchen.veritycustomapi.item.ItemRegistryService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import varmite.verity.entity.llm.actions.Tools;

/**
 * 给物品工具（按名字/ID 给）。
 *
 * <p><b>为什么单独一个 mixin</b>：这个工具原来和「接管原版物品搜索」写在同一个
 * {@code VerityToolsMixin} 里。原版 6.0.0 删掉了 {@code list_available_itemids}，
 * 而那个 {@code @Inject} 找不到目标会让**整个 {@code Tools} 类加载失败**
 * （第一次对话才触发：{@code MixinTransformerError: An unexpected critical error}）。
 * 修法是把那个注入点按能力探测跳过，但跳过时同一个 mixin 里的 {@code @Unique}
 * 工具也会一起失效——给物品是玩家实际在用的功能，不能陪葬。
 *
 * <p>所以拆成两个：{@code VerityToolsMixin} 只管「接管原版搜索」（可能不适用），
 * 这个类只管「新增给物品」（任何版本都适用）。两者互不牵连。
 *
 * <p>不注入任何原版方法，只需要 {@code @Shadow} 拿到 {@code player} 字段，
 * 因此不会因为目标方法消失而失败。
 */
@Mixin(Tools.class)
public abstract class VerityGiveItemByNameMixin {

    @Shadow(remap = false)
    public ServerPlayer player;

    /** 新增给物品工具（原版自带的 give_item 只认 ID，这里补按名字给；名字不能与原版重复）。 */
    @Unique
    @Tool(name = "give_item_by_name", value = {
            "把物品直接放进玩家背包（背包满了会掉在脚边）。",
            "item 参数优先用物品ID（如 minecraft:diamond），也支持英文名或中文名（如 diamond、钻石）。",
            "count 是数量，范围 1-64，不填默认 1。只给普通物品，不要生成带特殊数据的物品。"
    })
    public String verityExt$giveItem(
            @P("要给予的物品：ID、英文名或中文名") String item,
            @P("数量，1-64，默认 1") int count) {
        if (!Boolean.TRUE.equals(ModConfig.SERVER_VERITY_ITEM_TOOLS.get())) {
            return "给物品功能还没开启（让玩家到「Verity 扩展增强」的设置里打开后就能用了）。";
        }
        try {
            ServerPlayer target = this.player;
            if (target == null || target.server == null) {
                return "现在读不到你的玩家数据，稍后再试试吧。";
            }
            int requested = count <= 0 ? 1 : count;
            return target.server.isSameThread()
                    ? ItemRegistryService.give(target, item, requested)
                    : target.server.submit(
                            () -> ItemRegistryService.give(target, item, requested)).join();
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.warn("给物品工具失败", error);
            return "给物品时出问题了，请稍后再试。";
        }
    }
}
