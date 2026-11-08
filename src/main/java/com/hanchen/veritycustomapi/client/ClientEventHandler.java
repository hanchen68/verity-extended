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
import com.hanchen.veritycustomapi.compat.VeritySpeaker;
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
                                        com.hanchen.veritycustomapi.config.SettingsExperience.open(parentScreen)
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
        /**
         * 配置界面的说明浮层画在所有内容之上。
         *
         * <p>🔴 这是**唯一**能保证「最后画」的位置：Forge 的
         * {@code ForgeHooksClient.drawScreenInternal} 顺序是
         * {@code Render.Pre → renderWithTooltip → Render.Post}，
         * {@code Post} 在界面自身 {@code render()} 和原生 tooltip 都结束之后才发。
         * 放在界面 {@code render()} 末尾画，实测仍会被行文字盖住。
         */
        @SubscribeEvent
        public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
            if (event.getScreen() instanceof com.hanchen.veritycustomapi.config.NativeOllamaProxyConfigScreen screen) {
                screen.renderHintOverlay(event.getGuiGraphics(), event.getMouseX(), event.getMouseY());
            }
        }

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
            // 站头状态按实体 id 存：实体 id 每个存档从 0 开始，不清会串到下一个存档
            com.hanchen.veritycustomapi.ride.ClientPerchState.clear();
            // 自主状态也按实体 id 存，同样会串存档
            com.hanchen.veritycustomapi.autonomy.ClientAutonomyState.clear();
            com.hanchen.veritycustomapi.combat.GuardianRegistry.clear();
            com.hanchen.veritycustomapi.client.BallAnimator.clear();
            com.hanchen.veritycustomapi.client.GuiCompanion.clear();
            com.hanchen.veritycustomapi.client.SecondSightManager.clear();
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
         * 桌面端：把旧的「V 键语音」迁移到不冲突的备用键。
         *
         * <p>历史原因：早期扩展自己管语音，默认键就是 V；后来接进原版
         * {@code PUSH_TO_TALK}（同样默认 V），两条路径撞在同一个键上，
         * 按键设置里就会出现红色「该按键也用于」提示，而且旧存档仍把扩展键固定成 V。
         *
         * <p>🔴 标记文件用 <b>v2</b>：上一版 bug 版已经写过一次
         * {@code speech-key-conflict.flag}（当时桌面端默认键恰好是 B，而 B 已被
         * quick_command 占用，等于没修还留了个「已完成」标记），
         * 沿用旧名会让迁移从此永久跳过——玩家看到的就是「装了新版但 V 还在冲突」。
         *
         * <p>🔴 <b>手机端绝对不能迁移</b>：原版注入在 Android 上会主动跳过
         * （见 {@code OriginalVeritySttInjector.inject()}），手机没有可用的原版录音链，
         * 扩展自己的键是唯一入口。把 V 迁走等于让手机玩家再也发不了语音。
         * 玩家自己改过的其它键一律不动。
         */
        private static void restoreLegacySpeechKey(Minecraft mc) {
            // 🔴 只有在「确实做出了决定」之后才能置位。
            // 原来第一行就置位，然后才判 options/手机端/按键值：
            // 首帧 options 还没就绪时会走 return，而标记已经消耗掉，
            // 之后每 tick 都直接返回——迁移永远不会再跑（本次实际踩到的坑）。
            if (legacySpeechKeyChecked) return;
            try {
                if (mc.options == null || mc.gameDirectory == null) return;
                // 手机端保持原样：唯一入口不能动；这也是最终决定，可以置位
                if (com.hanchen.veritycustomapi.compat.RuntimePlatform.isAndroidRuntime()) {
                    legacySpeechKeyChecked = true;
                    return;
                }
                boolean keysym = KeyBindings.SPEECH_KEY.getKey().getType() == InputConstants.Type.KEYSYM;
                int key = KeyBindings.SPEECH_KEY.getKey().getValue();
                if (!keysym) {
                    legacySpeechKeyChecked = true;
                    return;
                }
                // 只有「还是历史默认值」才迁移；玩家手动设的其它键完全不动
                if (key != GLFW.GLFW_KEY_V && key != GLFW.GLFW_KEY_CAPS_LOCK) {
                    legacySpeechKeyChecked = true;
                    return;
                }
                Path marker = mc.gameDirectory.toPath()
                        .resolve("config").resolve("verity_extended")
                        .resolve("speech-key-conflict-v2.flag");
                if (Files.exists(marker)) {
                    legacySpeechKeyChecked = true;
                    return;
                }

                // 🔴 注入是在「进世界」时做的（onClientLogin），而主菜单阶段本 tick 已经在跑，
                // 那时 isActive() 一定是 false。所以这里不能直接判定「原版不可用」，
                // 只有等到玩家真的进了世界（player != null）之后才做最终决定。
                if (mc.player == null) return;
                if (!OriginalVeritySttInjector.isActive()) {
                    // 进了世界注入仍未成功 -> 原版链路不可用，保留 V（此时它是唯一入口）
                    OllamaProxyMod.LOGGER.info("[按键] 原版 STT 链路不可用，语音键保留 V 作为唯一入口");
                    legacySpeechKeyChecked = true;
                    return;
                }

                // 目标键必须真的空闲：写死一个键迟早和别人撞（B 就被快捷指令面板占了）。
                int target = findFreeSpeechKey();
                KeyBindings.SPEECH_KEY.setKey(
                        InputConstants.Type.KEYSYM.getOrCreate(target));
                KeyMapping.resetMapping();
                mc.options.save();
                OllamaProxyMod.LOGGER.info("[按键] 桌面端语音键已从 V 迁到 {}（避开原版 push-to-talk 与其它已用键）",
                        KeyBindings.SPEECH_KEY.getTranslatedKeyMessage().getString());
                Files.createDirectories(marker.getParent());
                Files.writeString(marker,
                        "Desktop: speech key moved off V so the vanilla push-to-talk keeps it."
                                + System.lineSeparator());
                // 真正处理完才置位
                legacySpeechKeyChecked = true;
            } catch (Throwable t) {
                OllamaProxyMod.LOGGER.warn("[按键] 语音键迁移失败: {}", t.getMessage());
            }
        }

        /**
         * 找一个当前没人用的字母键给语音用。
         *
         * <p>为什么不写死：写死过的两个值都撞了——V 撞原版 push-to-talk，
         * B 撞快捷指令面板。玩家的 options.txt 还会自己改键，静态值永远追不上。
         * 这里按「扩展自己的键 + 原版 Verity 的键」逐个排除，只留真正空闲的。
         *
         * @return 可用的 GLFW 键值；实在找不到就退回 J
         */
        private static int findFreeSpeechKey() {
            java.util.Set<Integer> used = new java.util.HashSet<>();
            try {
                for (KeyMapping mapping : Minecraft.getInstance().options.keyMappings) {
                    if (mapping == KeyBindings.SPEECH_KEY) continue;
                    if (mapping.getKey().getType() == InputConstants.Type.KEYSYM) {
                        used.add(mapping.getKey().getValue());
                    }
                }
            } catch (Throwable ignored) {
                // 拿不到按键表就按下面的候选顺序走，最坏情况是多一次冲突提示，不影响功能
            }
            // 优先顺序：离家近、适合长按的字母键
            int[] preferred = {GLFW.GLFW_KEY_J, GLFW.GLFW_KEY_B, GLFW.GLFW_KEY_U,
                    GLFW.GLFW_KEY_Y, GLFW.GLFW_KEY_G, GLFW.GLFW_KEY_L};
            for (int candidate : preferred) {
                if (!used.contains(candidate)) return candidate;
            }
            return GLFW.GLFW_KEY_J;
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
            SecondSightManager.onClientTick(mc);
            ReminderManager.onClientTick(mc);
            VerityNavigationService.onClientTick(mc);
            com.hanchen.veritycustomapi.tts.VanillaAudioSink.tickClient(mc);

            // J 键：界面里的 Verity 开关
            if (mc.player != null && mc.level != null && mc.screen == null
                    && KeyBindings.GUI_COMPANION_KEY.consumeClick()) {
                GuiCompanion.toggle();
                mc.player.displayClientMessage(Component.literal(
                        GuiCompanion.isEnabled() ? "界面陪伴：开" : "界面陪伴：关"), true);
            }

            // R 键：第二视角
            if (mc.player != null && mc.level != null && mc.screen == null
                    && KeyBindings.SECOND_SIGHT_KEY.consumeClick()) {
                if (SecondSightManager.isActive()) {
                    SecondSightManager.stop();
                    mc.player.displayClientMessage(Component.literal("已退出第二视角"), true);
                } else if (SecondSightManager.toggle(mc)) {
                    mc.player.displayClientMessage(Component.literal(
                            "第二视角：她看到的 —— 再按 R 退出"), true);
                } else {
                    mc.player.displayClientMessage(Component.literal("附近找不到 Verity"), true);
                }
            }

            if (mc.player != null && mc.level != null && mc.screen == null
                    && KeyBindings.MMD_CONTROL_KEY.consumeClick()) {
                // 多人游戏下 MMD 已禁用：给一句明确提示，而不是打开一个什么都做不了的界面
                if (!com.hanchen.veritycustomapi.mmd.MmdPlaybackManager.isPlaybackAvailable()) {
                    mc.player.displayClientMessage(Component.literal(
                            com.hanchen.veritycustomapi.mmd.MmdPlaybackManager.MULTIPLAYER_DISABLED_MESSAGE), false);
                    return;
                }
                mc.setScreen(new MmdActionWheelScreen());
                return;
            }

            // K 键：快捷指令面板
            if (mc.player != null && mc.level != null && mc.screen == null
                    && KeyBindings.QUICK_COMMAND_KEY.consumeClick()) {
                mc.setScreen(new QuickCommandScreen(true));
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

            // R 键：骑上 / 从 Verity 身上下来。
            // 骑乘不能继续用「潜行 + 空手右键」——那是 PatPat 的抚摸手势，
            // 而且我们必须在 HIGHEST 优先级取消右键才能拦住原版把 Verity 收进物品，
            // 一取消抚摸就永远收不到。两边抢同一个手势只能有一个生效，所以骑乘独立成键。
            if (mc.player != null && mc.level != null && mc.screen == null
                    && KeyBindings.RIDE_KEY.consumeClick()) {
                com.hanchen.veritycustomapi.ride.RideKeyHandler.onRideKey(mc);
                return;
            }

            // 原版 PUSH_TO_TALK 已被劫持时，语音完全走那条链路（原版默认 V）。
            // 此时扩展自己的备用键不参与录音——否则一次按键会开两条录音链，
            // 抢麦克风并产生两份识别结果。
            if (OriginalVeritySttInjector.isActive()) {
                wasSpeechKeyPressed = false;
                return;
            }

            // 走到这里说明原版链路不可用（未装 Verity / 版本字段变了 / 手机端跳过注入），
            // 备用键是此时唯一的语音入口。
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
            // 双语开着却没有译文：只显示英文本身没问题，但玩家会以为双语坏了。
            // 每分钟最多提示一次（同一句反复出现时提示会刷屏），并且只在真是 Verity 台词时提示。
            noteMissingTranslation(messageText);
            TtsChatListener.handleChatMessage(messageText);
        }

        private static boolean cloudWarningShown = false;

        /**
         * 双语开启但这一句没有中文时，给玩家一次说明（每分钟最多一次）。
         *
         * <p>为什么需要：翻译失败或预算不足时代理会静默退化成「只显示英文」，
         * 玩家看到满屏英文，只会认为「双语模式坏了/不稳定」，而真实原因（额度、超时、
         * 关闭了二次翻译）完全没露出来。
         *
         * <p>为什么限流：同一条台词（例如待机口头禅）会反复刷新，每次都提示会刷满聊天栏。
         */
        private static long lastMissingTranslationNoticeAt = 0L;

        private static void noteMissingTranslation(String rawMessage) {
            if (!ConfigManager.isBilingualSpeechSubtitlesEnabled()) return;
            if (rawMessage == null || rawMessage.isBlank()) return;
            // 只针对 Verity 自己的台词；玩家聊天不该触发这个提示
            String body = VeritySpeaker.stripPrefix(rawMessage);
            if (body == null || body.isBlank()) return;
            if (BilingualSpeechText.translatedContent(rawMessage) != null) return;
            // 原文本来就是中文（模型没按英文回答）不算「缺译文」
            if (BilingualSpeechText.isMostlyChinese(rawMessage)) return;
            long now = System.currentTimeMillis();
            if (now - lastMissingTranslationNoticeAt < 60_000L) return;
            lastMissingTranslationNoticeAt = now;
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null && minecraft.player != null) {
                minecraft.player.displayClientMessage(Component.literal(
                        ConfigManager.isBilingualSecondPassEnabled()
                                ? "§7【双语】这句没拿到中文译文，已只显示英文（可能触发了额度或超时限制）"
                                : "§7【双语】二次翻译已关闭，模型没给中文时只显示英文"), false);
            }
        }

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
                                        minecraft.execute(() -> minecraft.setScreen(com.hanchen.veritycustomapi.config.SettingsExperience.open(event.getScreen())));
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

            net.minecraft.client.gui.screens.Screen confirmScreen =
                    new com.hanchen.veritycustomapi.config.VerityConfirmScreen(
                    titleScreen,
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
