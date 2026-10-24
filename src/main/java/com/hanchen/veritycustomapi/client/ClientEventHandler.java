package com.hanchen.veritycustomapi.client;

import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.OllamaProxyServer;
import com.hanchen.veritycustomapi.cloud.CloudConfigManager;
import com.hanchen.veritycustomapi.config.NativeOllamaProxyConfigScreen;
import com.hanchen.veritycustomapi.config.ModConfig;
import com.hanchen.veritycustomapi.compat.OriginalVerityConfigInjector;
import com.hanchen.veritycustomapi.compat.OriginalVerityConfigSync;
import com.hanchen.veritycustomapi.compat.OriginalVeritySttInjector;
import com.hanchen.veritycustomapi.compat.RuntimePlatform;
import com.hanchen.veritycustomapi.compat.BilingualSpeechText;
import com.hanchen.veritycustomapi.compat.OverheadOnlySpeechText;
import com.hanchen.veritycustomapi.compat.VeritySubtitleManager;
import com.hanchen.veritycustomapi.compat.VerityGrowthState;
import com.hanchen.veritycustomapi.compat.VerityPatPatCompat;
import com.hanchen.veritycustomapi.skin.SkinManager;
import com.hanchen.veritycustomapi.stt.SpeechRecognitionManager;
import com.hanchen.veritycustomapi.stt.VoskModelInstaller;
import com.hanchen.veritycustomapi.tts.TtsChatListener;
import com.hanchen.veritycustomapi.tts.TtsManager;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.Util;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.ClientChatEvent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

@Mod.EventBusSubscriber(
        modid = OllamaProxyMod.MOD_ID,
        value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD
)
public class ClientEventHandler {
    private static boolean wasSpeechKeyPressed = false;
    private static boolean legacySpeechKeyChecked = false;
    private static int cloudAuthGuardTicks;
    private static String lastTitleStatusToast = null;

    /** 供配置界面在授权成功后启动全局守护。 */
    public static void beginCloudAuthGuard() {
        ForgeBusEvents.beginCloudAuthGuard();
    }
    private static volatile String pendingBuiltinMimoVoice;
    private static volatile long clientSessionGeneration;

    @SuppressWarnings("removal")
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            ModLoadingContext.get().registerExtensionPoint(
                        ConfigScreenHandler.ConfigScreenFactory.class,
                        () -> new ConfigScreenHandler.ConfigScreenFactory(
                                (Minecraft mc, Screen parentScreen) ->
                                        new NativeOllamaProxyConfigScreen(parentScreen)
                        )
                );
            SkinManager.getInstance().init();
            VerityPatPatCompat.isAvailable();
        });

        // 启动时扫描 MMD 资产目录
        event.enqueueWork(() -> com.hanchen.veritycustomapi.mmd.MmdPlaybackManager.get().scanAndLoad());

        event.enqueueWork(() -> {
            OriginalVerityConfigInjector.inject();
            SpeechRecognitionManager.INSTANCE.init();
            TtsManager.init();
            CloudConfigManager.startClient(Minecraft.getInstance()::execute, ClientEventHandler::reloadCloudStt);
        });

        OllamaProxyMod.LOGGER.info("客户端模块初始化完成");
    }

    /** 注册游戏内 HUD：对话气泡、状态角标与导航箭头。 */
    @SubscribeEvent
    public static void onRegisterGuiOverlays(RegisterGuiOverlaysEvent event) {
        event.registerAboveAll("verity_extended_hud", VerityHudOverlay.HUD);
        event.registerAboveAll("verity_horror", VerityHorrorDirector.OVERLAY);
    }

    // Forge 运行时总线：处理按键、聊天这些实时事件
    @Mod.EventBusSubscriber(
            modid = OllamaProxyMod.MOD_ID,
            value = Dist.CLIENT,
            bus = Mod.EventBusSubscriber.Bus.FORGE
    )
    public static class ForgeBusEvents {
        @SubscribeEvent
        public static void onClientLogin(ClientPlayerNetworkEvent.LoggingIn event) {
            long sessionGeneration = ++clientSessionGeneration;
            OriginalVerityConfigInjector.inject();
            OriginalVeritySttInjector.inject();
            OriginalVeritySttInjector.startSession(sessionGeneration);
            CloudConfigManager.startClient(Minecraft.getInstance()::execute, ClientEventHandler::reloadCloudStt);
            OllamaProxyServer.start(ConfigManager.getPort());
            SpeechRecognitionManager.INSTANCE.startSession(sessionGeneration);
            TtsManager.startSession(sessionGeneration);
            VerityAwarenessManager.onLogin();
            // 进服时再次扫描 MMD 资产目录（兜底：FMLClientSetupEvent 阶段过早就被跳过/路径未就绪的情况）
            com.hanchen.veritycustomapi.mmd.MmdPlaybackManager.get().scanAndLoad();
        }

        @SubscribeEvent
        public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
            com.hanchen.veritycustomapi.mmd.MmdPlaybackManager.get().stopPlaybackForAllDemons();
            long sessionGeneration = ++clientSessionGeneration;
            VerityAwarenessManager.onLogout();
            try {
                com.hanchen.veritycustomapi.memory.MemoryAutoWriter.resetState();
                com.hanchen.veritycustomapi.memory.MemoryMetaStore.clear();
            } catch (Throwable error) {
                // 运行中替换 jar 时，惰性加载的类可能抛 NoClassDefFoundError（Error，不是 Exception）。
                // 清理失败不应把退出世界变成崩溃；正常启动时不会走到这里。
                OllamaProxyMod.LOGGER.warn("退出世界时清理记忆状态失败（可忽略）: {}", error.toString());
            }
            VerityDialogueFeed.clear();
            ReminderManager.clear();
            OriginalVeritySttInjector.endSession(sessionGeneration);
            SpeechRecognitionManager.INSTANCE.shutdown();
            TtsManager.shutdown();
            VeritySubtitleManager.clear();
            VerityGrowthState.clearClient();
            VerityHorrorDirector.reset();
            CloudConfigManager.stopClient();
            OllamaProxyServer.stop();
        }

        /**
         * 语音结果由 VerityChatSender 发 /v，语音键保持 V。
         * 只做一次性迁移：若之前被改成 CAPS LOCK，恢复为 V；用户自定义键不覆盖。
         */
        private static void restoreLegacySpeechKey(Minecraft mc) {
            if (legacySpeechKeyChecked) return;
            legacySpeechKeyChecked = true;
            try {
                if (mc.options == null || mc.gameDirectory == null) return;
                if (KeyBindings.SPEECH_KEY.getKey().getType() != InputConstants.Type.KEYSYM) return;
                int key = KeyBindings.SPEECH_KEY.getKey().getValue();
                if (key != GLFW.GLFW_KEY_V && key != GLFW.GLFW_KEY_CAPS_LOCK) return;
                Path marker = mc.gameDirectory.toPath()
                        .resolve("config").resolve("verity_extended")
                        .resolve("speech-key-v.flag");
                if (Files.exists(marker)) return;

                if (key == GLFW.GLFW_KEY_CAPS_LOCK) {
                    KeyBindings.SPEECH_KEY.setKey(
                            InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_V));
                    KeyMapping.resetMapping();
                    mc.options.save();
                    OllamaProxyMod.LOGGER.info("[按键] 语音键恢复为 V");
                }
                Files.createDirectories(marker.getParent());
                Files.writeString(marker, "Speech key keeps V.\n");
            } catch (Throwable t) {
                OllamaProxyMod.LOGGER.warn("[按键] 恢复语音键失败: {}", t.getMessage());
            }
        }

        /** 授权成功后启动 5 秒全局守护，即使配置界面已关闭也生效。 */
        public static void beginCloudAuthGuard() {
            cloudAuthGuardTicks = 100;
        }

        private static void tickCloudAuthGuard() {
            if (cloudAuthGuardTicks <= 0) return;
            cloudAuthGuardTicks--;
            if (ConfigManager.getCloudApiKey().isBlank()) return;
            if (!ModConfig.CLOUD_ENABLE.get()) {
                ModConfig.CLOUD_ENABLE.set(true);
                try {
                    ConfigManager.saveCommonConfig();
                } catch (RuntimeException error) {
                    OllamaProxyMod.LOGGER.warn("云端授权守护保存失败: {}", error.getMessage());
                }
                OllamaProxyMod.LOGGER.info("云端授权守护：cloud.enable 被改回 false，已恢复 true");
            }
            if (!CloudConfigManager.isActive()) {
                try {
                    syncCloudConfigNow(success -> {});
                } catch (RuntimeException error) {
                    OllamaProxyMod.LOGGER.warn("云端授权守护重启线程失败: {}", error.getMessage());
                }
            }
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getInstance();
            restoreLegacySpeechKey(mc);
            tickCloudAuthGuard();
            com.hanchen.veritycustomapi.mmd.MmdPlaybackManager.get().tickClient();
            VerityAwarenessManager.onClientTick(mc);
            ReminderManager.onClientTick(mc);
            VerityNavigationService.onClientTick(mc);
            com.hanchen.veritycustomapi.tts.VanillaAudioSink.tickClient(mc);

            if (mc.player != null && mc.level != null && mc.screen == null
                    && KeyBindings.MMD_CONTROL_KEY.consumeClick()) {
                // 多人游戏下 MMD 已禁用：给一句明确提示，而不是打开一个什么都做不了的界面
                if (!com.hanchen.veritycustomapi.mmd.MmdPlaybackManager.isPlaybackAvailable()) {
                    mc.player.displayClientMessage(Component.literal(
                            com.hanchen.veritycustomapi.mmd.MmdPlaybackManager.MULTIPLAYER_DISABLED_MESSAGE), false);
                    return;
                }
                mc.setScreen(new MmdPlaybackScreen(null));
                return;
            }

            // K 键：快捷指令面板
            if (mc.player != null && mc.level != null && mc.screen == null
                    && KeyBindings.QUICK_COMMAND_KEY.consumeClick()) {
                mc.setScreen(new QuickCommandScreen());
                return;
            }

            // O 键：让 Verity 看一眼摄像头
            if (mc.player != null && mc.level != null && mc.screen == null
                    && KeyBindings.CAMERA_LOOK_KEY.consumeClick()) {
                VerityAwarenessManager.triggerCameraLook();
                return;
            }

            // H 键：让 Verity 站到头上 / 从头上下来
            if (mc.player != null && mc.level != null && mc.screen == null
                    && KeyBindings.HEAD_PERCH_KEY.consumeClick()) {
                com.hanchen.veritycustomapi.network.HeadPerchNetwork.sendToggle();
                return;
            }

            // SttInjector 已劫持 Verity 的 PUSH_TO_TALK(V)，无需重复处理
            if (OriginalVeritySttInjector.isActive()) {
                wasSpeechKeyPressed = false;
                return;
            }

            // 非游戏内界面时不响应语音按键
            if (mc.player == null || mc.screen != null) {
                wasSpeechKeyPressed = KeyBindings.SPEECH_KEY.isDown();
                return;
            }

            boolean isPressed = KeyBindings.SPEECH_KEY.isDown();
            // 按键按下：启动录音
            if (isPressed && !wasSpeechKeyPressed) {
                SpeechRecognitionManager.INSTANCE.onKeyPressed();
            }
            // 按键松开：结束录音并识别
            if (!isPressed && wasSpeechKeyPressed) {
                SpeechRecognitionManager.INSTANCE.onKeyReleased();
            }
            wasSpeechKeyPressed = isPressed;
        }

        /** 玩家出手即进入战斗静默窗口，避免打怪时被主动感知打断。 */
        @SubscribeEvent
        public static void onInteractionKeyMappingTriggered(InputEvent.InteractionKeyMappingTriggered event) {
            if (event.isAttack()) {
                VerityAwarenessManager.onPlayerAttack();
            }
        }

        /** 玩家发出聊天时顺便识别「N 分钟后提醒我」这类定时提醒请求。 */
        @SubscribeEvent
        public static void onClientChatSend(ClientChatEvent event) {
            if (!ConfigManager.isReminderEnabled()) {
                return;
            }
            ReminderManager.tryScheduleFromMessage(event.getMessage());
        }

        @SubscribeEvent
        public static void onClientChatReceived(ClientChatReceivedEvent event) {
            String messageText = event.getMessage().getString();
            // 原版 AI 的英文报错对小白不友好：换成中文指引，教程提示直接隐藏
            if (friendlyAiError(event, messageText)) {
                return;
            }
            // HUD 对话气泡：识别 Verity 的发言并缓存最近一句
            VerityDialogueFeed.captureFromChat(messageText);
            // 摄像头技能：代理层判定这一句只是"请求观察"，直接隐藏，只展示看图后的回复
            if (messageText.contains(OllamaProxyServer.CAMERA_HOLD_MARKER)) {
                event.setCanceled(true);
                return;
            }
            // 流式等路径下标记仍在正文里的情况：隐藏该句并开始观察
            CameraChatVision.SkillDisplayResult skill = CameraChatVision.handleDisplayedSkillRequest(messageText);
            if (skill.cancel()) {
                event.setCanceled(true);
                return;
            }
            if (!skill.text().equals(messageText)) {
                event.setMessage(Component.literal(skill.text()).withStyle(event.getMessage().getStyle()));
                messageText = skill.text();
            }
            OverheadOnlySpeechText.Parts display = OverheadOnlySpeechText.decode(messageText);
            if (display.overheadOnly()) {
                event.setCanceled(true);
                return;
            }
            BilingualSpeechText.Parts parts = BilingualSpeechText.decode(display.text());
            if (parts.bilingual()) {
                // 双语模式：聊天栏显示中文（带 [译] 标记），英文只用于朗读。
                // 前缀（<名字> 与多人的 @玩家名:）从原文里原样保留。
                event.setMessage(Component.literal(BilingualSpeechText.toChineseDisplay(display.text()))
                        .withStyle(event.getMessage().getStyle()));
                return;
            }
            // 无论双语开关是否仍打开，都不能把内部协议分隔符显示给玩家。
            // 流式中间片段可能只剩半截标记，至少先剥掉它，不让 <|verity_zh|> 泄漏到聊天栏。
            String sanitized = BilingualSpeechText.stripDelimiter(display.text());
            if (!sanitized.equals(display.text())) {
                event.setMessage(Component.literal(sanitized).withStyle(event.getMessage().getStyle()));
                messageText = sanitized;
            }
            TtsChatListener.handleChatMessage(messageText);
        }

        /** 原版 AI 报错/教程提示转小白友好的中文指引；命中返回 true。 */
        private static boolean friendlyAiError(ClientChatReceivedEvent event, String messageText) {
            if (messageText == null || messageText.isBlank()) {
                return false;
            }
            if (messageText.contains("Problem setting up AI")) {
                event.setMessage(Component.literal(
                        "【提示】Verity 的 AI 功能没有启动成功（常见原因：接口配置有误，或装了冲突的模组）。"
                                + "打开「Verity 扩展增强」的设置 →「服务连接检查」→「重新检查」按提示修复即可；"
                                + "站头、骑乘、语音等本地功能不受影响。"));
                return true;
            }
            if (messageText.strip().equals("Setup Tutorial")) {
                event.setCanceled(true);
                return true;
            }
            // 原版的报错有三种前缀形态，只匹配 "> ERROR:" 会漏掉带 @玩家名 的那种：
            //   "> ERROR: ..."         单人
            //   "@玩家名: ERROR: ..."  多人（截图里就是这种，之前漏了）
            //   "ERROR: ..."           无前缀
            // 所以判据放宽成「含 ERROR: 且整条不长」——报错都很短，
            // 正常台词里出现 ERROR: 且短于 200 字符的情况实际上不存在。
            String stripped = messageText.strip();
            boolean looksLikeError = stripped.contains("ERROR:")
                    && stripped.length() <= 200;
            if (looksLikeError) {
                event.setMessage(Component.literal(
                        "【提示】Verity 的 AI 刚才报了一个错，已写进日志。偶尔一次可以忽略；"
                                + "反复出现的话，到设置 →「服务连接检查」里点「重新检查」，"
                                + "或「导出诊断包」发给作者查看。"));
                return true;
            }
            return false;
        }

        private static boolean cloudWarningShown = false;

        @SubscribeEvent
        public static void onScreenInit(ScreenEvent.Init.Post event) {
            if (event.getScreen() instanceof TitleScreen titleScreen) {
                maybeShowTitleStatusToast(titleScreen);
                maybeShowCloudConfigWarning(titleScreen);
                return;
            }
            if (!RuntimePlatform.isAndroidRuntime() && isVerityConfigScreen(event.getScreen())) {
                event.addListener(Button.builder(
                                Component.literal("安装离线中文模型"),
                                button -> VoskModelInstaller.installAsync(() -> {
                                    SpeechRecognitionManager.INSTANCE.onConfigSaved();
                                    Minecraft minecraft = Minecraft.getInstance();
                                    if (minecraft != null) {
                                        minecraft.execute(() -> minecraft.setScreen(new NativeOllamaProxyConfigScreen(event.getScreen())));
                                    }
                                })
                        )
                        .bounds(event.getScreen().width - 154, 6, 150, 20)
                        .build());
            }
        }

        private static boolean isVerityConfigScreen(Screen screen) {
            return screen != null
                    && screen.getClass().getName().contains("clothconfig2")
                    && screen.getTitle().getString().contains("Verity");
        }

        private static void maybeShowTitleStatusToast(Screen screen) {
            if (!(screen instanceof TitleScreen)) {
                return;
            }
            if (RuntimePlatform.isAndroidRuntime()) return;

            Minecraft mc = screen.getMinecraft();
            if (mc == null) {
                return;
            }

            if (VoskModelInstaller.isInstalling()) {
                showTitleStatusToast(mc, "正在安装离线语音模型", VoskModelInstaller.getLastStatus());
            }
        }

        private static void showTitleStatusToast(Minecraft mc, String title, String status) {
            String toastKey = title + "|" + status;
            if (toastKey.equals(lastTitleStatusToast)) {
                return;
            }
            lastTitleStatusToast = toastKey;
            try {
                SystemToast.add(
                        mc.getToasts(),
                        SystemToast.SystemToastIds.PERIODIC_NOTIFICATION,
                        Component.literal("[Verity] " + title),
                        Component.literal(status)
                );
            } catch (Throwable t) {
                OllamaProxyMod.LOGGER.warn("主菜单离线语音状态提示显示失败: {}", status, t);
            }
        }

        private static void maybeShowCloudConfigWarning(TitleScreen titleScreen) {
            if (cloudWarningShown) return;
            if (!ConfigManager.isCloudEnabled()) return;

            String apiUrl = ConfigManager.getCloudApiUrl();
            String apiKey = ConfigManager.getCloudApiKey();
            if ((apiUrl != null && !apiUrl.isBlank()) && (apiKey != null && !apiKey.isBlank())) {
                return; // 已配置完整，无需提示
            }

            cloudWarningShown = true;
            Minecraft mc = titleScreen.getMinecraft();
            if (mc == null) return;

            ConfirmScreen confirmScreen = new ConfirmScreen(
                    result -> {
                        if (result) {
                            // 用户点击"是"：打开云端配置网站
                            try {
                                Util.getPlatform().openUri("https://verity.xrstudio.cn/get-started/");
                            } catch (Throwable t) {
                                OllamaProxyMod.LOGGER.warn("无法打开云端配置网站: {}", t.getMessage());
                            }
                        }
                        // 点「稍后」或直接按 ESC 都不能关闭云端开关：
                        // ConfirmScreen.onClose() 会回调 false，以前这里把 false 当成「否」并写盘，
                        // 结果玩家只想跳过提示，云端配置却被永久关掉了。
                        mc.setScreen(titleScreen);
                    },
                    Component.literal("云端配置"),
                    Component.literal("检测到云端配置未完成。\n\n未配置云端将无法使用内置 API，\n请点击「是」前往网站获取配置密钥，\n或点击「稍后」先使用本地配置。"),
                    Component.literal("是"),
                    Component.literal("稍后")
            );
            mc.setScreen(confirmScreen);
        }
    }

    public static void setPendingBuiltinMimoVoice(String voice) {
        OllamaProxyMod.LOGGER.info("[CONFIG-DEBUG] setPendingBuiltinMimoVoice 被调用: voice={}", voice);
        pendingBuiltinMimoVoice = voice;
    }

    /**
     * 配置保存后调用，刷新各模块运行时状态
     */
    public static void onConfigSaved() {
        if (ModConfig.INSTALL_VOSK_MODEL.get()) {
            ModConfig.INSTALL_VOSK_MODEL.set(false);
            // Android/FCL 不支持 Vosk，跳过安装
            if (!RuntimePlatform.isAndroidRuntime()) {
                VoskModelInstaller.installAsync(() -> {
                    SpeechRecognitionManager.INSTANCE.onConfigSaved();
                });
            }
        }
        String mimoVoice = pendingBuiltinMimoVoice;
        pendingBuiltinMimoVoice = null;
        // 云端配置不覆盖本地用户选择，pending 覆盖机制已禁用；
        // 界面里的选择通过 ModConfig.BUILTIN_MIMO_TTS_VOICE.set() 直接生效
        OriginalVerityConfigSync.applyPendingChanges();
        OriginalVerityConfigInjector.inject();
        OriginalVeritySttInjector.onConfigSaved();
        OllamaProxyServer.start(ConfigManager.getPort());
        SpeechRecognitionManager.INSTANCE.onConfigSaved();
        TtsManager.shutdown();
        TtsManager.startSession(clientSessionGeneration);
        CloudConfigManager.onConfigSaved();
        VerityAwarenessManager.onConfigSaved();
        SkinManager.getInstance().reloadActiveSkin();
        String presetName = ConfigManager.getSelectedPresetName();
        String prompt = ConfigManager.getEffectiveSystemPrompt();
        OllamaProxyMod.LOGGER.info("配置已保存，当前人设: {}，提示词长度: {} 字符", 
                presetName, prompt != null ? prompt.length() : 0);
    }

    public static void syncCloudConfigNow(Consumer<Boolean> completion) {
        Minecraft minecraft = Minecraft.getInstance();
        CloudConfigManager.startClient(
                minecraft::execute,
                ClientEventHandler::reloadCloudStt,
                success -> minecraft.execute(() -> completion.accept(success))
        );
    }

    private static void reloadCloudStt() {
        OriginalVeritySttInjector.onConfigSaved();
        SpeechRecognitionManager.INSTANCE.onConfigSaved();
    }
}
