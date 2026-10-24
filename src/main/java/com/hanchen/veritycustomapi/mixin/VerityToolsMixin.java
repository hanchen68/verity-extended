package com.hanchen.veritycustomapi.mixin;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.compat.StellarRemoteCompat;
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

    // 「给物品」工具已拆到 {@link VerityGiveItemByNameMixin}：
    // 本类因为要 @Inject 原版 list_available_itemids，在 6.0.0（该方法已删除）会被整块跳过，
    // 而给物品是玩家实际在用的功能，不能跟着失效。

    /** 《The Shooting Star》适配：Verity 借玩家的星之遥控器，从自己位置朝目标施放技能。 */
    @Unique
    @Tool(name = "use_stellar_remote", value = {
            "让 Verity 举起星之遥控器（The Shooting Star 模组）施放技能，弹道从 Verity 当前位置朝目标打出。",
            "⚠️ 只有玩家自己明确提到遥控器、或明确要求放技能时才调用。这个工具存在不代表玩家身上有遥控器——没提就不要主动提议，更不要假设他拿着。",
            "skill 传技能 id（如 the_shooting_star、gungnir、halley）；传 list 会返回遥控器上的技能清单。",
            "target 传 \"x y z\" 表示想打的位置；传 look 或不传则朝玩家准星指向的位置。",
            "返回值里带实际落点坐标。技能是从 Verity 眼睛沿这条视线打出去、命中第一个障碍，所以中间有遮挡时会打近；发现落点偏了就把偏移原因告诉玩家，或换个位置/落点再试。",
            "这些技能威力很大，只在玩家明确要求时才调用。"
    })
    public String verityExt$useStellarRemote(
            @P("技能 id 或关键字；传 list 查询可用技能") String skill,
            @P("落点坐标 \"x y z\"，或 look") String target) {
        try {
            ServerPlayer owner = this.player;
            if (owner == null || owner.server == null) {
                return "现在读不到你的玩家数据，稍后再试试吧。";
            }
            return owner.server.isSameThread()
                    ? StellarRemoteCompat.cast(owner, skill, target)
                    : owner.server.submit(() -> StellarRemoteCompat.cast(owner, skill, target)).join();
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.warn("星之遥控器工具失败", error);
            return "施放星之遥控器时出问题了，请稍后再试。";
        }
    }
}
