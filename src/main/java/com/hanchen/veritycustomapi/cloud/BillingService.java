package com.hanchen.veritycustomapi.cloud;

import java.util.UUID;

/**
 * 计费入口：**已整体交给云端，模组端不再做任何扣费**。
 *
 * <p>为什么保留这个类而不是直接删掉：它被 20 多处调用（对话/记忆/日记/感知/自主/翻译/
 * 语音识别/语音合成），删类要同时改完所有调用点的分支语义——例如代理里
 * 「reservation == null 时是否因为缺 usage 而中断对话」这类判断，改错会直接让对话失败。
 * 现在把这些调用全部降级成一次性常量开销的空操作，调用点结构原样保留，风险最小。
 *
 * <p>三项职责都撤销了：
 * <ul>
 *   <li>预授权（reserve）——不再冻结额度；</li>
 *   <li>结算/上报（settle / report）——不再产生任何账单请求；</li>
 *   <li>本地额度拦截——{@code isCreditsSufficient} 的判断已从各调用点移除。</li>
 * </ul>
 *
 * <p>余额仍然显示：{@code credits_remaining} 来自云端 getconfig 下发，与本地计费无关。
 */
public final class BillingService {

    private BillingService() {
    }

    /** 仍然需要：调用方用它做请求幂等键（与会话日志关联），与计费无关。 */
    public static String requestId() {
        return UUID.randomUUID().toString();
    }

    /** 启动时已无需重放任何待结算记录（历史遗留在云端的队列不再由模组处理）。 */
    public static void initialize() {
        // 不再扫描 pending 计费文件：模组端没有待结算项了
    }

    /**
     * 预授权：现在总是「成功且无需释放」。
     *
     * <p>{@code legacySkipped = true} 很关键：调用点靠它区分
     * 「需要结算」与「上游自行结算」，从而不会因为缺少 usage 字段而中断对话。
     */
    public static Reservation reservePaidChat(String requestId, String routeId, int estimatedCost) {
        return Reservation.skipped();
    }

    public static void settlePaidChat(Reservation reservation, UsageMeter.ChatUsage usage) {
        // 云端计费，模组不上报
    }

    public static void releasePaidChat(Reservation reservation, String reason) {
        // 没有预授权，自然不需要释放
    }

    public static void reportChat(String requestId, String tier, UsageMeter.ChatUsage usage) {
        // 云端计费，模组不上报
    }

    public static void reportChat(String requestId, String tier, String routeId, UsageMeter.ChatUsage usage) {
        // 云端计费，模组不上报
    }

    public static void reportStt(String requestId, String tier, String routeId, double audioSeconds) {
        // 云端计费，模组不上报
    }

    public static void reportTts(String requestId, String tier, String routeId, int characters, boolean clone) {
        // 云端计费，模组不上报
    }

    /** 「这次请求要不要本地结算」的答案恒为否；字段保留以兼容调用点。 */
    public record Reservation(boolean success, int status, String reservationId, String requestId,
                              String routeId, String error, Resume context, boolean legacySkipped) {

        /** 无需结算的占位结果：成功、无错误、且标记为「上游自行计费」。 */
        public static Reservation skipped() {
            return new Reservation(true, 200, "", "", "", null, null, true);
        }

        public static Reservation success(String reservationId, String requestId, String routeId,
                                          Resume context) {
            return new Reservation(true, 200, reservationId, requestId, routeId, null, context, false);
        }

        public static Reservation failure(int status, String error) {
            return new Reservation(false, status, "", "", "", error, null, false);
        }
    }

    /**
     * 兼容占位：原先是预授权上下文（含结算端点），现已全部撤销。
     *
     * <p>只保留一个空记录，让仍按旧签名构造它的调用点继续编译；
     * 里面不再保存任何端点，避免「看起来还在联络计费服务」。
     */
    public record Resume() {
    }
}
