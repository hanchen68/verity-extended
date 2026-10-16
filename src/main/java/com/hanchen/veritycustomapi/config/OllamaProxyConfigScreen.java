package com.hanchen.veritycustomapi.config;

import com.hanchen.veritycustomapi.ApiProviderEnum;
import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.SttApiProviderEnum;
import com.hanchen.veritycustomapi.TtsApiProviderEnum;
import com.hanchen.veritycustomapi.client.ClientEventHandler;
import com.hanchen.veritycustomapi.compat.OriginalVerityConfigSync;
import com.hanchen.veritycustomapi.stt.VoskModelInstaller;
import com.hanchen.veritycustomapi.stt.VoskRuntimeSupport;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.impl.builders.SubCategoryBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class OllamaProxyConfigScreen {

    /**
     * 从配置字符串反查对应的人设枚举
     */
    private static ConfigManager.PromptPreset getCurrentPreset() {
        String selectedName = ModConfig.SELECTED_PRESET.get();
        for (ConfigManager.PromptPreset preset : ConfigManager.PromptPreset.values()) {
            if (preset.name().equals(selectedName)) {
                return preset;
            }
        }
        for (ConfigManager.PromptPreset preset : ConfigManager.PromptPreset.values()) {
            if (preset.displayName.equals(selectedName)) {
                return preset;
            }
        }
        return ConfigManager.PromptPreset.ORIGINAL_VERITY;
    }

    public static Screen createScreen(Screen parentScreen) {
        ConfigBuilder builder = ConfigBuilder.create()
                .setParentScreen(parentScreen)
                .setTitle(Component.translatable("config.veritycustomapi.title"))
                .setSavingRunnable(ClientEventHandler::onConfigSaved);

        ConfigEntryBuilder entry = builder.entryBuilder();

        // ===================== 0. 状态提示 =====================
        ConfigCategory statusCat = builder.getOrCreateCategory(
                Component.literal("状态提示")
        );

        SubCategoryBuilder statusBase = entry.startSubCategory(
                Component.literal("离线语音识别")
        ).setExpanded(true);
        statusBase.add(entry.startStrField(
                        Component.literal("当前状态"),
                        VoskRuntimeSupport.getOfflineStatus(ModConfig.VOSK_MODEL_PATH.get())
                )
                .setDefaultValue(VoskRuntimeSupport.getOfflineStatus("./config/vosk-model-small-cn"))
                .setTooltip(Component.literal("这里会直接显示离线语音识别当前是否可用，以及不可用时的具体原因。"))
                .setSaveConsumer(v -> {})
                .build());
        statusBase.add(entry.startStrField(
                        Component.literal("安装器状态"),
                        VoskModelInstaller.getLastStatus()
                )
                .setDefaultValue("未安装")
                .setTooltip(Component.literal("这里显示最近一次模型下载/解压/安装的执行状态。"))
                .setSaveConsumer(v -> {})
                .build());
        statusBase.add(entry.startStrField(
                        Component.literal("界面说明"),
                        "主菜单也会显示离线语音状态提示"
                )
                .setDefaultValue("主菜单也会显示离线语音状态提示")
                .setTooltip(Component.literal("如果离线语音不可用，主菜单会弹出提示，不需要进入世界后看聊天框。"))
                .setSaveConsumer(v -> {})
                .build());
        statusCat.addEntry(statusBase.build());

        ConfigCategory originalVerityCat = builder.getOrCreateCategory(
                Component.literal("原版 Verity 设置")
        );

        SubCategoryBuilder originalVoice = entry.startSubCategory(
                Component.literal("语音桥接")
        ).setExpanded(true);
        originalVoice.add(entry.startTextDescription(Component.literal(
                "原版 TTS 总开关：已接管并固定关闭，避免原版语音与扩展语音同时播放"
        )).build());
        originalVoice.add(entry.startTextDescription(Component.literal(
                "原版系统朗读、本地 TTS 和 Kokoro TTS：均已接管并关闭"
        )).build());
        originalVoice.add(entry.startStrField(
                        Component.literal("原版角色音色"),
                        OriginalVerityConfigSync.getVoiceValue("voice", "Daniel")
                )
                .setDefaultValue("Daniel")
                .setTooltip(Component.literal("原版 Verity 自带音色名。扩展在线 TTS 音色请在“内置 API”或“语音合成设置”里修改。"))
                .setSaveConsumer(v -> OriginalVerityConfigSync.queueVoiceString("voice", v))
                .build());
        originalVerityCat.addEntry(originalVoice.build());

        Minecraft minecraft = Minecraft.getInstance();
        boolean remoteServer = minecraft.getCurrentServer() != null && !minecraft.hasSingleplayerServer();
        if (remoteServer) {
            originalVerityCat.addEntry(entry.startTextDescription(Component.literal(
                    "当前连接的是远程多人服务器。本机配置无法修改服务器权威的玩法与危险行为设置，因此这些开关已隐藏。"
            )).build());
        } else {
            SubCategoryBuilder originalCommon = entry.startSubCategory(
                    Component.literal("常用玩法（单人/本地服务端）")
            ).setExpanded(true);
        originalCommon.add(entry.startIntField(
                        Component.literal("多少游戏日后进入敌对状态"),
                        OriginalVerityConfigSync.getGeneralInt("dayCount", 5)
                )
                .setDefaultValue(5)
                .setMin(1)
                .setMax(100)
                .setTooltip(Component.literal("对应原版 dayCount。"))
                .setSaveConsumer(v -> OriginalVerityConfigSync.queueGeneralInt("dayCount", v))
                .build());
        originalCommon.add(entry.startBooleanToggle(
                        Component.literal("每句话必须包含 Verity"),
                        OriginalVerityConfigSync.getGeneralBoolean("requireVerity", false)
                )
                .setDefaultValue(false)
                .setTooltip(Component.literal("对应原版 requireVerity。开启后玩家说话需要包含 Verity 才会触发。"))
                .setSaveConsumer(v -> OriginalVerityConfigSync.queueGeneralBoolean("requireVerity", v))
                .build());
        originalCommon.add(entry.startBooleanToggle(
                        Component.literal("播放启动视频"),
                        OriginalVerityConfigSync.getGeneralBoolean("playVideo", true)
                )
                .setDefaultValue(true)
                .setTooltip(Component.literal("对应原版 playVideo。"))
                .setSaveConsumer(v -> OriginalVerityConfigSync.queueGeneralBoolean("playVideo", v))
                .build());
        originalCommon.add(entry.startBooleanToggle(
                        Component.literal("真实黑暗"),
                        OriginalVerityConfigSync.getGeneralBoolean("trueDarkness", true)
                )
                .setDefaultValue(true)
                .setTooltip(Component.literal("对应原版 trueDarkness。"))
                .setSaveConsumer(v -> OriginalVerityConfigSync.queueGeneralBoolean("trueDarkness", v))
                .build());
        originalCommon.add(entry.startBooleanToggle(
                        Component.literal("显示 Karma 条"),
                        OriginalVerityConfigSync.getGeneralBoolean("showKarma", false)
                )
                .setDefaultValue(false)
                .setTooltip(Component.literal("对应原版 showKarma。"))
                .setSaveConsumer(v -> OriginalVerityConfigSync.queueGeneralBoolean("showKarma", v))
                .build());
            originalVerityCat.addEntry(originalCommon.build());

            SubCategoryBuilder originalRisk = entry.startSubCategory(
                    Component.literal("危险行为（单人/本地服务端）")
            ).setExpanded(false);
        originalRisk.add(entry.startBooleanToggle(
                        Component.literal("允许踢出/崩溃玩家"),
                        OriginalVerityConfigSync.getGeneralBoolean("canCrash", true)
                )
                .setDefaultValue(true)
                .setTooltip(Component.literal("对应原版 canCrash。关闭后禁止 Verity 使用踢出/崩溃类行为。"))
                .setSaveConsumer(v -> OriginalVerityConfigSync.queueGeneralBoolean("canCrash", v))
                .build());
        originalRisk.add(entry.startBooleanToggle(
                        Component.literal("随机杀死野生动物"),
                        OriginalVerityConfigSync.getGeneralBoolean("killWildlife", true)
                )
                .setDefaultValue(true)
                .setTooltip(Component.literal("对应原版 killWildlife。"))
                .setSaveConsumer(v -> OriginalVerityConfigSync.queueGeneralBoolean("killWildlife", v))
                .build());
        originalRisk.add(entry.startBooleanToggle(
                        Component.literal("允许杀死实体"),
                        OriginalVerityConfigSync.getGeneralBoolean("killEntities", true)
                )
                .setDefaultValue(true)
                .setTooltip(Component.literal("对应原版 killEntities。"))
                .setSaveConsumer(v -> OriginalVerityConfigSync.queueGeneralBoolean("killEntities", v))
                .build());
        originalRisk.add(entry.startBooleanToggle(
                        Component.literal("允许杀死村民"),
                        OriginalVerityConfigSync.getGeneralBoolean("killVillagers", true)
                )
                .setDefaultValue(true)
                .setTooltip(Component.literal("对应原版 killVillagers。"))
                .setSaveConsumer(v -> OriginalVerityConfigSync.queueGeneralBoolean("killVillagers", v))
                .build());
            originalVerityCat.addEntry(originalRisk.build());
        }

        // ===================== 1. 代理设置 =====================
        ConfigCategory proxyCat = builder.getOrCreateCategory(
                Component.translatable("config.veritycustomapi.category.proxy")
        );

        SubCategoryBuilder proxyBase = entry.startSubCategory(
                Component.translatable("config.veritycustomapi.sub.base")
        ).setExpanded(true);
        proxyBase.add(entry.startIntField(
                        Component.translatable("config.veritycustomapi.proxy.port"),
                        ModConfig.SERVER_PORT.get()
                )
                .setDefaultValue(4000)
                .setTooltip(Component.translatable("config.veritycustomapi.proxy.port.tooltip"))
                .setSaveConsumer(ModConfig.SERVER_PORT::set)
                .build());
        proxyCat.addEntry(proxyBase.build());

        ConfigCategory awarenessCat = builder.getOrCreateCategory(
                Component.literal("事件感知与主动吐槽")
        );
        SubCategoryBuilder awarenessEvents = entry.startSubCategory(
                Component.literal("游戏事件")
        ).setExpanded(true);
        awarenessEvents.add(entry.startBooleanToggle(
                        Component.literal("启用主动事件感知"),
                        ModConfig.ENABLE_PROACTIVE_AWARENESS.get()
                )
                .setDefaultValue(false)
                .setTooltip(Component.literal("允许 Verity 根据游戏状态主动生成简短回应。所有事件共用冷却，避免刷屏和额外 Token 消耗。"))
                .setSaveConsumer(ModConfig.ENABLE_PROACTIVE_AWARENESS::set)
                .build());
        awarenessEvents.add(entry.startBooleanToggle(Component.literal("打开暂停菜单"), ModConfig.PROACTIVE_PAUSE_EVENT.get())
                .setDefaultValue(true).setSaveConsumer(ModConfig.PROACTIVE_PAUSE_EVENT::set).build());
        awarenessEvents.add(entry.startBooleanToggle(Component.literal("玩家死亡"), ModConfig.PROACTIVE_DEATH_EVENT.get())
                .setDefaultValue(true).setSaveConsumer(ModConfig.PROACTIVE_DEATH_EVENT::set).build());
        awarenessEvents.add(entry.startBooleanToggle(Component.literal("生命值低于 25%"), ModConfig.PROACTIVE_LOW_HEALTH_EVENT.get())
                .setDefaultValue(true).setSaveConsumer(ModConfig.PROACTIVE_LOW_HEALTH_EVENT::set).build());
        awarenessEvents.add(entry.startBooleanToggle(Component.literal("睡觉与醒来"), ModConfig.PROACTIVE_SLEEP_EVENT.get())
                .setDefaultValue(true).setSaveConsumer(ModConfig.PROACTIVE_SLEEP_EVENT::set).build());
        awarenessEvents.add(entry.startBooleanToggle(Component.literal("进入与退出世界"), ModConfig.PROACTIVE_WORLD_EVENT.get())
                .setDefaultValue(true).setSaveConsumer(ModConfig.PROACTIVE_WORLD_EVENT::set).build());
        awarenessEvents.add(entry.startIntField(
                        Component.literal("主动吐槽冷却（秒）"),
                        ModConfig.PROACTIVE_COOLDOWN_SECONDS.get()
                )
                .setDefaultValue(300)
                .setMin(30)
                .setMax(3600)
                .setTooltip(Component.literal("默认 300 秒，即五分钟。冷却期间发生的其他事件不会再次调用 AI。"))
                .setSaveConsumer(ModConfig.PROACTIVE_COOLDOWN_SECONDS::set)
                .build());
        awarenessCat.addEntry(awarenessEvents.build());

        SubCategoryBuilder awarenessNotice = entry.startSubCategory(
                Component.literal("通知与范围")
        ).setExpanded(true);
        awarenessNotice.add(entry.startBooleanToggle(
                        Component.literal("桌面通知"),
                        ModConfig.PROACTIVE_DESKTOP_NOTIFICATIONS.get()
                )
                .setDefaultValue(false)
                .setTooltip(Component.literal("仅在系统支持且 Minecraft 窗口不活跃时显示通知。默认关闭。"))
                .setSaveConsumer(ModConfig.PROACTIVE_DESKTOP_NOTIFICATIONS::set)
                .build());
        awarenessNotice.add(entry.startTextDescription(Component.literal(
                "主动吐槽只读取游戏内事件和玩家状态，不会截取真实电脑屏幕，也不会上传图像。"
        )).build());
        awarenessCat.addEntry(awarenessNotice.build());

        // ===================== 2. 对话 API 设置 =====================
        ConfigCategory llmCat = builder.getOrCreateCategory(
                Component.translatable("config.veritycustomapi.category.chatApi")
        );

        SubCategoryBuilder llmBase = entry.startSubCategory(
                Component.translatable("config.veritycustomapi.sub.baseConfig")
        ).setExpanded(true);
        llmBase.add(entry.startEnumSelector(
                        Component.translatable("config.veritycustomapi.llm.provider"),
                        ApiProviderEnum.class,
                        ConfigManager.getLlmApiProvider()
                )
                .setDefaultValue(ApiProviderEnum.CUSTOM_OPENAI)
                .setTooltip(Component.translatable("config.veritycustomapi.llm.provider.tooltip"))
                .setSaveConsumer(v -> ModConfig.LLM_API_PROVIDER.set(v.name()))
                .build());
        llmBase.add(entry.startStrField(
                        Component.translatable("config.veritycustomapi.llm.apiUrl"),
                        ModConfig.TARGET_API_URL.get()
                )
                .setDefaultValue("")
                .setTooltip(Component.translatable("config.veritycustomapi.llm.apiUrl.tooltip"))
                .setSaveConsumer(ModConfig.TARGET_API_URL::set)
                .build());
        llmBase.add(entry.startStrField(
                        Component.translatable("config.veritycustomapi.llm.apiKey"),
                        ModConfig.TARGET_API_KEY.get()
                )
                .setDefaultValue("")
                .setTooltip(Component.translatable("config.veritycustomapi.llm.apiKey.tooltip"))
                .setSaveConsumer(ModConfig.TARGET_API_KEY::set)
                .build());
        llmBase.add(entry.startStrField(
                        Component.translatable("config.veritycustomapi.llm.model"),
                        ModConfig.TARGET_MODEL.get()
                )
                .setDefaultValue("")
                .setTooltip(Component.translatable("config.veritycustomapi.llm.model.tooltip"))
                .setSaveConsumer(ModConfig.TARGET_MODEL::set)
                .build());
        llmBase.add(entry.startBooleanToggle(
                        Component.literal("对话流式输出"),
                        ModConfig.CHAT_STREAM.get()
                )
                .setDefaultValue(false)
                .setTooltip(Component.literal("开启后，Chat 代理会逐字把回复推送给 Verity 客户端；关闭时维持一次性 JSON 响应，兼容性最好。"))
                .setSaveConsumer(ModConfig.CHAT_STREAM::set)
                .build());
        llmCat.addEntry(llmBase.build());

        SubCategoryBuilder llmAdvanced = entry.startSubCategory(
                Component.translatable("config.veritycustomapi.sub.advanced")
        ).setExpanded(false);
        llmAdvanced.add(entry.startIntField(
                        Component.translatable("config.veritycustomapi.llm.timeout"),
                        ModConfig.REQUEST_TIMEOUT.get()
                )
                .setDefaultValue(30)
                .setMin(5)
                .setMax(300)
                .setTooltip(Component.translatable("config.veritycustomapi.llm.timeout.tooltip"))
                .setSaveConsumer(ModConfig.REQUEST_TIMEOUT::set)
                .build());
        llmCat.addEntry(llmAdvanced.build());

        // ===================== 2.5 内置 API 服务设置 =====================
        ConfigCategory builtinApiCat = builder.getOrCreateCategory(
                Component.literal("内置 API")
        );

        SubCategoryBuilder builtinApiBase = entry.startSubCategory(
                Component.literal("可用性")
        ).setExpanded(true);

        builtinApiBase.add(entry.startBooleanToggle(
                        Component.literal("启用内置 STT 服务商"),
                        ModConfig.ENABLE_BUILTIN_STT_APIS.get()
                )
                .setDefaultValue(false)
                .setTooltip(Component.literal("内置服务由免费渠道提供，高峰期可能排队、失败或不稳定；关闭后保留并使用当前选择的语音识别服务商"))
                .setSaveConsumer(ModConfig.ENABLE_BUILTIN_STT_APIS::set)
                .build());

        builtinApiBase.add(entry.startBooleanToggle(
                        Component.literal("启用内置 TTS 服务商"),
                        ModConfig.ENABLE_BUILTIN_TTS_APIS.get()
                )
                .setDefaultValue(false)
                .setTooltip(Component.literal("内置服务由免费渠道提供，高峰期可能排队、失败或不稳定；关闭后保留并使用当前选择的语音合成服务商"))
                .setSaveConsumer(ModConfig.ENABLE_BUILTIN_TTS_APIS::set)
                .build());

        builtinApiBase.add(entry.startEnumSelector(
                        Component.literal("内置 MiMo TTS 音色"),
                        ConfigManager.MimoTtsVoice.class,
                        ConfigManager.getBuiltinMimoTtsVoice()
                )
                .setDefaultValue(ConfigManager.MimoTtsVoice.SUDA)
                .setTooltip(Component.literal("仅在启用内置 TTS 服务商时生效。可选音色：冰糖、茉莉、苏打、白桦。"))
                .setSaveConsumer(v -> ClientEventHandler.setPendingBuiltinMimoVoice(v.voiceName))
                .build());

        builtinApiCat.addEntry(builtinApiBase.build());

        // ===================== 3. 系统提示词 =====================
        ConfigCategory promptCat = builder.getOrCreateCategory(
                Component.translatable("config.veritycustomapi.category.prompt")
        );

        SubCategoryBuilder presetGroup = entry.startSubCategory(
                Component.translatable("config.veritycustomapi.sub.preset")
        ).setExpanded(true);
        
        presetGroup.add(entry.startBooleanToggle(
                        Component.translatable("启用自定义提示词"),
                        ModConfig.ENABLE_CUSTOM_PROMPT.get()
                )
                .setDefaultValue(false)
                .setTooltip(Component.translatable("关闭则使用Verity原版提示词，不进行任何修改"))
                .setSaveConsumer(ModConfig.ENABLE_CUSTOM_PROMPT::set)
                .build());

        presetGroup.add(entry.startEnumSelector(
                        Component.translatable("config.veritycustomapi.prompt.preset"),
                        ConfigManager.PromptPreset.class,
                        getCurrentPreset()
                )
                .setDefaultValue(ConfigManager.PromptPreset.ORIGINAL_VERITY)
                .setTooltip(Component.translatable("config.veritycustomapi.prompt.preset.tooltip"))
                .setSaveConsumer(preset -> ModConfig.SELECTED_PRESET.set(preset.name()))
                .build());

        presetGroup.add(entry.startStrField(
                        Component.translatable("config.veritycustomapi.prompt.preview"),
                        ConfigManager.getEffectiveSystemPrompt()
                )
                .setDefaultValue(ConfigManager.PromptPreset.ORIGINAL_VERITY.getPrompt())
                .setTooltip(Component.translatable("config.veritycustomapi.prompt.preview.tooltip"))
                .setSaveConsumer(s -> {})
                .build());

        presetGroup.add(entry.startStrField(
                        Component.translatable("config.veritycustomapi.prompt.customFile"),
                        ModConfig.CUSTOM_PROMPT_FILE.get()
                )
                .setDefaultValue("./config/verity_custom_prompt.txt")
                .setTooltip(Component.translatable("config.veritycustomapi.prompt.customFile.tooltip"))
                .setSaveConsumer(ModConfig.CUSTOM_PROMPT_FILE::set)
                .build());
        promptCat.addEntry(presetGroup.build());

        // ===================== 4. 语音识别设置 =====================
        ConfigCategory sttCat = builder.getOrCreateCategory(
                Component.translatable("config.veritycustomapi.category.stt")
        );

        SubCategoryBuilder sttMain = entry.startSubCategory(
                Component.translatable("config.veritycustomapi.sub.main")
        ).setExpanded(true);
        sttMain.add(entry.startBooleanToggle(
                        Component.translatable("config.veritycustomapi.stt.enable"),
                        ModConfig.ENABLE_VOICE_INPUT.get()
                )
                .setDefaultValue(true)
                .setTooltip(Component.translatable("config.veritycustomapi.stt.enable.tooltip"))
                .setSaveConsumer(ModConfig.ENABLE_VOICE_INPUT::set)
                .build());
        sttMain.add(entry.startEnumSelector(
                        Component.translatable("config.veritycustomapi.stt.mode"),
                        ConfigManager.RecognitionMode.class,
                        ConfigManager.getRecognitionMode()
                )
                .setDefaultValue(ConfigManager.RecognitionMode.LOCAL)
                .setTooltip(Component.translatable("config.veritycustomapi.stt.mode.tooltip"))
                .setSaveConsumer(v -> ModConfig.RECOGNITION_MODE.set(v.name().toLowerCase()))
                .build());
        sttCat.addEntry(sttMain.build());

        SubCategoryBuilder sttLocal = entry.startSubCategory(
                Component.translatable("config.veritycustomapi.sub.localMode")
        ).setExpanded(true);
        sttLocal.add(entry.startStrField(
                        Component.translatable("config.veritycustomapi.stt.voskPath"),
                        ModConfig.VOSK_MODEL_PATH.get()
                )
                .setDefaultValue("./config/vosk-model-small-cn")
                .setTooltip(Component.literal("自动安装会使用默认路径 ./config/vosk-model-small-cn；如果你已经手动放好了 Vosk 中文模型，也可以在这里填写模型文件夹路径。"))
                .setSaveConsumer(ModConfig.VOSK_MODEL_PATH::set)
                .build());
        sttLocal.add(entry.startStrField(
                        Component.literal("当前离线识别状态"),
                        VoskRuntimeSupport.getOfflineStatus(ModConfig.VOSK_MODEL_PATH.get())
                )
                .setDefaultValue(VoskRuntimeSupport.getOfflineStatus("./config/vosk-model-small-cn"))
                .setTooltip(Component.literal("这里会区分模型是否已安装，以及当前版本是否包含 Vosk 运行库。这个字段仅用于显示状态，不需要手动修改。"))
                .setSaveConsumer(v -> {})
                .build());
        sttLocal.add(entry.startTextDescription(Component.literal(
                "安装模型请点击本配置页右下角的“安装离线中文模型”按钮。"
                        + "点击后会立即后台下载并解压，不需要保存配置。"
                        + "下载地址：https://dl.xrstudio.cn/vosk-model-small-cn.zip；"
                        + "安装路径：./config/vosk-model-small-cn；"
                        + VoskModelInstaller.getUiStatus()
        )).build());
        sttCat.addEntry(sttLocal.build());

        SubCategoryBuilder sttOnline = entry.startSubCategory(
                Component.translatable("config.veritycustomapi.sub.onlineMode")
        ).setExpanded(false);
        sttOnline.add(entry.startEnumSelector(
                        Component.translatable("config.veritycustomapi.stt.provider"),
                        SttApiProviderEnum.class,
                        ConfigManager.getSttApiProvider()
                )
                .setDefaultValue(SttApiProviderEnum.CUSTOM_OPENAI)
                .setTooltip(Component.translatable("config.veritycustomapi.stt.provider.tooltip"))
                .setSaveConsumer(v -> ModConfig.STT_API_PROVIDER.set(v.name()))
                .build());
        sttOnline.add(entry.startStrField(
                        Component.translatable("config.veritycustomapi.stt.apiUrl"),
                        ModConfig.SPEECH_API_URL.get()
                )
                .setDefaultValue("")
                .setTooltip(Component.translatable("config.veritycustomapi.stt.apiUrl.tooltip"))
                .setSaveConsumer(ModConfig.SPEECH_API_URL::set)
                .build());
        sttOnline.add(entry.startStrField(
                        Component.translatable("config.veritycustomapi.stt.apiKey"),
                        ModConfig.SPEECH_API_KEY.get()
                )
                .setDefaultValue("")
                .setSaveConsumer(ModConfig.SPEECH_API_KEY::set)
                .build());
        sttOnline.add(entry.startStrField(
                        Component.translatable("config.veritycustomapi.stt.apiModel"),
                        ModConfig.SPEECH_API_MODEL.get()
                )
                .setDefaultValue("")
                .setSaveConsumer(ModConfig.SPEECH_API_MODEL::set)
                .build());
        sttCat.addEntry(sttOnline.build());

        SubCategoryBuilder sttAdvanced = entry.startSubCategory(
                Component.translatable("config.veritycustomapi.sub.advanced")
        ).setExpanded(false);
        sttAdvanced.add(entry.startIntField(
                        Component.translatable("config.veritycustomapi.stt.sampleRate"),
                        ModConfig.SAMPLE_RATE.get()
                )
                .setDefaultValue(16000)
                .setMin(8000)
                .setMax(48000)
                .setTooltip(Component.translatable("config.veritycustomapi.stt.sampleRate.tooltip"))
                .setSaveConsumer(ModConfig.SAMPLE_RATE::set)
                .build());
        sttAdvanced.add(entry.startStrField(
                        Component.translatable("config.veritycustomapi.stt.requestFormat"),
                        ModConfig.SPEECH_REQUEST_FORMAT.get()
                )
                .setDefaultValue("multipart")
                .setTooltip(Component.translatable("config.veritycustomapi.stt.requestFormat.tooltip"))
                .setSaveConsumer(ModConfig.SPEECH_REQUEST_FORMAT::set)
                .build());
        sttAdvanced.add(entry.startStrField(
                        Component.translatable("config.veritycustomapi.stt.authType"),
                        ModConfig.SPEECH_AUTH_TYPE.get()
                )
                .setDefaultValue("bearer")
                .setTooltip(Component.translatable("config.veritycustomapi.stt.authType.tooltip"))
                .setSaveConsumer(ModConfig.SPEECH_AUTH_TYPE::set)
                .build());
        sttAdvanced.add(entry.startStrField(
                        Component.translatable("config.veritycustomapi.stt.responseField"),
                        ModConfig.SPEECH_RESPONSE_FIELD.get()
                )
                .setDefaultValue("text")
                .setTooltip(Component.translatable("config.veritycustomapi.stt.responseField.tooltip"))
                .setSaveConsumer(ModConfig.SPEECH_RESPONSE_FIELD::set)
                .build());
        sttCat.addEntry(sttAdvanced.build());

        // ===================== 5. 语音合成设置 =====================
        ConfigCategory ttsCat = builder.getOrCreateCategory(
                Component.translatable("config.veritycustomapi.category.tts")
        );

        SubCategoryBuilder ttsMain = entry.startSubCategory(
                Component.translatable("config.veritycustomapi.sub.main")
        ).setExpanded(true);
        ttsMain.add(entry.startBooleanToggle(
                        Component.translatable("config.veritycustomapi.tts.enable"),
                        ModConfig.ENABLE_TTS.get()
                )
                .setDefaultValue(true)
                .setTooltip(Component.translatable("config.veritycustomapi.tts.enable.tooltip"))
                .setSaveConsumer(ModConfig.ENABLE_TTS::set)
                .build());
        ttsMain.add(entry.startEnumSelector(
                        Component.translatable("config.veritycustomapi.tts.mode"),
                        ConfigManager.TtsMode.class,
                        ConfigManager.getTtsMode()
                )
                .setDefaultValue(ConfigManager.TtsMode.ONLINE)
                .setTooltip(Component.translatable("config.veritycustomapi.tts.mode.tooltip"))
                .setSaveConsumer(v -> ModConfig.TTS_MODE.set(v.name().toLowerCase()))
                .build());
        ttsCat.addEntry(ttsMain.build());

        SubCategoryBuilder ttsOnline = entry.startSubCategory(
                Component.translatable("config.veritycustomapi.sub.onlineMode")
        ).setExpanded(true);
        ttsOnline.add(entry.startEnumSelector(
                        Component.translatable("config.veritycustomapi.tts.provider"),
                        TtsApiProviderEnum.class,
                        ConfigManager.getTtsApiProvider()
                )
                .setDefaultValue(TtsApiProviderEnum.CUSTOM_OPENAI)
                .setTooltip(Component.translatable("config.veritycustomapi.tts.provider.tooltip"))
                .setSaveConsumer(v -> ModConfig.TTS_API_PROVIDER.set(v.name()))
                .build());
        ttsOnline.add(entry.startStrField(
                        Component.translatable("config.veritycustomapi.tts.apiUrl"),
                        ModConfig.TTS_API_URL.get()
                )
                .setDefaultValue("")
                .setTooltip(Component.translatable("config.veritycustomapi.tts.apiUrl.tooltip"))
                .setSaveConsumer(ModConfig.TTS_API_URL::set)
                .build());
        ttsOnline.add(entry.startStrField(
                        Component.translatable("config.veritycustomapi.tts.apiKey"),
                        ModConfig.TTS_API_KEY.get()
                )
                .setDefaultValue("")
                .setSaveConsumer(ModConfig.TTS_API_KEY::set)
                .build());
        ttsOnline.add(entry.startStrField(
                        Component.translatable("config.veritycustomapi.tts.apiModel"),
                        ModConfig.TTS_API_MODEL.get()
                )
                .setDefaultValue("")
                .setSaveConsumer(ModConfig.TTS_API_MODEL::set)
                .build());
        ttsOnline.add(entry.startStrField(
                        Component.translatable("config.veritycustomapi.tts.apiVoice"),
                        ModConfig.TTS_API_VOICE.get()
                )
                .setDefaultValue("alloy")
                .setSaveConsumer(ModConfig.TTS_API_VOICE::set)
                .build());
        ttsCat.addEntry(ttsOnline.build());

        SubCategoryBuilder ttsLocal = entry.startSubCategory(
                Component.translatable("config.veritycustomapi.sub.localMode")
        ).setExpanded(false);
        ttsLocal.add(entry.startStrField(
                        Component.translatable("config.veritycustomapi.tts.modelPath"),
                        ModConfig.TTS_MODEL_PATH.get()
                )
                .setDefaultValue("./config/tts-model")
                .setTooltip(Component.translatable("config.veritycustomapi.tts.modelPath.tooltip"))
                .setSaveConsumer(ModConfig.TTS_MODEL_PATH::set)
                .build());
        ttsCat.addEntry(ttsLocal.build());

        SubCategoryBuilder ttsPlay = entry.startSubCategory(
                Component.translatable("config.veritycustomapi.sub.playback")
        ).setExpanded(true);
        ttsPlay.add(entry.startIntField(
                        Component.translatable("config.veritycustomapi.tts.speed"),
                        (int)(ModConfig.TTS_SPEED.get() * 100)
                )
                .setDefaultValue(100)
                .setMin(25)
                .setMax(400)
                .setTooltip(Component.translatable("config.veritycustomapi.tts.speed.tooltip"))
                .setSaveConsumer(v -> ModConfig.TTS_SPEED.set(v / 100.0))
                .build());
        ttsPlay.add(entry.startIntField(
                        Component.translatable("config.veritycustomapi.tts.volume"),
                        (int)(ModConfig.TTS_VOLUME.get() * 100)
                )
                .setDefaultValue(100)
                .setMin(0)
                .setMax(200)
                .setTooltip(Component.translatable("config.veritycustomapi.tts.volume.tooltip"))
                .setSaveConsumer(v -> ModConfig.TTS_VOLUME.set(v / 100.0))
                .build());
        ttsPlay.add(entry.startIntField(
                        Component.translatable("config.veritycustomapi.tts.sampleRate"),
                        ModConfig.TTS_SAMPLE_RATE.get()
                )
                .setDefaultValue(22050)
                .setMin(8000)
                .setMax(48000)
                .setSaveConsumer(ModConfig.TTS_SAMPLE_RATE::set)
                .build());
        ttsCat.addEntry(ttsPlay.build());

        return builder.build();
    }

}
