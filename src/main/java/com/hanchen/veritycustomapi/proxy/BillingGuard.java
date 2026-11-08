package com.hanchen.veritycustomapi.proxy;

import com.google.gson.JsonObject;
import com.hanchen.veritycustomapi.ConfigManager;

import java.util.List;
import java.util.Locale;

/**
 * 计费相关的收尾动作——**现在全部是空操作**。
 *
 * <p>计费已整体交给云端：模组不再预授权、结算、上报用量，也不再在本地因为余额不足拦截请求。
 * 这个类被代理主链路的多条分支调用（成功、失败、空响应、超时…），
 * 那些调用点本身是错误处理流程的一部分，删掉它们会打散「先收尾再返回」的结构；
 * 所以保留调用点、把动作降级为空，是最小改动。
 *
 * <p>仍然有用的只有 {@link #describeCacheHit} 与 {@link #readUsageLong}：
 * 它们只是把上游返回的 usage 读出来写日志，用于排查「为什么这轮这么慢」，与扣费无关。
 */
public final class BillingGuard {

    private BillingGuard() {
    }

    /** 保留签名：调用点仍在遍历结算列表，但列表恒为空。 */
    public static void settleAll(List<BillingSettlement> settlements) {
        if (settlements != null) settlements.clear();
    }

    /** 同上：没有预授权，就没有需要释放的东西。 */
    public static void releaseAll(List<BillingSettlement> settlements, String reason) {
        if (settlements != null) settlements.clear();
    }

    /**
     * 不再上报用量。
     *
     * <p>保留方法是为了让代理主链路的上报调用点继续编译；
     * 真正的额度统计由云端在上游完成。
     */
    public static void reportUsage(String requestId, ConfigManager.ChatRoute route,
                                   String requestBody, String responseBody, String outputText) {
        // 云端计费，模组不上报
    }

    /**
     * 汇总上游提示词缓存命中情况，用于排查「为什么这轮这么慢/这么贵」。
     *
     * <p>只读上游返回值，不涉及扣费。
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
