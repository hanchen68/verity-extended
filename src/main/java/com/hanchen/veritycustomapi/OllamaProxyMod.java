package com.hanchen.veritycustomapi;

import com.hanchen.veritycustomapi.action.GameActionHandler;
import com.hanchen.veritycustomapi.action.AiBuildingManager;
import com.hanchen.veritycustomapi.cloud.CloudConfigManager;
import com.hanchen.veritycustomapi.compat.DemonRecallService;
import com.hanchen.veritycustomapi.compat.OriginalVerityConfigInjector;
import com.hanchen.veritycustomapi.compat.VerityFeedingEvents;
import com.hanchen.veritycustomapi.compat.VerityGrowthState;
import com.hanchen.veritycustomapi.config.ModConfig;
import com.hanchen.veritycustomapi.debug.ConnectionTestHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod("verity_extended")
@SuppressWarnings({"unused", "SpellCheckingInspection"})
public class OllamaProxyMod {
    public static final String MOD_ID = "verity_extended";
    private static final String FALLBACK_VERSION = "5.0";
    public static final Logger LOGGER = LogManager.getLogger();

    /** 实际加载的模组版本；独立测试未启动 Forge 时回退到当前发布版本。 */
    public static String version() {
        try {
            return ModList.get().getModContainerById(MOD_ID)
                    .map(container -> container.getModInfo().getVersion().toString())
                    .filter(value -> !value.isBlank())
                    .orElse(FALLBACK_VERSION);
        } catch (Throwable ignored) {
            return FALLBACK_VERSION;
        }
    }

    /**
     * 注册 JVM 关闭钩子：退出前最后验一次两份 TOML，不完整就用快照恢复。
     *
     * <p>🔴 为什么不能只挂在 {@code ServerStoppingEvent} 上：那个事件只在
     * **离开世界**时触发。实测过一次「从主菜单直接退出游戏」——
     * 日志里连 `代理服务器已关闭` 都没有，说明整个 {@code onServerStopping}
     * 根本没执行，挂在里面的校验自然也没跑。
     *
     * <p>而写坏配置的动作恰好发生在退出瞬间（实测 `Stopping!` 之后 15~31 毫秒）。
     * 关闭钩子是 JVM 层面唯一能保证「不管从哪条路退出都会执行」的时机，
     * 所以这里必须用钩子，不能依赖 Forge 的事件。
     *
     * <p>钩子里只做文件读写（几 KB），不碰 Minecraft 的任何状态——
     * 退出阶段游戏对象可能已经处于半销毁状态，碰了会抛。
     */
    private static void registerConfigShutdownVerify() {
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    com.hanchen.veritycustomapi.config.ForgeConfigRepair.verifyOnShutdown();
                } catch (Throwable ignored) {
                    // 退出路径上任何异常都不能影响进程结束
                }
            }, "verity-config-verify"));
        } catch (Throwable error) {
            LOGGER.warn("注册配置关闭校验钩子失败（退出时不做配置自检）: {}", error.toString());
        }
    }

    static {
        // 在 JNA 初始化之前设置编码，避免 Vosk 离线识别中文乱码
        // 用户无需再手动配置 -Djna.encoding=UTF-8 虚拟机参数
        System.setProperty("jna.encoding", "UTF-8");
        // 修复 Windows 中文环境下 Forge 读取 UTF-8 TOML 配置文件的编码问题
        System.setProperty("file.encoding", "UTF-8");
    }

    public OllamaProxyMod() {
        // Forge 要到 ModLoading 阶段才真正读配置文件，先在这里把重复项洗干净，避免一开局就崩
        com.hanchen.veritycustomapi.config.ForgeConfigRepair.repairIfNeeded();
        // 注册配置
        ConfigManager.registerAllConfigs();
        registerConfigShutdownVerify();
        com.hanchen.veritycustomapi.cloud.BillingService.initialize();
        VerityGrowthState.register();
        VerityFeedingEvents.register();
        com.hanchen.veritycustomapi.gift.VerityGiftEvents.register();
        com.hanchen.veritycustomapi.compat.VerityKickEvents.register();
        com.hanchen.veritycustomapi.network.VerityPatNetwork.register();
        com.hanchen.veritycustomapi.network.HorrorEventNetwork.register();
        DemonRecallService.register();
        com.hanchen.veritycustomapi.compat.CrossSavePursuit.register();
        MinecraftForge.EVENT_BUS.register(VerityGrowthState.class);

        // 注册事件总线，监听服务器启停
        MinecraftForge.EVENT_BUS.register(this);
        // 骑乘已改成独立按键（默认 R），不再拦截右键：
        // 「潜行 + 空手右键」和 PatPat 的抚摸手势完全重合，拦截右键等于让抚摸永远失效。

        // MMD 按需生成恶魔 + 永久禁用玩家目标的恶魔友好模式
        com.hanchen.veritycustomapi.network.MmdDemonNetwork.register();
        // 站头/骑乘的网络通道
        com.hanchen.veritycustomapi.network.HeadPerchNetwork.register();
        // Verity 手持物品（星之遥控器）的同步通道
        com.hanchen.veritycustomapi.network.VerityHoldNetwork.register();
        // 物品索引必须等注册事件全部结束再构建：构造阶段模组物品还没进注册表
        net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus()
                .addListener(this::onCommonSetup);
        // 强制加载 DemonFriendlyModeService：仅靠 @Mod.EventBusSubscriber 在没人引用时不会触发静态初始化。
        try {
            Class.forName("com.hanchen.veritycustomapi.network.DemonFriendlyModeService");
            LOGGER.info("恶魔友好模式服务已强制加载（@Mod.EventBusSubscriber 现已生效）");
        } catch (Throwable t) {
            LOGGER.warn("加载 DemonFriendlyModeService 失败，恶魔可能会攻击玩家", t);
        }

        LOGGER.info("Verity扩展增强模组加载完成");
    }

    /** 注册事件结束后预热 AI 物品索引：此时全部模组的物品都已进入注册表。 */
    private void onCommonSetup(net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent event) {
        try {
            com.hanchen.veritycustomapi.item.ItemRegistryService.warmup();
        } catch (Throwable t) {
            LOGGER.warn("AI 物品工具预热失败：{}", t.toString());
        }
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        CloudConfigManager.start(event.getServer());
        // 从 JSON 恢复上次保存的 MiMo 音色（绕过 Forge TOML 编码问题）
        String savedVoice = ConfigManager.loadMimoVoice();
        if (savedVoice != null && !savedVoice.isBlank()) {
            ModConfig.BUILTIN_MIMO_TTS_VOICE.set(savedVoice);
        }
        int port = ConfigManager.getPort();
        OriginalVerityConfigInjector.inject();   // 反射注入原版 AI/语音配置
        // 客户端专属逻辑（STT 注入、聊天链路接管）由 Dist.CLIENT 专用的
        // ClientEventHandler 在客户端侧初始化，专用服务器不会触达客户端类。
        OllamaProxyServer.start(port);
        GameActionHandler.getInstance().registerEvents();
        // 恢复上次未完成的 AI 建造任务（要求开启 AI_BUILDING_PERSIST）
        AiBuildingManager.getInstance().loadPersistedJobs(event.getServer());
        LOGGER.info("代理服务器已启动，端口：{}", port);
        
        // 打印当前人设信息，方便排查配置问题
        String prompt = ConfigManager.getEffectiveSystemPrompt();
        LOGGER.info("当前人设已加载，提示词长度: {} 字符", prompt != null ? prompt.length() : 0);
        
        LOGGER.info("Verity扩展增强模组初始化完成");

        // Dev 模式：启动连通性诊断
        if (ConfigManager.isDevMode() && FMLEnvironment.dist.isClient()) {
            ConnectionTestHelper.testAll();
        }
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        CloudConfigManager.stop();
        AiBuildingManager.getInstance().clear();
        GameActionHandler.getInstance().clear();
        VerityFeedingEvents.clear();
        DemonRecallService.reset();
        VerityGrowthState.clearServer();
        com.hanchen.veritycustomapi.ride.RideGrace.clear();
        com.hanchen.veritycustomapi.ride.HeadPerchState.clear();
        // 下面这几处都是「static 表 + 玩家/实体 id 做 key」，不清会跨存档残留：
        // 实体 id 是每个存档从 0 开始计数的，新存档里的 id 5 会读到上个存档的状态。
        com.hanchen.veritycustomapi.ride.PerchRideState.clear();
        com.hanchen.veritycustomapi.ride.VerityStepStore.clear();
        com.hanchen.veritycustomapi.ai.AiEventThrottle.reset();
        com.hanchen.veritycustomapi.compat.CrossSavePursuit.resetSession();
        com.hanchen.veritycustomapi.memory.MemoryAutoWriter.resetState();
        com.hanchen.veritycustomapi.memory.MemoryMetaStore.clear();
        // 服务器停止时，关闭代理服务释放端口
        OllamaProxyServer.stop();
        // 🔴 最后一道闸：关机时可能有别的代码路径（Forge 自己的 ConfigTracker、
        // 配置界面关屏、其它模组）把 TOML 写坏。2026-10-24 实测到关机瞬间
        // common.toml 从 122 个键变成 20 个，而那次写盘**没有经过我们的保存入口**，
        // 所以保存守卫根本没被调用（日志里一次都没有）。
        // 这里在关机末尾主动验一遍：不完整就用快照恢复，保证下次启动读到的是好文件。
        com.hanchen.veritycustomapi.config.ForgeConfigRepair.verifyOnShutdown();
        LOGGER.info("代理服务器已关闭");
    }
}
