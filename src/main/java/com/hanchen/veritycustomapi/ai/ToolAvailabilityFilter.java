package com.hanchen.veritycustomapi.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.compat.StellarRemoteCompat;

import java.util.ArrayList;
import java.util.List;

/**
 * 摘掉「前置条件不满足」的工具，让模型看不到它们。
 *
 * <p><b>为什么需要这一层</b>：模型会把「工具在我手上可用」读成「玩家现在有这个东西」。
 * 现场事故就是这样：{@code use_stellar_remote} 无条件出现在工具清单里，
 * 模型于是主动开口「你手里拿着星之遥控器，要我帮你打那座山吗」——
 * 而玩家手里和背包里都没有那个物品，也根本没装《The Shooting Star》。
 * 玩家反驳之后它才去查背包，才发现是空的。**观感上就是 Verity 在胡说八道。**
 *
 * <p>工具描述写得再小心也没用：模型看的是「有没有这个能力」，
 * 不是「描述里有没有免责声明」。唯一可靠的办法是**根本不给它**。
 *
 * <p>与 {@link ToolListTrimmer} 的分工：
 * <ul>
 *   <li>Trimmer 管「工具太多 → 按意图砍」，是**省 token**；</li>
 *   <li>本类管「工具不该存在 → 摘掉」，是**防误报**。</li>
 * </ul>
 * 两者顺序不能反：先摘掉不该有的，再按意图裁剪，否则 Trimmer 可能把
 * 一个本该摘掉的工具当成「命中意图」保留下来。
 *
 * <p>🔴 判据必须是**真实前置条件**，不能是「玩家可能会误用」这种主观判断：
 * 每摘一个工具都对应一次「这个工具现在调了一定失败」。宁可不摘，也不要
 * 因为担心误用而让功能消失——那属于内容策略，不属于这里。
 */
public final class ToolAvailabilityFilter {

    /** 需要《The Shooting Star》+ 玩家持有遥控器才能用的工具名。 */
    private static final String STELLAR_REMOTE_TOOL = "use_stellar_remote";

    /** 需要「AI 建造」开关打开才能用。 */
    private static final String BUILD_TOOL = "build_structure";

    /** 需要「视觉能力 + AI 主动观察」两个开关都打开才能用。 */
    private static final String CAMERA_TOOL = "look_through_camera";

    private ToolAvailabilityFilter() {
    }

    /**
     * 原地摘掉不可用工具。
     *
     * @return 是否真的改动过（调用方据此决定要不要重新序列化）
     */
    public static boolean applyInPlace(JsonObject body) {
        if (body == null) return false;
        try {
            if (!body.has("tools") || !body.get("tools").isJsonArray()) return false;
            JsonArray tools = body.getAsJsonArray("tools");
            if (tools.isEmpty()) return false;

            List<String> removed = new ArrayList<>();
            JsonArray kept = new JsonArray();
            for (JsonElement element : tools) {
                String name = nameOf(element);
                if (STELLAR_REMOTE_TOOL.equals(name) && !stellarRemoteUsable()) {
                    removed.add(name);
                    continue;
                }
                if (BUILD_TOOL.equals(name) && !buildUsable()) {
                    removed.add(name);
                    continue;
                }
                if (CAMERA_TOOL.equals(name) && !cameraUsable()) {
                    removed.add(name);
                    continue;
                }
                kept.add(element);
            }
            if (removed.isEmpty()) return false;

            body.add("tools", kept);
            OllamaProxyMod.LOGGER.debug("【工具前置】摘掉不可用工具 {}（剩余 {} 个）",
                    removed, kept.size());
            return true;
        } catch (Throwable error) {
            // 过滤失败不是致命问题：最坏情况是回到「工具可见但调用会失败」，
            // 也就是修复前的行为。让请求照常发出去比丢掉整轮对话好得多。
            OllamaProxyMod.LOGGER.debug("工具可用性过滤失败，保留原清单: {}", error.toString());
            return false;
        }
    }

    /**
     * 星之遥控器是否真的能用。
     *
     * <p>两个条件缺一不可：
     * <ol>
     *   <li>装了《The Shooting Star》（{@link StellarRemoteCompat#available()} 内部会解析
     *       模组类 + 查物品注册表，没装时返回 false 且已熔断，不会反复试）；</li>
     *   <li>适配没在配置里被关掉。</li>
     * </ol>
     *
     * <p>注意这里**不检查玩家是否持有遥控器**：持有状态是每 tick 变的，
     * 而工具清单在请求发出那一刻才决定。如果按持有状态动态摘除，
     * 模型就会「有时知道有这个能力、有时不知道」，行为前后不一致；
     * 而持有检查在 {@code StellarRemoteCompat.cast()} 里已经做过了，
     * 调用时会给出明确提示。这里只负责「模组/开关这一级」的静态可用性。
     */
    private static boolean stellarRemoteUsable() {
        try {
            if (!StellarRemoteCompat.enabled()) return false;
            return StellarRemoteCompat.available();
        } catch (Throwable error) {
            // 探测异常按「不可用」处理：没有证据表明能用，就不要让模型以为能用
            return false;
        }
    }

    /** AI 建造：开关打开才给这个工具，否则模型会凭空说「我帮你盖好了」。 */
    private static boolean buildUsable() {
        try {
            return com.hanchen.veritycustomapi.config.ModConfig.ENABLE_AI_BUILDING.get();
        } catch (Throwable error) {
            return false;
        }
    }

    /**
     * 摄像头观察：需要「视觉能力」+「AI 主动观察」两个开关都打开。
     *
     * <p>只开视觉不开主动观察时，玩家是希望自己按快捷键控制，不希望模型主动来拍，
     * 所以这种情况下也要把工具摘掉。
     */
    private static boolean cameraUsable() {
        try {
            return com.hanchen.veritycustomapi.ConfigManager.isCameraEnabled()
                    && com.hanchen.veritycustomapi.ConfigManager.isCameraSkillEnabled();
        } catch (Throwable error) {
            return false;
        }
    }

    private static String nameOf(JsonElement element) {
        if (element == null || !element.isJsonObject()) return "";
        JsonObject tool = element.getAsJsonObject();
        if (tool.has("function") && tool.get("function").isJsonObject()) {
            return primitive(tool.getAsJsonObject("function"), "name");
        }
        // 兼容扁平写法（部分上游只吃 name）
        return primitive(tool, "name");
    }

    private static String primitive(JsonObject object, String field) {
        if (object == null || !object.has(field)) return "";
        JsonElement value = object.get(field);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }
}
