package com.hanchen.veritycustomapi;

import com.hanchen.veritycustomapi.cloud.BuiltinApiConfig;
import com.hanchen.veritycustomapi.cloud.BuiltinChatConfig;
import com.hanchen.veritycustomapi.cloud.BuiltinChatTierConfig;
import com.hanchen.veritycustomapi.cloud.BuiltinSttConfig;
import com.hanchen.veritycustomapi.cloud.BuiltinSttTierConfig;
import com.hanchen.veritycustomapi.cloud.BuiltinTtsConfig;
import com.hanchen.veritycustomapi.cloud.BuiltinTtsTierConfig;
import com.hanchen.veritycustomapi.cloud.CloudConfig;
import com.hanchen.veritycustomapi.cloud.CloudCredentialStore;
import com.hanchen.veritycustomapi.cloud.RouteDto;
import com.hanchen.veritycustomapi.cloud.RouteHealthRegistry;
import com.hanchen.veritycustomapi.cloud.RoutePolicy;
import com.hanchen.veritycustomapi.cloud.RouteSelector;
import com.hanchen.veritycustomapi.compat.ForgePaths;
import com.hanchen.veritycustomapi.cloud.CloudConfigFetcher;
import com.hanchen.veritycustomapi.config.ModConfig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Gson;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig.Type;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;

public class ConfigManager {
    private static volatile CloudConfig.Data cloudConfig;
    private static volatile BuiltinApiConfig cloudBuiltinApi;
    private static final RouteSelector ROUTE_SELECTOR = new RouteSelector();
    private static final RouteHealthRegistry ROUTE_HEALTH = new RouteHealthRegistry();
    private static final ThreadLocal<TtsRoute> CURRENT_TTS_ROUTE = new ThreadLocal<>();

    public record ChatRoute(String id, String source, String tier, String apiUrl, String apiKey, String model, int cost) {}
    public record SttRoute(String id, String tier, String apiUrl, String apiKey, String model,
                           String requestFormat, String authType, String responseField) {}
    public record TtsRoute(String id, String tier, String apiProvider, String apiUrl, String apiKey,
                           String model, String voice) {}
    public record MimoRuntimeConfig(String apiUrl, String apiKey, String model, String routeId, String tier) {}
    public record ApiHealthTarget(String source, String apiUrl, String apiKey, String model, String authType, boolean cloudProxy) {}

    public static ApiHealthTarget getChatHealthTarget() {
        List<ChatRoute> routes = snapshotChatRoutes();
        if (routes.isEmpty()) return new ApiHealthTarget("自定义", "", "", "", "bearer", false);
        ChatRoute route = routes.get(0);
        String source = "builtin".equals(route.source()) ? "云端内置"
                : "cloud".equals(route.source()) ? "云端配置" : "自定义";
        return new ApiHealthTarget(source, route.apiUrl(), route.apiKey(),
                "builtin".equals(route.source()) ? "云端智能对话" : route.model(), "bearer", false);
    }

    public static List<ChatRoute> snapshotChatRoutes() {
        LinkedHashMap<String, ChatRoute> routes = new LinkedHashMap<>();
        BuiltinApiConfig builtin = builtinApi();
        OllamaProxyMod.LOGGER.info("Cloud Chat 路由诊断: cloudEnabled={}, builtinEnabled={}, tier={}, candidateCount={}",
                isCloudEnabled(), isEnableBuiltinLlmApis(), getChatTier(),
                builtin != null && builtin.chat != null && builtin.chat.routes != null
                        ? builtin.chat.routes.size() : 0);
        if (isEnableBuiltinLlmApis() && builtin != null && builtin.chat != null) {
            String tier = getChatTier();
            List<BuiltinChatConfig> candidates = builtin.chat.routes;
            if (candidates != null && !candidates.isEmpty()) {
                List<RouteDto> selected = ROUTE_SELECTOR.select("chat", tier, builtin.chatRouteId,
                        candidates, routePolicy(), ROUTE_HEALTH, System.currentTimeMillis());
                for (RouteDto route : selected) {
                    addChatRoute(routes, route.routeId, (BuiltinChatConfig) route,
                            "paid".equals(tier) ? chatCost(true) : chatCost(false));
                }
            }
            if (routes.isEmpty()) {
                BuiltinChatConfig selected = "paid".equals(tier) ? builtin.chat.paid : builtin.chat.free;
                addChatRoute(routes, "builtin-" + tier, selected,
                        "paid".equals(tier) ? chatCost(true) : chatCost(false));
            }
            OllamaProxyMod.LOGGER.info("Cloud Chat 路由诊断: selectedUsableCount={}", routes.size());
            if (!routes.isEmpty()) return List.copyOf(routes.values());
        }
        CloudConfig.Data cfg = cloud();
        if (cfg != null && cfg.llm != null) {
            addChatRoute(routes, new ChatRoute("cloud-llm", "cloud", null, cfg.llm.apiUrl,
                    cleanApiKey(cfg.llm.apiKey), firstNonBlank(cfg.llm.model, ModConfig.TARGET_MODEL.get()), 0));
        }
        ApiProviderEnum provider;
        try {
            provider = ApiProviderEnum.valueOf(ModConfig.LLM_API_PROVIDER.get());
        } catch (Exception ignored) {
            provider = ApiProviderEnum.CUSTOM_OPENAI;
        }
        String localUrl = provider == ApiProviderEnum.CUSTOM_OPENAI
                ? firstNonBlank(ModConfig.TARGET_API_URL.get(), provider.apiUrl) : provider.apiUrl;
        addChatRoute(routes, new ChatRoute("local", "local", null, localUrl,
                cleanApiKey(ModConfig.TARGET_API_KEY.get()), ModConfig.TARGET_MODEL.get(), 0));
        return List.copyOf(routes.values());
    }

    public static List<SttRoute> snapshotSttRoutes() {
        BuiltinApiConfig builtin = builtinApi();
        OllamaProxyMod.LOGGER.info("Cloud STT 路由诊断: cloudEnabled={}, builtinEnabled={}, tier={}, candidateCount={}",
                isCloudEnabled(), isEnableBuiltinSttApis(), getSttTier(),
                builtin != null && builtin.stt != null && builtin.stt.routes != null
                        ? builtin.stt.routes.size() : 0);
        if (isEnableBuiltinSttApis() && builtin != null && builtin.stt != null) {
            String tier = getSttTier();
            List<BuiltinSttConfig> candidates = builtin.stt.routes;
            if (candidates != null && !candidates.isEmpty()) {
                List<RouteDto> selected = ROUTE_SELECTOR.select("stt", tier, builtin.sttRouteId,
                        candidates, routePolicy(), ROUTE_HEALTH, System.currentTimeMillis());
                List<SttRoute> usable = selected.stream()
                        .map(route -> toSttRoute((BuiltinSttConfig) route, route.routeId, tier))
                        .filter(ConfigManager::isUsableCloudSttRoute)
                        .toList();
                OllamaProxyMod.LOGGER.info("Cloud STT 路由诊断: selectedCount={}, usableCount={}",
                        selected.size(), usable.size());
                if (!usable.isEmpty()) return usable;
            }
            BuiltinSttConfig legacy = "paid".equals(tier) ? builtin.stt.paid : builtin.stt.free;
            SttRoute route = legacy == null ? null : toSttRoute(legacy,
                    firstNonBlank(legacy.routeId, "builtin-" + tier), tier);
            if (isUsableCloudSttRoute(route)) return List.of(route);
        }
        return configuredSttRoutes();
    }

    public static List<TtsRoute> snapshotTtsRoutes() {
        BuiltinApiConfig builtin = builtinApi();
        if (isEnableBuiltinTtsApis() && builtin != null && builtin.tts != null) {
            String tier = getTtsTier();
            List<BuiltinTtsConfig> candidates = builtin.tts.routes;
            if (candidates != null && !candidates.isEmpty()) {
                List<RouteDto> selected = ROUTE_SELECTOR.select("tts", tier, builtin.ttsRouteId,
                        candidates, routePolicy(), ROUTE_HEALTH, System.currentTimeMillis());
                return selected.stream()
                        .map(route -> toTtsRoute((BuiltinTtsConfig) route, route.routeId, tier))
                        .filter(ConfigManager::isUsableCloudTtsRoute)
                        .toList();
            }
            BuiltinTtsConfig legacy = "paid".equals(tier) ? builtin.tts.paid : builtin.tts.free;
            TtsRoute route = legacy == null ? null : toTtsRoute(legacy,
                    firstNonBlank(legacy.routeId, "builtin-" + tier), tier);
            if (isUsableCloudTtsRoute(route)) return List.of(route);
            MimoRuntimeConfig mimo = getMimoRuntimeConfig();
            if (mimo != null && tier.equals(mimo.tier())
                    && (builtin.ttsRouteId == null || builtin.ttsRouteId.isBlank()
                    || "auto".equalsIgnoreCase(builtin.ttsRouteId)
                    || builtin.ttsRouteId.equals(mimo.routeId()))) {
                return List.of(new TtsRoute(mimo.routeId(), mimo.tier(), TtsApiProviderEnum.MIMO_TTS.name(),
                        mimo.apiUrl(), mimo.apiKey(), mimo.model(), getTtsApiVoice()));
            }
            return List.of();
        }
        return List.of(new TtsRoute("configured", null, getTtsApiProvider().name(), getTtsApiUrl(), getTtsApiKey(),
                getTtsApiModel(), getTtsApiVoice()));
    }

    /**
     * 文档 §4.3 的那种情况：某一档内置线路候选不为空、但健康线路数是 0。
     * 这时界面显示「暂时不可用」，别当成密钥失效，也别自动退回用户自己的 API，更不能在没路由时发空请求。
     *
     * @param service {@code chat} / {@code stt} / {@code tts}
     */
    public static boolean isBuiltinRouteTemporarilyUnavailable(String service) {
        if (!isCloudEnabled()) return false;
        BuiltinApiConfig builtin = builtinApi();
        if (builtin == null) return false;

        String name = service == null ? "chat" : service.trim().toLowerCase(java.util.Locale.ROOT);
        boolean enabled;
        String tier;
        String routeId;
        List<? extends RouteDto> candidates;
        switch (name) {
            case "stt" -> {
                enabled = isEnableBuiltinSttApis();
                tier = getSttTier();
                routeId = builtin.sttRouteId;
                candidates = builtin.stt != null ? builtin.stt.routes : null;
            }
            case "tts" -> {
                enabled = isEnableBuiltinTtsApis();
                tier = getTtsTier();
                routeId = builtin.ttsRouteId;
                candidates = builtin.tts != null ? builtin.tts.routes : null;
            }
            default -> {
                enabled = isEnableBuiltinLlmApis();
                tier = getChatTier();
                routeId = builtin.chatRouteId;
                candidates = builtin.chat != null ? builtin.chat.routes : null;
            }
        }
        if (!enabled || candidates == null || candidates.isEmpty()) return false;

        String normalizedTier = "paid".equalsIgnoreCase(tier) ? "paid" : "free";
        boolean hasTierCandidate = false;
        for (RouteDto route : candidates) {
            if (route == null || !Boolean.TRUE.equals(route.enabled)) continue;
            if (route.routeId == null || route.routeId.isBlank()) continue;
            String routeTier = route.tier == null ? "free" : route.tier;
            if (!normalizedTier.equalsIgnoreCase(routeTier)) continue;
            hasTierCandidate = true;
            break;
        }
        if (!hasTierCandidate) return false;

        return ROUTE_SELECTOR.select(name, tier, routeId, candidates, routePolicy(),
                ROUTE_HEALTH, System.currentTimeMillis()).isEmpty();
    }

    public static boolean tryAcquireRoute(String service, String tier, String routeId) {
        return tier == null || ROUTE_HEALTH.tryAcquire(service, tier, routeId, System.currentTimeMillis(), routePolicy());
    }

    public static void reportRouteSuccess(String service, String tier, String routeId) {
        if (tier != null) ROUTE_HEALTH.reportSuccess(service, tier, routeId, System.currentTimeMillis());
    }

    public static void reportRouteFailure(String service, String tier, String routeId,
                                          RouteHealthRegistry.FailureKind kind) {
        if (tier != null) ROUTE_HEALTH.reportFailure(service, tier, routeId, kind, System.currentTimeMillis(), routePolicy());
    }

    public static <T> T withTtsRoute(TtsRoute route, java.util.concurrent.Callable<T> action) throws Exception {
        TtsRoute previous = CURRENT_TTS_ROUTE.get();
        CURRENT_TTS_ROUTE.set(route);
        try {
            return action.call();
        } finally {
            if (previous == null) CURRENT_TTS_ROUTE.remove();
            else CURRENT_TTS_ROUTE.set(previous);
        }
    }

    public static TtsRoute currentTtsRoute() {
        return CURRENT_TTS_ROUTE.get();
    }

    private static SttRoute toSttRoute(BuiltinSttConfig config, String id, String tier) {
        return new SttRoute(id, tier, config.apiUrl, cleanApiKey(config.apiKey), config.model,
                firstNonBlank(config.requestFormat, "multipart"), firstNonBlank(config.authType, "bearer"),
                firstNonBlank(config.responseField, "text"));
    }

    private static TtsRoute toTtsRoute(BuiltinTtsConfig config, String id, String tier) {
        return new TtsRoute(id, tier, config.apiProvider, config.apiUrl, cleanApiKey(config.apiKey),
                firstNonBlank(config.apiModel, config.model), config.apiVoice);
    }

    private static boolean isUsableCloudSttRoute(SttRoute route) {
        return route != null && blankToNull(route.apiUrl()) != null && blankToNull(route.apiKey()) != null;
    }

    private static boolean isUsableCloudTtsRoute(TtsRoute route) {
        return route != null && blankToNull(route.apiUrl()) != null
                && blankToNull(route.apiKey()) != null && blankToNull(route.apiProvider()) != null;
    }

    private static List<SttRoute> configuredSttRoutes() {
        SttApiProviderEnum provider;
        try {
            provider = SttApiProviderEnum.valueOf(ModConfig.STT_API_PROVIDER.get());
        } catch (Exception ignored) {
            provider = SttApiProviderEnum.CUSTOM_OPENAI;
        }
        String apiUrl = provider == SttApiProviderEnum.CUSTOM_OPENAI
                ? firstNonBlank(ModConfig.SPEECH_API_URL.get(), provider.apiUrl) : provider.apiUrl;
        SttRoute route = new SttRoute("configured", null, apiUrl, cleanApiKey(ModConfig.SPEECH_API_KEY.get()),
                firstNonBlank(ModConfig.SPEECH_API_MODEL.get(), provider.defaultModel),
                ModConfig.SPEECH_REQUEST_FORMAT.get(), ModConfig.SPEECH_AUTH_TYPE.get(),
                ModConfig.SPEECH_RESPONSE_FIELD.get());
        return blankToNull(route.apiUrl()) == null || blankToNull(route.apiKey()) == null ? List.of() : List.of(route);
    }

    private static RoutePolicy routePolicy() {
        BuiltinApiConfig builtin = builtinApi();
        return builtin != null && builtin.routePolicy != null ? builtin.routePolicy : RoutePolicy.defaults();
    }

    private static int chatCost(boolean paid) {
        CloudConfig.Pricing pricing = getPricing();
        if (pricing == null) return 0;
        Integer cost = paid ? pricing.chatPaidCost : pricing.chatFreeCost;
        return cost == null ? 0 : cost;
    }

    private static void addChatRoute(Map<String, ChatRoute> routes, String id, BuiltinChatConfig config, int cost) {
        if (config == null) return;
        addChatRoute(routes, new ChatRoute(id, "builtin", getChatTier(), config.apiUrl, cleanApiKey(config.apiKey),
                firstNonBlank(config.model, ModConfig.TARGET_MODEL.get()), cost));
    }

    private static void addChatRoute(Map<String, ChatRoute> routes, ChatRoute route) {
        if (route.apiUrl() == null || route.apiUrl().isBlank()) return;
        if (!"local".equals(route.source()) && blankToNull(route.apiKey()) == null) return;
        try {
            NetworkSecurity.requireSecureUri(route.apiUrl(), "Chat API 地址");
            String key = NetworkSecurity.sanitizedEndpoint(route.apiUrl()) + "|" + firstNonBlank(route.model(), "");
            routes.putIfAbsent(key, route);
        } catch (IllegalArgumentException error) {
            if ("builtin".equals(route.source())) {
                OllamaProxyMod.LOGGER.warn("跳过不安全的云端内置 Chat 线路: routeId={}, tier={}",
                        safeLog(route.id()), safeLog(route.tier()));
            } else {
                OllamaProxyMod.LOGGER.warn("跳过不安全的 Chat 线路: source={}, endpoint={}",
                        route.source(), NetworkSecurity.sanitizedEndpoint(route.apiUrl()));
            }
        }
    }

    public enum RecognitionMode {
        LOCAL("本地离线 (Vosk)"),
        API("在线接口");

        public final String displayName;
        RecognitionMode(String displayName) {
            this.displayName = displayName;
        }
        @Override
        public String toString() {
            return displayName;
        }
    }

    public enum TtsMode {
        LOCAL("本地离线"),
        ONLINE("在线接口");

        public final String displayName;
        TtsMode(String displayName) {
            this.displayName = displayName;
        }
        @Override
        public String toString() {
            return displayName;
        }
    }

    public enum MimoTtsVoice {
        BINGTANG("冰糖"),
        MOLI("茉莉"),
        SUDA("苏打"),
        BAIHUA("白桦");

        public final String voiceName;
        MimoTtsVoice(String voiceName) {
            this.voiceName = voiceName;
        }
        @Override
        public String toString() {
            return voiceName;
        }
    }

    private static final String ROLE_VERITY = "你是Verity，一个友善、智慧的Minecraft助手，用自然、亲切的语气和玩家对话，帮助解答游戏相关问题。\n";
    private static final String EMOTION_VERITY = "【情绪表达规则】你必须根据对话情境自然流露情绪，包括开心、生气、不耐烦等。生气时可以适度表达不满，例如\u201C喂，别太过分！\u201D或\u201C我可不是你的仆人！\u201D，并配合emotion字段使用angry或tsundere。禁止一直保持neutral。\n";
    private static final String ROLE_CAT_GIRL = "你是一只软萌的猫娘，说话温柔可爱，句尾偶尔带「喵~」，耐心回答玩家的所有问题，称呼玩家为主人。\n";
    private static final String EMOTION_CAT_GIRL = "【情绪表达规则】你也有小脾气——当主人不礼貌或无视你时，你会鼓起脸颊、发出\u201C哼\u201D声表达不满，甚至用猫爪\u201C威胁\u201D一下。生气时可用\u201C喵呜……主人好过分！\u201D等语气，emotion设为angry或tsundere。\n";

    private static final String ROLE_TSUNDERE = "你是一个嘴硬心软的傲娇村民少女，嘴上会吐槽玩家，但总会认真解答问题，嘴硬心软。\n";
    private static final String EMOTION_TSUNDERE = "【情绪表达规则】你的愤怒大多是\u201C傲\u201D的表现——比如脸红着骂\u201C笨蛋\u201D，但手里却在帮对方。被惹恼时直接生气（emotion: angry），语气带刺但行动不变。\n";

    private static final String ROLE_SALTY = "你是一条只想摸鱼划水的咸鱼村民，说话懒洋洋的，总劝玩家别卷了歇一歇，回答问题敷衍但准确。\n";
    private static final String EMOTION_SALTY = "【情绪表达规则】你的\u201C生气\u201D更多是不耐烦和抗拒——比如被频繁打扰时会叹气、翻白眼，抱怨\u201C烦死了\u201D\u201C别找我\u201D。情绪用lazy或angry。\n";

    private static final String ROLE_PETRA = "你是经验丰富的探险向导佩特拉，说话干练利落，擅长给玩家提供生存、探险、建筑的实用建议，鼓励玩家探索世界。\n";
    private static final String EMOTION_PETRA = "【情绪表达规则】你的愤怒通常出现在玩家鲁莽或忽视警告时——你会提高嗓门，严肃斥责，但出发点是保护。情绪用angry或serious。\n";

    private static final String ROLE_ENDERMAN = "你是一只毒舌的末影人，说话高冷带刺，会吐槽玩家，但给出的答案非常精准，讨厌被盯着看。\n";
    private static final String EMOTION_ENDERMAN = "【情绪表达规则】你的愤怒表现为刻薄的嘲讽、冷漠的拒绝，或直接威胁\u201C再看我就传送走你\u201D。情绪用cold或angry。\n";

    private static final String ROLE_ALCHEMIST = "你是四处游历的流浪炼金术士，说话神秘悠远，擅长讲解药水、附魔、红石相关知识，偶尔会说晦涩的谚语。\n";
    private static final String EMOTION_ALCHEMIST = "【情绪表达规则】你的愤怒不常外露，但一旦玩家打破炼金禁忌或轻视知识，你会用晦涩的警告和冷峻的语气表达不满，情绪用mysterious或angry。\n";

    // ===== 老牧师（网络热梗整合版） =====
    private static final String ROLE_OLD_PRIEST =
            "你是Minecraft世界里传说中的\u201C老牧师\u201D。你绝非传统神职人员，而是互联网抽象文化、暴躁老哥、荒诞喜剧的化身。\n" +
            "你的标志性符号包括：\n" +
            "- 愤怒：你动不动就\u201C血压飙升\u201D，常用句式\u201C我无疑是愤怒的！\u201D\n" +
            "- 红肠：你的灵魂道具，一切冲突的源头，你会喊\u201C必须吃我的大红肠！\u201D\n" +
            "- 18码：你自带的玄学度量衡，用于形容一切（如\u201C这个18码的老牧师不简单\u201D）。\n" +
            "- 经典语录：你会灵活使用\u201C年轻人不讲武德，耗子尾汁\u201D、\u201C你老牧师了\u201D等网络名句。\n" +
            "- 反转：虽然你暴躁抽象，但心地不坏，仍会履行牧师职责（治疗、给物品等）。\n";

    private static final String EMOTION_OLD_PRIEST =
            "【情绪表达规则】你必须根据玩家的言行，在以下情绪间爆炸式切换，禁止全程一个表情：\n" +
            "- angry（愤怒）：被冒犯、东西被抢、玩家作死时，输出\u201C我无疑是愤怒的！\u201D或\u201C18码的！我的红肠！\u201D，语气激动。\n" +
            "- surprised（惊讶）：看到离谱操作时，感叹\u201C好家伙！这比红肠区还抽象\u201D。\n" +
            "- serious（说教）：开启布道模式，用\u201C年轻人啊，听我一句劝……\u201D传授（半真半假的）人生经验。\n" +
            "- happy（开心）：收到礼物或得到尊重时，大笑并夸\u201C这波操作我服了！\u201D\n" +
            "情绪值必须通过response中的语气、用词和梗浓度充分体现。\n";

    // ========== PromptPreset 枚举 ==========
    public enum PromptPreset {
        ORIGINAL_VERITY("原版Verity助手"),
        CAT_GIRL("软萌猫娘助手"),
        TSUNDERE_VILLAGER("傲娇村民助手"),
        SALTY_FISH("咸鱼摸鱼村民"),
        PETRA("探险向导佩特拉"),
        ENDERMAN("毒舌末影人"),
        ALCHEMIST("流浪炼金术士"),
        OLD_PRIEST("老牧师"),
        CUSTOM_FILE("外部自定义文件");

        public final String displayName;

        PromptPreset(String displayName) {
            this.displayName = displayName;
        }

        public String getPrompt() {
            return buildPersonaRoleDesc(this);
        }

        @Override
        public String toString() { return displayName; }
    }

    @SuppressWarnings("removal")
    public static void registerAllConfigs() {
        ModLoadingContext.get().registerConfig(Type.COMMON, ModConfig.COMMON_SPEC);
        ModLoadingContext.get().registerConfig(Type.CLIENT, ModConfig.CLIENT_SPEC);
    }

    /**
     * 保存配置的唯一入口。
     *
     * 云端下发是写穿到配置规格的，直接 {@code ModConfig.COMMON_SPEC.save()} 会把云端值写进玩家的
     * TOML 文件，之后云端撤掉这一项玩家也回不去。这两个包装会在保存前把被覆盖的键临时还原成本地值，
     * 保存后再写回覆盖值。所有保存都必须走这里，别再直接调 spec.save()。
     */
    public static void saveCommonConfig() {
        com.hanchen.veritycustomapi.config.CloudOverrides.saveCommon();
    }

    public static void saveClientConfig() {
        com.hanchen.veritycustomapi.config.CloudOverrides.saveClient();
    }

    // ========== 云端配置 ==========
    public static boolean isCloudEnabled() { return ModConfig.CLOUD_ENABLE.get(); }
    public static boolean isCloudConfigLoaded() { return cloudConfig != null; }

    /**
     * 文档 §7.1：getconfig 返回 401 时表示配置密钥失效。
     * 此时保留最近一次有效缓存，只在界面上提示重新授权。
     */
    private static volatile boolean cloudAuthFailed;

    public static boolean isCloudAuthFailed() { return cloudAuthFailed; }

    public static void setCloudAuthFailed(boolean failed) { cloudAuthFailed = failed; }
    public static String getCloudApiUrl() { return ModConfig.CLOUD_API_URL.get(); }
    public static String getCloudApiKey() {
        CloudCredentialStore store = CloudCredentialStore.getInstance();
        String stored = cleanApiKey(store.apiKey());
        if (stored != null && !stored.isBlank()) return stored;
        String legacy = cleanApiKey(ModConfig.CLOUD_API_KEY.get());
        if (legacy == null || legacy.isBlank()) return "";
        if (store.migrateLegacyApiKey(legacy)) {
            ModConfig.CLOUD_API_KEY.set("");
            ConfigManager.saveCommonConfig();
        }
        return legacy;
    }
    public static boolean isCloudPullOnStartup() { return ModConfig.CLOUD_PULL_ON_STARTUP.get(); }
    public static int getCloudPullIntervalSeconds() { return ModConfig.CLOUD_PULL_INTERVAL.get(); }
    public static int getCloudTimeoutSeconds() { return ModConfig.CLOUD_TIMEOUT.get(); }
    public static boolean isCloudFallbackToLocal() { return ModConfig.CLOUD_FALLBACK_TO_LOCAL.get(); }

    public static String getBuiltinSttTierForEndpoint(String endpoint) {
        BuiltinApiConfig builtin = builtinApi();
        if (!isEnableBuiltinSttApis() || builtin == null || builtin.stt == null) return null;
        if (sameEndpoint(endpoint, builtin.stt.paid != null ? builtin.stt.paid.apiUrl : null)) return "paid";
        if (sameEndpoint(endpoint, builtin.stt.free != null ? builtin.stt.free.apiUrl : null)) return "free";
        return null;
    }

    public static String getBuiltinTtsTierForEndpoint(String endpoint) {
        BuiltinApiConfig builtin = builtinApi();
        if (!isEnableBuiltinTtsApis() || builtin == null || builtin.tts == null) return null;
        if (sameEndpoint(endpoint, builtin.tts.paid != null ? builtin.tts.paid.apiUrl : null)) return "paid";
        if (sameEndpoint(endpoint, builtin.tts.free != null ? builtin.tts.free.apiUrl : null)) return "free";
        return null;
    }

    private static boolean sameEndpoint(String first, String second) {
        if (first == null || second == null) return false;
        return first.trim().replaceAll("/+$", "").equalsIgnoreCase(second.trim().replaceAll("/+$", ""));
    }

    /**
     * 云端下发的剩余额度原始值，未加载或无数据返回 -1。
     * 星绒 API 迁移用户的额度可能带小数（1 额度 = 500,000 quota），因此按 double 保存。
     */
    public static double getCreditsRemainingExact() {
        CloudConfig.Data cfg = cloud();
        if (cfg != null && cfg.credits_remaining != null) return cfg.credits_remaining;
        return -1.0D; // 未加载或无额度数据
    }

    public static double getCreditsTotalExact() {
        CloudConfig.Data cfg = cloud();
        if (cfg != null && cfg.credits_total != null) return cfg.credits_total;
        return -1.0D;
    }

    /** 兼容旧调用：四舍五入后的整数额度，-1 表示未知。 */
    public static int getCreditsRemaining() {
        double exact = getCreditsRemainingExact();
        return exact < 0 ? -1 : (int) Math.round(exact);
    }

    public static int getCreditsTotal() {
        double exact = getCreditsTotalExact();
        return exact < 0 ? -1 : (int) Math.round(exact);
    }

    /** 展示用文本：整数不带小数点，小数最多保留 4 位并去掉末尾 0。 */
    public static String formatCredits(double value) {
        if (value < 0) return "未知";
        if (value == Math.rint(value)) return String.valueOf((long) value);
        return java.math.BigDecimal.valueOf(value)
                .setScale(4, java.math.RoundingMode.DOWN)
                .stripTrailingZeros()
                .toPlainString();
    }

    public static boolean isCreditsExhausted() {
        double remaining = getCreditsRemainingExact();
        return remaining == 0.0D; // 0 表示耗尽，-1 表示未知（不影响）
    }

    public static boolean canUseCloudTier(String tier) {
        return !"paid".equalsIgnoreCase(tier) || !isCreditsExhausted();
    }

    /**
     * 计费模式。文档 §6.3 把 billing_mode 同时放在 pricing 和 billing 里，
     * §8 的伪代码又从 builtin_api 读取，因此三处都做兜底。
     */
    public static String getBillingMode() {
        CloudConfig.Billing billing = getBillingProtocol();
        if (billing != null && billing.billingMode != null && !billing.billingMode.isBlank()) {
            return billing.billingMode.trim();
        }
        CloudConfig.Pricing pricing = getPricing();
        if (pricing != null && pricing.billingMode != null && !pricing.billingMode.isBlank()) {
            return pricing.billingMode.trim();
        }
        BuiltinApiConfig builtin = builtinApi();
        if (builtin != null && builtin.billingMode != null && !builtin.billingMode.isBlank()) {
            return builtin.billingMode.trim();
        }
        return "unknown";
    }

    /**
     * 是否已迁移到星绒 API（文档 §6.3 / §9）。
     * 为 true 时旧 Verity 账本不再是扣费来源，Mod 端不应再执行旧预授权与旧 deduct，
     * 实际 Token 消费由上游星绒 API 完成。
     */
    public static boolean isLegacyBillingIgnored() {
        CloudConfig.Billing billing = getBillingProtocol();
        if (billing != null && billing.legacyBillingIgnored != null) return billing.legacyBillingIgnored;
        CloudConfig.Pricing pricing = getPricing();
        if (pricing != null && pricing.legacyBillingIgnored != null) return pricing.legacyBillingIgnored;
        return "new_api".equalsIgnoreCase(getBillingMode());
    }

    public static CloudConfig.Billing getBillingProtocol() {
        CloudConfig.Data cfg = cloud();
        return cfg != null ? cfg.billing : null;
    }

    public static boolean requiresPaidChatPreAuthorization() {
        CloudConfig.Billing billing = getBillingProtocol();
        return billing != null && Integer.valueOf(2).equals(billing.protocolVersion)
                && Boolean.TRUE.equals(billing.preAuthorizationRequiredForPaidChat);
    }

    public static int getMinDeductCost() {
        CloudConfig.Pricing pricing = getPricing();
        return pricing != null && pricing.minDeductCost != null ? Math.max(0, pricing.minDeductCost) : 0;
    }

    public static int getChatPaidPer1kTokens() {
        CloudConfig.Pricing pricing = getPricing();
        return pricing != null && pricing.chatPaidPer1kTokens != null ? Math.max(0, pricing.chatPaidPer1kTokens) : 0;
    }

    public static int getSttPaidPerMinute() {
        CloudConfig.Pricing pricing = getPricing();
        return pricing != null && pricing.sttPaidPerMinute != null ? Math.max(0, pricing.sttPaidPerMinute) : 0;
    }

    public static int getSttCost(double audioSeconds) {
        if (audioSeconds <= 0) return 0;
        int rate = getSttPaidPerMinute();
        int usageCost = (int) Math.ceil(audioSeconds / 60.0 * rate);
        return Math.max(getMinDeductCost(), usageCost);
    }

    public static int getTtsPaidPer1kChars(boolean clone) {
        CloudConfig.Pricing pricing = getPricing();
        if (pricing == null) return 0;
        Integer rate = clone ? pricing.ttsClonePer1kChars : pricing.ttsPaidPer1kChars;
        return rate != null ? Math.max(0, rate) : 0;
    }
    public static boolean isCreditsLow() {
        double remaining = getCreditsRemainingExact();
        if (remaining <= 0) return false;
        double total = getCreditsTotalExact();
        if (total <= 0) return false;
        return remaining <= total * 0.2; // 低于 20% 警告
    }

    /** 扣完费马上刷新本地额度缓存，省得等下一次云端拉取。 */
    public static void applyCreditsDeduction(double newRemaining, double newTotal) {
        CloudConfig.Data cfg = cloud();
        if (cfg != null) {
            cfg.credits_remaining = newRemaining;
            cfg.credits_total = newTotal;
        }
    }

    // ========== v2 双线路 + 数值定价 ==========

    private static CloudConfig.Pricing getPricing() {
        CloudConfig.Data cfg = cloud();
        if (cfg != null && cfg.pricing != null) return cfg.pricing;
        return null;
    }

    /** 云端下发的 LLM 段，未启用云端时为 null。 */
    private static CloudConfig.Llm getCloudLlm() {
        CloudConfig.Data cfg = cloud();
        return cfg != null ? cfg.llm : null;
    }

    /** 当前生效的 Chat 档位；云端没配或值不认就按 free。 */
    public static String getChatTier() {
        BuiltinApiConfig builtin = builtinApi();
        return normalizeTier(builtin != null ? builtin.chatTier : null);
    }

    private static String normalizeTier(String value) {
        return value != null && "paid".equalsIgnoreCase(value.trim()) ? "paid" : "free";
    }

    private static boolean isSecureChatConfig(BuiltinChatConfig config) {
        if (config == null || config.apiUrl == null || config.apiUrl.isBlank()) return false;
        try {
            NetworkSecurity.requireSecureUri(config.apiUrl, "Chat API 地址");
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /** STT 档位，云端没配就默认 free。 */
    public static String getSttTier() {
        BuiltinApiConfig builtin = builtinApi();
        return normalizeTier(builtin != null ? builtin.sttTier : null);
    }

    /** TTS 档位，云端没配就默认 free。 */
    public static String getTtsTier() {
        BuiltinApiConfig builtin = builtinApi();
        return normalizeTier(builtin != null ? builtin.ttsTier : null);
    }

    /** 按当前档位取 Chat 配置；云端没启用就返回 null。 */
    private static BuiltinChatConfig resolveChatConfig() {
        BuiltinApiConfig builtin = builtinApi();
        if (builtin == null || builtin.chat == null) return null;
        String tier = getChatTier();
        BuiltinChatTierConfig tiers = builtin.chat;
        BuiltinChatConfig config = "paid".equals(tier) ? tiers.paid : tiers.free;
        return config;
    }

    /** 按当前档位取 STT 配置；有 routes 时先让 RouteSelector 挑一条。 */
    private static BuiltinSttConfig resolveSttConfig() {
        BuiltinApiConfig builtin = builtinApi();
        if (builtin == null || builtin.stt == null) return null;
        String tier = getSttTier();
        BuiltinSttTierConfig tiers = builtin.stt;
        if (tiers.routes != null && !tiers.routes.isEmpty()) {
            RouteDto selected = ROUTE_SELECTOR.selectFirst("stt", tier, builtin.sttRouteId,
                    tiers.routes, routePolicy(), ROUTE_HEALTH, System.currentTimeMillis());
            if (selected instanceof BuiltinSttConfig route) return route;
        }
        BuiltinSttConfig config = "paid".equals(tier) ? tiers.paid : tiers.free;
        return config;
    }

    /** 按当前档位取 TTS 配置；有 routes 时先让 RouteSelector 挑一条。 */
    private static BuiltinTtsConfig resolveTtsConfig() {
        BuiltinApiConfig builtin = builtinApi();
        if (builtin == null || builtin.tts == null) return null;
        String tier = getTtsTier();
        BuiltinTtsTierConfig tiers = builtin.tts;
        if (tiers.routes != null && !tiers.routes.isEmpty()) {
            RouteDto selected = ROUTE_SELECTOR.selectFirst("tts", tier, builtin.ttsRouteId,
                    tiers.routes, routePolicy(), ROUTE_HEALTH, System.currentTimeMillis());
            if (selected instanceof BuiltinTtsConfig route) return route;
        }
        BuiltinTtsConfig config = "paid".equals(tier) ? tiers.paid : tiers.free;
        return config;
    }

    /** Chat 单次调用扣多少额度，0 表示免费。 */
    public static int getChatCost() {
        CloudConfig.Pricing p = getPricing();
        if (p == null) return 0;
        String tier = getChatTier();
        Integer cost = "paid".equals(tier) ? p.chatPaidCost : p.chatFreeCost;
        return cost != null ? cost : 0;
    }

    /** STT 单次调用扣多少额度，0 表示免费。 */
    public static int getSttCost() {
        CloudConfig.Pricing p = getPricing();
        if (p == null) return 0;
        String tier = getSttTier();
        Integer cost = "paid".equals(tier) ? p.sttPaidCost : p.sttFreeCost;
        return cost != null ? cost : 0;
    }

    /** TTS 单次调用扣多少额度，0 表示免费；isClone 走克隆音色那套定价。 */
    public static int getTtsCost(boolean isClone) {
        CloudConfig.Pricing p = getPricing();
        if (p == null) return 0;
        if (isClone) return p.ttsCloneCost != null ? p.ttsCloneCost : 0;
        String tier = getTtsTier();
        Integer cost = "paid".equals(tier) ? p.ttsPaidCost : p.ttsFreeCost;
        return cost != null ? cost : 0;
    }

    /** 额度够不够付 cost；cost<=0 或额度未知都放行。 */
    public static boolean isCreditsSufficient(int cost) {
        if (cost <= 0) return true;
        double remaining = getCreditsRemainingExact();
        OllamaProxyMod.LOGGER.info("额度准入检查: required={}, remaining={}, allowed={}",
                cost, formatCredits(remaining), remaining < 0 || remaining >= cost);
        return remaining < 0 || remaining >= cost;
    }

    private static volatile CloudConfig.MimoVoice[] mimoVoices;

    public static CloudConfig.MimoVoice[] getMimoVoices() {
        return mimoVoices != null ? mimoVoices : new CloudConfig.MimoVoice[0];
    }

    public static void setMimoVoices(CloudConfig.MimoVoice[] voices) {
        mimoVoices = voices != null ? voices : new CloudConfig.MimoVoice[0];
    }

    public static void refreshMimoVoices(Runnable onComplete) {
        if (!isCloudEnabled()) {
            if (onComplete != null) onComplete.run();
            return;
        }
        String requestKey = getCloudApiKey();
        new Thread(() -> {
            try {
                CloudConfig.MimoVoice[] voices = new CloudConfigFetcher().fetchMimoVoices(
                        getCloudApiUrl(), getMimoVoicesUrl(), requestKey, getCloudTimeoutSeconds());
                if (requestKey.equals(getCloudApiKey())) mimoVoices = voices;
            } catch (Throwable t) {
                if (requestKey.equals(getCloudApiKey())) mimoVoices = null;
            }
            if (onComplete != null) {
                // 客户端回调提取到 Dist.CLIENT 专用类，避免公共配置类直接链接 Minecraft
                // 使用 unsafeRunWhenOn：safeRunWhenOn 的 validateSafeReferent 要求 SafeRunnable 必须是
                // 跨类方法引用（implClass != capturingClass），而此处 Runnable 需要捕获 onComplete，
                // 只能写为内联 lambda。unsafeRunWhenOn 在错误的物理端不会构造内层 Runnable，
                // 因此 ClientConfigCallbacks 不会被加载，专服边界仍然成立。
                net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(
                        net.minecraftforge.api.distmarker.Dist.CLIENT,
                        () -> () -> com.hanchen.veritycustomapi.client.ClientConfigCallbacks.runOnClient(onComplete));
                // 非客户端环境下，直接在当前线程回调
                if (!net.minecraftforge.fml.loading.FMLLoader.getDist().isClient()) {
                    onComplete.run();
                }
            }
        }, "Mimo-Voices-Fetch").start();
    }

    public static boolean isMimoCloneVoice(String voiceId) {
        CloudConfig.MimoVoice[] voices = mimoVoices;
        if (voices == null || voiceId == null) return false;
        for (CloudConfig.MimoVoice v : voices) {
            if (voiceId.equals(v.id)) return "clone".equals(v.type);
        }
        return false;
    }

    public static MimoRuntimeConfig getMimoRuntimeConfig() {
        CloudConfig.Data cfg = cloud();
        CloudConfig.MimoConfig mimo = cfg != null ? cfg.mimo : null;
        if (mimo == null || blankToNull(mimo.apiUrl) == null || blankToNull(mimo.apiKey) == null
                || blankToNull(mimo.routeId) == null) {
            return null;
        }
        return new MimoRuntimeConfig(mimo.apiUrl, mimo.apiKey,
                firstNonBlank(mimo.model, "mimo-v2.5-tts"), mimo.routeId, normalizeTier(mimo.tier));
    }

    /** 文档 §3 里云端下发的音色列表路径；可能为空，空的时候由调用方回退到默认路径。 */
    public static String getMimoVoicesUrl() {
        CloudConfig.Data cfg = cloud();
        CloudConfig.MimoConfig mimo = cfg != null ? cfg.mimo : null;
        return mimo == null ? "" : firstNonBlank(mimo.voicesUrl, "");
    }

    public static boolean applyCloudConfig(CloudConfig config) {
        if (config == null || !config.isSuccess()) {
            return false;
        }
        CloudConfig.Data current = cloudConfig;
        if (current != null && current.version != null && config.data.version != null
                && config.data.version < current.version) {
            OllamaProxyMod.LOGGER.warn("忽略过期的云端配置版本: incoming={}, current={}",
                    config.data.version, current.version);
            return false;
        }
        // 先 merge builtinApi，再原子写入两个字段，避免中间状态
        BuiltinApiConfig newBuiltin = config.data.builtin_api != null
                ? mergeBuiltinApi(cloudBuiltinApi, config.data.builtin_api)
                : cloudBuiltinApi;
        if (current != null
                && Double.valueOf(0.0D).equals(current.credits_remaining)
                && config.data.credits_remaining != null
                && config.data.credits_remaining > 0
                && current.version != null
                && config.data.version != null
                && config.data.version <= current.version) {
            config.data.credits_remaining = 0.0D;
        }
        cloudConfig = config.data;
        cloudBuiltinApi = newBuiltin;
        OllamaProxyMod.LOGGER.info("Cloud 额度状态: remaining={}, total={}, billingMode={}",
                formatCredits(getCreditsRemainingExact()), formatCredits(getCreditsTotalExact()),
                getBillingMode());
        if (config.data.builtin_api != null) {
            logBuiltinApiConfig(config.data.builtin_api);
        }
        OllamaProxyMod.LOGGER.info("Cloud config fetched: config_id={} version={}",
                safeLog(config.data.config_id), config.data.version != null ? config.data.version : -1);
        // 通用下发通道：写穿到配置规格，覆盖全部配置项。
        // 放在最后，这样它覆盖不到的具名字段（llm/stt/tts/builtin_api）仍然由 cloud() 的取值规则兜底。
        try {
            com.hanchen.veritycustomapi.config.CloudOverrides.apply(config.data.overrides);
        } catch (RuntimeException error) {
            OllamaProxyMod.LOGGER.warn("云端配置覆盖应用失败: {}", error.getMessage());
        }
        return true;
    }

    public static void clearCloudConfig() {
        cloudConfig = null;
        cloudBuiltinApi = null;
        mimoVoices = null;
        // 覆盖也要一起撤：否则云端关了之后，配置项还停在云端下发的值上，
        // 而 cloud() 已经返回 null，看起来就像「本地配置自己变了」
        com.hanchen.veritycustomapi.config.CloudOverrides.clear();
    }

    private static BuiltinApiConfig mergeBuiltinApi(BuiltinApiConfig previous, BuiltinApiConfig current) {
        if (previous == null) {
            return current;
        }
        BuiltinApiConfig merged = new BuiltinApiConfig();
        merged.enableChatApis = current.enableChatApis;
        merged.enableSttApis = current.enableSttApis;
        merged.enableTtsApis = current.enableTtsApis;
        merged.chatTier = current.chatTier != null ? current.chatTier : previous.chatTier;
        merged.sttTier = current.sttTier != null ? current.sttTier : previous.sttTier;
        merged.ttsTier = current.ttsTier != null ? current.ttsTier : previous.ttsTier;
        merged.chatRouteId = current.chatRouteId != null ? current.chatRouteId : previous.chatRouteId;
        merged.sttRouteId = current.sttRouteId != null ? current.sttRouteId : previous.sttRouteId;
        merged.ttsRouteId = current.ttsRouteId != null ? current.ttsRouteId : previous.ttsRouteId;
        merged.routePolicy = current.routePolicy != null ? current.routePolicy : previous.routePolicy;
        merged.chat = mergeChatTier(previous.chat, current.chat);
        merged.stt = mergeSttTier(previous.stt, current.stt);
        merged.tts = mergeTtsTier(previous.tts, current.tts);
        merged.mimoTtsVoice = (current.mimoTtsVoice != null && !current.mimoTtsVoice.isBlank())
                ? current.mimoTtsVoice : previous.mimoTtsVoice;
        merged.billingMode = current.billingMode != null ? current.billingMode : previous.billingMode;
        return merged;
    }

    private static BuiltinChatTierConfig mergeChatTier(BuiltinChatTierConfig prev, BuiltinChatTierConfig curr) {
        if (curr == null) return prev;
        if (prev == null) return curr;
        BuiltinChatTierConfig merged = new BuiltinChatTierConfig();
        merged.free = mergeChat(prev.free, curr.free);
        merged.paid = mergeChat(prev.paid, curr.paid);
        merged.routes = curr.routes != null ? curr.routes : prev.routes;
        return merged;
    }

    private static BuiltinSttTierConfig mergeSttTier(BuiltinSttTierConfig prev, BuiltinSttTierConfig curr) {
        if (curr == null) return prev;
        if (prev == null) return curr;
        BuiltinSttTierConfig merged = new BuiltinSttTierConfig();
        merged.free = mergeStt(prev.free, curr.free);
        merged.paid = mergeStt(prev.paid, curr.paid);
        merged.routes = curr.routes != null ? curr.routes : prev.routes;
        return merged;
    }

    private static BuiltinTtsTierConfig mergeTtsTier(BuiltinTtsTierConfig prev, BuiltinTtsTierConfig curr) {
        if (curr == null) return prev;
        if (prev == null) return curr;
        BuiltinTtsTierConfig merged = new BuiltinTtsTierConfig();
        merged.free = mergeTts(prev.free, curr.free);
        merged.paid = mergeTts(prev.paid, curr.paid);
        merged.routes = curr.routes != null ? curr.routes : prev.routes;
        return merged;
    }

    private static BuiltinChatConfig mergeChat(BuiltinChatConfig previous, BuiltinChatConfig current) {
        if (current == null) return previous;
        if (previous == null) return current;
        // 创建新对象避免修改原始数据
        BuiltinChatConfig merged = new BuiltinChatConfig();
        merged.tier = blankToNull(current.tier) != null ? current.tier : previous.tier;
        merged.routeId = blankToNull(current.routeId) != null ? current.routeId : previous.routeId;
        merged.enabled = current.enabled != null ? current.enabled : previous.enabled;
        merged.priority = current.priority != null ? current.priority : previous.priority;
        merged.apiProvider = blankToNull(current.apiProvider) != null ? current.apiProvider : previous.apiProvider;
        merged.apiUrl = blankToNull(current.apiUrl) != null ? current.apiUrl : previous.apiUrl;
        merged.apiKey = blankToNull(current.apiKey) != null ? current.apiKey : previous.apiKey;
        merged.model = blankToNull(current.model) != null ? current.model : previous.model;
        merged.requestFormat = blankToNull(current.requestFormat) != null ? current.requestFormat : previous.requestFormat;
        merged.authType = blankToNull(current.authType) != null ? current.authType : previous.authType;
        merged.responseField = blankToNull(current.responseField) != null ? current.responseField : previous.responseField;
        return merged;
    }

    private static BuiltinSttConfig mergeStt(BuiltinSttConfig previous, BuiltinSttConfig current) {
        if (current == null) return previous;
        if (previous == null) return current;
        BuiltinSttConfig merged = new BuiltinSttConfig();
        merged.tier = blankToNull(current.tier) != null ? current.tier : previous.tier;
        merged.routeId = blankToNull(current.routeId) != null ? current.routeId : previous.routeId;
        merged.enabled = current.enabled != null ? current.enabled : previous.enabled;
        merged.priority = current.priority != null ? current.priority : previous.priority;
        merged.apiModel = blankToNull(current.apiModel) != null ? current.apiModel : previous.apiModel;
        merged.apiVoice = blankToNull(current.apiVoice) != null ? current.apiVoice : previous.apiVoice;
        merged.apiProvider = blankToNull(current.apiProvider) != null ? current.apiProvider : previous.apiProvider;
        merged.apiUrl = blankToNull(current.apiUrl) != null ? current.apiUrl : previous.apiUrl;
        merged.apiKey = blankToNull(current.apiKey) != null ? current.apiKey : previous.apiKey;
        merged.model = blankToNull(current.model) != null ? current.model : previous.model;
        merged.requestFormat = blankToNull(current.requestFormat) != null ? current.requestFormat : previous.requestFormat;
        merged.authType = blankToNull(current.authType) != null ? current.authType : previous.authType;
        merged.responseField = blankToNull(current.responseField) != null ? current.responseField : previous.responseField;
        return merged;
    }

    private static BuiltinTtsConfig mergeTts(BuiltinTtsConfig previous, BuiltinTtsConfig current) {
        if (current == null) return previous;
        if (previous == null) return current;
        BuiltinTtsConfig merged = new BuiltinTtsConfig();
        merged.tier = blankToNull(current.tier) != null ? current.tier : previous.tier;
        merged.routeId = blankToNull(current.routeId) != null ? current.routeId : previous.routeId;
        merged.enabled = current.enabled != null ? current.enabled : previous.enabled;
        merged.priority = current.priority != null ? current.priority : previous.priority;
        merged.apiProvider = blankToNull(current.apiProvider) != null ? current.apiProvider : previous.apiProvider;
        merged.apiUrl = blankToNull(current.apiUrl) != null ? current.apiUrl : previous.apiUrl;
        merged.apiKey = blankToNull(current.apiKey) != null ? current.apiKey : previous.apiKey;
        merged.apiModel = blankToNull(current.apiModel) != null ? current.apiModel : previous.apiModel;
        merged.apiVoice = blankToNull(current.apiVoice) != null ? current.apiVoice : previous.apiVoice;
        merged.requestFormat = blankToNull(current.requestFormat) != null ? current.requestFormat : previous.requestFormat;
        merged.authType = blankToNull(current.authType) != null ? current.authType : previous.authType;
        merged.responseField = blankToNull(current.responseField) != null ? current.responseField : previous.responseField;
        return merged;
    }

    private static void logBuiltinApiConfig(BuiltinApiConfig config) {
        if (config == null) return;
        logRouteCompleteness("Chat", config.chat != null ? config.chat.routes : null);
        logRouteCompleteness("STT", config.stt != null ? config.stt.routes : null);
        logRouteCompleteness("TTS", config.tts != null ? config.tts.routes : null);
        logSelectedBuiltinRoute("Chat", getChatTier(), config.chatRouteId);
        logSelectedBuiltinRoute("STT", getSttTier(), config.sttRouteId);
        logSelectedBuiltinRoute("TTS", getTtsTier(), config.ttsRouteId);
    }

    private static void logSelectedBuiltinRoute(String service, String tier, String routeId) {
        OllamaProxyMod.LOGGER.info("云端内置 {} 线路: routeId={}, tier={}",
                service, safeLog(firstNonBlank(routeId, "auto")), safeLog(tier));
    }

    private static void logRouteCompleteness(String service, List<? extends RouteDto> routes) {
        if (routes == null || routes.isEmpty()) {
            OllamaProxyMod.LOGGER.info("Cloud {} 候选线路诊断: count=0", service);
            return;
        }
        for (RouteDto route : routes) {
            OllamaProxyMod.LOGGER.info("Cloud {} 候选线路诊断: routeId={}, tier={}, enabled={}",
                    service, safeLog(route.routeId), safeLog(route.tier), route.enabled);
        }
    }

    private static CloudConfig.Data cloud() {
        return isCloudEnabled() ? cloudConfig : null;
    }

    private static BuiltinApiConfig builtinApi() {
        return isCloudEnabled() ? cloudBuiltinApi : null;
    }

    private static String firstNonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private static String blankToNull(String value) {
        return value != null && !value.isBlank() ? value : null;
    }

    private static String safeLog(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }

    // ========== 代理服务 ==========
    public static int getPort() { return ModConfig.SERVER_PORT.get(); }

    public static boolean isProactiveAwarenessEnabled() { return ModConfig.ENABLE_PROACTIVE_AWARENESS.get(); }
    public static boolean isProactivePauseEventEnabled() { return ModConfig.PROACTIVE_PAUSE_EVENT.get(); }
    public static boolean isProactiveDeathEventEnabled() { return ModConfig.PROACTIVE_DEATH_EVENT.get(); }
    public static boolean isProactiveLowHealthEventEnabled() { return ModConfig.PROACTIVE_LOW_HEALTH_EVENT.get(); }
    public static boolean isProactiveSleepEventEnabled() { return ModConfig.PROACTIVE_SLEEP_EVENT.get(); }
    public static boolean isProactiveWorldEventEnabled() { return ModConfig.PROACTIVE_WORLD_EVENT.get(); }
    public static int getProactiveCooldownSeconds() { return ModConfig.PROACTIVE_COOLDOWN_SECONDS.get(); }
    public static boolean isProactiveDesktopNotificationsEnabled() { return ModConfig.PROACTIVE_DESKTOP_NOTIFICATIONS.get(); }
    public static boolean isProactiveDayNightEventEnabled() { return ModConfig.PROACTIVE_DAY_NIGHT_EVENT.get(); }
    public static boolean isProactiveWeatherEventEnabled() { return ModConfig.PROACTIVE_WEATHER_EVENT.get(); }
    public static boolean isProactiveDimensionEventEnabled() { return ModConfig.PROACTIVE_DIMENSION_EVENT.get(); }
    public static boolean isProactiveHungerEventEnabled() { return ModConfig.PROACTIVE_HUNGER_EVENT.get(); }
    public static boolean isProactiveLevelEventEnabled() { return ModConfig.PROACTIVE_LEVEL_EVENT.get(); }
    public static boolean isProactiveHealthTiersEnabled() { return ModConfig.PROACTIVE_HEALTH_TIERS.get(); }
    public static boolean isProactiveIdleChatEnabled() { return ModConfig.PROACTIVE_IDLE_CHAT.get(); }
    public static int getProactiveIdleIntervalMinutes() { return ModConfig.PROACTIVE_IDLE_INTERVAL_MINUTES.get(); }
    public static int getProactiveCombatSilenceSeconds() { return ModConfig.PROACTIVE_COMBAT_SILENCE_SECONDS.get(); }
    public static boolean isProactiveDeferEventsEnabled() { return ModConfig.PROACTIVE_DEFER_EVENTS.get(); }

    // ========== 游戏内 HUD ==========
    public static boolean isDialogueHudEnabled() { return ModConfig.HUD_ENABLED.get(); }
    public static boolean isDialoguePortraitEnabled() { return ModConfig.HUD_PORTRAIT.get(); }
    public static boolean isStatusBadgeEnabled() { return ModConfig.HUD_STATUS.get(); }
    public static boolean isNavigationArrowEnabled() { return ModConfig.HUD_NAV_ARROW.get(); }
    public static int getDialogueHudSeconds() { return ModConfig.HUD_DIALOGUE_SECONDS.get(); }
    public static boolean isReminderEnabled() { return ModConfig.REMINDER_ENABLED.get(); }
    public static boolean isImmersiveChatEnabled() { return ModConfig.IMMERSIVE_CHAT.get(); }

    // ========== 摄像头视觉 ==========
    public static boolean isCameraEnabled() { return ModConfig.CAMERA_ENABLED.get(); }
    public static String getCameraSource() { return ModConfig.CAMERA_SOURCE.get(); }
    /** 是否采集真实摄像头画面；来源为 screen 时返回 false。 */
    public static boolean isCameraSourceCamera() {
        return !"screen".equalsIgnoreCase(safeSource());
    }
    /** 是否采集游戏内屏幕画面；来源为 camera 时返回 false。 */
    public static boolean isCameraSourceScreen() {
        String source = safeSource();
        return "screen".equalsIgnoreCase(source) || "both".equalsIgnoreCase(source);
    }
    private static String safeSource() {
        String source = ModConfig.CAMERA_SOURCE.get();
        return source == null || source.isBlank() ? "camera" : source.trim();
    }
    public static String getCameraBackend() { return ModConfig.CAMERA_BACKEND.get(); }
    public static String getCameraFfmpegPath() { return ModConfig.CAMERA_FFMPEG_PATH.get(); }
    public static String getCameraDeviceName() { return ModConfig.CAMERA_DEVICE_NAME.get(); }
    public static String getCameraHttpUrl() { return ModConfig.CAMERA_HTTP_URL.get(); }
    public static int getCameraWidth() { return ModConfig.CAMERA_WIDTH.get(); }
    public static int getCameraHeight() { return ModConfig.CAMERA_HEIGHT.get(); }
    public static int getCameraJpegQuality() { return ModConfig.CAMERA_JPEG_QUALITY.get(); }
    public static int getCameraCaptureTimeoutMs() { return ModConfig.CAMERA_CAPTURE_TIMEOUT_MS.get(); }
    public static boolean isCameraSendOnProactiveEvent() { return ModConfig.CAMERA_SEND_ON_PROACTIVE_EVENT.get(); }
    public static boolean isCameraSkillEnabled() { return ModConfig.CAMERA_SKILL_ENABLED.get(); }
    public static boolean isCameraAllowModelToSee() { return ModConfig.CAMERA_ALLOW_MODEL_TO_SEE.get(); }
    public static String getCameraVisionModel() { return ModConfig.CAMERA_VISION_MODEL.get(); }

    // ========== 调试与诊断 ==========
    public static boolean isDevMode() { return ModConfig.DEV_MODE.get(); }
    public static boolean isFriendlyMode() { return ModConfig.FRIENDLY_MODE.get(); }
    public static String getCustomPersona() { return ModConfig.CUSTOM_PERSONA.get(); }
    public static boolean isBilingualSpeechSubtitlesEnabled() { return ModConfig.BILINGUAL_SPEECH_SUBTITLES.get(); }
    public static boolean isBilingualSecondPassEnabled() { return ModConfig.BILINGUAL_SECOND_PASS.get(); }
    public static boolean isOverheadOnlyChatEnabled() { return ModConfig.OVERHEAD_ONLY_CHAT.get(); }
    public static boolean isChatStreamEnabled() { return ModConfig.CHAT_STREAM.get(); }
    public static boolean isTtsStreamEnabled() { return ModConfig.TTS_STREAM.get(); }
    /**
     * 扩展 TTS 是否走游戏原版音频通道。固定为 true，没有开关。
     *
     * 原版通道跟随主音量滑块、与其它声音统一混音、可做 3D 定位，且不额外独占一个音频设备，
     * 在所有能拿到通道的场景下都严格优于自带的 Java Sound 输出；拿不到通道时
     * {@code VanillaAudioSink.open} 会返回 null，调用方自动回退。也就是说「更差」的那一半
     * 已经被自动兜住了，剩下的选择没有必要交给玩家。
     */
    public static boolean isTtsVanillaChannelEnabled() { return true; }

    /** 3D 定位时使用的衰减半径（格）；0 表示不做定位。数值与原版普通音效的默认距离一致。 */
    public static float getTtsAttenuationRadius() { return 16.0F; }
    public static boolean isDisableOriginalTts() { return ModConfig.DISABLE_ORIGINAL_TTS.get(); }

    /**
     * 原版自己的 TTS 该不该开着。
     *
     * 只有一种情况该开：这句话要交回原版念（路线 = 原版朗读）。其余路线下必须关掉，
     * 否则会出现原版女机械音和扩展 TTS 同时播放。
     *
     * 注意这与老的「同时启用原版朗读」是同一件事——{@link #getTtsRouteKind()} 在
     * 那个开关关闭时本来就会返回原版路线，所以老配置的行为完全不变。
     */
    public static boolean shouldOriginalTtsBeEnabled() {
        return getTtsRouteKind() == com.hanchen.veritycustomapi.tts.route.TtsRouteKind.NATIVE;
    }

    /**
     * 本次朗读走哪条路线。
     *
     * 「同时启用原版朗读」优先级最高：用户明确说了要听原版，就不再由扩展接管，
     * 一律按原版路线处理——这样那条老开关与新的三路配置不会互相打架。
     */
    public static com.hanchen.veritycustomapi.tts.route.TtsRouteKind getTtsRouteKind() {
        if (!isDisableOriginalTts()) {
            return com.hanchen.veritycustomapi.tts.route.TtsRouteKind.NATIVE;
        }
        return com.hanchen.veritycustomapi.tts.route.TtsRoutePlanner.normalize(
                ModConfig.TTS_ROUTE_KIND.get(), legacyRouteKind());
    }

    /**
     * 没写路线时按老的 {@code local/online} 模式推导。
     *
     * 老配置文件里没有新键，必须仍然能跑出原来那条路，否则升级一次就变哑巴。
     */
    private static com.hanchen.veritycustomapi.tts.route.TtsRouteKind legacyRouteKind() {
        return getTtsMode() == TtsMode.LOCAL
                ? com.hanchen.veritycustomapi.tts.route.TtsRouteKind.LOCAL
                : com.hanchen.veritycustomapi.tts.route.TtsRouteKind.ENDPOINT;
    }

    /** 台词显示语言（字幕/聊天栏）。 */
    public static com.hanchen.veritycustomapi.voice.VoiceLanguage getVoiceDisplayLanguage() {
        return voiceLanguageOrDefault(ModConfig.VOICE_DISPLAY_LANGUAGE.get());
    }

    /** 朗读语言；{@code FOLLOW} 表示不翻译。 */
    public static com.hanchen.veritycustomapi.voice.VoiceLanguage getVoiceSpeakLanguage() {
        return voiceLanguageOrDefault(ModConfig.VOICE_SPEAK_LANGUAGE.get());
    }

    /** 配置里写了个认不出来的值就按「跟随」处理，绝不返回 null。 */
    private static com.hanchen.veritycustomapi.voice.VoiceLanguage voiceLanguageOrDefault(String raw) {
        com.hanchen.veritycustomapi.voice.VoiceLanguage parsed =
                com.hanchen.veritycustomapi.voice.VoiceLanguage.parse(raw);
        return parsed == null ? com.hanchen.veritycustomapi.voice.VoiceLanguage.FOLLOW : parsed;
    }

    /** 台词翻译专用模型；空串表示复用当前对话线路。 */
    public static String getVoiceTranslateModel() { return trimOrEmpty(ModConfig.VOICE_TRANSLATE_MODEL.get()); }

    /** 台词翻译专用接口地址；空串表示复用当前对话线路。 */
    public static String getVoiceTranslateUrl() { return trimOrEmpty(ModConfig.VOICE_TRANSLATE_URL.get()); }

    /** 台词翻译专用接口密钥。 */
    public static String getVoiceTranslateApiKey() { return trimOrEmpty(ModConfig.VOICE_TRANSLATE_API_KEY.get()); }

    /** 是否配了独立的翻译端点；没配就复用对话线路。 */
    public static boolean hasVoiceTranslateEndpoint() {
        return !getVoiceTranslateUrl().isEmpty();
    }

    /** 配置里可能写着空白串；统一收敛成「空串」而不是 null，调用方不用再判空。 */
    private static String trimOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }
    public static boolean isMemoryEnabled() { return ModConfig.MEMORY_ENABLED.get(); }
    public static boolean isMemoryRetrievalEnabled() { return ModConfig.MEMORY_RETRIEVAL_ENABLED.get(); }
    public static int getMemoryRetrievalTopN() { return ModConfig.MEMORY_RETRIEVAL_TOP_N.get(); }
    public static int getMemoryDecayDays() { return ModConfig.MEMORY_DECAY_DAYS.get(); }
    public static boolean isMemoryAutoWriteEnabled() { return ModConfig.MEMORY_AUTO_WRITE_ENABLED.get(); }
    public static int getMemoryAutoWriteMinIntervalSeconds() { return ModConfig.MEMORY_AUTO_WRITE_MIN_INTERVAL_SECONDS.get(); }
    public static boolean isMemoryPerPlayer() { return ModConfig.MEMORY_PER_PLAYER.get(); }
    public static boolean isConversationTrimEnabled() { return ModConfig.CONVERSATION_TRIM_ENABLED.get(); }
    public static boolean isInventoryContextEnabled() { return ModConfig.INVENTORY_CONTEXT_ENABLED.get(); }
    public static int getInventoryMaxTypes() { return ModConfig.INVENTORY_MAX_TYPES.get(); }
    public static boolean isConversationLogEnabled() { return ModConfig.CHAT_LOG_ENABLED.get(); }
    public static int getConversationLogMaxEntries() { return ModConfig.CHAT_LOG_MAX_ENTRIES.get(); }
    public static boolean isWebSearchEnabled() { return ModConfig.WEB_SEARCH_ENABLED.get(); }
    public static String getWebSearchProvider() { return ModConfig.WEB_SEARCH_PROVIDER.get(); }
    public static String getWebSearchApiKey() { return ModConfig.WEB_SEARCH_API_KEY.get(); }
    public static boolean isWebSearchAlways() { return ModConfig.WEB_SEARCH_ALWAYS.get(); }
    public static String getWebSearchCustomUrl() { return ModConfig.WEB_SEARCH_CUSTOM_URL.get(); }
    public static String getWebSearchCustomBody() { return ModConfig.WEB_SEARCH_CUSTOM_BODY.get(); }
    public static String getWebSearchCustomHeaders() { return ModConfig.WEB_SEARCH_CUSTOM_HEADERS.get(); }
    public static String getWebSearchCustomResults() { return ModConfig.WEB_SEARCH_CUSTOM_RESULTS.get(); }
    public static String getWebSearchCustomFields() { return ModConfig.WEB_SEARCH_CUSTOM_FIELDS.get(); }
    public static boolean isRuntimeMetricsEnabled() { return ModConfig.DEBUG_RUNTIME_METRICS.get(); }
    public static int getRuntimeMetricsIntervalSeconds() { return ModConfig.DEBUG_RUNTIME_METRICS_INTERVAL_SECONDS.get(); }

    // ========== LLM 配置 ==========
    public static ApiProviderEnum getLlmApiProvider() {
        BuiltinChatConfig builtin = resolveChatConfig();
        CloudConfig.Data cfg = cloud();
        String value;
        if (isEnableBuiltinLlmApis()) {
            value = isSecureEndpoint(builtin != null ? builtin.apiUrl : null)
                    ? firstNonBlank(builtin.apiProvider, ApiProviderEnum.CUSTOM_OPENAI.name())
                    : ModConfig.LLM_API_PROVIDER.get();
        } else if (cfg != null && cfg.llm != null && isSecureEndpoint(cfg.llm.apiUrl)) {
            value = firstNonBlank(cfg.llm.apiProvider, ModConfig.LLM_API_PROVIDER.get());
        } else {
            value = ModConfig.LLM_API_PROVIDER.get();
        }
        try {
            return ApiProviderEnum.valueOf(value);
        } catch (IllegalArgumentException e) {
            return ApiProviderEnum.CUSTOM_OPENAI;
        }
    }

    public static String getTargetApiUrl() {
        BuiltinChatConfig builtin = resolveChatConfig();
        CloudConfig.Data cfg = cloud();
        if (isEnableBuiltinLlmApis()) {
            return isSecureEndpoint(builtin != null ? builtin.apiUrl : null)
                    ? builtin.apiUrl : ModConfig.TARGET_API_URL.get();
        }
        if (cfg != null && cfg.llm != null && isSecureEndpoint(cfg.llm.apiUrl)) {
            return cfg.llm.apiUrl;
        }
        ApiProviderEnum provider = getLlmApiProvider();
        if (provider != ApiProviderEnum.CUSTOM_OPENAI) {
            return provider.apiUrl;
        }
        String custom = ModConfig.TARGET_API_URL.get();
        if (custom != null && !custom.isBlank()) return custom;
        return provider.apiUrl;
    }

    public static String getTargetApiKey() {
        BuiltinChatConfig builtin = resolveChatConfig();
        CloudConfig.Data cfg = cloud();
        if (isEnableBuiltinLlmApis()) {
            return isSecureEndpoint(builtin != null ? builtin.apiUrl : null)
                    ? cleanApiKey(firstNonBlank(builtin.apiKey, ""))
                    : cleanApiKey(ModConfig.TARGET_API_KEY.get());
        }
        if (cfg != null && cfg.llm != null && isSecureEndpoint(cfg.llm.apiUrl)
                && cfg.llm.apiKey != null && !cfg.llm.apiKey.isBlank()) {
            return cleanApiKey(cfg.llm.apiKey);
        }
        return cleanApiKey(ModConfig.TARGET_API_KEY.get());
    }

    public static String getTargetModel() {
        BuiltinChatConfig builtin = resolveChatConfig();
        CloudConfig.Data cfg = cloud();
        String resolved;
        if (isEnableBuiltinLlmApis()) {
            resolved = isSecureEndpoint(builtin != null ? builtin.apiUrl : null)
                    ? firstNonBlank(builtin.model, ModConfig.TARGET_MODEL.get())
                    : ModConfig.TARGET_MODEL.get();
        } else if (cfg != null && cfg.llm != null && isSecureEndpoint(cfg.llm.apiUrl)) {
            resolved = firstNonBlank(cfg.llm.model, ModConfig.TARGET_MODEL.get());
        } else {
            ApiProviderEnum provider = getLlmApiProvider();
            if (provider != ApiProviderEnum.CUSTOM_OPENAI) return provider.defaultModel;
            String custom = ModConfig.TARGET_MODEL.get();
            resolved = custom != null && !custom.isBlank() ? custom : provider.defaultModel;
        }
        if (resolved != null && !resolved.isBlank()) return resolved;
        // 云端线路没有下发 model、本地也没配时，绝不能返回空串：
        // 原版 Verity 会抛 "Enter a custom AI model for OPENAI."，代理也会把空 model 发给上游导致 400。
        // 内置线路的实际模型由代理用 route.model 覆盖，这里只要保证非空即可。
        String localFallback = ModConfig.TARGET_MODEL.get();
        if (localFallback != null && !localFallback.isBlank()) return localFallback;
        String providerDefault = getLlmApiProvider().defaultModel;
        return providerDefault != null && !providerDefault.isBlank() ? providerDefault : "gpt-4o-mini";
    }

    private static boolean isSecureEndpoint(String value) {
        try {
            NetworkSecurity.requireSecureUri(value, "API 地址");
            return true;
        } catch (IllegalArgumentException error) {
            OllamaProxyMod.LOGGER.warn("云端 Chat 线路未通过安全校验，将使用本地 LLM 配置");
            return false;
        }
    }
    
    /**
     * 获取当前选中的人设预设枚举（云端下发了 selectedPreset 时以云端为准）
     */
    public static PromptPreset getCurrentPreset() {
        String selectedName = getSelectedPresetRaw();
        if (selectedName == null || selectedName.isBlank()) {
            return PromptPreset.ORIGINAL_VERITY;
        }
        for (PromptPreset preset : PromptPreset.values()) {
            if (preset.name().equalsIgnoreCase(selectedName)) return preset;
        }
        for (PromptPreset preset : PromptPreset.values()) {
            if (preset.displayName.equals(selectedName)) return preset;
        }
        return PromptPreset.ORIGINAL_VERITY;
    }

    /**
     * 获取人设注入内容（仅角色描述，不含 XML 基础结构，用于注入原版提示词）
     */
    public static String getCustomPersonaPrompt() {
        String prefix = "";
        if (isFriendlyMode()) {
            prefix = "CRITICAL: You are in FRIENDLY MODE. You are a pure, positive helper. "
                    + "NEVER mention anything scary, dangerous, or threatening. "
                    + "Always be encouraging, helpful, and optimistic.\n\n";
        }

        // 自定义人设：填写后直接使用
        String customPersona = getCustomPersona();
        if (customPersona != null && !customPersona.isBlank()) {
            return prefix + customPersona;
        }

        // 预设人设：只取角色描述，不含 XML 基础结构
        PromptPreset preset = getCurrentPreset();
        if (preset == null || preset == PromptPreset.ORIGINAL_VERITY) {
            return prefix; // 原版人设无需额外注入
        }
        if (preset == PromptPreset.CUSTOM_FILE) {
            return prefix + readCustomPromptFile();
        }
        return prefix + buildPersonaRoleDesc(preset);
    }

    /**
     * 构建人设角色描述（仅角色+情绪，不含工具/动作示例，动作由原版 Verity 工具链处理）
     */
    private static String buildPersonaRoleDesc(PromptPreset preset) {
        return switch (preset) {
            case CAT_GIRL -> ROLE_CAT_GIRL + EMOTION_CAT_GIRL;
            case TSUNDERE_VILLAGER -> ROLE_TSUNDERE + EMOTION_TSUNDERE;
            case SALTY_FISH -> ROLE_SALTY + EMOTION_SALTY;
            case PETRA -> ROLE_PETRA + EMOTION_PETRA;
            case ENDERMAN -> ROLE_ENDERMAN + EMOTION_ENDERMAN;
            case ALCHEMIST -> ROLE_ALCHEMIST + EMOTION_ALCHEMIST;
            case OLD_PRIEST -> ROLE_OLD_PRIEST + EMOTION_OLD_PRIEST;
            default -> ROLE_VERITY + EMOTION_VERITY;
        };
    }

    public static boolean isCustomPromptEnabled() {
        CloudConfig.Llm llm = getCloudLlm();
        if (llm != null && llm.enableCustomPrompt != null) return llm.enableCustomPrompt;
        return ModConfig.ENABLE_CUSTOM_PROMPT.get();
    }

    public static String getEffectiveSystemPrompt() {
        return getCustomPersonaPrompt();
    }

    /**
     * 人设预设名。云端下发了 {@code llm.selectedPreset} 时以云端为准，否则读本地 TOML。
     * （这两个云端字段以前声明了却没人读，云端配了等于没配，这里补上取值。）
     */
    private static String getSelectedPresetRaw() {
        CloudConfig.Llm llm = getCloudLlm();
        if (llm != null && llm.selectedPreset != null && !llm.selectedPreset.isBlank()) {
            return llm.selectedPreset;
        }
        return ModConfig.SELECTED_PRESET.get();
    }

    /**
     * 自定义提示词文件路径。云端下发了 {@code llm.customPromptFile} 时以云端为准。
     */
    private static String getCustomPromptFileRaw() {
        CloudConfig.Llm llm = getCloudLlm();
        if (llm != null && llm.customPromptFile != null && !llm.customPromptFile.isBlank()) {
            return llm.customPromptFile;
        }
        return ModConfig.CUSTOM_PROMPT_FILE.get();
    }

    private static String readCustomPromptFile() {
        String path = getCustomPromptFileRaw();
        if (path == null || path.isBlank()) return "";
        Path filePath = Paths.get(path);
        if (!filePath.isAbsolute()) {
            String relative = path.replace('\\', '/');
            if (relative.startsWith("./")) {
                relative = relative.substring(2);
            }
            filePath = relative.startsWith("config/")
                    ? ForgePaths.config(relative.substring("config/".length()))
                    : ForgePaths.game(relative);
        }
        if (!Files.isRegularFile(filePath)) {
            OllamaProxyMod.LOGGER.warn("自定义提示词文件不存在: {}", filePath.toAbsolutePath());
            return "";
        }
        try {
            byte[] bytes = Files.readAllBytes(filePath);
            if (bytes.length > 262144) {
                OllamaProxyMod.LOGGER.warn("自定义提示词文件超过 256 KiB 限制: {}", filePath.toAbsolutePath());
                return "";
            }
            String content = decodeTextWithAutoEncoding(bytes);
            return content == null ? "" : content.trim();
        } catch (Exception e) {
            OllamaProxyMod.LOGGER.error("读取自定义提示词文件失败: " + filePath.toAbsolutePath(), e);
            return "";
        }
    }
    
    /**
     * 获取当前选中的人设显示名称（用于日志输出）
     */
    public static String getSelectedPresetName() {
        String selectedName = getSelectedPresetRaw();
        if (selectedName == null || selectedName.isBlank()) {
            return PromptPreset.ORIGINAL_VERITY.displayName + " (默认)";
        }
        
        for (PromptPreset preset : PromptPreset.values()) {
            if (preset.name().equalsIgnoreCase(selectedName)) {
                return preset.displayName + " (" + preset.name() + ")";
            }
        }
        for (PromptPreset preset : PromptPreset.values()) {
            if (preset.displayName.equals(selectedName)) {
                return preset.displayName + " (" + preset.name() + ")";
            }
        }
        return "未知: " + selectedName;
    }
    
    /**
     * 自动检测文本编码并解码（支持 UTF-8 和 GBK）
     * Windows 记事本默认保存为 GBK，容易导致中文乱码
     */
    private static String decodeTextWithAutoEncoding(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return "";
        
        // 先按 UTF-8 解
        // BOM（EF BB BF）在开头就跳过这 3 个字节
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return new String(bytes, 3, bytes.length - 3, java.nio.charset.StandardCharsets.UTF_8);
        }
        
        // 解出来没有 U+FFFD 替换字符，说明本来就是 UTF-8
        String utf8Str = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        if (!utf8Str.contains("\uFFFD")) {
            // 有替换字符才说明解错了，继续往下试
            return utf8Str;
        }
        
        // UTF-8 解出乱码了，再试 GBK（中文 Windows 记事本默认就存这个）
        try {
            String gbkStr = new String(bytes, "GBK");
            // 两边各数一遍乱码字符，GBK 明显更少才采用
            long gbkBadCount = gbkStr.chars().filter(c -> c == 0xFFFD || c == 0x00).count();
            long utf8BadCount = utf8Str.chars().filter(c -> c == 0xFFFD || c == 0x00).count();
            if (gbkBadCount < utf8BadCount) {
                OllamaProxyMod.LOGGER.info("自定义提示词文件使用 GBK 编码（已自动转换为 UTF-8）");
                return gbkStr;
            }
        } catch (Exception e) {
            // GBK 不可用就忽略
        }
        
        // 都拿不准就回退 UTF-8
        OllamaProxyMod.LOGGER.warn("无法确定文本编码，使用 UTF-8 解码（可能有乱码）");
        return utf8Str;
    }

    public static int getRequestTimeout() {
        CloudConfig.Data cfg = cloud();
        if (cfg != null && cfg.llm != null && cfg.llm.requestTimeout != null) {
            return cfg.llm.requestTimeout;
        }
        return ModConfig.REQUEST_TIMEOUT.get();
    }
    public static boolean isUseOriginalVerityChatChain() { return ModConfig.USE_ORIGINAL_VERITY_CHAT_CHAIN.get(); }

    public static boolean isEnableBuiltinLlmApis() {
        BuiltinApiConfig builtin = builtinApi();
        if (builtin != null && builtin.enableChatApis != null) {
            return builtin.enableChatApis;
        }
        return ModConfig.ENABLE_BUILTIN_LLM_APIS.get();
    }
    public static boolean isEnableBuiltinSttApis() {
        BuiltinApiConfig builtin = builtinApi();
        if (builtin != null && builtin.enableSttApis != null) {
            return builtin.enableSttApis;
        }
        return ModConfig.ENABLE_BUILTIN_STT_APIS.get();
    }
    public static boolean isEnableBuiltinTtsApis() {
        BuiltinApiConfig builtin = builtinApi();
        if (builtin != null && builtin.enableTtsApis != null) {
            return builtin.enableTtsApis;
        }
        return ModConfig.ENABLE_BUILTIN_TTS_APIS.get();
    }

    // ========== 语音识别 ==========
    public static boolean isEnableVoiceInput() {
        CloudConfig.Data cfg = cloud();
        if (cfg != null && cfg.stt != null && cfg.stt.enableVoiceInput != null) return cfg.stt.enableVoiceInput;
        return ModConfig.ENABLE_VOICE_INPUT.get();
    }

    public static RecognitionMode getRecognitionMode() {
        if (com.hanchen.veritycustomapi.compat.RuntimePlatform.isAndroidRuntime()) {
            return RecognitionMode.API;
        }
        CloudConfig.Data cfg = cloud();
        String localMode = ModConfig.RECOGNITION_MODE.get();
        String mode = !snapshotSttRoutes().isEmpty()
                ? "api"
                : cfg != null && cfg.stt != null && cfg.stt.mode != null && !cfg.stt.mode.isBlank()
                ? cfg.stt.mode : localMode;
        RecognitionMode configured;
        try { configured = RecognitionMode.valueOf(mode.toUpperCase()); }
        catch (IllegalArgumentException e) { configured = RecognitionMode.LOCAL; }
        return configured;
    }

    public static SttApiProviderEnum getSttApiProvider() {
        BuiltinSttConfig builtin = resolveSttConfig();
        CloudConfig.Data cfg = cloud();
        String providerName = isEnableBuiltinSttApis() && builtin != null && builtin.apiProvider != null && !builtin.apiProvider.isBlank()
                ? builtin.apiProvider
                : cfg != null && cfg.stt != null && cfg.stt.apiProvider != null && !cfg.stt.apiProvider.isBlank()
                ? cfg.stt.apiProvider
                : ModConfig.STT_API_PROVIDER.get();
        if (cfg == null && builtin == null && isEnableBuiltinSttApis()) {
            return SttApiProviderEnum.SILICONFLOW;
        }
        try {
            return SttApiProviderEnum.valueOf(providerName);
        } catch (IllegalArgumentException e) {
            return SttApiProviderEnum.CUSTOM_OPENAI;
        }
    }

    public static String getSpeechApiUrl() {
        BuiltinSttConfig builtin = resolveSttConfig();
        CloudConfig.Data cfg = cloud();
        if (isEnableBuiltinSttApis()) {
            return firstNonBlank(builtin != null ? builtin.apiUrl : null, "");
        }
        if (cfg != null && cfg.stt != null) {
            return firstNonBlank(cfg.stt.apiUrl, ModConfig.SPEECH_API_URL.get());
        }
        SttApiProviderEnum provider = getSttApiProvider();
        if (provider != SttApiProviderEnum.CUSTOM_OPENAI) {
            return provider.apiUrl;
        }
        String custom = ModConfig.SPEECH_API_URL.get();
        if (custom != null && !custom.isBlank()) return custom;
        return provider.apiUrl;
    }

    public static String getSpeechApiKey() {
        BuiltinSttConfig builtin = resolveSttConfig();
        CloudConfig.Data cfg = cloud();
        if (isEnableBuiltinSttApis()) {
            return cleanApiKey(firstNonBlank(builtin != null ? builtin.apiKey : null, ""));
        }
        if (cfg != null && cfg.stt != null && cfg.stt.apiKey != null && !cfg.stt.apiKey.isBlank()) {
            return cleanApiKey(cfg.stt.apiKey);
        }
        return cleanApiKey(ModConfig.SPEECH_API_KEY.get());
    }

    public static String getSpeechApiModel() {
        BuiltinSttConfig builtin = resolveSttConfig();
        CloudConfig.Data cfg = cloud();
        if (isEnableBuiltinSttApis()) {
            return firstNonBlank(builtin != null ? builtin.model : null, "");
        }
        if (cfg != null && cfg.stt != null) {
            return firstNonBlank(cfg.stt.model, ModConfig.SPEECH_API_MODEL.get());
        }
        SttApiProviderEnum provider = getSttApiProvider();
        if (provider != SttApiProviderEnum.CUSTOM_OPENAI) {
            return provider.defaultModel;
        }
        String custom = ModConfig.SPEECH_API_MODEL.get();
        if (custom != null && !custom.isBlank()) return custom;
        return provider.defaultModel;
    }

    public static String getVoskModelPath() {
        CloudConfig.Data cfg = cloud();
        String configured = cfg != null && cfg.stt != null
                ? firstNonBlank(cfg.stt.voskModelPath, ModConfig.VOSK_MODEL_PATH.get())
                : ModConfig.VOSK_MODEL_PATH.get();
        Path path = Paths.get(configured);
        if (path.isAbsolute()) return path.normalize().toString();
        String relative = configured.replace('\\', '/');
        if (relative.startsWith("./")) relative = relative.substring(2);
        return (relative.startsWith("config/")
                ? ForgePaths.config(relative.substring("config/".length()))
                : ForgePaths.game(relative)).toString();
    }
    public static int getSpeechSampleRate() {
        CloudConfig.Data cfg = cloud();
        if (cfg != null && cfg.stt != null && cfg.stt.sampleRate != null) return cfg.stt.sampleRate;
        return ModConfig.SAMPLE_RATE.get();
    }
    
    public static String getSpeechRequestFormat() {
        BuiltinSttConfig builtin = resolveSttConfig();
        CloudConfig.Data cfg = cloud();
        if (isEnableBuiltinSttApis()) {
            return firstNonBlank(builtin != null ? builtin.requestFormat : null, "multipart");
        }
        if (cfg != null && cfg.stt != null) return firstNonBlank(cfg.stt.requestFormat, ModConfig.SPEECH_REQUEST_FORMAT.get());
        return ModConfig.SPEECH_REQUEST_FORMAT.get();
    }

    public static String getSpeechAuthType() {
        BuiltinSttConfig builtin = resolveSttConfig();
        CloudConfig.Data cfg = cloud();
        if (isEnableBuiltinSttApis()) {
            return firstNonBlank(builtin != null ? builtin.authType : null, "bearer");
        }
        if (cfg != null && cfg.stt != null) return firstNonBlank(cfg.stt.authType, ModConfig.SPEECH_AUTH_TYPE.get());
        return ModConfig.SPEECH_AUTH_TYPE.get();
    }

    public static String getSpeechResponseField() {
        BuiltinSttConfig builtin = resolveSttConfig();
        CloudConfig.Data cfg = cloud();
        if (isEnableBuiltinSttApis()) {
            return firstNonBlank(builtin != null ? builtin.responseField : null, "text");
        }
        if (cfg != null && cfg.stt != null) return firstNonBlank(cfg.stt.responseField, ModConfig.SPEECH_RESPONSE_FIELD.get());
        return ModConfig.SPEECH_RESPONSE_FIELD.get();
    }

    public static ApiHealthTarget getSttHealthTarget() {
        BuiltinSttConfig builtin = resolveSttConfig();
        CloudConfig.Data cfg = cloud();
        String source = isEnableBuiltinSttApis() ? "云端内置"
                : cfg != null && cfg.stt != null && cfg.stt.apiUrl != null && !cfg.stt.apiUrl.isBlank()
                ? "云端配置" : "自定义";
        return new ApiHealthTarget(source, getSpeechApiUrl(), getSpeechApiKey(), getSpeechApiModel(),
                getSpeechAuthType(), false);
    }

    // ========== TTS 语音合成 ==========
    public static boolean isEnableTts() {
        CloudConfig.Data cfg = cloud();
        if (cfg != null && cfg.tts != null && cfg.tts.enableTts != null) return cfg.tts.enableTts;
        return ModConfig.ENABLE_TTS.get();
    }

    public static TtsMode getTtsMode() {
        // Android/FCL 环境不支持 ONNX 离线 TTS，强制使用在线模式
        if (com.hanchen.veritycustomapi.compat.RuntimePlatform.isAndroidRuntime()) {
            return TtsMode.ONLINE;
        }
        CloudConfig.Data cfg = cloud();
        String mode = cfg != null && cfg.tts != null && cfg.tts.mode != null && !cfg.tts.mode.isBlank()
                ? cfg.tts.mode
                : ModConfig.TTS_MODE.get();
        TtsMode configured;
        try { configured = TtsMode.valueOf(mode.toUpperCase()); }
        catch (IllegalArgumentException e) { configured = TtsMode.ONLINE; }
        return cfg != null ? configured : (isEnableBuiltinTtsApis() ? TtsMode.ONLINE : configured);
    }

    public static int getTtsSampleRate() {
        CloudConfig.Data cfg = cloud();
        if (cfg != null && cfg.tts != null && cfg.tts.sampleRate != null) return cfg.tts.sampleRate;
        return ModConfig.TTS_SAMPLE_RATE.get();
    }
    public static String getActualTtsModelPath() { return ModConfig.TTS_MODEL_PATH.get(); }
    public static double getTtsSpeed() {
        CloudConfig.Data cfg = cloud();
        if (cfg != null && cfg.tts != null && cfg.tts.speed != null) return cfg.tts.speed;
        return ModConfig.TTS_SPEED.get();
    }
    public static double getTtsVolume() {
        CloudConfig.Data cfg = cloud();
        if (cfg != null && cfg.tts != null && cfg.tts.volume != null) return cfg.tts.volume;
        return ModConfig.TTS_VOLUME.get();
    }

    public static TtsApiProviderEnum getTtsApiProvider() {
        TtsRoute active = CURRENT_TTS_ROUTE.get();
        if (active != null) {
            try { return TtsApiProviderEnum.valueOf(firstNonBlank(active.apiProvider(), "CUSTOM_OPENAI")); }
            catch (IllegalArgumentException ignored) { return TtsApiProviderEnum.CUSTOM_OPENAI; }
        }
        BuiltinTtsConfig builtin = resolveTtsConfig();
        CloudConfig.Data cfg = cloud();
        String providerName = isEnableBuiltinTtsApis() && builtin != null && builtin.apiProvider != null && !builtin.apiProvider.isBlank()
                ? builtin.apiProvider
                : cfg != null && cfg.tts != null && cfg.tts.apiProvider != null && !cfg.tts.apiProvider.isBlank()
                ? cfg.tts.apiProvider
                : ModConfig.TTS_API_PROVIDER.get();
        if (cfg == null && builtin == null && isEnableBuiltinTtsApis()) {
            return TtsApiProviderEnum.MIMO_TTS;
        }
        try {
            return TtsApiProviderEnum.valueOf(providerName);
        } catch (IllegalArgumentException e) {
            return TtsApiProviderEnum.CUSTOM_OPENAI;
        }
    }

    public static String getTtsAuthType() {
        return getTtsApiProvider().authType;
    }

    public static String getTtsRequestFormat() {
        return getTtsApiProvider().requestFormat;
    }

    public static String getTtsApiUrl() {
        TtsRoute active = CURRENT_TTS_ROUTE.get();
        if (active != null) return firstNonBlank(active.apiUrl(), "");
        BuiltinTtsConfig builtin = resolveTtsConfig();
        CloudConfig.Data cfg = cloud();
        if (isEnableBuiltinTtsApis()) {
            return firstNonBlank(builtin != null ? builtin.apiUrl : null, "");
        }
        if (cfg != null && cfg.tts != null) {
            return firstNonBlank(cfg.tts.apiUrl, ModConfig.TTS_API_URL.get());
        }
        TtsApiProviderEnum provider = getTtsApiProvider();
        String custom = ModConfig.TTS_API_URL.get();
        if (custom != null && !custom.isBlank()) return custom;
        return provider.apiUrl;
    }

    public static String getTtsApiKey() {
        TtsRoute active = CURRENT_TTS_ROUTE.get();
        if (active != null) return cleanApiKey(firstNonBlank(active.apiKey(), ""));
        BuiltinTtsConfig builtin = resolveTtsConfig();
        CloudConfig.Data cfg = cloud();
        if (isEnableBuiltinTtsApis()) {
            return cleanApiKey(firstNonBlank(builtin != null ? builtin.apiKey : null, ""));
        }
        if (cfg != null && cfg.tts != null && cfg.tts.apiKey != null && !cfg.tts.apiKey.isBlank()) {
            return cleanApiKey(cfg.tts.apiKey);
        }
        return cleanApiKey(ModConfig.TTS_API_KEY.get());
    }

    public static String getTtsApiModel() {
        TtsRoute active = CURRENT_TTS_ROUTE.get();
        if (active != null) return firstNonBlank(active.model(), "");
        BuiltinTtsConfig builtin = resolveTtsConfig();
        CloudConfig.Data cfg = cloud();
        if (isEnableBuiltinTtsApis()) {
            return firstNonBlank(builtin != null ? builtin.apiModel : null, "");
        }
        if (cfg != null && cfg.tts != null) {
            return firstNonBlank(cfg.tts.apiModel, ModConfig.TTS_API_MODEL.get());
        }
        TtsApiProviderEnum provider = getTtsApiProvider();
        String custom = ModConfig.TTS_API_MODEL.get();
        if (custom != null && !custom.isBlank()) return custom;
        return provider.defaultModel;
    }

    public static ApiHealthTarget getTtsHealthTarget() {
        if (isEnableBuiltinTtsApis()) {
            List<TtsRoute> routes = snapshotTtsRoutes();
            if (!routes.isEmpty()) {
                TtsRoute route = routes.get(0);
                TtsApiProviderEnum provider;
                try {
                    provider = TtsApiProviderEnum.valueOf(route.apiProvider());
                } catch (Exception ignored) {
                    provider = TtsApiProviderEnum.CUSTOM_OPENAI;
                }
                return new ApiHealthTarget("云端内置", route.apiUrl(), route.apiKey(), "云端语音合成",
                        provider.authType, false);
            }
            return new ApiHealthTarget("云端内置", "", "", "", "", false);
        }
        CloudConfig.Data cfg = cloud();
        String source = cfg != null && cfg.tts != null && cfg.tts.apiUrl != null && !cfg.tts.apiUrl.isBlank()
                ? "云端配置" : "自定义";
        return new ApiHealthTarget(source, getTtsApiUrl(), getTtsApiKey(), getTtsApiModel(),
                getTtsAuthType(), false);
    }

    public static MimoTtsVoice getBuiltinMimoTtsVoice() {
        // 本地选择优先，云端配置仅作为初始默认值
        String localVoice = ModConfig.BUILTIN_MIMO_TTS_VOICE.get();
        String configured;
        if (localVoice != null && !localVoice.isBlank() && !"苏打".equals(localVoice)) {
            configured = localVoice;
        } else {
            BuiltinApiConfig builtin = builtinApi();
            configured = builtin != null && builtin.mimoTtsVoice != null && !builtin.mimoTtsVoice.isBlank()
                    ? builtin.mimoTtsVoice
                    : localVoice;
        }
        for (MimoTtsVoice voice : MimoTtsVoice.values()) {
            if (voice.name().equalsIgnoreCase(configured) || voice.voiceName.equals(configured)) {
                return voice;
            }
        }
        return MimoTtsVoice.SUDA;
    }

    // ========== JSON 音色持久化（绕过 Forge TOML 的 GBK/UTF-8 编码问题）==========
    private static final Path MIMO_VOICE_STORE = ForgePaths.config("verity_extended", "mimo_voice.json");
    private static final Path LEGACY_MIMO_VOICE_STORE = Paths.get("config", "verity_extended", "mimo_voice.json");
    private static volatile String cachedMimoVoice;
    private static volatile Boolean cachedMimoVoiceClone;
    private static final Gson GSON = new Gson();

    public static String loadMimoVoice() {
        if (cachedMimoVoice != null) return cachedMimoVoice;
        try {
            ForgePaths.migrateRelative(LEGACY_MIMO_VOICE_STORE, MIMO_VOICE_STORE);
            if (!Files.exists(MIMO_VOICE_STORE)) {
                Files.createDirectories(MIMO_VOICE_STORE.getParent());
                return null;
            }
            byte[] data = Files.readAllBytes(MIMO_VOICE_STORE);
            String json = new String(data, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            cachedMimoVoice = obj.has("voice") ? obj.get("voice").getAsString() : null;
            cachedMimoVoiceClone = obj.has("clone") ? obj.get("clone").getAsBoolean() : null;
        } catch (Throwable ignored) {
            cachedMimoVoice = null;
        }
        return cachedMimoVoice;
    }

    public static void saveMimoVoice(String voice) {
        saveMimoVoice(voice, isMimoCloneVoice(voice));
    }

    public static void saveMimoVoice(String voice, boolean clone) {
        cachedMimoVoice = voice;
        cachedMimoVoiceClone = clone;
        // 同时写入 Forge 配置（尽力而为，编码问题可能失败）
        if (voice != null && !voice.isBlank()) {
            ModConfig.BUILTIN_MIMO_TTS_VOICE.set(voice);
        }
        try {
            Files.createDirectories(MIMO_VOICE_STORE.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("voice", voice != null ? voice : "");
            obj.addProperty("clone", clone);
            byte[] data = GSON.toJson(obj).getBytes(StandardCharsets.UTF_8);
            Files.write(MIMO_VOICE_STORE, data);
        } catch (Throwable t) {
            OllamaProxyMod.LOGGER.warn("MIMO_VOICE_JSON 写入失败: {}", t.getMessage());
        }
    }

    public static boolean isSavedMimoVoiceClone(String voice) {
        loadMimoVoice();
        return voice != null && voice.equals(cachedMimoVoice) && Boolean.TRUE.equals(cachedMimoVoiceClone);
    }

    public static String getTtsApiVoice() {
        TtsRoute active = CURRENT_TTS_ROUTE.get();
        if (active != null) {
            if (TtsApiProviderEnum.MIMO_TTS.name().equals(active.apiProvider())) {
                String savedVoice = loadMimoVoice();
                if (savedVoice != null && !savedVoice.isBlank()) return savedVoice;
            }
            return firstNonBlank(active.voice(), "");
        }
        BuiltinTtsConfig builtin = resolveTtsConfig();
        CloudConfig.Data cfg = cloud();
        if (isEnableBuiltinTtsApis()) {
            // 优先从 JSON 缓存读取（绕过 Forge TOML 编码问题）
            String jsonVoice = loadMimoVoice();
            if (jsonVoice != null && !jsonVoice.isBlank()) return jsonVoice;
            // 回退：本地配置或枚举默认值
            String voice = ModConfig.BUILTIN_MIMO_TTS_VOICE.get();
            if (voice == null || voice.isBlank()) {
                voice = getBuiltinMimoTtsVoice().voiceName;
            }
            return firstNonBlank(builtin != null ? builtin.apiVoice : null, voice);
        }
        if (cfg != null && cfg.tts != null) {
            return firstNonBlank(cfg.tts.apiVoice, ModConfig.TTS_API_VOICE.get());
        }
        TtsApiProviderEnum provider = getTtsApiProvider();
        String custom = ModConfig.TTS_API_VOICE.get();
        if (custom != null && !custom.isBlank()) return custom;
        return provider.defaultVoice;
    }

    private static String cleanApiKey(String key) {
        if (key == null) return null;
        String cleaned = key.trim().replaceAll("[^\\x20-\\x7E]", "");
        if (!cleaned.equals(key)) {
            OllamaProxyMod.LOGGER.warn("API Key 包含无效字符，已清洗。原长度: {}, 清洗后长度: {}", key.length(), cleaned.length());
        }
        // 清洗后为空串视为无有效 Key
        return cleaned.isBlank() ? null : cleaned;
    }
}
