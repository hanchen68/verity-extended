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
    private static final String FALLBACK_VERSION = "4.5.3";
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
        // 空手右键骑乘：原版会把空手右键在 EntityInteract 阶段取消掉，
        // mobInteract 那条路走不到，必须挂在事件本身上（详见 VerityRideInteraction）
        MinecraftForge.EVENT_BUS.register(com.hanchen.veritycustomapi.ride.VerityRideInteraction.class);
        // 插件系统：规则引擎挂在事件总线上，命令在 PluginCommand 里注册
        MinecraftForge.EVENT_BUS.register(com.hanchen.veritycustomapi.plugin.PluginEngine.class);

        // MMD 按需生成恶魔 + 永久禁用玩家目标的恶魔友好模式
        com.hanchen.veritycustomapi.network.MmdDemonNetwork.register();
        // 站头/骑乘的网络通道
        com.hanchen.veritycustomapi.network.HeadPerchNetwork.register();
        // 插件管理界面的网络通道
        com.hanchen.veritycustomapi.network.PluginNetwork.register();
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
        int pluginCount = com.hanchen.veritycustomapi.plugin.PluginStore.loadAll();
        if (pluginCount > 0) {
            LOGGER.info("插件系统：已加载 {} 个插件", pluginCount);
        }
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
        com.hanchen.veritycustomapi.plugin.PluginEngine.clear();
        com.hanchen.veritycustomapi.ride.RideGrace.clear();
        com.hanchen.veritycustomapi.ride.HeadPerchState.clear();
        com.hanchen.veritycustomapi.memory.MemoryAutoWriter.resetState();
        com.hanchen.veritycustomapi.memory.MemoryMetaStore.clear();
        // 服务器停止时，关闭代理服务释放端口
        OllamaProxyServer.stop();
        LOGGER.info("代理服务器已关闭");
    }
}
