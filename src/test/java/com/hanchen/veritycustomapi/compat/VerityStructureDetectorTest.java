package com.hanchen.veritycustomapi.compat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 验证「原版 Verity 回复发送结构」的探测逻辑。
 *
 * 这是 6.2 适配的关键判定：6.0.x/6.1.x 把「播 TTS + 广播聊天」写在
 * {@code AiManager.lambda$queryAI$4} 里，6.2 起抽成了 {@code AiManager.sendReplyPart}。
 * 探错了就会启用签名对不上的 Mixin，注入直接报错。两个真实构建都得验一遍。
 */
public final class VerityStructureDetectorTest {
    private VerityStructureDetectorTest() {
    }

    private static final String AI_MANAGER = VerityStructureDetector.AI_MANAGER_RESOURCE;
    private static final String OLD_BUILD = "src/main/java/com/hanchen/veritycustomapi/verity-6.0.0-beta.9";
    private static final String NEW_BUILD = "src/main/java/com/hanchen/veritycustomapi/verity_ext-1.0.0（修改版）";

    public static void main(String[] args) throws Exception {
        // 基本扫描行为
        byte[] sample = "xx sendReplyPart yy".getBytes("UTF-8");
        require(VerityStructureDetector.hasMethod(sample, "sendReplyPart"), "应能在字节里找到方法名");
        require(!VerityStructureDetector.hasMethod(sample, "noSuchMethodXyz"), "不应误报不存在的方法");
        require(!VerityStructureDetector.hasMethod(null, "sendReplyPart"), "null 输入应返回 false");
        require(!VerityStructureDetector.hasMethod(sample, ""), "空方法名应返回 false");
        // 跨边界：needle 比内容长
        require(!VerityStructureDetector.hasMethod(new byte[]{1, 2}, "abcdefgh"), "过短内容不应越界");

        // 旧构建：应判定为非新版结构，且没有内置抚摸支持
        byte[] oldBytes = readBuild(OLD_BUILD);
        if (oldBytes == null) {
            System.out.println("跳过：未找到 6.0.0-beta.9 构建目录");
        } else {
            require(!VerityStructureDetector.isNewReplyStructure(oldBytes),
                    "6.0.0-beta.9 不应被判定为新版结构");
            require(VerityStructureDetector.hasMethod(oldBytes, "lambda$queryAI$4"),
                    "6.0.0-beta.9 应存在旧入口 lambda$queryAI$4");
            require(!hasResourceInBuild(OLD_BUILD, VerityStructureDetector.BUILTIN_PAT_RESOURCE),
                    "6.0.0-beta.9 不应有内置抚摸支持（varmite/verity/pat 包）");
            byte[] oldTools = readResource(OLD_BUILD, VerityStructureDetector.TOOLS_RESOURCE);
            if (oldTools != null) {
                require(!VerityStructureDetector.hasMethod(oldTools, VerityStructureDetector.GOLDEN_BOOK_METHOD),
                        "6.0.0-beta.9 不应有 goldenMakeBook —— 旧版不能限制附魔书数量");
                require(!VerityStructureDetector.hasMethod(oldTools, VerityStructureDetector.RUN_COMMAND_METHOD),
                        "6.0.0-beta.9 不应有 run_command —— 旧版不能注入「物品数据规则」");
            }
            byte[] oldModEvents = readResource(OLD_BUILD, "varmite/verity/event/ModEvents.class");
            if (oldModEvents != null) {
                require(!VerityStructureDetector.hasMethod(oldModEvents, VeritySpeechContext.MARKER),
                        "6.0.0-beta.9 不应有 SPEECH KARMA EVALUATION —— 旧版不能加语音上下文前缀");
            }
        }

        // 新构建：应判定为新版结构，且有内置抚摸支持
        byte[] newBytes = readBuild(NEW_BUILD);
        if (newBytes == null) {
            System.out.println("跳过：未找到 6.2 修改版构建目录");
        } else {
            require(VerityStructureDetector.isNewReplyStructure(newBytes),
                    "6.2 修改版应被判定为新版结构（含 sendReplyPart）");
            require(VerityStructureDetector.hasMethod(newBytes, "sendReplyPart"),
                    "6.2 修改版应存在新入口 sendReplyPart");
            require(hasResourceInBuild(NEW_BUILD, VerityStructureDetector.BUILTIN_PAT_RESOURCE),
                    "6.2 修改版应有内置抚摸支持 —— 扩展的 PatPat 兼容层必须让位，否则会回复两次");
            require(!hasResourceInBuild(NEW_BUILD, "varmite/verity/types/TTSLanguage.class"),
                    "6.2 修改版已删除 types/TTSLanguage，可用来佐证版本判定");

            // 金附魔书机制：give_item 两版都有，不能拿它当判据；
            // 真正新增的是 goldenMakeBook，只有它出现时才限制附魔书数量。
            byte[] newTools = readResource(NEW_BUILD, VerityStructureDetector.TOOLS_RESOURCE);
            require(newTools != null, "6.2 修改版应有 Tools.class");
            require(VerityStructureDetector.hasMethod(newTools, "give_item"),
                    "give_item 两版都有，作为反向对照");
            require(VerityStructureDetector.hasMethod(newTools, VerityStructureDetector.GOLDEN_BOOK_METHOD),
                    "6.2 修改版应有 goldenMakeBook —— 这是限制附魔书数量的唯一依据");
            // run_command 是 6.2 新增的「能给带 NBT 物品」的工具；
            // 引导模型用对工具的提示词只在它存在时注入。
            require(VerityStructureDetector.hasMethod(newTools, VerityStructureDetector.RUN_COMMAND_METHOD),
                    "6.2 修改版应有 run_command —— 这是注入「物品数据规则」的唯一依据");

            // 语音上下文机制：原版靠查询里有没有 SPEECH KARMA EVALUATION 判定「玩家亲口说的话」，
            // 缺了它 pickup_nearby_items（捡东西/收礼物）会被 Refused。
            byte[] newModEvents = readResource(NEW_BUILD, "varmite/verity/event/ModEvents.class");
            require(newModEvents != null, "6.2 修改版应有 ModEvents.class");
            require(VerityStructureDetector.hasMethod(newModEvents, VeritySpeechContext.MARKER),
                    "6.2 修改版应含 SPEECH KARMA EVALUATION 标记 —— 缺标记时工具会被拒绝");

            // 原版抚摸通道：6.2 提供 PatC2SPacket.send(int)，但自己不检测抚摸
            // （mixin 配置无 PatPat hook、PatC2SPacket 也没有客户端发送方），
            // 所以触发必须由扩展发这个包 —— 不能让位，否则摸头没反应。
            byte[] newPatPacket = readResource(NEW_BUILD, "varmite/verity/network/PatC2SPacket.class");
            require(newPatPacket != null, "6.2 修改版应有 PatC2SPacket.class");
            require(VerityStructureDetector.hasMethod(newPatPacket, "send"),
                    "6.2 修改版的 PatC2SPacket 应有 send 方法（扩展靠它触发抚摸回复）");
        }

        // 内部提示词调用：记忆提炼 / 日记写入必须原样透传，不能被当成玩家对话注入上下文
        require(VerityStructureDetector.isInternalPromptRequest("<memory_refinement>\n    <task>…"),
                "记忆提炼请求应判为内部调用");
        require(VerityStructureDetector.isInternalPromptRequest("  <system_prompt>\n…"),
                "日记类请求应判为内部调用（允许前导空白）");
        require(!VerityStructureDetector.isInternalPromptRequest(
                        "<SYSTEM> SPEECH KARMA EVALUATION: … Message: 你好"),
                "玩家对话的语音上下文装饰前缀不能被误判为内部调用");
        require(!VerityStructureDetector.isInternalPromptRequest("你好，帮我建个房子"),
                "普通对话不能被误判为内部调用");
        require(!VerityStructureDetector.isInternalPromptRequest("玩家说 <system_prompt> 这个词"),
                "只有开头是根标签才算内部调用，正文里提及不算");
        require(!VerityStructureDetector.isInternalPromptRequest(null), "null 应返回 false");
        require(!VerityStructureDetector.isInternalPromptRequest("   "), "空白应返回 false");

        // 用真实提示词文件复核：记忆提炼与日记提示词必须都能被识别
        for (String promptFile : new String[]{"prompts/memory_refine.xml", "prompts/journal_reply.xml"}) {
            String content = readText(NEW_BUILD, promptFile);
            if (content != null) {
                require(VerityStructureDetector.isInternalPromptRequest(content),
                        promptFile + " 应被识别为内部提示词调用");
            }
        }

        // 门控矩阵：所有 6.2 专项适配都得在检测到该改版时才生效，
        // 跑原版时走原来的兼容路径，这些适配一个都不能触发。
        // 下面逐条核对每个门控信号在两个真实构建里的取值。
        verifyGatingMatrix();

        // beta.9 专项：Mixin 注入目标必须在这个构建里真实存在。
        // 只看编译能否通过不够 —— 移动/改名后的方法会在运行期让 Mixin 注入失败。
        verifyBeta9MixinTargets();

        // 5. 右键骑乘的触发点必须挂在 Forge 事件上。
        // 原版会在 PlayerInteractEvent.EntityInteract 阶段取消空手右键（把实体收进物品），
        // 而该事件在 Entity.interact 之前触发，取消后 mobInteract 根本不会执行。
        // 触发点一旦退回 mobInteract，骑乘就会静默失效 —— 这条断言把这个结论钉住。
        byte[] rideTrigger = readOwnClass("com/hanchen/veritycustomapi/ride/VerityRideInteraction.class");
        require(rideTrigger != null, "应能读到 VerityRideInteraction.class");
        require(VerityStructureDetector.hasMethod(rideTrigger, "EntityInteract"),
                "骑乘触发点必须挂在 PlayerInteractEvent.EntityInteract 上（mobInteract 会被原版取消挡死）");
        // 原版的收进物品+discard 写在它自己的 NORMAL 处理器里，且从不检查 isCanceled ——
        // LOWEST 的服务端拦截排在它后面，实体早已被丢弃（实测就是「蹲下右键她还是被收走」）。
        // 唯一可靠拦截点是客户端 HIGHEST 先取消、再走自己的骑乘请求包，这条断言把方案钉住。
        require(VerityStructureDetector.hasMethod(rideTrigger, "sendRideRequest"),
                "骑乘触发点必须在客户端拦下右键并发送骑乘请求包（服务端拦截拦不住原版的内联收进物品）");
        // 手势必须要求潜行：普通右键要留给原版的「把她收进物品」。
        // 该方法名是 MC 成员，生产包里会被 reobf 成 m_6047_，所以两种写法都接受。
        byte[] rideManager = readOwnClass("com/hanchen/veritycustomapi/ride/RideManager.class");
        require(rideManager != null, "应能读到 RideManager.class");
        require(VerityStructureDetector.hasMethod(rideManager, "isShiftKeyDown")
                        || VerityStructureDetector.hasMethod(rideManager, "m_6047_"),
                "骑乘必须是「潜行 + 空手右键」触发 —— 普通右键要留给原版收进物品");
        // 骑乘手势用潜行，而原版的下车判定恰恰就是「按着潜行」（Player.rideTick -> wantsToStopRiding），
        // 所以上骑后必须有免疫窗口，否则骑上去下一 tick 就被原版踢下来（已实测）。
        byte[] grace = readOwnClass("com/hanchen/veritycustomapi/mixin/PlayerRideGraceMixin.class");
        require(grace != null, "应能读到 PlayerRideGraceMixin.class");
        require(VerityStructureDetector.hasMethod(grace, "wantsToStopRiding"),
                "潜行免疫必须挂在原版真正用来判断下车的方法上（rideTick 调的就是它）");
        require(VerityStructureDetector.hasMethod(grace, "RideGrace"),
                "潜行免疫必须走 RideGrace，别把判定散到各处");
        require(VerityStructureDetector.hasMethod(rideManager, "RideGrace"),
                "上骑时必须登记免疫窗口，否则骑上去会被潜行立刻踢下来");
        // 站头必须走「状态 + 自己定位」，不能再回到乘客系统：
        // 玩家当载具时原版 tickPassenger 会强制把她踢下车，而且乘客照样会被 tick，
        // 她自己的 aiStep -> travel 会与每帧定位打架（已实测站不住）。
        byte[] perchMixin = readOwnClass("com/hanchen/veritycustomapi/mixin/EntityPerchRideMixin.class");
        require(perchMixin != null, "应能读到 EntityPerchRideMixin.class");
        require(VerityStructureDetector.hasMethod(perchMixin, "HeadPerchState"),
                "站头必须由 HeadPerchState 驱动（不占乘客位、每帧自己定位）");
        byte[] perchManager = readOwnClass("com/hanchen/veritycustomapi/ride/HeadPerchManager.class");
        require(perchManager != null, "应能读到 HeadPerchManager.class");
        require(!VerityStructureDetector.hasMethod(perchManager, "startRiding"),
                "站头不能再调用 startRiding —— 乘客路线已被实测否掉（原版会强制下车）");
        // 站头期间必须掐掉她自己的 aiStep：它同时是重力/移动的来源，也是 pushEntities 的调用点，
        // 不掐她就会把玩家推着走（实测「她会推着我走」）。
        byte[] livingPerch = readOwnClass(
                "com/hanchen/veritycustomapi/mixin/LivingEntityPerchRideMixin.class");
        require(livingPerch != null, "应能读到 LivingEntityPerchRideMixin.class");
        require(VerityStructureDetector.hasMethod(livingPerch, "aiStep"),
                "站头必须跳过她的 aiStep（否则 pushEntities 会把玩家推着走）");
        require(VerityStructureDetector.hasMethod(livingPerch, "HeadPerchState"),
                "站头跳过 aiStep 必须按 HeadPerchState 判断");
        // Fish Audio：它的请求体是 text + reference_id（音色模型 id），不是 OpenAI 那套 model/input/voice，
        // 必须有独立分支；一旦落回 openai 分支，音色就传错字段、直接合成失败。
        byte[] ttsProviders = readOwnClass("com/hanchen/veritycustomapi/TtsApiProviderEnum.class");
        require(ttsProviders != null, "应能读到 TtsApiProviderEnum.class");
        require(VerityStructureDetector.hasMethod(ttsProviders, "api.fish.audio"),
                "必须有 Fish Audio 供应商（端点 https://api.fish.audio/v1/tts）");
        byte[] onlineTts = readOwnClass("com/hanchen/veritycustomapi/tts/OnlineTtsService.class");
        require(onlineTts != null, "应能读到 OnlineTtsService.class");
        require(VerityStructureDetector.hasMethod(onlineTts, "reference_id"),
                "Fish Audio 的音色参数叫 reference_id，必须走独立分支而不是 OpenAI 的 voice");
        // 补齐的五家语音服务：每家的请求体字段名都不同，必须各自有分支，不能落回 openai。
        // 这些字段名就是各家的身份证，少一个就说明那条分支被并没了。
        for (String marker : new String[]{
                "voice_setting",       // MiniMax：音色在voice_setting里
                "transcript",          // Cartesia：文本字段叫 transcript
                "Cartesia-Version",    // Cartesia：必须带版本头
                "delivery_method"}) {  // Moss：取音频用下载链接
            require(VerityStructureDetector.hasMethod(onlineTts, marker),
                    "在线 TTS 缺少 " + marker + " —— 对应的服务商分支可能被并回了 openai");
        }
        for (String endpoint : new String[]{"api.groq.com", "api.minimax.cn", "api.cartesia.ai", "api.mosi.cn"}) {
            require(VerityStructureDetector.hasMethod(ttsProviders, endpoint),
                    "TtsApiProviderEnum 缺少 " + endpoint + " 这家服务商");
        }
        // 半透明界面必须走统一助手：它负责「世界里透出世界 / 主菜单按普通菜单背景」的分流，
        // 任何界面自己 fill 一个不透明底色都会把效果吃掉。
        for (String screen : new String[]{"NativeOllamaProxyConfigScreen", "PluginManagerScreen",
                "PluginCreateScreen", "QuickCommandScreen", "MmdPlaybackScreen"}) {
            byte[] bytes = readOwnClass("com/hanchen/veritycustomapi/client/" + screen + ".class");
            if (bytes == null) {
                bytes = readOwnClass("com/hanchen/veritycustomapi/config/" + screen + ".class");
            }
            require(bytes != null, "应能读到 " + screen + ".class");
            require(VerityStructureDetector.hasMethod(bytes, "ScreenBackgrounds"),
                    screen + " 必须用 ScreenBackgrounds 画背景（半透明 + 主菜单分流）");
        }

        System.out.println("Verity structure detector checks passed");
    }

    /** 逐条核对每个 6.2 专项适配的门控信号：旧版必须全 false，新版必须全 true。 */
    /**
     * beta.9 的 Mixin 注入目标必须真实存在。
     *
     * <p>编译通过只说明扩展自己的代码能编；如果原版把方法改名/挪包，Mixin 会在运行期
     * 注入失败。这里把 beta.9 里所有被扩展注入的类/方法固定成回归断言。
     */
    private static void verifyBeta9MixinTargets() throws IOException {
        if (readBuild(OLD_BUILD) == null) {
            System.out.println("跳过：未找到 6.0.0-beta.9 构建目录，Mixin 目标未校验");
            return;
        }
        requireTarget(OLD_BUILD, "varmite/verity/event/ModEvents.class", "onPlayerChat", "私聊入口");
        requireTarget(OLD_BUILD, "varmite/verity/event/ModEvents.class", "updateAndSyncKarma", "好感同步");
        requireTarget(OLD_BUILD, "varmite/verity/event/ModEvents.class", "onDeath", "死亡世界状态");
        requireTarget(OLD_BUILD, "varmite/verity/event/ModEvents.class", "onDemonLeaveLevel", "恶魔离场");
        requireTarget(OLD_BUILD, "varmite/verity/entity/llm/AiManager.class", "lambda$queryAI$4", "旧版回复发送");
        requireTarget(OLD_BUILD, "varmite/verity/entity/llm/actions/Tools.class", "list_available_itemids", "物品搜索工具");
        requireTarget(OLD_BUILD, "varmite/verity/entity/llm/AiAPI.class", "ask", "AI 请求");
        requireTarget(OLD_BUILD, "varmite/verity/entity/verity/VerityEntity.class", "getTextureRL", "皮肤/说话状态");
        requireTarget(OLD_BUILD, "varmite/verity/entity/verity/VerityEntity.class", "transformIntoDemon", "恶魔转化");
        requireTarget(OLD_BUILD, "varmite/verity/entity/verity/VerityEntity.class", "m_8119_", "实体 tick");
        requireTarget(OLD_BUILD, "varmite/verity/entity/verity/rendering/VerityEntityTexture.class", "setBaseTexture", "皮肤贴图");
        requireTarget(OLD_BUILD, "varmite/verity/environment/items/client/VerityItemRenderer.class", "m_108829_", "物品渲染");
        requireTarget(OLD_BUILD, "varmite/verity/entity/utils/SphereEntityRenderer.class", "render", "球形态渲染");
        requireTarget(OLD_BUILD, "varmite/verity/entity/demon/rendering/VerityDemonModel.class", "setCustomAnimations", "恶魔动画");
        requireTarget(OLD_BUILD, "varmite/verity/entity/demon/rendering/VerityDemonRenderer.class", "render", "恶魔渲染");
        requireTarget(OLD_BUILD, "varmite/verity/network/PlayTtsClientHandler.class", "handlePacket", "TTS 包处理");
        requireTarget(OLD_BUILD, "varmite/verity/client/KeybindHandler.class", "onClientTick", "语音按键");
        requireTarget(OLD_BUILD, "varmite/verity/client/KeybindRegistry.class", "PUSH_TO_TALK", "语音按键映射");
        requireTarget(OLD_BUILD, "varmite/verity/VerityConfig.class", "USE_TTS", "原版 TTS 开关");
        requireTarget(OLD_BUILD, "varmite/verity/VerityConfig.class", "API_KEY", "原版 AI 密钥");
        requireTarget(OLD_BUILD, "varmite/verity/VerityConfig.class", "AI_PROVIDER", "原版 AI 供应商");
        requireTarget(OLD_BUILD, "varmite/verity/VerityConfig.class", "STT_PROVIDER", "原版 STT 供应商");
        requireTarget(OLD_BUILD, "varmite/verity/VerityConfig.class", "PERSONALITY", "原版人格");
        requireTarget(OLD_BUILD, "varmite/verity/entity/VerityState.class", "verityEntity", "原版实体单例");
        requireTarget(OLD_BUILD, "varmite/verity/event/WorldSpawnData.class", "verityKarma", "原版好感存储");
        requireTarget(OLD_BUILD, "varmite/verity/event/WorldSpawnData.class", "activeDemon", "原版恶魔存储");
    }

    private static void requireTarget(String buildDir, String resource, String name, String label)
            throws IOException {
        byte[] bytes = readResource(buildDir, resource);
        require(bytes != null, "beta.9 缺少 " + label + " 目标类: " + resource);
        require(VerityStructureDetector.hasMethod(bytes, name),
                "beta.9 缺少 " + label + " 目标: " + name);
    }

    private static void verifyGatingMatrix() throws IOException {
        // 旧版：所有门控必须为 false，走原来的兼容路径
        // 两个构建目录都是第三方产物，不进仓库。缺哪个就跳过对应那一组门控检查，
        // 这样精简环境（刚 clone、还没放依赖）也能把其余自检跑完。
        if (readBuild(OLD_BUILD) == null) {
            System.out.println("跳过：未找到 6.0.0-beta.9 构建目录，旧版门控未校验");
        } else {
            checkGate(OLD_BUILD, false, "AiManager.sendReplyPart（新版回复入口）", AI_MANAGER, "sendReplyPart");
            checkGate(OLD_BUILD, false, "AiManager.requestInFlight（单飞锁）", AI_MANAGER, "requestInFlight");
            checkGate(OLD_BUILD, false, "pat/PatQuips（原版自带抚摸）", PAT_RESOURCE, null);
            checkGate(OLD_BUILD, false, "network/PatC2SPacket（原版抚摸通道）", PAT_PACKET, null);
            checkGate(OLD_BUILD, false, "Tools.goldenMakeBook（金附魔书）", TOOLS_RESOURCE,
                    VerityStructureDetector.GOLDEN_BOOK_METHOD);
            checkGate(OLD_BUILD, false, "Tools.run_command（物品数据规则判据）", TOOLS_RESOURCE,
                    VerityStructureDetector.RUN_COMMAND_METHOD);
            checkGate(OLD_BUILD, false, "ModEvents.SPEECH KARMA EVALUATION（语音上下文）", MOD_EVENTS,
                    VeritySpeechContext.MARKER);
            checkGate(OLD_BUILD, false, "llm/MemoryRefiner（新版记忆提炼器）", MEMORY_REFINER, null);
            checkGate(OLD_BUILD, false, "store/memory/MemoryCategory（新版记忆分类）", MEMORY_CATEGORY, null);
            checkGate(OLD_BUILD, false, "VerityEntity.isShoulderActive（肩载机制）", VERITY_ENTITY,
                    VerityStructureDetector.SHOULDER_METHOD);
        }

        // 新版：所有门控必须为 true，启用专项适配
        if (readBuild(NEW_BUILD) == null) {
            System.out.println("跳过：未找到 verity_ext-1.0.0（修改版）构建目录，新版门控未校验");
        } else {
            checkGate(NEW_BUILD, true, "AiManager.sendReplyPart（新版回复入口）", AI_MANAGER, "sendReplyPart");
            checkGate(NEW_BUILD, true, "AiManager.requestInFlight（单飞锁）", AI_MANAGER, "requestInFlight");
            checkGate(NEW_BUILD, true, "pat/PatQuips（原版自带抚摸）", PAT_RESOURCE, null);
            checkGate(NEW_BUILD, true, "network/PatC2SPacket（原版抚摸通道）", PAT_PACKET, null);
            checkGate(NEW_BUILD, true, "PatC2SPacket.send（扩展靠它触发抚摸回复）", PAT_PACKET, "send");
            checkGate(NEW_BUILD, true, "Tools.goldenMakeBook（金附魔书）", TOOLS_RESOURCE,
                    VerityStructureDetector.GOLDEN_BOOK_METHOD);
            checkGate(NEW_BUILD, true, "Tools.run_command（物品数据规则判据）", TOOLS_RESOURCE,
                    VerityStructureDetector.RUN_COMMAND_METHOD);
            checkGate(NEW_BUILD, true, "ModEvents.SPEECH KARMA EVALUATION（语音上下文）", MOD_EVENTS,
                    VeritySpeechContext.MARKER);
            checkGate(NEW_BUILD, true, "llm/MemoryRefiner（新版记忆提炼器）", MEMORY_REFINER, null);
            checkGate(NEW_BUILD, true, "store/memory/MemoryCategory（新版记忆分类）", MEMORY_CATEGORY, null);
            // 肩载不占乘客位，不避让就会出现「既被扛在肩上又被挂成乘客」两套定位同时生效
            checkGate(NEW_BUILD, true, "VerityEntity.isShoulderActive（肩载机制）", VERITY_ENTITY,
                    VerityStructureDetector.SHOULDER_METHOD);
            }
        }

        /**
         * 检查某个门控信号在指定构建里的取值。
         *
         * @param methodName 为 {@code null} 时只检查 class 资源是否存在
         */
        private static void checkGate(String buildDir, boolean expected, String label,
                                      String resource, String methodName) throws IOException {
            byte[] bytes = readResource(buildDir, resource);
            boolean actual = methodName == null ? bytes != null
                    : VerityStructureDetector.hasMethod(bytes, methodName);
            require(actual == expected,
                    label + " 在 " + (buildDir.contains("6.0") ? "旧版" : "新版")
                            + " 上应为 " + expected + "，实际为 " + actual
                            + " —— 门控失效会让专项适配在错误的版本上生效/失效");
        }

        private static final String PAT_RESOURCE = VerityStructureDetector.BUILTIN_PAT_RESOURCE;
        private static final String PAT_PACKET = "varmite/verity/network/PatC2SPacket.class";
        private static final String TOOLS_RESOURCE = VerityStructureDetector.TOOLS_RESOURCE;
        private static final String MOD_EVENTS = "varmite/verity/event/ModEvents.class";
        private static final String MEMORY_REFINER = VerityStructureDetector.MEMORY_REFINER_RESOURCE;
        private static final String MEMORY_CATEGORY =
                "varmite/verity/entity/llm/store/memory/MemoryCategory.class";
        private static final String VERITY_ENTITY = VerityStructureDetector.VERITY_ENTITY_RESOURCE;

        /** 读扩展自己的 class 字节（走类加载器，用于给关键结论加回归断言）。 */
        private static byte[] readOwnClass(String resource) throws IOException {
            try (InputStream in = VerityStructureDetectorTest.class.getClassLoader()
                    .getResourceAsStream(resource)) {
                return in == null ? null : in.readAllBytes();
            }
        }

        /** 在指定的构建目录里查找 class 资源（不依赖类加载器，便于跨版本验证）。 */
        private static boolean hasResourceInBuild(String buildDir, String resource) {
            return Files.isRegularFile(Paths.get(buildDir, resource.replace('/', java.io.File.separatorChar)));
        }

        /** 从构建目录读 AiManager.class；目录不存在时返回 null（允许在精简环境里跳过）。 */
        private static byte[] readBuild(String buildDir) throws IOException {
            return readResource(buildDir, AI_MANAGER);
        }

        /** 从构建目录读任意 class 资源；不存在时返回 null。 */
        private static byte[] readResource(String buildDir, String resource) throws IOException {
            Path path = Paths.get(buildDir, resource.replace('/', java.io.File.separatorChar));
            if (!Files.isRegularFile(path)) return null;
            try (InputStream in = Files.newInputStream(path)) {
                return in.readAllBytes();
            }
        }

        /** 从构建目录读文本资源（提示词 xml）；不存在或读取失败时返回 null。 */
        private static String readText(String buildDir, String resource) {
            Path path = Paths.get(buildDir, resource.replace('/', java.io.File.separatorChar));
            if (!Files.isRegularFile(path)) return null;
            try {
                return Files.readString(path, StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                return null;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
