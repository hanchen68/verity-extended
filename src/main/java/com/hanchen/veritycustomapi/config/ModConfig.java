package com.hanchen.veritycustomapi.config;

import net.minecraftforge.common.ForgeConfigSpec;

public class ModConfig {
    public static final ForgeConfigSpec COMMON_SPEC;
    public static final ForgeConfigSpec CLIENT_SPEC;

    // ========== 代理服务配置 ==========
    public static final ForgeConfigSpec.IntValue SERVER_PORT;

    // ========== LLM 核心配置 ==========
    public static final ForgeConfigSpec.ConfigValue<String> LLM_API_PROVIDER;
    public static final ForgeConfigSpec.ConfigValue<String> TARGET_API_URL;
    public static final ForgeConfigSpec.ConfigValue<String> TARGET_API_KEY;
    public static final ForgeConfigSpec.ConfigValue<String> TARGET_MODEL;
    public static final ForgeConfigSpec.BooleanValue ENABLE_CUSTOM_PROMPT;
    public static final ForgeConfigSpec.BooleanValue USE_ORIGINAL_VERITY_CHAT_CHAIN;
    public static final ForgeConfigSpec.BooleanValue BILINGUAL_SPEECH_SUBTITLES;
    public static final ForgeConfigSpec.BooleanValue BILINGUAL_SECOND_PASS;
    public static final ForgeConfigSpec.BooleanValue OVERHEAD_ONLY_CHAT;
    public static final ForgeConfigSpec.BooleanValue ENABLE_VERITY_GROWTH;
    public static final ForgeConfigSpec.BooleanValue CHAT_STREAM;
    public static final ForgeConfigSpec.ConfigValue<String> SELECTED_PRESET;
    public static final ForgeConfigSpec.ConfigValue<String> CUSTOM_PROMPT_FILE;
    public static final ForgeConfigSpec.IntValue REQUEST_TIMEOUT;

    // ========== 内置 API 开关 ==========
    public static final ForgeConfigSpec.BooleanValue ENABLE_BUILTIN_LLM_APIS;
    public static final ForgeConfigSpec.BooleanValue ENABLE_BUILTIN_STT_APIS;
    public static final ForgeConfigSpec.BooleanValue ENABLE_BUILTIN_TTS_APIS;
    public static final ForgeConfigSpec.ConfigValue<String> BUILTIN_MIMO_TTS_VOICE;

    // ========== 云端配置 ==========
    public static final ForgeConfigSpec.BooleanValue CLOUD_ENABLE;
    public static final ForgeConfigSpec.ConfigValue<String> CLOUD_API_URL;
    public static final ForgeConfigSpec.ConfigValue<String> CLOUD_API_KEY;
    public static final ForgeConfigSpec.BooleanValue CLOUD_PULL_ON_STARTUP;
    public static final ForgeConfigSpec.IntValue CLOUD_PULL_INTERVAL;
    public static final ForgeConfigSpec.IntValue CLOUD_TIMEOUT;
    public static final ForgeConfigSpec.BooleanValue CLOUD_FALLBACK_TO_LOCAL;

    public static final ForgeConfigSpec.BooleanValue ENABLE_PROACTIVE_AWARENESS;
    public static final ForgeConfigSpec.BooleanValue PROACTIVE_PAUSE_EVENT;
    public static final ForgeConfigSpec.BooleanValue PROACTIVE_DEATH_EVENT;
    public static final ForgeConfigSpec.BooleanValue PROACTIVE_LOW_HEALTH_EVENT;
    public static final ForgeConfigSpec.BooleanValue PROACTIVE_SLEEP_EVENT;
    public static final ForgeConfigSpec.BooleanValue PROACTIVE_WORLD_EVENT;
    public static final ForgeConfigSpec.IntValue PROACTIVE_COOLDOWN_SECONDS;
    public static final ForgeConfigSpec.BooleanValue PROACTIVE_DESKTOP_NOTIFICATIONS;
    public static final ForgeConfigSpec.BooleanValue PROACTIVE_DAY_NIGHT_EVENT;
    public static final ForgeConfigSpec.BooleanValue PROACTIVE_WEATHER_EVENT;
    public static final ForgeConfigSpec.BooleanValue PROACTIVE_DIMENSION_EVENT;
    public static final ForgeConfigSpec.BooleanValue PROACTIVE_HUNGER_EVENT;
    public static final ForgeConfigSpec.BooleanValue PROACTIVE_LEVEL_EVENT;
    public static final ForgeConfigSpec.BooleanValue PROACTIVE_HEALTH_TIERS;
    public static final ForgeConfigSpec.BooleanValue PROACTIVE_IDLE_CHAT;
    public static final ForgeConfigSpec.IntValue PROACTIVE_IDLE_INTERVAL_MINUTES;
    public static final ForgeConfigSpec.IntValue PROACTIVE_COMBAT_SILENCE_SECONDS;
    public static final ForgeConfigSpec.BooleanValue PROACTIVE_DEFER_EVENTS;

    // ========== 游戏内 HUD ==========
    public static final ForgeConfigSpec.BooleanValue HUD_ENABLED;
    public static final ForgeConfigSpec.BooleanValue HUD_PORTRAIT;
    public static final ForgeConfigSpec.BooleanValue HUD_STATUS;
    public static final ForgeConfigSpec.BooleanValue HUD_NAV_ARROW;
    public static final ForgeConfigSpec.IntValue HUD_DIALOGUE_SECONDS;
    public static final ForgeConfigSpec.BooleanValue REMINDER_ENABLED;
    public static final ForgeConfigSpec.BooleanValue IMMERSIVE_CHAT;

    // ========== 摄像头视觉（全局能力） ==========
    public static final ForgeConfigSpec.BooleanValue CAMERA_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<String> CAMERA_SOURCE;
    public static final ForgeConfigSpec.ConfigValue<String> CAMERA_BACKEND;
    public static final ForgeConfigSpec.ConfigValue<String> CAMERA_FFMPEG_PATH;
    public static final ForgeConfigSpec.ConfigValue<String> CAMERA_DEVICE_NAME;
    public static final ForgeConfigSpec.ConfigValue<String> CAMERA_HTTP_URL;
    public static final ForgeConfigSpec.IntValue CAMERA_WIDTH;
    public static final ForgeConfigSpec.IntValue CAMERA_HEIGHT;
    public static final ForgeConfigSpec.IntValue CAMERA_JPEG_QUALITY;
    public static final ForgeConfigSpec.IntValue CAMERA_CAPTURE_TIMEOUT_MS;
    public static final ForgeConfigSpec.BooleanValue CAMERA_SEND_ON_PROACTIVE_EVENT;
    public static final ForgeConfigSpec.BooleanValue CAMERA_SKILL_ENABLED;
    public static final ForgeConfigSpec.BooleanValue CAMERA_ALLOW_MODEL_TO_SEE;
    public static final ForgeConfigSpec.ConfigValue<String> CAMERA_VISION_MODEL;

    // ========== 语音识别核心配置 ==========
    public static final ForgeConfigSpec.BooleanValue ENABLE_VOICE_INPUT;
    public static final ForgeConfigSpec.ConfigValue<String> RECOGNITION_MODE;
    public static final ForgeConfigSpec.ConfigValue<String> STT_API_PROVIDER;
    public static final ForgeConfigSpec.ConfigValue<String> SPEECH_API_URL;
    public static final ForgeConfigSpec.ConfigValue<String> SPEECH_API_KEY;
    public static final ForgeConfigSpec.ConfigValue<String> SPEECH_API_MODEL;
    public static final ForgeConfigSpec.ConfigValue<String> SPEECH_REQUEST_FORMAT;
    public static final ForgeConfigSpec.ConfigValue<String> SPEECH_AUTH_TYPE;
    public static final ForgeConfigSpec.ConfigValue<String> SPEECH_RESPONSE_FIELD;
    public static ForgeConfigSpec.ConfigValue<String> VOSK_MODEL_PATH;
    public static ForgeConfigSpec.BooleanValue INSTALL_VOSK_MODEL;
    public static final ForgeConfigSpec.IntValue SAMPLE_RATE;

    // ========== TTS 语音合成配置 ==========
    public static final ForgeConfigSpec.BooleanValue ENABLE_TTS;
    public static final ForgeConfigSpec.ConfigValue<String> TTS_MODE;
    public static final ForgeConfigSpec.IntValue TTS_SAMPLE_RATE;
    public static final ForgeConfigSpec.ConfigValue<String> TTS_MODEL_PATH;
    public static final ForgeConfigSpec.DoubleValue TTS_SPEED;
    public static final ForgeConfigSpec.DoubleValue TTS_VOLUME;
    public static final ForgeConfigSpec.ConfigValue<String> TTS_API_PROVIDER;
    public static final ForgeConfigSpec.ConfigValue<String> TTS_API_URL;
    public static final ForgeConfigSpec.ConfigValue<String> TTS_API_KEY;
    public static final ForgeConfigSpec.ConfigValue<String> TTS_API_MODEL;
    public static final ForgeConfigSpec.ConfigValue<String> TTS_API_VOICE;
    
    // ========== 扩展选项 ==========
    public static final ForgeConfigSpec.BooleanValue FRIENDLY_MODE;
    public static final ForgeConfigSpec.ConfigValue<String> CUSTOM_PERSONA;
    public static final ForgeConfigSpec.BooleanValue TTS_STREAM;
    public static final ForgeConfigSpec.BooleanValue DISABLE_ORIGINAL_TTS;
    public static final ForgeConfigSpec.ConfigValue<String> TTS_ROUTE_KIND;

    // ========== 朗读语言 / 台词翻译 ==========
    public static final ForgeConfigSpec.ConfigValue<String> VOICE_DISPLAY_LANGUAGE;
    public static final ForgeConfigSpec.ConfigValue<String> VOICE_SPEAK_LANGUAGE;
    public static final ForgeConfigSpec.ConfigValue<String> VOICE_TRANSLATE_MODEL;
    public static final ForgeConfigSpec.ConfigValue<String> VOICE_TRANSLATE_URL;
    public static final ForgeConfigSpec.ConfigValue<String> VOICE_TRANSLATE_API_KEY;

    public static final ForgeConfigSpec.BooleanValue MEMORY_ENABLED;
    public static final ForgeConfigSpec.BooleanValue MEMORY_RETRIEVAL_ENABLED;
    public static final ForgeConfigSpec.IntValue MEMORY_RETRIEVAL_TOP_N;
    public static final ForgeConfigSpec.IntValue MEMORY_DECAY_DAYS;
    public static final ForgeConfigSpec.BooleanValue MEMORY_AUTO_WRITE_ENABLED;
    public static final ForgeConfigSpec.IntValue MEMORY_AUTO_WRITE_MIN_INTERVAL_SECONDS;
    public static final ForgeConfigSpec.BooleanValue MEMORY_PER_PLAYER;
    public static final ForgeConfigSpec.BooleanValue CONVERSATION_TRIM_ENABLED;
    public static final ForgeConfigSpec.BooleanValue INVENTORY_CONTEXT_ENABLED;
    public static final ForgeConfigSpec.IntValue INVENTORY_MAX_TYPES;
    public static final ForgeConfigSpec.BooleanValue CHAT_LOG_ENABLED;
    public static final ForgeConfigSpec.IntValue CHAT_LOG_MAX_ENTRIES;
    public static final ForgeConfigSpec.BooleanValue WEB_SEARCH_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<String> WEB_SEARCH_PROVIDER;
    public static final ForgeConfigSpec.ConfigValue<String> WEB_SEARCH_API_KEY;
    public static final ForgeConfigSpec.BooleanValue WEB_SEARCH_ALWAYS;
    public static final ForgeConfigSpec.ConfigValue<String> WEB_SEARCH_CUSTOM_URL;
    public static final ForgeConfigSpec.ConfigValue<String> WEB_SEARCH_CUSTOM_BODY;
    public static final ForgeConfigSpec.ConfigValue<String> WEB_SEARCH_CUSTOM_HEADERS;
    public static final ForgeConfigSpec.ConfigValue<String> WEB_SEARCH_CUSTOM_RESULTS;
    public static final ForgeConfigSpec.ConfigValue<String> WEB_SEARCH_CUSTOM_FIELDS;
    public static final ForgeConfigSpec.BooleanValue ALLOW_VERITY_DEMON_TRANSFORMATION;
    public static final ForgeConfigSpec.BooleanValue ALLOW_VERITY_GIFT;
    public static final ForgeConfigSpec.BooleanValue ALLOW_VERITY_KICK;
    public static final ForgeConfigSpec.BooleanValue CROSS_SAVE_PURSUIT;
    public static final ForgeConfigSpec.IntValue DAILY_KARMA_GAIN_CAP;

    public static final ForgeConfigSpec.BooleanValue ENABLE_AI_BUILDING;
    public static final ForgeConfigSpec.BooleanValue AI_BUILDING_VERITY_BUILDER_MODE;
    public static final ForgeConfigSpec.BooleanValue AI_BUILDING_OVERWRITE_BLOCKS;
    public static final ForgeConfigSpec.IntValue AI_BUILDING_BLOCKS_PER_TICK;
    public static final ForgeConfigSpec.IntValue AI_BUILDING_MAX_SIZE;
    public static final ForgeConfigSpec.IntValue AI_BUILDING_MAX_BLOCKS;
    public static final ForgeConfigSpec.BooleanValue AI_BUILDING_GHOST_PREVIEW;
    public static final ForgeConfigSpec.IntValue AI_BUILDING_GHOST_PREVIEW_TICKS;
    public static final ForgeConfigSpec.BooleanValue AI_BUILDING_BOSS_BAR_PROGRESS;
    public static final ForgeConfigSpec.BooleanValue AI_BUILDING_PARTICLES;
    public static final ForgeConfigSpec.BooleanValue AI_BUILDING_SOUND;
    public static final ForgeConfigSpec.ConfigValue<String> AI_BUILDING_BLUEPRINT_DIR;
    public static final ForgeConfigSpec.BooleanValue AI_BUILDING_PERSIST;
    public static final ForgeConfigSpec.BooleanValue AI_BUILDING_ALLOW_MULTIPLAYER;

    // ========== 服务端 Verity 多人适配 ==========
    public static final ForgeConfigSpec.BooleanValue SERVER_VERITY_ADAPTER_ENABLED;
    public static final ForgeConfigSpec.BooleanValue SERVER_VERITY_PRIVATE_CHAT;
    public static final ForgeConfigSpec.BooleanValue SERVER_VERITY_REQUIRE_COMMAND;
    public static final ForgeConfigSpec.BooleanValue SERVER_VERITY_ALLOW_PUBLIC_CHAT;
    public static final ForgeConfigSpec.BooleanValue SERVER_VERITY_AUTO_CLAIM_UNOWNED;
    public static final ForgeConfigSpec.IntValue SERVER_VERITY_MAX_ENTITIES_PER_PLAYER;
    public static final ForgeConfigSpec.IntValue SERVER_VERITY_DEMON_MAX_HEALTH;
    public static final ForgeConfigSpec.BooleanValue SERVER_VERITY_ALLOW_GLOBAL_ENTITY_PURGE;
    public static final ForgeConfigSpec.BooleanValue SERVER_VERITY_BALL_RIDE;
    public static final ForgeConfigSpec.BooleanValue SERVER_VERITY_RIDE_OWNER_ONLY;
    public static final ForgeConfigSpec.BooleanValue SERVER_VERITY_HEAD_PERCH;
    public static final ForgeConfigSpec.IntValue SERVER_VERITY_AI_EVENT_COOLDOWN_TICKS;
    public static final ForgeConfigSpec.BooleanValue SERVER_VERITY_ITEM_TOOLS;
    public static final ForgeConfigSpec.IntValue SERVER_VERITY_ITEM_QUERY_LIMIT;

    // ========== 星之遥控器（The Shooting Star 适配） ==========
    public static final ForgeConfigSpec.BooleanValue STELLAR_REMOTE_ENABLED;
    public static final ForgeConfigSpec.BooleanValue STELLAR_REMOTE_REQUIRE_ITEM;
    public static final ForgeConfigSpec.ConfigValue<String> STELLAR_REMOTE_ALLOWED_SKILLS;
    public static final ForgeConfigSpec.BooleanValue STELLAR_REMOTE_SHOW_IN_HAND;

    // ========== 服务端恐怖规则 ==========
    public static final ForgeConfigSpec.BooleanValue HORROR_SERVER_ENABLED;
    public static final ForgeConfigSpec.BooleanValue HORROR_SERVER_AMBIENT_EVENTS;
    public static final ForgeConfigSpec.DoubleValue HORROR_SERVER_EVENT_FREQUENCY;
    public static final ForgeConfigSpec.IntValue HORROR_SERVER_COOLDOWN_SECONDS;

    // ========== 恐怖增强 ==========
    public static final ForgeConfigSpec.BooleanValue HORROR_ENABLED;
    public static final ForgeConfigSpec.DoubleValue HORROR_INTENSITY;
    public static final ForgeConfigSpec.BooleanValue HORROR_VISUALS;
    public static final ForgeConfigSpec.BooleanValue HORROR_AUDIO;
    public static final ForgeConfigSpec.BooleanValue HORROR_CAMERA_SHAKE;
    public static final ForgeConfigSpec.BooleanValue HORROR_JUMPSCARES;
    public static final ForgeConfigSpec.BooleanValue HORROR_HALLUCINATIONS;
    public static final ForgeConfigSpec.BooleanValue HORROR_AMBIENT_ANOMALIES;
    public static final ForgeConfigSpec.BooleanValue HORROR_ANOMALY_TEXT;
    public static final ForgeConfigSpec.DoubleValue HORROR_ANOMALY_FREQUENCY;
    public static final ForgeConfigSpec.BooleanValue LOCAL_VERITY_PUBLIC_DEFAULT;

    // ========== 恶魔 MMD ==========
    public static final ForgeConfigSpec.DoubleValue MMD_CAMERA_HEIGHT;
    public static final ForgeConfigSpec.BooleanValue AI_DEMON_AUTO_SPAWN;
    public static final ForgeConfigSpec.BooleanValue AI_DEMON_FRIENDLY_MODE;
    public static final ForgeConfigSpec.IntValue AI_DEMON_SPAWN_RETRY_MS;

    // ========== 内置 PMX 模型（新增功能，默认关闭） ==========
    public static final ForgeConfigSpec.BooleanValue ENABLE_BUILTIN_PMX;
    public static final ForgeConfigSpec.ConfigValue<String> BUILTIN_PMX_MODEL;

    // ========== 皮肤管理 ==========
    public static final ForgeConfigSpec.ConfigValue<String> VERITY_SKIN_NAME;

    // ========== 原版界面汉化 ==========
    public static final ForgeConfigSpec.BooleanValue ENABLE_VERITY_LOCALIZATION;

    // ========== Verity 说话时的 Q 弹形变 ==========
    public static final ForgeConfigSpec.BooleanValue VERITY_JELLY_ENABLED;
    public static final ForgeConfigSpec.BooleanValue VERITY_JELLY_CHAOS;
    public static final ForgeConfigSpec.DoubleValue VERITY_JELLY_AMPLITUDE;
    public static final ForgeConfigSpec.DoubleValue VERITY_JELLY_SPEED;
    public static final ForgeConfigSpec.DoubleValue VERITY_JELLY_SQUASH;

    // ========== 调试与诊断 ==========
    public static final ForgeConfigSpec.BooleanValue DEV_MODE;
    public static final ForgeConfigSpec.BooleanValue DEBUG_RUNTIME_METRICS;
    public static final ForgeConfigSpec.IntValue DEBUG_RUNTIME_METRICS_INTERVAL_SECONDS;

    // ========== 插件系统 ==========
    public static final ForgeConfigSpec.BooleanValue PLUGIN_ENABLED;
    public static final ForgeConfigSpec.IntValue PLUGIN_MAX_LOADED;
    public static final ForgeConfigSpec.BooleanValue PLUGIN_ALLOW_BLOCK_EDIT;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        ForgeConfigSpec.Builder clientBuilder = new ForgeConfigSpec.Builder();

        // 服务配置
        builder.push("server");
        SERVER_PORT = builder
                .comment("代理服务器监听端口，默认与Verity原生配置保持一致")
                .defineInRange("port", 4000, 1, 65535);
        builder.pop();

        // LLM 配置 - 默认服务商改为自定义
        builder.push("llm");
        LLM_API_PROVIDER = builder
                .comment("大模型服务商")
                .define("apiProvider", "CUSTOM_OPENAI");
        TARGET_API_URL = builder
                .comment("目标大模型完整API接口地址")
                .define("apiUrl", "");
        TARGET_API_KEY = builder
                .comment("目标API访问密钥")
                .define("apiKey", "");
        TARGET_MODEL = builder
                .comment("使用的大模型名称")
                .define("model", "");
        ENABLE_CUSTOM_PROMPT = builder
                .comment("是否启用自定义提示词（关闭则使用Verity原版提示词）")
                .define("enableCustomPrompt", false);
        USE_ORIGINAL_VERITY_CHAT_CHAIN = builder
                .comment("是否由本 Mod 入口直接代理调用原版 Verity 对话处理链路")
                .define("useOriginalVerityChatChain", true);
        BILINGUAL_SPEECH_SUBTITLES = builder
                .comment("让 Verity 使用英文回答和朗读，并在实体上方显示对应的中文字幕")
                .define("bilingualSpeechSubtitles", false);
        BILINGUAL_SECOND_PASS = builder
                .comment("双语模式下，模型没有在同一次回复里给出中文时，是否额外再调用一次模型翻译。开启=中文更稳但有额外延迟；关闭=回复最快，模型没给中文时就只显示英文")
                .define("bilingualSecondPass", true);
        OVERHEAD_ONLY_CHAT = builder
                .comment("让 Verity 的回复只在实体上方显示，不再显示到聊天栏")
                .define("overheadOnlyChat", false);
        ENABLE_VERITY_GROWTH = builder
                .comment("开启后，Verity 会根据持续互动逐步展现更明显的体型变化")
                .define("enableVerityGrowth", false);
        CHAT_STREAM = builder
                .comment("聊天流式输出（默认关闭）。开启后，Chat 代理会将上游 OpenAI 兼容接口的 SSE 流透明转发给 Verity 客户端，让回复逐字生成；关闭时维持原有的一次性 JSON 响应，便于兼容所有服务商")
                .define("chatStream", false);
        SELECTED_PRESET = builder
                .comment("选中的人设预设名称")
                .define("selectedPreset", "ORIGINAL_VERITY");
        CUSTOM_PROMPT_FILE = builder
                .comment("自定义提示词文件路径")
                .define("customPromptFile", "./config/verity_custom_prompt.txt");
        REQUEST_TIMEOUT = builder
                .comment("请求超时时间（秒）")
                .defineInRange("requestTimeout", 30, 5, 300);
        builder.pop();

        builder.push("cloud");
        CLOUD_ENABLE = builder
                .comment("是否启用云端配置拉取。关闭时不发起任何网络请求")
                .define("enable", false);
        CLOUD_API_URL = builder
                .comment("云端配置拉取接口地址")
                .define("apiUrl", "https://verity.xrstudio.cn/api/v1/getconfig");
        CLOUD_API_KEY = builder
                .comment("云端配置密钥。敏感字段不会写入日志")
                .define("apiKey", "");
        CLOUD_PULL_ON_STARTUP = builder
                .comment("服务器启动时是否立即拉取云端配置")
                .define("pullOnStartup", true);
        CLOUD_PULL_INTERVAL = builder
                .comment("云端配置定期拉取间隔，单位秒。0 表示不定期拉取")
                .defineInRange("pullInterval", 0, 0, 86400);
        CLOUD_TIMEOUT = builder
                .comment("云端配置 HTTP 请求超时时间，单位秒")
                .defineInRange("timeout", 10, 1, 120);
        CLOUD_FALLBACK_TO_LOCAL = builder
                .comment("云端拉取失败时是否回退本地 TOML 配置")
                .define("fallbackToLocal", true);
        builder.pop();

        clientBuilder.push("builtin_api");
        ENABLE_BUILTIN_LLM_APIS = clientBuilder
                .comment("是否启用内置 Chat 服务商列表（默认关闭，关闭后保留当前服务商配置）")
                .define("enableChatApis", false);
        ENABLE_BUILTIN_STT_APIS = clientBuilder
                .comment("是否启用内置 STT 服务商列表（默认关闭，关闭后保留当前服务商配置）")
                .define("enableSttApis", false);
        ENABLE_BUILTIN_TTS_APIS = clientBuilder
                .comment("是否启用内置 TTS 服务商列表（默认关闭，关闭后保留当前服务商配置）")
                .define("enableTtsApis", false);
        BUILTIN_MIMO_TTS_VOICE = clientBuilder
                .comment("内置 MiMo TTS 专用音色，不影响普通在线 TTS 的 apiVoice")
                .define("mimoTtsVoice", "苏打");
        clientBuilder.pop();

        clientBuilder.push("proactive_awareness");
        ENABLE_PROACTIVE_AWARENESS = clientBuilder
                .comment("是否允许 Verity 根据本地游戏事件主动发言")
                .define("enabled", false);
        PROACTIVE_PAUSE_EVENT = clientBuilder
                .comment("打开暂停菜单时是否允许主动发言")
                .define("pauseEvent", true);
        PROACTIVE_DEATH_EVENT = clientBuilder
                .comment("玩家死亡时是否允许主动发言")
                .define("deathEvent", true);
        PROACTIVE_LOW_HEALTH_EVENT = clientBuilder
                .comment("玩家生命值不高于百分之二十五时是否允许主动发言")
                .define("lowHealthEvent", true);
        PROACTIVE_SLEEP_EVENT = clientBuilder
                .comment("玩家睡觉或醒来时是否允许主动发言")
                .define("sleepEvent", true);
        PROACTIVE_WORLD_EVENT = clientBuilder
                .comment("进入或退出世界时是否允许主动发言")
                .define("worldEvent", true);
        PROACTIVE_COOLDOWN_SECONDS = clientBuilder
                .comment("所有主动发言共用的全局冷却时间，默认五分钟")
                .defineInRange("cooldownSeconds", 300, 30, 3600);
        PROACTIVE_DESKTOP_NOTIFICATIONS = clientBuilder
                .comment("游戏窗口不活跃时是否显示系统托盘通知，默认关闭")
                .define("desktopNotifications", false);
        PROACTIVE_DAY_NIGHT_EVENT = clientBuilder
                .comment("昼夜交替时是否允许主动发言（天亮 / 天黑各一次）")
                .define("dayNightEvent", false);
        PROACTIVE_WEATHER_EVENT = clientBuilder
                .comment("天气变化时是否允许主动发言（下雨 / 雷暴 / 放晴）")
                .define("weatherEvent", false);
        PROACTIVE_DIMENSION_EVENT = clientBuilder
                .comment("切换维度时是否允许主动发言（下界 / 末地 / 主世界）")
                .define("dimensionEvent", false);
        PROACTIVE_HUNGER_EVENT = clientBuilder
                .comment("饥饿值偏低时是否允许主动发言")
                .define("hungerEvent", false);
        PROACTIVE_LEVEL_EVENT = clientBuilder
                .comment("经验等级提升时是否允许主动发言")
                .define("levelEvent", false);
        PROACTIVE_HEALTH_TIERS = clientBuilder
                .comment("把低血量提醒拆成 50% / 25% / 10% 三档，每档只提醒一次")
                .define("healthTiers", true);
        PROACTIVE_IDLE_CHAT = clientBuilder
                .comment("长时间没有事件时是否定时闲聊一句")
                .define("idleChat", false);
        PROACTIVE_IDLE_INTERVAL_MINUTES = clientBuilder
                .comment("定时闲聊的间隔分钟数")
                .defineInRange("idleIntervalMinutes", 20, 5, 180);
        PROACTIVE_COMBAT_SILENCE_SECONDS = clientBuilder
                .comment("战斗期间静默秒数：玩家挨打或出手后这段时间内不打扰，0 表示不静默")
                .defineInRange("combatSilenceSeconds", 15, 0, 120);
        PROACTIVE_DEFER_EVENTS = clientBuilder
                .comment("上个请求未结束时把更重要的事件排队，等结束后补发，而不是直接丢弃")
                .define("deferEvents", true);
        clientBuilder.pop();

        clientBuilder.push("hud");
        HUD_ENABLED = clientBuilder
                .comment("在屏幕上显示 Verity 的对话气泡")
                .define("dialogueHud", true);
        HUD_PORTRAIT = clientBuilder
                .comment("对话气泡左侧显示 Verity 当前表情头像")
                .define("dialoguePortrait", true);
        HUD_STATUS = clientBuilder
                .comment("在屏幕左上角显示好感度 / 记忆条数 / 今日对话 / 额度")
                .define("statusBadge", false);
        HUD_NAV_ARROW = clientBuilder
                .comment("指向导航目标时在准星下方显示方向箭头")
                .define("navigationArrow", true);
        HUD_DIALOGUE_SECONDS = clientBuilder
                .comment("对话气泡的停留秒数")
                .defineInRange("dialogueSeconds", 12, 3, 60);
        REMINDER_ENABLED = clientBuilder
                .comment("允许通过「N 分钟后提醒我」创建定时提醒")
                .define("reminderEnabled", true);
        IMMERSIVE_CHAT = clientBuilder
                .comment("沉浸式对话模式：原版聊天栏不再显示任何内容（含其它玩家与指令反馈），"
                        + "平时只看右下角的 Verity 气泡；按 T 打开聊天框时全部聊天记录照常显示")
                .define("immersiveChat", false);
        clientBuilder.pop();

        clientBuilder.push("camera_vision");
        CAMERA_ENABLED = clientBuilder
                .comment("是否启用视觉能力，让 AI 能看到画面（全局可用：快捷键、对话、主动事件）")
                .define("enabled", false);
        CAMERA_SOURCE = clientBuilder
                .comment("画面来源：camera 只看真实摄像头（默认）/ both 摄像头+游戏画面 / screen 只看游戏内屏幕截图")
                .define("source", "camera");
        CAMERA_BACKEND = clientBuilder
                .comment("摄像头后端：auto 自动挑选 / ffmpeg / powershell(Windows内置) / http(网络快照地址)")
                .define("backend", "auto");
        CAMERA_FFMPEG_PATH = clientBuilder
                .comment("ffmpeg 可执行文件路径；留空表示用 PATH 里的 ffmpeg")
                .define("ffmpegPath", "");
        CAMERA_DEVICE_NAME = clientBuilder
                .comment("DirectShow 摄像头设备名（ffmpeg 后端使用），留空则自动枚举第一个可用设备")
                .define("deviceName", "");
        CAMERA_HTTP_URL = clientBuilder
                .comment("HTTP 快照地址（http 后端使用），例如手机摄像头 App 提供的 http://192.168.1.5:8080/shot.jpg")
                .define("httpSnapshotUrl", "");
        CAMERA_WIDTH = clientBuilder
                .comment("采集宽度（像素），越大越清晰也越慢，建议 640")
                .defineInRange("width", 640, 160, 1920);
        CAMERA_HEIGHT = clientBuilder
                .comment("采集高度（像素），建议 480")
                .defineInRange("height", 480, 120, 1080);
        CAMERA_JPEG_QUALITY = clientBuilder
                .comment("JPEG 压缩质量 1-100，越大越清晰，传输体积也越大")
                .defineInRange("jpegQuality", 70, 10, 100);
        CAMERA_CAPTURE_TIMEOUT_MS = clientBuilder
                .comment("单次采集超时时间（毫秒）")
                .defineInRange("captureTimeoutMs", 6000, 1000, 30000);
        CAMERA_SEND_ON_PROACTIVE_EVENT = clientBuilder
                .comment("主动事件触发时是否附带一张摄像头画面（需要视觉模型支持）")
                .define("sendOnProactiveEvent", false);
        CAMERA_SKILL_ENABLED = clientBuilder
                .comment("允许 AI 通过技能主动采集画面观察：只在 AI 自己判断需要时才采集，不会每条对话都拍")
                .define("skillEnabled", true);
        CAMERA_ALLOW_MODEL_TO_SEE = clientBuilder
                .comment("允许模型描述摄像头画面；关闭后仍会发送图片但仅作上下文，不鼓励描述")
                .define("allowModelToSee", true);
        CAMERA_VISION_MODEL = clientBuilder
                .comment("视觉请求专用模型名；留空则沿用当前 Chat 线路的模型")
                .define("visionModel", "");
        clientBuilder.pop();

        // 语音识别配置
        builder.push("speech_recognition");
        ENABLE_VOICE_INPUT = builder
                .comment("是否启用语音输入功能")
                .define("enableVoiceInput", true);
        RECOGNITION_MODE = builder
                .comment("语音识别模式：local 本地Vosk离线 / api 在线接口")
                .define("mode", "local");
        STT_API_PROVIDER = builder
                .comment("在线语音识别服务商")
                .define("apiProvider", "CUSTOM_OPENAI");
        SPEECH_API_URL = builder
                .comment("在线语音识别API地址")
                .define("apiUrl", "");
        SPEECH_API_KEY = builder
                .comment("在线语音识别API密钥")
                .define("apiKey", "");
        SPEECH_API_MODEL = builder
                .comment("在线语音识别模型名称")
                .define("model", "");
        SPEECH_REQUEST_FORMAT = builder
                .comment("请求格式：multipart 多表单 / json JSON格式 / url_encoded URL编码")
                .define("requestFormat", "multipart");
        SPEECH_AUTH_TYPE = builder
                .comment("认证方式：bearer Bearer令牌 / api_key_header X-API-Key头 / api_key_param URL参数 / custom_header 自定义头(格式:HeaderName:Value)")
                .define("authType", "bearer");
        SPEECH_RESPONSE_FIELD = builder
                .comment("响应中提取文本的字段名")
                .define("responseField", "text");
        VOSK_MODEL_PATH = builder
                .comment("本地Vosk模型文件路径")
                .define("voskModelPath", "./config/vosk-model-small-cn");
        INSTALL_VOSK_MODEL = builder
                .comment("保存配置时一键下载并安装离线中文语音识别模型")
                .define("installVoskModel", false);
        SAMPLE_RATE = builder
                .comment("语音识别音频采样率")
                .defineInRange("sampleRate", 16000, 8000, 48000);
        builder.pop();

        // TTS 语音合成配置 - 默认服务商改为自定义
        builder.push("text_to_speech");
        ENABLE_TTS = builder
                .comment("是否启用语音合成功能")
                .define("enableTts", true);
        TTS_MODE = builder
                .comment("TTS模式：local 本地离线 / online 在线接口")
                .define("mode", "online");
        TTS_SAMPLE_RATE = builder
                .comment("TTS音频采样率")
                .defineInRange("sampleRate", 22050, 8000, 48000);
        TTS_MODEL_PATH = builder
                .comment("本地TTS模型文件路径")
                .define("modelPath", "./config/tts-model");
        TTS_SPEED = builder
                .comment("语音语速，范围0.25-4.0")
                .defineInRange("speed", 1.0, 0.25, 4.0);
        TTS_VOLUME = builder
                .comment("语音音量，范围0.0-2.0")
                .defineInRange("volume", 1.0, 0.0, 2.0);
        TTS_API_PROVIDER = builder
                .comment("在线TTS服务商")
                .define("apiProvider", "CUSTOM_OPENAI");
        TTS_API_URL = builder
                .comment("在线TTS API地址")
                .define("apiUrl", "");
        TTS_API_KEY = builder
                .comment("在线TTS API密钥")
                .define("apiKey", "");
        TTS_API_MODEL = builder
                .comment("在线TTS模型名称")
                .define("apiModel", "");
        TTS_API_VOICE = builder
                .comment("在线TTS音色名称")
                .define("apiVoice", "alloy");
        builder.pop();
        
        builder.push("friendly_mode");
        FRIENDLY_MODE = builder
                .comment("友好模式：使用友好、积极的对话语气，并阻止之后的恶魔实体转换")
                .define("enabled", false);
        builder.pop();

        builder.push("custom_persona");
        CUSTOM_PERSONA = builder
                .comment("自定义人设提示词。留空使用默认基于verity.xml的动态人设，填写后覆盖")
                .define("prompt", "");
        builder.pop();

        builder.push("tts_stream");
        TTS_STREAM = builder
                .comment("TTS流式响应（仅对MiMo端点生效）")
                .define("enabled", true);
        builder.pop();

        builder.push("disable_original_tts");
        DISABLE_ORIGINAL_TTS = builder
                .comment("禁用原版 Verity TTS（系统朗读），避免和扩展TTS同时播放。关闭后原版和扩展TTS各自独立运行")
                .define("enabled", true);
        builder.pop();

        // 扩展 TTS 的输出通道不再做成配置项：固定走游戏原版音频通道（跟随主音量、统一混音、按实体定位），
        // 申请不到通道时自动回退到自带的 Java Sound 设备。理由：原版通道在各方面都严格更优，
        // 唯一「更差」的情况是拿不到通道，而那时本来就该自动回退，不需要玩家做决定。

        builder.push("tts_route");
        TTS_ROUTE_KIND = builder
                .comment("朗读路线三选一：auto 自动 / native 原版朗读 / local 本地离线模型 / endpoint 网络端点",
                        "auto：跟随上面 [text_to_speech] 的 mode（local/online），老配置升级后行为不变。",
                        "native：完全由原版 mod 自己朗读，扩展不接管（会顺带把原版 TTS 打开，否则没人出声）。",
                        "local：只用本地离线模型；本地不可用时交还原版，不会偷偷改用要花钱的联网端点。",
                        "endpoint：用网络端点；端点不可用时退回本地，再不行交还原版。",
                        "关闭「禁用原版系统朗读」时本项被忽略，一律按 native 处理。")
                .define("kind", "auto");
        builder.pop();

        builder.push("voice_language");
        VOICE_DISPLAY_LANGUAGE = builder
                .comment("台词的阅读语言：聊天栏和字幕按这种语言看。follow 表示不做任何转换，模型说什么就看什么。",
                        "取值 follow / zh / en / ja / ko。")
                .define("display", "follow");
        VOICE_SPEAK_LANGUAGE = builder
                .comment("配音语言： Verity 用哪种语言念出台词。follow 表示照原文念，不做转换。",
                        "选了别的语言时，配音前先把台词转换过去；转换不出来就照原文念，不会因此没声音。",
                        "取值 follow / zh / en / ja / ko。")
                .define("speak", "follow");
        VOICE_TRANSLATE_MODEL = builder
                .comment("配音转换专用的模型名；不填就借用当前对话服务正在用的那个。")
                .define("translateModel", "");
        VOICE_TRANSLATE_URL = builder
                .comment("配音转换专用的服务地址；不填就借用上面的对话服务。",
                        "想给配音单独配一个便宜、响应快的模型时才需要填。")
                .define("translateUrl", "");
        VOICE_TRANSLATE_API_KEY = builder
                .comment("上面专用服务对应的密钥，只在填了专用地址时才会用到。")
                .define("translateApiKey", "");
        builder.pop();

        builder.push("ai_extensions");
        MEMORY_ENABLED = builder
                .define("memoryEnabled", true);
        MEMORY_RETRIEVAL_ENABLED = builder
                .comment("把与当前对话最相关的记忆条目直接注入提示词，避免模型漏用记忆工具")
                .define("memoryRetrievalEnabled", true);
        MEMORY_RETRIEVAL_TOP_N = builder
                .comment("每次注入的记忆条数上限")
                .defineInRange("memoryRetrievalTopN", 6, 1, 20);
        MEMORY_DECAY_DAYS = builder
                .comment("记忆权重的时间衰减半衰期（天），越大越不容易被时间冲淡")
                .defineInRange("memoryDecayDays", 30, 1, 365);
        MEMORY_AUTO_WRITE_ENABLED = builder
                .comment("对话结束后自动提取值得长期记住的信息并写入记忆")
                .define("memoryAutoWriteEnabled", false);
        MEMORY_AUTO_WRITE_MIN_INTERVAL_SECONDS = builder
                .comment("自动提取的最小间隔秒数，避免每句话都额外请求一次模型")
                .defineInRange("memoryAutoWriteMinIntervalSeconds", 120, 30, 3600);
        MEMORY_PER_PLAYER = builder
                .comment("自动写入的记忆按玩家隔离，多人共用一个世界时互不可见")
                .define("memoryPerPlayer", true);
        CONVERSATION_TRIM_ENABLED = builder
                .comment("对较早的历史做机械裁剪（旧工具结果与超长旧回复截断、旧截图占位），最近两轮完整保留，降低长会话的 token")
                .define("conversationTrimEnabled", true);
        INVENTORY_CONTEXT_ENABLED = builder
                .comment("把玩家背包里的物品摘要写进对话上下文，让 Verity 知道你有什么")
                .define("inventoryContextEnabled", true);
        INVENTORY_MAX_TYPES = builder
                .comment("背包摘要最多列出多少种物品")
                .defineInRange("inventoryMaxTypes", 12, 3, 40);
        CHAT_LOG_ENABLED = builder
                .comment("把每轮对话记录到本地文件，可在游戏内查看与导出")
                .define("conversationLogEnabled", true);
        CHAT_LOG_MAX_ENTRIES = builder
                .comment("对话记录最多保留的条数，超出后丢弃最早的")
                .defineInRange("conversationLogMaxEntries", 500, 50, 5000);
        WEB_SEARCH_ENABLED = builder
                .define("webSearchEnabled", false);
        WEB_SEARCH_PROVIDER = builder
                .define("webSearchProvider", "TAVILY");
        WEB_SEARCH_API_KEY = builder
                .define("webSearchApiKey", "");
        WEB_SEARCH_ALWAYS = builder
                .comment("每轮对话都联网搜索。开启后不再靠关键词判断——"
                        + "问「今天几号」「这个项目最新版本是多少」这类问题一定会搜；代价是每句话都多一次请求")
                .define("webSearchAlways", false);
        // ---- 自定义搜索 API（provider = CUSTOM 时生效）----
        WEB_SEARCH_CUSTOM_URL = builder
                .comment("自定义搜索接口地址，POST 请求。需 HTTPS；http 仅允许本机回环地址")
                .define("webSearchCustomUrl", "");
        WEB_SEARCH_CUSTOM_BODY = builder
                .comment("请求体模板。占位符：{query} 搜索词、{count} 条数、{key} 访问密钥（会自动做 JSON 转义）")
                .define("webSearchCustomBody", "{\"query\":\"{query}\",\"count\":{count}}");
        WEB_SEARCH_CUSTOM_HEADERS = builder
                .comment("附加请求头，每行一个「名称: 值」，支持 {key} 占位符。示例：Authorization: Bearer {key}")
                .define("webSearchCustomHeaders", "Authorization: Bearer {key}");
        WEB_SEARCH_CUSTOM_RESULTS = builder
                .comment("结果数组在响应里的路径，用点号分隔。例如 results、data.results、web.pages；留空表示响应本身就是数组")
                .define("webSearchCustomResults", "results");
        WEB_SEARCH_CUSTOM_FIELDS = builder
                .comment("字段映射：「输出名=响应字段」，多个用逗号分隔，可用 | 指定回退顺序。"
                        + "输出名支持 title / content / url；例如 title=title,content=summary|snippet")
                .define("webSearchCustomFields", "title=title,content=summary|snippet,url=url");
        builder.pop();

        builder.push("gameplay_safety");
        ALLOW_VERITY_DEMON_TRANSFORMATION = builder
                .define("allowVerityDemonTransformation", true);
        ALLOW_VERITY_GIFT = builder
                .comment("允许玩家潜行 + 丢弃物品（Q）对准 Verity 送礼：每次消耗 1 个，30 秒冷却，好感度 +1")
                .define("allowVerityGift", true);
        ALLOW_VERITY_KICK = builder
                .comment("允许玩家潜行 + 左键踢 Verity：不造成伤害，只推开并让她回应，10 秒冷却")
                .define("allowVerityKick", true);
        CROSS_SAVE_PURSUIT = builder
                .comment("实验性：Verity 恶魔化后记住你，删除存档/新建世界仍会追来；击败追击恶魔可解除。关闭后不再触发新的追击")
                .define("crossSavePursuit", false);
        DAILY_KARMA_GAIN_CAP = builder
                .comment("每游戏日最多增加多少好感度（喂食、送礼、Verity 自己在对话中加的好感共用）；0 表示不限制")
                .defineInRange("dailyKarmaGainCap", 3, 0, 20);
        builder.pop();

        builder.push("server_verity_adapter");
        SERVER_VERITY_ADAPTER_ENABLED = builder
                .comment("启用原版 Verity 的服务端多人归属、实体索引和私聊适配")
                .define("enabled", true);
        SERVER_VERITY_PRIVATE_CHAT = builder
                .comment("Verity AI 回复只发送给触发对话的玩家，不再默认广播到公屏")
                .define("privateChat", true);
        SERVER_VERITY_REQUIRE_COMMAND = builder
                .comment("是否必须使用 /v 或 /v! 指令才能触发 Verity 对话；关闭后普通聊天也可触发。单人游戏不受本开关限制（直接打字即可对话）")
                .define("requireCommand", true);
        SERVER_VERITY_ALLOW_PUBLIC_CHAT = builder
                .comment("允许玩家用 /v! 把这条对话公开给全服；关闭后 /v! 会被拒绝，只能用 /v")
                .define("allowPublicVerityChat", true);
        SERVER_VERITY_AUTO_CLAIM_UNOWNED = builder
                .comment("玩家首次与无归属 Verity 交互时自动认领；管理员生成的实体可关闭此项")
                .define("autoClaimUnowned", true);
        SERVER_VERITY_MAX_ENTITIES_PER_PLAYER = builder
                .comment("每名玩家最多拥有的 Verity 实体数量")
                .defineInRange("maxEntitiesPerPlayer", 1, 1, 64);
        SERVER_VERITY_DEMON_MAX_HEALTH = builder
                .comment("变异恶魔最大生命值上限；用于防止异常属性导致无法击杀")
                .defineInRange("demonMaxHealth", 400, 20, 4000);
        SERVER_VERITY_ALLOW_GLOBAL_ENTITY_PURGE = builder
                .comment("是否允许原版 Verity 第二阶段扫描并删除全世界生物；关闭可避免服务器卡顿")
                .define("allowGlobalEntityPurge", false);
        SERVER_VERITY_BALL_RIDE = builder
                .comment("允许玩家潜行 + 空手右键骑乘球形态（非恶魔形态）的 Verity，由玩家操控移动；"
                        + "普通右键仍为原版的「把Verity收进物品」")
                .define("ballRideEnabled", true);
        SERVER_VERITY_RIDE_OWNER_ONLY = builder
                .comment("是否只有 Verity 的主人能骑；关闭后所有玩家都可请求骑乘（Verity 仍可在求饶等状态下拒绝）")
                .define("rideOwnerOnly", true);
        SERVER_VERITY_HEAD_PERCH = builder
                .comment("允许 Verity 站到玩家头上，快捷键（默认 H）可让Verity上来/下来")
                .define("headPerchEnabled", true);
        SERVER_VERITY_AI_EVENT_COOLDOWN_TICKS = builder
                .comment("骑乘、头顶站坐等事件向 AI 反馈的最短冷却（tick，20=1秒），防止连点刷爆对话")
                .defineInRange("aiEventCooldownTicks", 60, 0, 1200);
        SERVER_VERITY_ITEM_TOOLS = builder
                .comment("增强原版 AI 的物品工具：查询返回物品 ID+英文名+中文名，并可直接把物品放进玩家背包")
                .define("itemToolsEnabled", true);
        SERVER_VERITY_ITEM_QUERY_LIMIT = builder
                .comment("AI 单次物品查询返回的最大条目数")
                .defineInRange("itemQueryLimit", 40, 5, 200);
        builder.pop();

        builder.push("stellar_remote");
        STELLAR_REMOTE_ENABLED = builder
                .comment("允许 Verity 借用《The Shooting Star》的星之遥控器施放技能（需要安装该模组）")
                .define("enabled", true);
        STELLAR_REMOTE_REQUIRE_ITEM = builder
                .comment("施放前要求玩家背包里至少有 1 个星之遥控器（shooting_star_demo:stellar_remote）")
                .define("requireItem", true);
        STELLAR_REMOTE_ALLOWED_SKILLS = builder
                .comment("允许 Verity 使用的技能 id 白名单，逗号分隔；留空表示不限制")
                .define("allowedSkills", "");
        STELLAR_REMOTE_SHOW_IN_HAND = builder
                .comment("施法时在 Verity 旁边显示她举着的星之遥控器（纯客户端渲染，不影响判定）")
                .define("showInHand", true);
        builder.pop();

        builder.push("horror_server");
        HORROR_SERVER_ENABLED = builder
                .comment("服务端恐怖导演总开关；关闭后服务器不发送恐怖事件")
                .define("enabled", true);
        HORROR_SERVER_AMBIENT_EVENTS = builder
                .comment("服务端是否允许普通 Verity 附近的低频日常异常")
                .define("ambientEvents", true);
        HORROR_SERVER_EVENT_FREQUENCY = builder
                .comment("服务端恐怖事件频率倍率，客户端不能绕过服务端冷却")
                .defineInRange("eventFrequency", 1.0D, 0.0D, 3.0D);
        HORROR_SERVER_COOLDOWN_SECONDS = builder
                .comment("同一玩家接收服务端恐怖事件的最短冷却秒数")
                .defineInRange("cooldownSeconds", 45, 10, 3600);
        builder.pop();

        clientBuilder.push("horror");
        HORROR_ENABLED = clientBuilder
                .comment("启用基于 Verity 6.0.0-beta.9 恶魔状态的分层恐怖导演")
                .define("enabled", true);
        HORROR_INTENSITY = clientBuilder
                .comment("本地恐怖效果强度：0 完全关闭，1 原设计强度，2 极强")
                .defineInRange("intensity", 1.35D, 0.0D, 2.0D);
        HORROR_VISUALS = clientBuilder
                .comment("启用动态暗角、黑场脉冲、红色边缘、故障条与追逐雾")
                .define("visuals", true);
        HORROR_AUDIO = clientBuilder
                .comment("启用心跳、身后脚步、骨裂、低语与洞穴异响")
                .define("audio", true);
        HORROR_CAMERA_SHAKE = clientBuilder
                .comment("启用追逐时的镜头呼吸、轻微震颤和 FOV 压迫")
                .define("cameraShake", true);
        HORROR_JUMPSCARES = clientBuilder
                .comment("允许低频、短时的跳脸画面与 jumpscare 音效")
                .define("jumpscares", true);
        HORROR_HALLUCINATIONS = clientBuilder
                .comment("允许视野边缘出现短暂的人形幻影与假靠近声")
                .define("hallucinations", true);
        HORROR_AMBIENT_ANOMALIES = clientBuilder
                .comment("普通 Verity 附近、没有剧情恶魔时，低频触发含蓄的日常异常")
                .define("ambientAnomalies", true);
        HORROR_ANOMALY_TEXT = clientBuilder
                .comment("日常异常中允许显示不会进入聊天记录的短时异常文字")
                .define("anomalyText", true);
        HORROR_ANOMALY_FREQUENCY = clientBuilder
                .comment("日常异常频率：0 关闭，1 默认长冷却，3 较频繁；仍受每小时次数上限保护")
                .defineInRange("anomalyFrequency", 1.0D, 0.0D, 3.0D);
        LOCAL_VERITY_PUBLIC_DEFAULT = clientBuilder
                .comment("本地 Verity 对话默认公开；关闭时回答只显示给自己。单次公开可使用 /v! 前缀")
                .define("localVerityPublicDefault", false);
        clientBuilder.pop();

        builder.push("ai_building");
        ENABLE_AI_BUILDING = builder
                .define("enabled", false);
        AI_BUILDING_VERITY_BUILDER_MODE = builder
                .define("verityBuilderMode", false);
        AI_BUILDING_OVERWRITE_BLOCKS = builder
                .define("overwriteBlocks", true);
        AI_BUILDING_BLOCKS_PER_TICK = builder
                .defineInRange("blocksPerTick", 2, 1, 32);
        AI_BUILDING_MAX_SIZE = builder
                .defineInRange("maxSize", 64, 3, 64);
        AI_BUILDING_MAX_BLOCKS = builder
                .defineInRange("maxBlocks", 32768, 64, 32768);
        AI_BUILDING_GHOST_PREVIEW = builder
                .comment("开始施工前先展示 1.5 秒半透明幽灵预览，让玩家可按 Esc 取消")
                .define("ghostPreview", false);
        AI_BUILDING_GHOST_PREVIEW_TICKS = builder
                .comment("幽灵预览的展示时长（游戏刻，20=1秒）")
                .defineInRange("ghostPreviewTicks", 30, 0, 600);
        AI_BUILDING_BOSS_BAR_PROGRESS = builder
                .comment("在屏幕顶部显示 Boss Bar 进度条")
                .define("bossBarProgress", true);
        AI_BUILDING_PARTICLES = builder
                .comment("施工时显示方块粒子效果")
                .define("particles", true);
        AI_BUILDING_SOUND = builder
                .comment("施工时播放方块放置音效")
                .define("sound", true);
        AI_BUILDING_BLUEPRINT_DIR = builder
                .comment("本地蓝图库目录（用于 /verity-build save / load）")
                .define("blueprintDir", "./config/verity_extended/blueprints");
        AI_BUILDING_PERSIST = builder
                .comment("施工任务在玩家退出世界或服务器重启后会自动保存和恢复")
                .define("persist", false);
        AI_BUILDING_ALLOW_MULTIPLAYER = builder
                .comment("允许在多人服务器里使用 AI 建造（按玩家 UUID 隔离任务）")
                .define("allowMultiplayer", false);
        builder.pop();

        clientBuilder.push("mmd");
        MMD_CAMERA_HEIGHT = clientBuilder
                .comment("MMD 镜头坐标系相对于恶魔脚底的垂直偏移，单位为方块")
                .defineInRange("cameraHeight", 2.4D, -16.0D, 32.0D);
        AI_DEMON_AUTO_SPAWN = clientBuilder
                .comment("MMD 播放时如果当前维度内没有恶魔实体，自动向服务器请求生成一只。开启后\"播放空项目\"也会立即生效")
                .define("autoSpawnDemon", true);
        AI_DEMON_FRIENDLY_MODE = clientBuilder
                .comment("永久让恶魔实体（VerityDemonEntity）不会把玩家当作攻击目标，包括按需生成的恶魔")
                .define("friendlyMode", true);
        AI_DEMON_SPAWN_RETRY_MS = clientBuilder
                .comment("客户端等待服务器恶魔 ACK 的最大毫秒数，超时后会再发送一次生成请求")
                .defineInRange("spawnRetryMs", 8000, 2000, 30000);
        clientBuilder.pop();

        clientBuilder.push("builtin_pmx");
        ENABLE_BUILTIN_PMX = clientBuilder
                .comment("新增功能：使用本 Mod 内置 PMX/PMD 渲染器替换 Verity 模型。关闭时完全使用原版渲染，不影响原有功能")
                .define("enabled", false);
        BUILTIN_PMX_MODEL = clientBuilder
                .comment("内置 PMX 模型目录名或项目内绝对路径；留空自动检测 config/verity_extended/pmx 下的第一个 PMX/PMD 文件")
                .define("model", "");
        clientBuilder.pop();

        clientBuilder.push("skin");
        VERITY_SKIN_NAME = clientBuilder
                .comment("Verity 实体皮肤名称（留空使用默认）。皮肤包放入 config/verity/skins/ 目录")
                .define("skinName", "Verity");
        clientBuilder.pop();

        clientBuilder.push("localization");
        ENABLE_VERITY_LOCALIZATION = clientBuilder
                .comment("把原版 Verity 配置界面和命令提示里硬编码的英文文本汉化，需要重启游戏生效")
                .define("enableVerityLocalization", true);
        clientBuilder.pop();

        clientBuilder.push("verity_jelly");
        VERITY_JELLY_ENABLED = clientBuilder
                .comment("Verity 说话时的「Q弹」形变开关：打开后她说话会明显一弹一弹，比原版夸张很多")
                .define("enabled", false);
        VERITY_JELLY_CHAOS = clientBuilder
                .comment("鬼畜模式：在 Q 弹的基础上幅度 ×1.6、速度 ×2.6，再叠加高频抽搐、左右甩头和原地弹跳")
                .define("chaos", false);
        VERITY_JELLY_AMPLITUDE = clientBuilder
                .comment("Q弹幅度：纵向拉伸/压扁的最大比例（0 = 几乎不动，1 = 非常夸张）")
                .defineInRange("amplitude", 0.45D, 0.05D, 1.0D);
        VERITY_JELLY_SPEED = clientBuilder
                .comment("Q弹速度：数值越大弹得越快")
                .defineInRange("speed", 0.75D, 0.1D, 3.0D);
        VERITY_JELLY_SQUASH = clientBuilder
                .comment("横向挤压：纵向拉长时横向收窄的强度，1 左右最像果冻")
                .defineInRange("squash", 0.85D, 0.0D, 1.5D);
        clientBuilder.pop();

        builder.push("debug");
        DEV_MODE = builder
                .comment("开发者模式（v6.2），启用后控制台输出提示词内容和Karma值等调试信息")
                .define("devMode", false);
        DEBUG_RUNTIME_METRICS = builder
                .comment("是否输出运行时内存/线程/队列等诊断日志（用于排查内存持续增长问题）")
                .define("runtimeMetrics", false);
        DEBUG_RUNTIME_METRICS_INTERVAL_SECONDS = builder
                .comment("诊断日志输出间隔（秒）")
                .defineInRange("runtimeMetricsIntervalSeconds", 60, 5, 3600);
        builder.pop();

        builder.push("plugin");
        PLUGIN_ENABLED = builder
                .comment("插件系统总开关：允许玩家把自己的想法交给 AI 生成插件并在游戏里运行。",
                        "插件只能调用模组内置的动作，不能执行任意代码；每个插件只对创建它的玩家生效。",
                        "服务器上如果不希望玩家自制功能，把它关掉即可，已生成的插件也不会再运行。")
                .define("enabled", true);
        PLUGIN_MAX_LOADED = builder
                .comment("最多加载多少个插件；超出后多余的插件不会被加载（日志会写明）")
                .defineInRange("maxLoaded", 30, 0, 500);
        PLUGIN_ALLOW_BLOCK_EDIT = builder
                .comment("是否允许插件放置方块（set_block 动作）。默认关闭：放置类动作最容易影响服务器，",
                        "只有确认可信时才打开。")
                .define("allowBlockEdit", false);
        builder.pop();

        COMMON_SPEC = builder.build();
        CLIENT_SPEC = clientBuilder.build();
    }
}
