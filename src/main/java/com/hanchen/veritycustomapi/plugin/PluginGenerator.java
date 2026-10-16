package com.hanchen.veritycustomapi.plugin;

import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.cloud.BillingService;
import com.hanchen.veritycustomapi.cloud.UsageMeter;
import com.hanchen.veritycustomapi.memory.ChatCompletionClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 把「玩家的一句话」变成插件。
 *
 * 不要求模型写代码，只让它填字段：提示词里附上完整能力清单，模型只能从清单里挑触发器、条件和动作。
 *
 * 校验失败会把错误原样回喂给模型重试——最常见的错误是编出不存在的动作名，
 * 把可用清单和具体错误一起给它，通常第二次就能改对。仍然失败时返回的错误文本会直接告诉玩家
 * 「你想要的功能缺哪个动作」，而不是一句含糊的失败。
 */
public final class PluginGenerator {

    /** 最多尝试几次（含首次）。 */
    private static final int MAX_ATTEMPTS = 3;
    private static final int READ_TIMEOUT_MS = 60000;

    public record Outcome(PluginDefinition plugin, List<String> errors) {
        public boolean ok() {
            return plugin != null && errors.isEmpty();
        }
    }

    private PluginGenerator() {
    }

    public static String systemPrompt() {
        return "你是一个 Minecraft 模组的插件生成器。玩家会用一句话描述他想要的功能，"
                + "你要把它翻译成一个 JSON 插件定义。\n"
                + "你**不能写代码**，只能在下面这份能力清单里挑选触发器、条件和动作。\n\n"
                + PluginVocabulary.describeForPrompt()
                + "\n输出要求：\n"
                + "1. 只输出一个 JSON 对象，不要解释、不要 markdown 代码块、不要多余文字。\n"
                + "2. 顶层字段只能是：id、name、description、enabled、trigger、conditions、actions。\n"
                + "3. id 用小写字母和数字，不超过 32 字符；name 是给玩家看的中文短名。\n"
                + "4. 参数名必须与清单里写的完全一致，数值必须在给定范围内。\n"
                + "5. 如果玩家的需求超出了清单能力，选择最接近能做到的部分，"
                + "并把 description 写成「只能做到 XXX」。\n"
                + "6. 需要限制频率时用 cooldown_ready 条件，不要靠堆动作。";
    }

    /**
     * 生成一个插件定义。必须在后台线程调用（会联网，最长等数十秒）。
     */
    public static Outcome generate(String description) {
        if (description == null || description.isBlank()) {
            return new Outcome(null, List.of("请先描述你想要的功能"));
        }
        List<ConfigManager.ChatRoute> routes;
        try {
            routes = ConfigManager.snapshotChatRoutes();
        } catch (Throwable error) {
            return new Outcome(null, List.of("读取对话服务失败：" + error));
        }
        if (routes.isEmpty()) {
            return new Outcome(null, List.of("没有可用的对话服务，请先在设置里配置"));
        }
        ConfigManager.ChatRoute route = routes.get(0);

        String user = "玩家想要的功能：" + description.trim();
        List<String> lastErrors = new ArrayList<>();
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String requestJson = ChatCompletionClient.toJson(
                    ChatCompletionClient.buildRequest(route.model(), systemPrompt(), user));
            String body;
            try {
                body = complete(route, requestJson);
            } catch (Exception error) {
                return new Outcome(null, List.of("调用对话服务失败：" + rootMessage(error)));
            }
            String text = ChatCompletionClient.extractText(body);
            if (text == null || text.isBlank()) {
                lastErrors = List.of("模型没有返回内容");
            } else {
                PluginParser.Result result = PluginParser.parse(text);
                if (result.ok()) {
                    return new Outcome(result.plugin(), List.of());
                }
                lastErrors = result.errors();
                OllamaProxyMod.LOGGER.info("插件生成第 {} 次校验未通过: {}", attempt, result.errorText());
                user = "玩家想要的功能：" + description.trim()
                        + "\n\n上一次你给出的 JSON 有以下问题，请修正后重新输出完整 JSON：\n- "
                        + String.join("\n- ", lastErrors);
            }
        }
        return new Outcome(null, lastErrors);
    }

    /** 与日记功能使用同一套计费契约，避免白用付费线路。 */
    private static String complete(ConfigManager.ChatRoute route, String requestJson) throws Exception {
        boolean paidBuiltin = "builtin".equals(route.source()) && "paid".equalsIgnoreCase(route.tier());
        String requestId = BillingService.requestId();
        BillingService.Reservation reservation = null;
        if (paidBuiltin) {
            int required = Math.max(1, Math.max(route.cost(), Math.max(
                    ConfigManager.getMinDeductCost(), ConfigManager.getChatCost())));
            reservation = BillingService.reservePaidChat(requestId, route.id(), required);
            if (!reservation.success()) {
                throw new IllegalStateException(reservation.error());
            }
        }
        String body;
        try {
            body = ChatCompletionClient.complete(route, requestJson, READ_TIMEOUT_MS);
        } catch (Exception error) {
            if (reservation != null) {
                BillingService.releasePaidChat(reservation, "upstream_error");
            }
            throw error;
        }
        String text = ChatCompletionClient.extractText(body);
        if (text == null || text.isBlank()) {
            if (reservation != null) {
                BillingService.releasePaidChat(reservation, "empty_response");
            }
            return body;
        }
        if (reservation != null) {
            UsageMeter.ChatUsage usage = UsageMeter.chat(requestJson, body, text, false);
            if (usage == null) {
                BillingService.releasePaidChat(reservation, "missing_usage");
            } else {
                BillingService.settlePaidChat(reservation, usage);
            }
        } else if ("builtin".equals(route.source())) {
            String tier = route.tier() != null ? route.tier() : "free";
            BillingService.reportChat(BillingService.requestId(), tier, route.id(),
                    UsageMeter.chat(requestJson, body, text, true));
        }
        return body;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
