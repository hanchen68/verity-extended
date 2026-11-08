package com.hanchen.veritycustomapi.cloud;

/**
 * 计费归属自检：模组端**不再**发起任何扣费/结算请求。
 *
 * <p>为什么值得单独测：计费被判断过两次方向（先本地预授权，再整体交给云端），
 * 每次反复都会留下「看起来还在扣费」的残留代码。这里用两类断言把它钉死：
 * <ol>
 *   <li>行为断言：预授权永远返回「无需结算」，不会阻塞调用方；</li>
 *   <li>结构断言：这个类里不允许再出现计费接口地址、HTTP 客户端或结算存储。</li>
 * </ol>
 */
public final class BillingOwnershipTest {
    private BillingOwnershipTest() {
    }

    public static void main(String[] args) throws Exception {
        reserveNeverBlocks();
        requestIdStaysUsable();
        noBillingTrafficLeft();
        System.out.println("计费归属检查通过：模组端不再扣费");
    }

    /** 预授权必须是「成功但跳过结算」，否则调用方会中断对话。 */
    private static void reserveNeverBlocks() {
        BillingService.Reservation reservation =
                BillingService.reservePaidChat("req-12345678", "route_x", 10);
        assertTrue(reservation.success(), "预授权不能失败：云端计费时模组无权拒绝请求");
        assertTrue(reservation.legacySkipped(),
                "必须标记为跳过结算，否则调用方会等本地 usage 并可能中断对话");
        assertTrue(reservation.error() == null, "不应返回错误文案");
    }

    /** 幂等键仍然要可用（与会话日志关联），但不再参与扣费。 */
    private static void requestIdStaysUsable() {
        String id = BillingService.requestId();
        assertTrue(id != null && id.length() >= 8, "请求 id 仍需可用");
        assertTrue(!id.equals(BillingService.requestId()), "请求 id 不能重复");
    }

    /**
     * 结构断言：类里不允许再出现计费网络调用。
     *
     * <p>只检查明确属于「扣费链路」的东西：结算/扣费接口路径、HTTP 客户端、
     * 待结算存储。这样即使以后有人把方法体改回去，测试会立刻红。
     */
    private static void noBillingTrafficLeft() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/hanchen/veritycustomapi/cloud/BillingService.java"));
        for (String forbidden : new String[]{
                "reserveUrl", "settleUrl", "deductUrl",
                "HttpClient", "HttpURLConnection", "PendingBillingStore", "CloudBillingClient"}) {
            assertTrue(!source.contains(forbidden),
                    "计费类里不应再出现扣费网络调用：" + forbidden);
        }
        // 已删除的两个结算实现类不应复活
        for (String removed : new String[]{
                "src/main/java/com/hanchen/veritycustomapi/cloud/CloudBillingClient.java",
                "src/main/java/com/hanchen/veritycustomapi/cloud/PendingBillingStore.java"}) {
            assertTrue(!java.nio.file.Files.exists(java.nio.file.Path.of(removed)),
                    "已移除的结算类不应重新出现：" + removed);
        }
        // 本地额度拦截也不该回来
        String proxy = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/hanchen/veritycustomapi/OllamaProxyServer.java"));
        assertTrue(!proxy.contains("额度不足，请前往云端积分中心充值"),
                "代理不应再用本地余额拦截请求（额度由云端判定）");
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
