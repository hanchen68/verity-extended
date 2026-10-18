package com.hanchen.veritycustomapi.tts;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.compat.BilingualSpeechText;
import com.hanchen.veritycustomapi.compat.OverheadOnlySpeechText;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import varmite.verity.entity.verity.VerityEntity;

import java.util.Set;

@OnlyIn(Dist.CLIENT)
public class TtsChatListener {
    private static final Gson GSON = new Gson();
    private static final Set<String> IDLE_MESSAGE_BLACKLIST = Set.of(
            "I'm alone... where did you go?",
            "Hello?",
            "Anyone there?"
    );

    public static void handleChatMessage(String message) {
        if (!ConfigManager.isEnableTts()) return;
        if (message == null || message.isBlank()) return;
        if (OverheadOnlySpeechText.decode(message).overheadOnly()) return;
        if (BilingualSpeechText.decode(message).bilingual()) return;

        String cleanText = message.trim();
        cleanText = extractAssistantText(cleanText);
        // 推理模型偶尔把内部思考标签混进聊天正文；这部分绝不能进入 TTS。
        cleanText = cleanThinkingTags(cleanText);
        // 半截双语消息（模型只回了 "Hello.<|verity_zh|>"）也不能把分隔符念出来
        cleanText = BilingualSpeechText.stripDelimiter(cleanText);

        // 前缀识别统一走 VeritySpeaker：玩家改了 Verity 自定义名字后依然能正确朗读
        String verityContent = com.hanchen.veritycustomapi.compat.VeritySpeaker.stripPrefix(cleanText);
        if (verityContent == null) {
            return;
        }

        if (verityContent.isBlank()) {
            return;
        }

        if (verityContent.startsWith("ERROR") || verityContent.contains("代理服务错误")) {
            return;
        }

        verityContent = filterText(verityContent);
        // 模型或聊天翻译插件可能留下「[翻译] / 译文：」这类标签，别让 TTS 念出来
        verityContent = BilingualSpeechText.stripLabels(verityContent);

        if (verityContent.isBlank()) {
            return;
        }

        if (IDLE_MESSAGE_BLACKLIST.contains(verityContent)) {
            return;
        }

        com.hanchen.veritycustomapi.OllamaProxyMod.LOGGER.debug("【TTS触发】Verity回复: {}", verityContent.length() > 50 ? verityContent.substring(0, 50) + "..." : verityContent);

        TtsManager.speak(verityContent, resolveVerityEntityId());
    }

    private static int resolveVerityEntityId() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return -1;
        }

        Entity nearest = null;
        double nearestDistance = Double.MAX_VALUE;

        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (!(entity instanceof VerityEntity) || !entity.isAlive() || entity.isRemoved()) {
                continue;
            }

            double distance = minecraft.player == null
                    ? 0.0
                    : minecraft.player.distanceToSqr(entity);
            if (distance < nearestDistance) {
                nearest = entity;
                nearestDistance = distance;
            }
        }

        return nearest == null ? -1 : nearest.getId();
    }

    private static String extractAssistantText(String text) {
        try {
            int jsonStart = text.indexOf('{');
            if (jsonStart < 0) {
                return text;
            }
            String prefix = text.substring(0, jsonStart).trim();
            JsonObject root = GSON.fromJson(text.substring(jsonStart), JsonObject.class);
            if (root != null && root.has("choices")) {
                String content = root.getAsJsonArray("choices")
                        .get(0).getAsJsonObject()
                        .getAsJsonObject("message")
                        .get("content").getAsString();
                return prefix.isBlank() ? content : prefix + " " + content;
            }
            if (root != null && root.has("message")) {
                String content = root.get("message").getAsString();
                return prefix.isBlank() ? content : prefix + " " + content;
            }
        } catch (RuntimeException ignored) {
        }
        return text;
    }

    private static String cleanThinkingTags(String text) {
        if (text == null || text.isBlank()) return "";
        return text.replaceAll("(?s)<think>.*?</think>", "")
                .replaceAll("(?s)<thinking>.*?</thinking>", "")
                .replaceAll("(?s)<reasoning>.*?</reasoning>", "")
                .trim();
    }

    private static String filterText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        String filtered = text;

        filtered = filtered.replaceAll("\\p{C}", "");

        filtered = filtered.replaceAll("[\\uD83C\\uDF00-\\uD83D\\uDDFF\\uD83D\\uDE00-\\uD83D\\uDE4F\\uD83D\\uDE80-\\uD83D\\uDEFF\\uD83C\\uDDE6-\\uD83C\\uDDFF\\uD83C\\uDD80-\\uD83C\\uDD9F\\u2600-\\u26FF\\u2700-\\u27BF\\u2300-\\u23FF\\u2100-\\u21FF\\u2B00-\\u2BFF\\u2A00-\\u2AFF]", "");

        filtered = filtered.replaceAll("[^\\u4e00-\\u9fa5a-zA-Z0-9，。！？、；：“”‘’（）【】《》…—,.!?;:\"'()\\[\\]<>\\-\\s]", "");

        filtered = filtered.replaceAll("\\s+", " ").trim();

        return filtered;
    }
}
