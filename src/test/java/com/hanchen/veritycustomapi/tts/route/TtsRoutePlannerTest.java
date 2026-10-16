package com.hanchen.veritycustomapi.tts.route;

import com.hanchen.veritycustomapi.compat.VerityStructureDetector;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 朗读路线决策的自检。
 *
 * 降级规则出问题的表现只有一种：没声音。它既不抛异常也不写日志，
 * 所以只能靠这里把每条边都钉死——尤其是「什么时候必须收手、把这句话让给原版」。
 */
public final class TtsRoutePlannerTest {

    private TtsRoutePlannerTest() {
    }

    public static void main(String[] args) throws IOException {
        nativeNeverDowngrades();
        fallbackChainsAlwaysEndAtNative();
        fallbackNeverEscalatesToPaidRoute();
        planRespectsConfiguredRoute();
        planDowngradesWhenConfiguredIsUnavailable();
        planNeverInventsARoute();
        nullConfiguredIsTolerated();
        normalizeHandlesLegacyAndJunkValues();
        parseIsLenient();
        engineRegistryIsComplete();
        wiringIsStillConnected();

        System.out.println("TTS route planner checks passed");
    }

    // 降级规则

    /** 原版路线是「我不想让扩展出声」的明确表态，不能被自动降级绕过。 */
    private static void nativeNeverDowngrades() {
        List<TtsRouteKind> order = TtsRoutePlanner.fallbackOrder(TtsRouteKind.NATIVE);
        require(order.size() == 1, "原版路线的候选只能有它自己，实际: " + order);
        require(order.get(0) == TtsRouteKind.NATIVE, "原版路线的唯一候选必须是原版");

        require(TtsRoutePlanner.plan(TtsRouteKind.NATIVE,
                        List.of(TtsRouteKind.NATIVE, TtsRouteKind.LOCAL, TtsRouteKind.ENDPOINT))
                        == TtsRouteKind.NATIVE,
                "即使本地与端点都可用，选了原版也必须走原版");
    }

    /** 每条候选链都必须以原版收尾：那是「永远可用」的那一级，保证不会无路可走。 */
    private static void fallbackChainsAlwaysEndAtNative() {
        for (TtsRouteKind configured : TtsRouteKind.values()) {
            List<TtsRouteKind> order = TtsRoutePlanner.fallbackOrder(configured);
            require(!order.isEmpty(), "候选链不能为空: " + configured);
            require(order.get(order.size() - 1) == TtsRouteKind.NATIVE,
                    "候选链必须以原版收尾: " + configured + " -> " + order);
            require(order.get(0) == configured,
                    "候选链必须从配置的那条开始: " + configured + " -> " + order);
            require(order.stream().distinct().count() == order.size(),
                    "候选链不能有重复: " + order);
        }

        require(TtsRoutePlanner.fallbackOrder(TtsRouteKind.LOCAL)
                        .equals(List.of(TtsRouteKind.LOCAL, TtsRouteKind.NATIVE)),
                "本地路线的降级顺序应为 本地 -> 原版");
        require(TtsRoutePlanner.fallbackOrder(TtsRouteKind.ENDPOINT)
                        .equals(List.of(TtsRouteKind.ENDPOINT, TtsRouteKind.LOCAL, TtsRouteKind.NATIVE)),
                "端点路线的降级顺序应为 端点 -> 本地 -> 原版");
    }

    /**
     * 降级只能往「不更贵」的方向走。
     *
     * 端点是要花钱的：用户选了本地离线，就不能在他不知情时产生联网调用。
     */
    private static void fallbackNeverEscalatesToPaidRoute() {
        require(!TtsRoutePlanner.fallbackOrder(TtsRouteKind.LOCAL).contains(TtsRouteKind.ENDPOINT),
                "选了本地就不能降级到要花钱的端点");
        require(TtsRoutePlanner.plan(TtsRouteKind.LOCAL,
                        List.of(TtsRouteKind.ENDPOINT, TtsRouteKind.NATIVE)) == TtsRouteKind.NATIVE,
                "本地不可用、只剩端点与原地时，必须交还原版而不是改用端点");
    }

    /** 配置的那条可用时就必须用它，不能「顺手」换成别的。 */
    private static void planRespectsConfiguredRoute() {
        List<TtsRouteKind> all = List.of(TtsRouteKind.NATIVE, TtsRouteKind.LOCAL, TtsRouteKind.ENDPOINT);
        require(TtsRoutePlanner.plan(TtsRouteKind.ENDPOINT, all) == TtsRouteKind.ENDPOINT,
                "端点可用时不应降级到本地");
        require(TtsRoutePlanner.plan(TtsRouteKind.LOCAL, all) == TtsRouteKind.LOCAL,
                "本地可用时不应改用端点");
    }

    /** 配置的那条不可用时才顺着候选链往下走。 */
    private static void planDowngradesWhenConfiguredIsUnavailable() {
        require(TtsRoutePlanner.plan(TtsRouteKind.ENDPOINT,
                        List.of(TtsRouteKind.LOCAL, TtsRouteKind.NATIVE)) == TtsRouteKind.LOCAL,
                "端点不可用时应降到本地");
        require(TtsRoutePlanner.plan(TtsRouteKind.LOCAL, List.of(TtsRouteKind.NATIVE))
                        == TtsRouteKind.NATIVE,
                "只剩原版可用时应落到原版（而不是抛异常或返回 null）");
    }

    /** 一条都不可用、或可用集合是空的，都必须收敛到原版。 */
    private static void planNeverInventsARoute() {
        require(TtsRoutePlanner.plan(TtsRouteKind.ENDPOINT, List.of()) == TtsRouteKind.NATIVE,
                "没有可用路线时必须落到原版");
        require(TtsRoutePlanner.plan(TtsRouteKind.ENDPOINT, null) == TtsRouteKind.NATIVE,
                "可用集合为 null 时必须落到原版");
        // 可用的那条不在候选链里（配置被改过、引擎被换掉）也不能凭空选中它
        require(TtsRoutePlanner.plan(TtsRouteKind.LOCAL, List.of(TtsRouteKind.ENDPOINT))
                        == TtsRouteKind.NATIVE,
                "可用集合里只有端点、配置是本地时应落到原版，不能跳过去用端点");
    }

    private static void nullConfiguredIsTolerated() {
        List<TtsRouteKind> order = TtsRoutePlanner.fallbackOrder(null);
        require(order.equals(List.of(TtsRouteKind.NATIVE)),
                "配置读不出来时只能退回原版（最保守），实际: " + order);
        require(TtsRoutePlanner.plan(null, List.of(TtsRouteKind.NATIVE)) == TtsRouteKind.NATIVE,
                "配置为 null 时应落到原版");
        require(TtsRoutePlanner.plan(null, List.of(TtsRouteKind.LOCAL, TtsRouteKind.NATIVE))
                        == TtsRouteKind.NATIVE,
                "配置为 null 时即使本地可用也不能擅自接管");
    }

    // 取值规范化

    private static void normalizeHandlesLegacyAndJunkValues() {
        require(TtsRoutePlanner.normalize("auto", TtsRouteKind.ENDPOINT) == TtsRouteKind.ENDPOINT,
                "auto 应回落到调用方给的默认值");
        require(TtsRoutePlanner.normalize("默认", TtsRouteKind.LOCAL) == TtsRouteKind.LOCAL,
                "中文「默认」应等同于 auto");
        require(TtsRoutePlanner.normalize(null, TtsRouteKind.LOCAL) == TtsRouteKind.LOCAL,
                "null 应回落到默认值");
        require(TtsRoutePlanner.normalize("   ", TtsRouteKind.LOCAL) == TtsRouteKind.LOCAL,
                "空白串应回落到默认值");
        require(TtsRoutePlanner.normalize("NATIVE", TtsRouteKind.ENDPOINT) == TtsRouteKind.NATIVE,
                "显式路线应大小写不敏感地认出来");
        require(TtsRoutePlanner.normalize("online", TtsRouteKind.LOCAL) == TtsRouteKind.ENDPOINT,
                "老写法 online 应映射到端点");
        require(TtsRoutePlanner.normalize("offline", TtsRouteKind.ENDPOINT) == TtsRouteKind.LOCAL,
                "老写法 offline 应映射到本地");
        require(TtsRoutePlanner.normalize("胡写的", TtsRouteKind.LOCAL) == TtsRouteKind.LOCAL,
                "认不出来的值应回落到默认值而不是静默变成别的路线");
    }

    private static void parseIsLenient() {
        require(TtsRouteKind.parse("原版") == TtsRouteKind.NATIVE, "「原版」应解析为原版路线");
        require(TtsRouteKind.parse("vanilla") == TtsRouteKind.NATIVE, "vanilla 应解析为原版路线");
        require(TtsRouteKind.parse("本地") == TtsRouteKind.LOCAL, "「本地」应解析为本地路线");
        require(TtsRouteKind.parse("api") == TtsRouteKind.ENDPOINT, "api 应解析为端点路线");
        require(TtsRouteKind.parse("不存在") == null, "认不出来必须返回 null，由调用方决定默认值");
        require(TtsRouteKind.parse(null) == null, "null 必须返回 null");

        for (TtsRouteKind kind : TtsRouteKind.values()) {
            require(TtsRouteKind.parse(kind.configValue()) == kind,
                    "自己的配置值必须能被解析回来: " + kind.configValue());
        }
    }

    // 接线

    private static void engineRegistryIsComplete() {
        List<TtsRouteKind> registered = TtsEngines.registeredKinds();
        require(registered.containsAll(List.of(TtsRouteKind.values())),
                "三条路线都必须注册引擎，实际: " + registered);
        for (TtsRouteKind kind : TtsRouteKind.values()) {
            TtsEngine engine = TtsEngines.of(kind);
            require(engine != null, "路线 " + kind + " 必须能取到引擎实例");
            require(engine.kind() == kind,
                    "引擎 " + engine.id() + " 自报的路线与注册的路线不一致");
            require(engine.id() != null && !engine.id().isBlank(), "引擎必须有稳定标识");
            require(engine.displayName() != null && !engine.displayName().isBlank(),
                    "引擎必须有显示名");
        }
    }

    /**
     * 字节级接线自检。
     *
     * 这条链断掉时的表现同样是「没声音」，而且往往是因为重构时把某一环换掉了却没人发现。
     * 这里只断言「谁引用了谁」，不碰具体实现细节。
     */
    private static void wiringIsStillConnected() throws IOException {
        byte[] router = readOwnClass("com/hanchen/veritycustomapi/tts/route/TtsRouter.class");
        require(router != null, "应能读到 TtsRouter.class");
        for (String method : List.of("claim", "speak", "warmUp", "stop", "shutdown", "plan")) {
            require(VerityStructureDetector.hasMethod(router, method),
                    "TtsRouter 必须提供 " + method + "（朗读链路依赖它）");
        }

        byte[] manager = readOwnClass("com/hanchen/veritycustomapi/tts/TtsManager.class");
        require(manager != null, "应能读到 TtsManager.class");
        require(VerityStructureDetector.hasMethod(manager,
                        "com/hanchen/veritycustomapi/tts/route/TtsRouter"),
                "TtsManager 必须经 TtsRouter 选路，不能自己直接挑引擎");
        require(VerityStructureDetector.hasMethod(manager,
                        "com/hanchen/veritycustomapi/voice/VoiceTranslator"),
                "TtsManager 必须经过 VoiceTranslator 处理朗读语言");
        require(!VerityStructureDetector.hasMethod(manager,
                        "com.hanchen.veritycustomapi.tts.OfflineTtsService"),
                "TtsManager 不应再直接引用离线服务 —— ONNX 运行时必须只从本地引擎那一层进来");

        byte[] payloadHandler = readOwnClass(
                "com/hanchen/veritycustomapi/mixin/PlayTtsClientHandlerMixin.class");
        require(payloadHandler != null, "应能读到 PlayTtsClientHandlerMixin.class");
        require(VerityStructureDetector.hasMethod(payloadHandler,
                        "com/hanchen/veritycustomapi/tts/TtsManager"),
                "朗读包入口必须仍然走 TtsManager（否则三路选路形同虚设）");

        byte[] engines = readOwnClass("com/hanchen/veritycustomapi/tts/route/TtsEngines.class");
        require(engines != null, "应能读到 TtsEngines.class");
        for (String engine : List.of("NativeTtsEngine", "LocalTtsEngine", "EndpointTtsEngine")) {
            require(VerityStructureDetector.hasMethod(engines,
                            "com/hanchen/veritycustomapi/tts/route/" + engine),
                    "TtsEngines 必须注册 " + engine);
        }

        byte[] local = readOwnClass("com/hanchen/veritycustomapi/tts/route/LocalTtsEngine.class");
        require(local != null, "应能读到 LocalTtsEngine.class");
        // 反射目标写成字符串常量，常量池里是点号形式，不是斜杠形式
        require(VerityStructureDetector.hasMethod(local,
                        "com.hanchen.veritycustomapi.tts.OfflineTtsService"),
                "本地引擎必须仍然用反射调离线服务（直接引用会把类加载失败带进启动链）");

        byte[] endpoint = readOwnClass("com/hanchen/veritycustomapi/tts/route/EndpointTtsEngine.class");
        require(endpoint != null, "应能读到 EndpointTtsEngine.class");
        require(VerityStructureDetector.hasMethod(endpoint,
                        "com.hanchen.veritycustomapi.tts.StreamTtsHelper"),
                "端点引擎必须仍然用反射调流式实现（流式不可用时才退回一次性合成）");

        // 选了「原版朗读」却把原版 TTS 关着 = 没人出声。这条链最容易在重构时被拆断。
        byte[] injector = readOwnClass(
                "com/hanchen/veritycustomapi/compat/OriginalVerityConfigInjector.class");
        require(injector != null, "应能读到 OriginalVerityConfigInjector.class");
        require(VerityStructureDetector.hasMethod(injector, "shouldOriginalTtsBeEnabled"),
                "原版 TTS 的开关必须跟着朗读路线走：选「原版朗读」时要把原版 TTS 打开，否则只会一片安静");
    }

    // 工具

    private static byte[] readOwnClass(String resource) throws IOException {
        try (InputStream in = TtsRoutePlannerTest.class.getClassLoader().getResourceAsStream(resource)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
