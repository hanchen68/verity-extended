package com.hanchen.veritycustomapi.proxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hanchen.veritycustomapi.ConfigManager;

/**
 * 提示词注入的「无状态」部分：把规则写进请求体的 messages。
 *
 * <p>抽自 {@code OllamaProxyServer.ChatProxyHandler}。这些方法只做一件事——
 * 读配置、拼字符串、改 bodyJson，不碰网络也不碰处理器状态，所以能独立成类。
 *
 * <p>留在处理器里的那几项（玩家状态、记忆规则、建造协议、人设）要读实例上下文，
 * 不属于这里；本类只收「纯规则文案」这一类。
 */
public final class PromptInjections {

    private PromptInjections() {
    }

    /**
     * 在 messages 最前面插一条 system 消息。
     *
     * <p>🔴 必须是**最前面**：多数上游只认第一条 system，放后面等于没写。
     */
    public static void appendSystemContext(JsonObject bodyJson, String context) {
        JsonArray messages = bodyJson.getAsJsonArray("messages");
        if (messages == null) return;
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", context);
        JsonArray updated = new JsonArray();
        updated.add(system);
        for (int i = 0; i < messages.size(); i++) {
            updated.add(messages.get(i));
        }
        bodyJson.add("messages", updated);
    }

    /**
     * 把动态上下文追加到消息**尾部**。
     *
     * <p>为什么不放前面：前面的 system 是静态前缀，上游按字节比对做前缀缓存；
     * 把每轮都变的检索结果插进去会让整段缓存失效，延迟和成本都会涨。
     */
    public static void appendSystemContextAtTail(JsonObject bodyJson, String context) {
        JsonArray messages = bodyJson.getAsJsonArray("messages");
        if (messages == null) return;
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", context);
        messages.add(system);
    }

    /**
     * 把技能规则合并进第一条 system 消息；没有 system 时新建一条。
     *
     * <p>合并而不是新建：技能规则与主提示词是同一层语义，拆成两条 system
     * 会让上游只取其一（这条踩过）。
     */
    public static void appendSkillRule(JsonObject bodyJson, String rule) {
        JsonArray messages = bodyJson.getAsJsonArray("messages");
        if (messages == null || messages.isEmpty()) return;
        JsonElement firstElement = messages.get(0);
        if (firstElement.isJsonObject()) {
            JsonObject first = firstElement.getAsJsonObject();
            if (first.has("role") && "system".equals(first.get("role").getAsString())) {
                String content = first.has("content") && first.get("content").isJsonPrimitive()
                        ? first.get("content").getAsString() : "";
                first.addProperty("content", content.isBlank() ? rule : content + "\n\n" + rule);
                return;
            }
        }
        appendSystemContext(bodyJson, rule);
    }

    /** 视觉能力是否可用：只在客户端、且画面采集本身可用时为真。 */
    public static boolean isCameraSkillAvailable() {
        if (!net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) return false;
        try {
            return com.hanchen.veritycustomapi.client.CameraChatVision.isSkillAvailable();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 双语字幕规则。
     *
     * <p>优先让模型在同一次回复里带上中文，不增加额外往返延迟；
     * 模型未给出中文时代理才补一次翻译调用，避免每条回复都多花约 3 秒。
     */
    public static void injectBilingualSpeechRequirement(JsonObject bodyJson) {
        if (!ConfigManager.isBilingualSpeechSubtitlesEnabled()) return;
        appendSystemContext(bodyJson,
                "Bilingual output rules: write the spoken line in natural English only. "
              + "Then output the literal delimiter <|verity_zh|> on its own line, followed by a faithful Simplified Chinese translation of that English line. "
              + "Do NOT write Chinese before the delimiter. "
              + "Do NOT add any other labels such as 【原文】, 【翻译】, [Original], [Translation], Original:, Translation:. "
              + "Preserve all action, emotion, plan, and blueprint fields required by the existing schema, and do not include markdown or text outside the JSON object.");
    }

    /**
     * 视觉技能：让模型知道它可以调用画面采集观察玩家。
     *
     * <p>只在视觉能力启用且允许技能调用时注入，避免模型凭空声称「看到了」。
     */
    public static void injectCameraSkillRule(JsonObject bodyJson) {
        if (!isCameraSkillAvailable()) return;
        appendSkillRule(bodyJson,
                "【视觉技能】你可以采集画面来观察玩家，来源包括玩家面前的真实摄像头，以及他当前的游戏内屏幕画面。默认情况下你看不到任何画面。\n"
              + "- 当你确实需要视觉信息时（例如玩家让你看他、看他周围、看他手里或身边的东西，"
              + "或问你他在游戏里看到什么、身边有什么、正在做什么，"
              + "或你判断只有看到画面才能给出准确回应），在返回的 JSON 顶层额外加上 \"camera_look\": true。\n"
              + "- camera_look 必须作为 JSON 字段返回，绝对不要把它写进 message / response 的台词正文里，也不要无意义地反复调用。\n"
              + "- 系统收到后会立刻采集画面，并单独发起一次视觉观察；届时结合画面内容自然回应即可。\n"
              + "- 平时不要调用，也不要连续调用；更不要在没获得画面时假装自己看到了什么。");
    }

    /**
     * 引导模型「给带数据的物品时用对工具」，仅在工具集含 {@code run_command} 时注入（6.0 无此工具）。
     *
     * <p>give_item 内部为 new ItemStack(item, count)、不带 NBT，带附魔/名称/耐久的物品须改用
     * run_command 执行 /give。
     */
    public static void injectItemDataRule(JsonObject bodyJson) {
        if (!com.hanchen.veritycustomapi.compat.VerityStructureDetector.hasRunCommandTool()) return;
        appendSkillRule(bodyJson,
                "【给物品必须带上正确的数据】give_item 只能生成不带任何数据（NBT）的纯净物品。"
              + "用它给附魔书，玩家拿到的是一本没有附魔的书，不是他要的那本；"
              + "不要靠「多给几本猜一个」来解决，那样只会给出一堆错的书。\n"
              + "- 只要物品带附魔、自定义名称、等级、耐久等任何数据，一律改用 run_command 执行 /give，"
              + "把数据写在物品 ID 后面的花括号里。例如玩家要「锋利 V 的附魔书」：\n"
              + "  /give @p minecraft:enchanted_book{Enchantments:[{id:\"minecraft:sharpness\",lvl:5}]} 1\n"
              + "- 附魔 ID 用原版命名空间（如 minecraft:sharpness / minecraft:protection），"
              + "等级 lvl 按玩家说的填（罗马数字 V = 5）。\n"
              + "- 只有玩家要的是完全不带数据的普通物品（如石头、面包）时，才用 give_item。\n"
              + "- 一次只给玩家真正要的那一件。");
    }

    /**
     * 联网指引：告诉模型「你有 web_search 且它真的能用」。
     *
     * <p>只把工具塞进 tools 数组是不够的——模型对「我是游戏角色、看不到现实世界」
     * 有很强的自我认知，不会主动联想。最后那句尤其关键：不加的话它会先回
     * 「我查不到」把话题结束掉，根本不试。
     */
    public static void injectWebSearchGuidance(JsonObject bodyJson) {
        if (!bodyJson.has("messages") || !bodyJson.get("messages").isJsonArray()) return;
        String guidance = "You have a web_search tool available and it works. Whenever the player asks "
                + "about anything outside this game that you cannot know from memory - news, current "
                + "events, the date or time, weather, prices, people, releases, versions - call "
                + "web_search first instead of saying you cannot see the outside world. Only say you "
                + "cannot look something up after web_search actually returned nothing.";
        JsonArray messages = bodyJson.getAsJsonArray("messages");
        for (JsonElement element : messages) {
            if (!element.isJsonObject()) continue;
            JsonObject message = element.getAsJsonObject();
            if (!message.has("role") || !"system".equals(message.get("role").getAsString())) continue;
            if (!message.has("content") || !message.get("content").isJsonPrimitive()) continue;
            String existing = message.get("content").getAsString();
            if (existing.contains("web_search tool available")) return;   // 已经加过
            message.addProperty("content", existing + "\n\n" + guidance);
            return;
        }
    }
}
