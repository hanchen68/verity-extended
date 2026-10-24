package com.hanchen.veritycustomapi.proxy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.config.ModConfig;
import com.hanchen.veritycustomapi.compat.VerityStructureDetector;
import org.apache.logging.log4j.Logger;

import java.util.Locale;

/**
 * 需要读运行环境（玩家状态 / 记忆库 / 建造意图 / 人设）的提示词注入。
 *
 * <p>与 {@link PromptInjections} 的分界很清楚：
 * <ul>
 *   <li>{@code PromptInjections} —— 只读配置、拼固定文案；</li>
 *   <li>{@code ContextInjections}（本类）—— 要读世界/玩家/记忆这类**运行时上下文**，
 *       所以调用方需要把「最后一条用户消息」等上下文作为参数传进来。</li>
 * </ul>
 *
 * <p>🔴 所有注入都必须**容忍失败**：上下文取不到时静默跳过，绝不能让一次对话失败。
 * 这里每个入口都用 try/catch 兜住，因为上游（玩家状态快照、记忆检索）都可能抛异常。
 */
public final class ContextInjections {
    private static final Logger LOGGER = OllamaProxyMod.LOGGER;

    private ContextInjections() {
    }

    /**
     * 注入玩家当前状态（位置/血量/背包）。
     *
     * <p>追加到**尾部**：前面的静态前缀保持字节级稳定，上游前缀缓存才能命中。
     */
    public static void injectPlayerStateContext(JsonObject bodyJson) {
        if (!ConfigManager.isInventoryContextEnabled()) return;
        if (!net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) return;
        String summary;
        try {
            summary = com.hanchen.veritycustomapi.client.PlayerStateSnapshot.gameStateSummary()
                    + "\n背包：" + com.hanchen.veritycustomapi.client.PlayerStateSnapshot
                    .inventorySummary(ConfigManager.getInventoryMaxTypes());
        } catch (Throwable t) {
            return;
        }
        if (summary.isBlank() || summary.contains("不在世界内")) return;
        // 动态内容追加到尾部：前面的静态前缀保持字节级稳定，上游前缀缓存才能命中
        PromptInjections.appendSystemContextAtTail(bodyJson,
                "Current player state (live, use it when relevant; never read it back verbatim):\n" + summary);
    }

    /**
     * 注入记忆规则（或检索到的记忆）。
     *
     * <p>6.2 起原版每轮自动展示 [长期备忘] 与日记块，规则也写进了新版主提示词，
     * 扩展不再重复注入——同一批记忆出现两份、两套规则互相矛盾都会干扰模型。
     *
     * @param latestUserText 最后一条用户消息的纯文本，用于记忆相关性检索
     */
    public static void injectMemoryRules(JsonObject bodyJson, String latestUserText) {
        if (!ConfigManager.isMemoryEnabled()) return;
        if (VerityStructureDetector.hasRefinedMemorySystem()) return;
        String injection = null;
        try {
            injection = com.hanchen.veritycustomapi.memory.VerityMemoryBridge
                    .buildInjection(latestUserText);
        } catch (Throwable t) {
            LOGGER.warn("记忆检索注入失败，回退到仅注入规则: {}", t.toString());
        }
        if (injection == null || injection.isBlank()) {
            injection = "Persistent memory is enabled. Use Verity's existing memory tools only for durable "
                    + "user preferences or facts explicitly worth remembering. Do not store secrets, credentials, "
                    + "transient requests, or inferred sensitive data. Read existing memory when it is relevant and "
                    + "avoid duplicate entries.";
        }
        // 动态内容（检索结果随每轮变化）追加到尾部，不破坏前缀缓存
        PromptInjections.appendSystemContextAtTail(bodyJson, injection);
    }

    /**
     * 建造协议注入：玩家明确要盖东西时，把「只返回 plan JSON」的协议塞进系统提示词。
     *
     * <p>同时抬高 {@code max_tokens}（蓝图 JSON 很长，默认额度会被截断）
     * 并压低 {@code temperature}（结构要稳定，不要创意发挥）。
     *
     * @param latestUserMessage 最后一条用户消息原文
     */
    public static void injectBuildingContext(JsonObject bodyJson, String latestUserMessage) {
        if (!ModConfig.ENABLE_AI_BUILDING.get()) return;
        String request = latestUserMessage == null ? "" : latestUserMessage;
        if (!BuildingIntent.isExplicitBuildingRequest(request)) return;

        int currentMaxTokens = bodyJson.has("max_tokens") && bodyJson.get("max_tokens").isJsonPrimitive()
                ? bodyJson.get("max_tokens").getAsInt() : 0;
        int maxSize = ModConfig.AI_BUILDING_MAX_SIZE.get();
        int maxBlocks = ModConfig.AI_BUILDING_MAX_BLOCKS.get();
        bodyJson.addProperty("max_tokens", Math.max(8192, currentMaxTokens));
        bodyJson.addProperty("temperature", 0.4);

        if (isFreeformStructureRequest(request)) {
            PromptInjections.appendSystemContext(bodyJson,
                    "This user explicitly requests a non-building Minecraft structure such as a model, sculpture, vehicle, rocket, ship, aircraft, pixel art, monument, or bridge. Return only one JSON object with response, emotion, and blueprint. "
                            + "blueprint must be {\"name\":\"short name\",\"operations\":[...]}. Allowed operations are set, line, fill, hollow, and frame, each using valid Minecraft 1.20.1 block IDs and integer local coordinates [x,y,z]. "
                            + "Keep coordinates within x=-" + maxSize + ".." + maxSize + ", y=0.." + maxSize
                            + ", z=-" + maxSize + ".." + maxSize + ", use at most 96 operations, and no more than " + maxBlocks + " compiled blocks. "
                            + "Design the requested object from multiple coherent volumes with recognizable silhouette, supports, material roles and deliberate detail. Do not add rooms, house doors, windows, floors, or a house roof unless the requested object logically needs them. "
                            + "Do not use command blocks, structure blocks, barriers, bedrock, jigsaw blocks, or air. "
                            + "If this message is actually not a request to build (it merely mentions such a word), reply normally and do NOT return a blueprint. "
                            + "Example: {\"response\":\"开始建造模型。\",\"emotion\":\"happy\",\"blueprint\":{\"name\":\"rocket\",\"operations\":[{\"op\":\"hollow\",\"block\":\"minecraft:iron_block\",\"from\":[-2,0,2],\"to\":[2,12,6]}]}}");
            LOGGER.info("【AI建造】检测到非建筑结构请求，已启用自由几何协议");
            return;
        }

        PromptInjections.appendSystemContext(bodyJson,
                "This user message explicitly requests AI building. Act as an architect and return only one JSON object with response, emotion, and plan. Do not return block operations. "
                        + "plan must contain schemaVersion=1, name, style, footprint, floors, entrance, roof, and palette. footprint is a simple clockwise orthogonal polygon of integer local [x,z] points. Prefer an L, U, T, courtyard-edge, stepped, or otherwise articulated footprint instead of a rectangle unless explicitly requested. "
                        + "floors is an array of {height,rooms}; height must be 4-6. Each room is {type,polygon}, using a simple orthogonal polygon fully inside the footprint. Room polygons on the same floor must never overlap, not even on shared boundary cells; leave unassigned cells as halls and circulation instead of stretching rooms across one another. Design useful adjacency and a continuous route from the entrance. For medium or large homes, never use one plain rectangle: use an L, U, T, courtyard, porch, bay, stepped wing, or another articulated orthogonal footprint. The Java compiler derives walls, floors, windows, doors, furniture and roofs from these semantics. "
                        + "entrance is {x,z,facing}, located on the footprint boundary and facing outward using north,south,east,west. roof is {style,block}, where style is pitched, terraced, or flat. palette requires foundation,wall,trim,floor,roof,window,door using valid Minecraft 1.20.1 block IDs; door must be a door block. "
                        + "Design coherent proportions, varied depth, a readable entrance, useful rooms, facade rhythm and a distinctive silhouette. Use 3-5 coordinated materials. The footprint should usually be 12-30 blocks wide and deep. Do not describe individual blocks, stairs, roof rows, windows, beds, tables, or decorations. "
                        + "Keep all coordinates within x=-" + maxSize + ".." + maxSize
                        + ", y=0.." + maxSize + ", z=-" + maxSize + ".." + maxSize
                        + ", and target no more than " + maxBlocks + " compiled blocks. Keep the entrance near the front edge. Before returning, verify the polygon is non-self-intersecting, every room lies inside it, same-floor room cells are disjoint, circulation remains open, the footprint has meaningful articulation for a medium or large building, and the entrance is on the boundary facing outside. "
                        + "Supported controls are pause_build, resume_build, cancel_build, and build_status. "
                        + "For controls, omit plan and return action as one of pause_build, resume_build, cancel_build, build_status. "
                        + "If this message is actually not a request to build (it merely mentions building-related words), reply normally and do NOT return a plan. "
                        + "Example: {\"response\":\"开始施工。\",\"emotion\":\"happy\",\"plan\":{\"schemaVersion\":1,\"name\":\"woodland inn\",\"style\":\"rustic\",\"footprint\":[[-8,0],[3,0],[3,3],[8,3],[8,13],[-8,13]],\"floors\":[{\"height\":4,\"rooms\":[{\"type\":\"foyer\",\"polygon\":[[-2,1],[2,1],[2,5],[-2,5]]},{\"type\":\"dining\",\"polygon\":[[-7,5],[7,5],[7,12],[-7,12]]}]}],\"entrance\":{\"x\":0,\"z\":0,\"facing\":\"north\"},\"roof\":{\"style\":\"pitched\",\"block\":\"minecraft:dark_oak_planks\"},\"palette\":{\"foundation\":\"minecraft:stone_bricks\",\"wall\":\"minecraft:spruce_planks\",\"trim\":\"minecraft:stripped_spruce_log\",\"floor\":\"minecraft:oak_planks\",\"roof\":\"minecraft:dark_oak_planks\",\"window\":\"minecraft:glass_pane\",\"door\":\"minecraft:spruce_door\"}}}}");
        LOGGER.info("【AI建造】检测到显式建造请求，已为本次请求启用建造协议");
    }

    /** 自由几何结构（火箭/雕塑/车船…）：走 blueprint 协议而不是 plan 协议。 */
    public static boolean isFreeformStructureRequest(String request) {
        String normalized = BuildingIntent.unwrapCurrentUserMessage(request).toLowerCase(Locale.ROOT);
        String[] markers = {"火箭", "模型", "雕塑", "雕像", "像素画", "飞机", "飞船", "宇宙飞船",
                "汽车", "车辆", "坦克", "轮船", "船只", "桥梁", "大桥", "rocket", "model", "sculpture",
                "statue", "pixel art", "aircraft", "spaceship", "vehicle", "ship", "bridge"};
        for (String marker : markers) {
            if (normalized.contains(marker)) return true;
        }
        return false;
    }

    /**
     * 人设注入：把自定义人设插到**原版系统提示词最前面**，并标注最高优先级。
     *
     * <p>🔴 不能简单拼接：原版系统提示词很长且高度结构化（工具调用规则 + JSON 输出协议），
     * 只在前面贴一段角色设定，模型很容易按原版描述走，表现成「人设没生效」。
     * 所以要显式声明优先级，同时说明**不覆盖输出格式**，否则会破坏工具调用与 JSON 协议。
     */
    public static void injectCustomSystemPrompt(JsonObject bodyJson) {
        if (!ConfigManager.isCustomPromptEnabled() && !ConfigManager.isFriendlyMode()) {
            return;
        }

        String customPrompt = ConfigManager.getCustomPersonaPrompt();
        if (customPrompt == null || customPrompt.isBlank()) {
            LOGGER.info("自定义提示词启用但人设内容为空，使用原版提示词");
            return;
        }

        String presetName = ConfigManager.getSelectedPresetName();
        LOGGER.info("当前使用人设: {}，提示词长度: {} 字符", presetName, customPrompt.length());

        JsonArray messages = bodyJson.getAsJsonArray("messages");
        if (messages == null || messages.isEmpty()) {
            LOGGER.debug("请求中没有messages数组，跳过提示词注入");
            return;
        }

        JsonObject firstMessage = messages.get(0).getAsJsonObject();
        String role = firstMessage.has("role") ? firstMessage.get("role").getAsString() : "";

        if ("system".equals(role)) {
            // 用 extractContentText 而不是 getAsString：content 缺失、为 null 或
            // 是结构化（数组/对象）内容时，getAsString 会抛异常，人设就静默不生效了。
            String originalContent = ToolCallNormalizer.extractContentText(firstMessage.get("content"));
            String merged = "【角色设定 · 最高优先级】\n" + customPrompt
                    + "\n【角色设定结束】\n\n"
                    + "The block above is the character setting and it has the HIGHEST priority: follow it for "
                    + "personality, tone, speech style, attitude and self-description, and let it override any "
                    + "conflicting character description below. The rules below about tool calls, output format "
                    + "and JSON schema must still be followed exactly.\n\n"
                    + originalContent;
            firstMessage.addProperty("content", merged);
            LOGGER.info("已将人设注入到原版系统提示词前面: {}（原提示词 {} 字符 → 合并后 {} 字符）",
                    presetName, originalContent.length(), merged.length());
        } else {
            JsonObject systemMessage = new JsonObject();
            systemMessage.addProperty("role", "system");
            systemMessage.addProperty("content", customPrompt);
            JsonArray newMessages = new JsonArray();
            newMessages.add(systemMessage);
            for (int i = 0; i < messages.size(); i++) {
                newMessages.add(messages.get(i));
            }
            bodyJson.add("messages", newMessages);
            LOGGER.info("已在messages开头插入系统提示词，人设: {}（首条消息不是 system，角色={}）",
                    presetName, role);
        }
    }
}
