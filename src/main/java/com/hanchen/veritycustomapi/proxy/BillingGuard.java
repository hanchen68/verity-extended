package com.hanchen.veritycustomapi.proxy;

import com.google.gson.JsonObject;
import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.cloud.BillingService;
import com.hanchen.veritycustomapi.cloud.UsageMeter;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Locale;

/**
 * 结算与用量上报。
 *
 * <p>抽自 {@code OllamaProxyServer.ChatProxyHandler}。这块逻辑短，但每条路径的
 * 语义差别很大，混在 3300 行的处理器里很容易改错：
 *
 * <ul>
 *   <li><b>settle</b>：请求真的成功了，按实际用量扣费；</li>
 *   <li><b>release</b>：请求失败/内容无效，把预授权释放掉，不能白扣玩家额度；</li>
 *   <li><b>report</b>：只对 {@code builtin} 线路上报用量（自有线路不走云端的账）。</li>
 * </ul>
 *
 * <p>🔴 共同点：都必须**处理完整列表**（原来的写法是 while + remove(0) 逐个消费）。
 * 少结算一次会漏扣、少释放一次会把玩家额度卡住，两种都是真实事故。
 */
public final class BillingGuard {
    private static final Logger LOGGER = OllamaProxyMod.LOGGER;

    private BillingGuard() {
    }

    /** 全部结算：按实际用量扣费。调用后列表清空。 */
    public static void settleAll(List<BillingSettlement> settlements) {
        if (settlements == null) return;
        while (!settlements.isEmpty()) {
            BillingSettlement settlement = settlements.get(0);
            BillingService.settlePaidChat(settlement.reservation(), settlement.usage());
            settlements.remove(0);
        }
    }

    /** 全部释放：请求没成功，把预授权还回去。调用后列表清空。 */
    public static void releaseAll(List<BillingSettlement> settlements, String reason) {
        if (settlements == null) return;
        while (!settlements.isEmpty()) {
            BillingSettlement settlement = settlements.get(0);
            BillingService.releasePaidChat(settlement.reservation(), reason);
            settlements.remove(0);
        }
    }

    /**
     * 上报本轮用量。
     *
     * <p>只对 {@code builtin} 线路生效：玩家自己填的第三方 key 不该被计入我们的额度。
     */
    public static void reportUsage(String requestId, ConfigManager.ChatRoute route,
                                   String requestBody, String responseBody, String outputText) {
        if (route == null || !"builtin".equals(route.source())) return;
        String tier = route.tier() != null ? route.tier() : "free";
        BillingService.reportChat(requestId, tier, route.id(),
                UsageMeter.chat(requestBody, responseBody, outputText, !"paid".equalsIgnoreCase(tier)));
    }

    /**
     * 汇总上游提示词缓存命中情况，用于排查「为什么这轮这么慢/这么贵」。
     *
     * @return 形如 {@code hit=1200, miss=300, hitRate=80.00%}；上游没给缓存字段时返回 {@code none}
     */
    public static String describeCacheHit(JsonObject usage) {
        Long hit = extractCacheHitTokens(usage);
        if (hit == null) return "none";
        Long miss = readUsageLong(usage, "prompt_cache_miss_tokens");
        Long prompt = readUsageLong(usage, "prompt_tokens");
        if (prompt == null) prompt = readUsageLong(usage, "input_tokens");
        StringBuilder summary = new StringBuilder("hit=").append(hit);
        if (miss != null) summary.append(", miss=").append(miss);
        // DeepSeek 的 prompt_tokens 已包含命中部分：有 miss 字段时用「命中 /（命中+未命中）」计算，
        // 缺 miss 时退回「命中 / prompt_tokens」（OpenAI 的 prompt_tokens 同样含命中部分）。
        long denominator = miss != null ? hit + Math.max(0, miss)
                : (prompt != null ? Math.max(0, prompt) : 0);
        if (denominator > 0) {
            summary.append(", hitRate=").append(
                    String.format(Locale.ROOT, "%.2f%%", hit * 100.0D / denominator));
        }
        return summary.toString();
    }

    /**
     * 取命中 token 数：DeepSeek 的 {@code prompt_cache_hit_tokens} 优先，
     * 其次 OpenAI 嵌套的 {@code prompt_tokens_details.cached_tokens}。
     */
    public static Long extractCacheHitTokens(JsonObject usage) {
        Long hit = readUsageLong(usage, "prompt_cache_hit_tokens");
        if (hit == null && usage != null
                && usage.has("prompt_tokens_details") && usage.get("prompt_tokens_details").isJsonObject()) {
            hit = readUsageLong(usage.getAsJsonObject("prompt_tokens_details"), "cached_tokens");
        }
        return hit;
    }

    /** 读一个可能不存在的 long 字段；类型不对或缺失都返回 null（不抛异常）。 */
    public static Long readUsageLong(JsonObject object, String field) {
        if (object == null || !object.has(field) || object.get(field).isJsonNull()
                || !object.get(field).isJsonPrimitive()) return null;
        try {
            return object.get(field).getAsLong();
        } catch (Exception ignored) {
            return null;
        }
    }
}
