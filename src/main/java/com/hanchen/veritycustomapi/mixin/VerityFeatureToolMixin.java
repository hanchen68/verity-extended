package com.hanchen.veritycustomapi.mixin;

import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.action.AiBuildingManager;
import com.hanchen.veritycustomapi.config.ModConfig;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import varmite.verity.entity.llm.actions.Tools;

/**
 * 把「AI 建造」和「摄像头观察」做成模型可调用的工具。
 *
 * <p><b>为什么值得改成工具</b>：这两个功能原来是靠**文本标记 / 关键词**触发的——
 * 摄像头要在回复里出现特定标记，建造要看玩家句子里有没有对应词。
 * 那是「猜意图」，模型想用的时候用不上、不想用的时候被触发。
 * 做成 {@code @Tool} 之后，能力出现在工具清单里，由模型自己决定什么时候调，
 * 参数也由它填（建筑类型、尺寸），比关键词匹配稳得多。
 *
 * <p><b>与 {@code ToolAvailabilityFilter} 的配合</b>：功能没开时这两个工具会被
 * 从清单里摘掉——模型看不到就不会编造「我帮你盖好了」。这里的返回文案是
 * 第二道防线，只在「清单里还有、但调用时前置条件变了」时才会出现。
 *
 * <p>与 {@link VerityGiveItemByNameMixin} 分开写，理由同那里：
 * 那个类会因原版方法变动被整块跳过，功能不能陪葬。
 */
@Mixin(Tools.class)
public abstract class VerityFeatureToolMixin {

    @Shadow(remap = false)
    public ServerPlayer player;

    /** 建筑类型白名单，和 {@code AiBuildingManager.createPlan} 的 switch 保持一致。 */
    @Unique
    private static final String BUILD_TYPES =
            "house（小屋）/ cottage（农舍）/ villa（别墅）/ tower（塔）/ wall（围墙）/ "
                    + "platform（平台）/ bridge（桥）/ pyramid（金字塔）/ temple（神庙）/ castle（城堡）";

    /**
     * AI 建造。
     *
     * <p>走 {@code AiBuildingManager.start}，它自己会做「是否单人世界 / 是否已有任务 /
     * 材料是否合法 / 方块数是否超上限」的全部校验并给玩家发提示，
     * 所以这里只需要把参数拼好、在服务端线程调用。
     */
    @Unique
    @Tool(name = "build_structure", value = {
            "在玩家面前盖一座建筑。只在玩家明确要求建造时调用。",
            "type 支持：" + BUILD_TYPES + "。",
            "size 是边长（格），3-32，默认 7；越大约耗材料和时间。",
            "调用后建造是分帧进行的，不要描述建造结果，也不要假装已经盖好；"
                    + "只说一句「开始盖了」这类简短回应即可。"
    })
    public String verityExt$buildStructure(
            @P("建筑类型，如 house / tower / castle") String type,
            @P("边长（格），3-32，默认 7") int size) {
        if (!ModConfig.ENABLE_AI_BUILDING.get()) {
            return "AI 建造功能没开启（让玩家到「Verity 扩展增强 → AI 建造」里打开）。";
        }
        ServerPlayer target = this.player;
        if (target == null || target.server == null) {
            return "现在读不到你的玩家数据，稍后再试试吧。";
        }
        String kind = type == null || type.isBlank() ? "house" : type.trim().toLowerCase(java.util.Locale.ROOT);
        int edge = Math.max(3, Math.min(32, size <= 0 ? 7 : size));
        String[] params = {kind, String.valueOf(edge)};
        try {
            // AiBuildingManager 会读玩家位置、写方块，必须在服务端线程执行。
            // 服务端线程上再 submit().join() 会把服务器锁死，所以分两种情况。
            if (target.server.isSameThread()) {
                AiBuildingManager.getInstance().start(target.server, target, params);
            } else {
                target.server.submit(() -> AiBuildingManager.getInstance()
                        .start(target.server, target, params)).join();
            }
            return "已开始建造 " + kind + "（边长 " + edge + "）。方块是一批批放的，"
                    + "现在不要描述建成的样子。";
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.warn("AI 建造工具调用失败", error);
            return "建造启动失败，请稍后再试。";
        }
    }

    /**
     * 摄像头观察。
     *
     * <p>🔴 采集在**客户端**（摄像头插在玩家机器上），而工具跑在服务端线程，
     * 所以必须经 {@link DistExecutor} 转到客户端；专用服务器上没有客户端，
     * 这时直接返回「这台服务器看不到摄像头」，绝不能加载客户端类。
     *
     * <p>画面不能作为工具返回值塞回来（工具结果只有文本），
     * 所以这里触发的是原有的「采集 → 回环追问」链路：
     * 这一轮先回一句「我看看」，下一轮会带着图片再问一次模型。
     */
    @Unique
    @Tool(name = "look_through_camera", value = {
            "打开玩家摄像头看一眼（也能看到他的游戏画面，取决于设置）。",
            "只在玩家明确要你看、或你确实需要知道他的样子/环境时调用，不要每句话都看。",
            "画面不会立刻出现在结果里：调用后这一轮只需回一句「我看看」这类短句，"
                    + "下一轮会自动附上图片。**不要在这一轮描述画面内容**，那是编的。"
    })
    public String verityExt$lookThroughCamera() {
        if (!ConfigManager.isCameraEnabled()) {
            return "视觉功能没开启（让玩家到「Verity 扩展增强 → 摄像头视觉」里打开）。";
        }
        if (!ConfigManager.isCameraSkillEnabled()) {
            return "AI 主动观察被关掉了（配置里 camera_vision.skillEnabled），只能用快捷键看。";
        }
        try {
            Boolean started = DistExecutor.unsafeCallWhenOn(Dist.CLIENT,
                    () -> com.hanchen.veritycustomapi.client.CameraChatVision::requestSkillLook);
            if (Boolean.TRUE.equals(started)) {
                return "画面正在采集，下一轮会附上。这一轮只说一句「我看看」，不要描述画面。";
            }
            return "画面没能采集（可能刚看过、在冷却里，或者摄像头被占用）。";
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.warn("摄像头工具调用失败", error);
            return "采集画面失败，请稍后再试。";
        }
    }
}
