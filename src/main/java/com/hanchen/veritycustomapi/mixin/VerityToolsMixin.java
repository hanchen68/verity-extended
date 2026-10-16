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
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import varmite.verity.entity.llm.actions.Tools;

/** 接管原版物品搜索工具并新增给物品工具：中英双语搜索，给予直接进背包（满了掉脚边）。 */
@Mixin(Tools.class)
public abstract class VerityToolsMixin {
    @Shadow(remap = false)
    public ServerPlayer player;

    @Inject(method = "list_available_itemids", at = @At("HEAD"), cancellable = true, remap = false)
    private void verityExt$bilingualItemSearch(String prefix, CallbackInfoReturnable<String> cir) {
        if (!Boolean.TRUE.equals(ModConfig.SERVER_VERITY_ITEM_TOOLS.get())) {
            return;
        }
        try {
            ServerPlayer target = this.player;
            if (target == null || target.server == null) {
                cir.setReturnValue("现在读不到你的玩家数据，稍后再试试吧。");
                return;
            }
            // 工具本身就在服务端线程被调用，submit().join() 会把服务器锁死
            cir.setReturnValue(target.server.isSameThread()
                    ? ItemRegistryService.search(prefix)
                    : target.server.submit(() -> ItemRegistryService.search(prefix)).join());
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.warn("物品搜索工具失败", error);
            cir.setReturnValue("物品搜索暂时出问题了，请稍后再试。");
        }
    }

    /** 新增给物品工具（原版 6.2 自带的 give_item 只认 ID，这里补按名字给；名字不能与原版重复）。 */
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
