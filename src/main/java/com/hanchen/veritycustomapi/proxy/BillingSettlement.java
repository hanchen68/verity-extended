package com.hanchen.veritycustomapi.proxy;

import com.hanchen.veritycustomapi.cloud.BillingService;
import com.hanchen.veritycustomapi.cloud.UsageMeter;

/**
 * 一次付费对话的结算凭据：预授权（reservation）+ 实际用量（usage）。
 *
 * <p>从 {@code OllamaProxyServer} 的内部 record 提为顶层类型，这样
 * {@link BillingGuard} 能直接处理它，而 {@code OllamaProxyServer} 只需
 * {@code import} 一行——**所有既有引用一处都不用改**。
 */
public record BillingSettlement(BillingService.Reservation reservation, UsageMeter.ChatUsage usage) {
}
