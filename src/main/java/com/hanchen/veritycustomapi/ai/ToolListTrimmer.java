package com.hanchen.veritycustomapi.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.OllamaProxyMod;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 两阶段工具清单：核心工具（表达/定位/跟随/声音/记忆）每轮常驻，其余按玩家最新一句话的
 * 意图关键词动态装载；清单不超过 PASS_THROUGH_LIMIT 个时原样放行。匹配按工具名小写子串进行，
 * 不依赖具体名单，原版加新工具时自动归组；解析失败原样返回。
 */
public final class ToolListTrimmer {
    /** 工具总数不超过这个值时直接放行：全量清单不大，两阶段反而多此一举。 */
    static final int PASS_THROUGH_LIMIT = 40;

    /** 核心工具名片段：每轮都可能用，常驻。 */
    private static final String[] CORE_FRAGMENTS = {
            "expression", "emotion", "coords", "coordinate", "distance", "dimension",
            "inventory", "backpack", "follow", "sound", "kerma", "memory", "mood", "wave"
    };

    /** 意图关键词 -> 工具名片段；玩家话里命中关键词才装载这一组非核心工具。 */
    private static final Object[][] INTENT_GROUPS = {
            {"传送|坐标|位置|哪里|过去|过去一下|biome|群系", new String[]{"teleport", "biome", "warp", "goto"}},
            {"搜索|搜|查|找|附近|天气", new String[]{"web_search", "search", "nearest", "locate", "find", "weather", "scan"}},
            {"给我|送我|送给|给点|送点|给个|送个|给我拿|帮我拿|拿走|物品|东西|装备|我要|想要|要个|要一个|来个|来一个|来点|弄个|搞个|拿个", new String[]{"give", "item", "equip", "hand"}},
            {"音乐|歌|唱|播放|音效|唱片", new String[]{"music", "play", "sing", "record", "disc", "note_block"}},
            {"建造|建筑|盖|房子|搭|图纸", new String[]{"build", "blueprint", "house", "structure", "schematic"}},
            {"记住|忘记|回忆|约定|承诺|日记|记事", new String[]{"recall", "remember", "promise", "diary", "journal", "note"}},
            {"种|收|耕|农|浇|养殖|喂", new String[]{"farm", "plant", "crop", "harvest", "water", "breed", "feed", "tame"}},
            // 摄像头观察：玩家说「看看我」「摄像头」这类话时才装载 look_through_camera。
            // 不补这一组的话，工具总数超过 PASS_THROUGH_LIMIT 时它会被当成无关工具裁掉，
            // 表现就是「AI 明明有这个能力却从来不调用」。
            {"看|看看|看到|摄像头|镜头|画面|照片|屏幕|观察|样子|长什么样",
                    new String[]{"camera", "look", "vision", "screenshot"}},
    };

    private static final List<Pattern> INTENT_PATTERNS = new ArrayList<>();
    static {
        for (Object[] group : INTENT_GROUPS) {
            INTENT_PATTERNS.add(Pattern.compile((String) group[0]));
        }
    }

    private ToolListTrimmer() {
    }

    /** 代理转发前的工具清单瘦身；没有可瘦身的内容时原样返回（返回同一字符串）。 */
    public static String apply(String rawBody) {
        if (!isEnabled() || rawBody == null || rawBody.isBlank()) {
            return rawBody;
        }
        try {
            JsonObject body = JsonParser.parseString(rawBody).getAsJsonObject();
            return applyInPlace(body) ? body.toString() : rawBody;
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.warn("工具清单瘦身失败，原样转发: {}", error.toString());
            return rawBody;
        }
    }

    /**
     * 原地瘦身工具清单：直接改传入的 {@link JsonObject}，**不做序列化**。
     *
     * @return 是否真的改动过
     */
    public static boolean applyInPlace(JsonObject body) {
        if (!isEnabled() || body == null) {
            return false;
        }
        try {
            if (!body.has("tools") || !body.get("tools").isJsonArray()) {
                return false;
            }
            JsonArray tools = body.getAsJsonArray("tools");
            int total = tools.size();
            if (total <= PASS_THROUGH_LIMIT) {
                return false;
            }
            String latestUser = latestUserText(body.getAsJsonArray("messages"));
            if (latestUser == null || latestUser.isBlank()) {
                return false;
            }
            JsonArray kept = new JsonArray();
            for (JsonElement tool : tools) {
                if (!tool.isJsonObject()) {
                    kept.add(tool); // 陌生结构保守保留
                    continue;
                }
                String name = toolName(tool.getAsJsonObject());
                if (name.isEmpty() || isCore(name) || matchIntent(name, latestUser)) {
                    kept.add(tool);
                }
            }
            if (kept.size() == total) {
                return false;
            }
            body.add("tools", kept);
            OllamaProxyMod.LOGGER.debug("【省 token】工具清单两阶段装载：{} -> {} 个（本轮：{}）",
                    total, kept.size(), clip(latestUser, 40));
            return true;
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.warn("工具清单瘦身失败，保留原请求体: {}", error.toString());
            return false;
        }
    }

    private static boolean isCore(String name) {
        return containsAny(name, CORE_FRAGMENTS);
    }

    private static boolean matchIntent(String name, String userText) {
        for (int i = 0; i < INTENT_GROUPS.length; i++) {
            if (!INTENT_PATTERNS.get(i).matcher(userText).find()) {
                continue;
            }
            if (containsAny(name, (String[]) INTENT_GROUPS[i][1])) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String name, String[] fragments) {
        String lower = name.toLowerCase();
        for (String fragment : fragments) {
            if (lower.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /** 最新一条 user 消息的纯文本；找不到时返回 null。 */
    private static String latestUserText(JsonArray messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            JsonElement element = messages.get(i);
            if (!element.isJsonObject()) continue;
            JsonObject message = element.getAsJsonObject();
            JsonElement role = message.get("role");
            if (role == null || !role.isJsonPrimitive() || !"user".equals(role.getAsString())) {
                continue;
            }
            return textOf(message);
        }
        return null;
    }

    /** 取消息正文的纯文本：字符串直接返回，多模态数组拼接 text 片段。 */
    private static String textOf(JsonObject message) {
        JsonElement content = message.get("content");
        if (content == null || content.isJsonNull()) {
            return "";
        }
        if (content.isJsonPrimitive()) {
            return content.getAsString();
        }
        if (!content.isJsonArray()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (JsonElement part : content.getAsJsonArray()) {
            if (!part.isJsonObject()) continue;
            JsonElement piece = part.getAsJsonObject().get("text");
            if (piece != null && piece.isJsonPrimitive()) {
                if (text.length() > 0) text.append(' ');
                text.append(piece.getAsString());
            }
        }
        return text.toString();
    }

    private static String toolName(JsonObject tool) {
        if (!tool.has("function") || !tool.get("function").isJsonObject()) {
            return "";
        }
        JsonObject function = tool.getAsJsonObject("function");
        return function.has("name") && function.get("name").isJsonPrimitive()
                ? function.get("name").getAsString() : "";
    }

    private static String clip(String text, int limit) {
        String value = text.replaceAll("\\s+", " ").strip();
        return value.length() <= limit ? value : value.substring(0, limit) + "…";
    }

    /** 配置不可用（自检环境）时按默认开启处理。 */
    private static boolean isEnabled() {
        try {
            return ConfigManager.isConversationTrimEnabled();
        } catch (Throwable ignored) {
            return true;
        }
    }
}
