package com.hanchen.veritycustomapi.client;

import com.hanchen.veritycustomapi.util.Silent;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.compat.OriginalVerityChatBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * 摄像头技能：AI 在对话里请求观察时抓一帧，再经原版 Verity 对话链路接回同一场对话。
 * 代理层只认本次请求最后一条 user 消息来判回环，历史里残留的标记只清掉、不参与注入。
 * 只客户端加载，公共代码调用它时得自己兜住异常。
 */
public final class CameraChatVision {

    /** 回环追问用的标记。代理层靠它把画面注入这一次请求，注入后从文本里删掉。 */
    public static final String FOLLOW_UP_MARKER = "[VERITY_CAMERA_FOLLOWUP]";

    private static final String FOLLOW_UP_PROMPT = FOLLOW_UP_MARKER
            + " 上面附带的图片就是刚采集到的画面（可能包含玩家摄像头画面和他的游戏内屏幕画面）。"
            + "直接说出你看到了什么，再接着聊，不要提这句话本身。";

    private static final long SKILL_COOLDOWN_MS = 30000L;
    private static final long NOTIFY_COOLDOWN_MS = 15000L;
    private static final long PENDING_TTL_MS = 120000L;
    private static final int MAX_VISION_ATTEMPTS = 2;
    private static final int MAX_ATTACHED_BODY_BYTES = 900 * 1024;

    private static final Gson GSON = new Gson();
    private static final ExecutorService CAPTURE_POOL = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Verity-Camera-Skill");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicReference<CameraCaptureService.CaptureResult> PENDING = new AtomicReference<>();
    private static final AtomicInteger VISION_ATTEMPTS = new AtomicInteger();
    private static volatile long pendingAt;
    private static final AtomicLong LAST_SKILL_REQUEST_AT = new AtomicLong();
    private static final AtomicLong LAST_NOTIFY_AT = new AtomicLong();

    /** 标记形态：{"camera_look": true} / "camera_look":true / camera_look：true 等。 */
    private static final Pattern SKILL_MARKER = Pattern.compile(
            "[,，\\s]*\\{?\\s*[\"']?camera_look[\"']?\\s*[:：]\\s*(true|false|1|0)\\s*}?",
            Pattern.CASE_INSENSITIVE);
    /** 是否明确请求观察（true / 1）。 */
    private static final Pattern SKILL_REQUESTED = Pattern.compile(
            "camera_look[\"']?\\s*[:：]\\s*(true|1)", Pattern.CASE_INSENSITIVE);
    /** 摄像头、游戏画面或图片相关的明确上下文。 */
    private static final Pattern CAMERA_CONTEXT = Pattern.compile(
            "摄像头|摄像|相机|镜头|监控|webcam|camera|cam|画面|照片|图片|视频|"
                    + "屏幕|截屏|截图|游戏里|游戏内|在玩|我在干什么|screen|screenshot|game",
            Pattern.CASE_INSENSITIVE);
    /** 要求 AI 查看、拍摄或判断画面的意图。 */
    private static final Pattern CAMERA_VIEW_INTENT = Pattern.compile(
            "看(?:看|一下|一眼|清|见|得见)?|瞧(?:瞧|一下)?|观察|识别|辨认|拍(?:一张|一下)?|调用|打开|开启|启动|启用|使用|接入|"
                    + "能(?:不)?能看(?:到|见)?|可(?:不)?可以看|是否看(?:到|见)?|有没有看(?:到|见)?|"
                    + "can you (?:see|look at|view)|(?:please )?(?:look at|see|view|check|identify|take a photo|use|open|enable|start|turn on|activate)",
            Pattern.CASE_INSENSITIVE);
    /** 不提设备时仍足够明确的“查看玩家本人”请求。 */
    private static final Pattern SELF_VIEW_REQUEST = Pattern.compile(
            "(?:看(?:看|一下|一眼)?|瞧(?:瞧|一下)?|观察|看看).{0,6}(?:我|本人|我的脸|我长什么样|我现在)|"
                    + "(?:能|可)??(?:不)?(?:能|可以)?(?:看(?:到|见)|看清).{0,6}(?:我|我的脸)|"
                    + "(?:can you see|look at) me",
            Pattern.CASE_INSENSITIVE);

    /** 显示层处理结果。 */
    public record SkillDisplayResult(String text, boolean cancel) {}

    private CameraChatVision() {
    }

    /** 摄像头技能当前是否可用（需要同时开启摄像头总开关和技能开关）。 */
    public static boolean isSkillAvailable() {
        return ConfigManager.isCameraEnabled() && ConfigManager.isCameraSkillEnabled();
    }

    /** 聊天显示层入口。技能可用、且这句确实是观察请求时返回 cancel=true 把这句话藏起来，等画面就绪后由回环回复补上结果。 */
    public static SkillDisplayResult handleDisplayedSkillRequest(String text) {
        if (text == null || text.isBlank() || !containsMarker(text)) {
            return new SkillDisplayResult(text, false);
        }
        boolean requested = SKILL_REQUESTED.matcher(text).find();
        String cleaned = stripSkillMarker(text);
        if (!requested) {
            return new SkillDisplayResult(cleaned, false);
        }
        if (!isSkillAvailable()) {
            notifyPlayer("§c【视觉】AI 想看你，但视觉功能未启用（配置 → 摄像头视觉）");
            return new SkillDisplayResult(cleaned, false);
        }
        OllamaProxyMod.LOGGER.info("从聊天显示层检测到摄像头技能请求，开始观察");
        boolean started = requestSkillLook();
        return new SkillDisplayResult(cleaned, started);
    }

    /** 显示兜底：把模型误写进正文的技能标记剥离掉。 */
    public static String stripSkillMarker(String text) {
        if (text == null || text.isBlank() || !containsMarker(text)) {
            return text;
        }
        String cleaned = SKILL_MARKER.matcher(text).replaceAll("")
                .replaceAll("[\\s,，、;；]+$", "")
                .trim();
        return cleaned.isEmpty() ? text : cleaned;
    }

    /** 判断请求中最后一条用户消息是否明确要求查看摄像头；兜底路径，不依赖模型返回 camera_look。 */
    public static boolean containsExplicitCameraRequest(String body) {
        JsonObject root = parseObject(body);
        if (root == null || !root.has("messages") || !root.get("messages").isJsonArray()) {
            return false;
        }
        JsonArray messages = root.getAsJsonArray("messages");
        for (int i = messages.size() - 1; i >= 0; i--) {
            JsonElement element = messages.get(i);
            if (!element.isJsonObject()) continue;
            JsonObject message = element.getAsJsonObject();
            if (!message.has("role") || !"user".equals(message.get("role").getAsString())) continue;
            String text = message.has("content") ? extractText(message.get("content")) : null;
            if (text == null || text.contains(FOLLOW_UP_MARKER)) {
                return false;
            }
            return isExplicitCameraRequest(text);
        }
        return false;
    }

    private static boolean isExplicitCameraRequest(String text) {
        String normalized = text.replaceAll("[\\u3000\\s]+", " ").trim();
        boolean hasCameraContext = CAMERA_CONTEXT.matcher(normalized).find();
        boolean hasViewIntent = CAMERA_VIEW_INTENT.matcher(normalized).find();
        return (hasCameraContext && hasViewIntent) || SELF_VIEW_REQUEST.matcher(normalized).find();
    }

    /** 技能请求是否仍处于冷却窗口内，冷却中返回 true。 */
    public static boolean isSkillRequestPending() {
        long last = LAST_SKILL_REQUEST_AT.get();
        return last > 0L && System.currentTimeMillis() - last < SKILL_COOLDOWN_MS;
    }

    public static boolean requestSkillLook() {
        if (!isSkillAvailable()) {
            return false;
        }
        long now = System.currentTimeMillis();
        long last = LAST_SKILL_REQUEST_AT.get();
        if (now - last < SKILL_COOLDOWN_MS) {
            OllamaProxyMod.LOGGER.debug("摄像头技能调用被冷却拦截");
            return false;
        }
        if (!LAST_SKILL_REQUEST_AT.compareAndSet(last, now)) {
            return false;
        }
        OllamaProxyMod.LOGGER.info("AI 调用摄像头技能，开始采集画面");
        try {
            CAPTURE_POOL.execute(() -> {
                CameraCaptureService.CaptureResult result = CameraCaptureService.capture();
                if (!result.ok()) {
                    LAST_SKILL_REQUEST_AT.set(0L);
                    OllamaProxyMod.LOGGER.warn("摄像头技能采集失败({}): {}", result.backend(), result.error());
                    notifyPlayer("§c【视觉】采集失败：" + result.error());
                    return;
                }
                PENDING.set(result);
                VISION_ATTEMPTS.set(0);
                pendingAt = System.currentTimeMillis();
                Minecraft.getInstance().execute(CameraChatVision::continueDialogueWithCapture);
            });
            return true;
        } catch (RuntimeException e) {
            OllamaProxyMod.LOGGER.warn("摄像头技能任务提交失败: {}", e.toString());
            return false;
        }
    }

    /** 代理层调用：若存在待注入画面且本次请求最后一条 user 消息为本轮回环追问，则把画面写成多模态消息。 */
    public static String maybeInjectPending(String body) {
        if (body == null || body.isBlank() || !body.contains(FOLLOW_UP_MARKER)) {
            return body;
        }
        JsonObject root = parseObject(body);
        JsonObject lastUser = null;
        String lastUserText = null;
        if (root != null) {
            JsonArray messages = root.has("messages") && root.get("messages").isJsonArray()
                    ? root.getAsJsonArray("messages") : null;
            if (messages != null) {
                for (int i = messages.size() - 1; i >= 0; i--) {
                    JsonElement element = messages.get(i);
                    if (!element.isJsonObject()) continue;
                    JsonObject message = element.getAsJsonObject();
                    if (!message.has("role") || !"user".equals(message.get("role").getAsString())) continue;
                    if (!message.has("content") || message.get("content").isJsonNull()) continue;
                    lastUser = message;
                    lastUserText = extractText(message.get("content"));
                    break;
                }
            }
        }
        // 最后一条 user 消息不带标记：说明只是历史残留，清理掉即可
        if (lastUser == null || lastUserText == null || !lastUserText.contains(FOLLOW_UP_MARKER)) {
            return stripFollowUpMarker(body);
        }

        CameraCaptureService.CaptureResult capture = PENDING.get();
        if (capture == null || !capture.ok()) {
            OllamaProxyMod.LOGGER.warn("技能回环请求到达，但没有可用的摄像头画面");
            return stripFollowUpMarker(body);
        }
        long age = System.currentTimeMillis() - pendingAt;
        if (age > PENDING_TTL_MS) {
            PENDING.compareAndSet(capture, null);
            VISION_ATTEMPTS.set(0);
            OllamaProxyMod.LOGGER.warn("摄像头画面已过期（{}ms），本次不注入", age);
            return stripFollowUpMarker(body);
        }
        String injected = injectInto(root, lastUser, lastUserText, capture);
        if (injected == null) {
            PENDING.compareAndSet(capture, null);
            VISION_ATTEMPTS.set(0);
            notifyPlayer("§c【视觉】画面注入失败，本次观察未生效");
            return stripFollowUpMarker(body);
        }
        int attempt = VISION_ATTEMPTS.incrementAndGet();
        OllamaProxyMod.LOGGER.info("技能回环已注入摄像头画面: backend={}, ~{} bytes, attempt={}",
                capture.backend(), capture.base64Jpeg().length() * 3 / 4, attempt);
        return injected;
    }

    /** 响应有文本才消费画面；首次空响应保留画面供 Verity 的自动重试再次附图。 */
    public static void completeVisionRequest(boolean hasUsableText) {
        CameraCaptureService.CaptureResult capture = PENDING.get();
        if (capture == null) return;
        int attempts = VISION_ATTEMPTS.get();
        if (hasUsableText) {
            PENDING.compareAndSet(capture, null);
            VISION_ATTEMPTS.set(0);
            OllamaProxyMod.LOGGER.info("视觉请求已返回有效文本，画面已消费");
            return;
        }
        if (attempts < MAX_VISION_ATTEMPTS) {
            OllamaProxyMod.LOGGER.warn("带图请求未返回可显示文本，保留画面等待自动重试 ({}/{})",
                    attempts, MAX_VISION_ATTEMPTS);
            return;
        }
        PENDING.compareAndSet(capture, null);
        VISION_ATTEMPTS.set(0);
        OllamaProxyMod.LOGGER.warn("带图请求连续 {} 次未返回可显示文本", attempts);
        notifyVisionFailure();
    }

    // ==================== 内部实现 ====================

    private static JsonObject parseObject(String body) {
        try {
            JsonElement parsed = GSON.fromJson(body, JsonElement.class);
            return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 采集完成后，用原版对话链路把这场对话接下去。 */
    private static void continueDialogueWithCapture() {
        Minecraft minecraft = Minecraft.getInstance();
        try {
            MinecraftServer server = minecraft.getSingleplayerServer();
            ServerPlayer target = resolveServerPlayer(minecraft, server);
            if (server == null || target == null) {
                // 非单机环境拿不到服务端玩家：退回本地观察链路
                OllamaProxyMod.LOGGER.info("技能回环无法接入对话链路，回退到本地观察");
                VerityAwarenessManager.triggerCameraLook();
                return;
            }
            server.execute(() -> OriginalVerityChatBridge.queryAi(target, FOLLOW_UP_PROMPT));
        } catch (Throwable t) {
            OllamaProxyMod.LOGGER.warn("技能回环接入对话失败: {}", t.toString());
            notifyPlayer("§c【视觉】接入对话失败：" + t.getMessage());
            VerityAwarenessManager.triggerCameraLook();
        }
    }

    private static ServerPlayer resolveServerPlayer(Minecraft minecraft, MinecraftServer server) {
        if (server == null) {
            return null;
        }
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) {
            return null;
        }
        if (minecraft.player != null) {
            for (ServerPlayer serverPlayer : players) {
                if (serverPlayer.getUUID().equals(minecraft.player.getUUID())) {
                    return serverPlayer;
                }
            }
        }
        return players.get(0);
    }

    /** 清理请求体里所有残留的标记（含历史消息），避免模型看到内部标记。 */
    private static String stripFollowUpMarker(String body) {
        return body.replace(FOLLOW_UP_MARKER, "").replace("  ", " ");
    }

    /** 把画面写成 OpenAI 多模态格式，替换目标 user 消息的内容。 */
    private static String injectInto(JsonObject root, JsonObject target, String originalText,
                                     CameraCaptureService.CaptureResult capture) {
        String cleaned = originalText.replace(FOLLOW_UP_MARKER, "").trim();

        JsonArray contentArray = new JsonArray();
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", cleaned + buildImageHint(capture));
        contentArray.add(textPart);

        if (capture.ok()) {
            contentArray.add(imagePart(capture.dataUri()));
        }
        if (capture.hasAttachedScreen()) {
            contentArray.add(imagePart(capture.screenDataUri()));
        }

        target.add("content", contentArray);

        String visionModel = ConfigManager.getCameraVisionModel();
        if (visionModel != null && !visionModel.isBlank()) {
            root.addProperty("model", visionModel.trim());
        }
        String result = GSON.toJson(root);
        if (result.getBytes(StandardCharsets.UTF_8).length > MAX_ATTACHED_BODY_BYTES) {
            OllamaProxyMod.LOGGER.warn("技能回环请求体过大，放弃注入");
            return null;
        }
        return result;
    }

    private static String extractText(JsonElement content) {
        if (content.isJsonPrimitive()) {
            return content.getAsString();
        }
        if (!content.isJsonArray()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (JsonElement part : content.getAsJsonArray()) {
            if (!part.isJsonObject()) continue;
            JsonObject object = part.getAsJsonObject();
            if (object.has("text") && object.get("text").isJsonPrimitive()) {
                if (builder.length() > 0) builder.append('\n');
                builder.append(object.get("text").getAsString());
            }
        }
        return builder.length() == 0 ? "" : builder.toString();
    }

    private static String buildImageHint(CameraCaptureService.CaptureResult capture) {
        String sourceDesc;
        if (capture.hasCamera() && capture.hasAttachedScreen()) {
            sourceDesc = "玩家摄像头拍下的真实画面，以及他当前的游戏内屏幕画面";
        } else if (capture.hasCamera()) {
            sourceDesc = "玩家摄像头刚刚拍下的真实画面";
        } else if (capture.hasScreen()) {
            sourceDesc = "玩家当前的游戏内屏幕画面（Minecraft 屏幕截图）";
        } else {
            sourceDesc = "刚刚采集到的画面";
        }
        if (ConfigManager.isCameraAllowModelToSee()) {
            return "\n\n（上面附带的就是" + sourceDesc + "。请务必先描述画面里的内容，"
                    + "再继续对话；不要假装看不到，也不要编造画面里没有的东西。）";
        }
        return "\n\n（附带图片仅作上下文参考，除非玩家明确问起，否则不必描述它。）";
    }

    /** 构造一条 OpenAI 多模态 image_url 片段。 */
    private static JsonObject imagePart(String dataUri) {
        JsonObject imagePart = new JsonObject();
        imagePart.addProperty("type", "image_url");
        JsonObject imageUrl = new JsonObject();
        imageUrl.addProperty("url", dataUri);
        imagePart.add("image_url", imageUrl);
        return imagePart;
    }

    private static String resolveVisionModelHint() {
        String visionModel = ConfigManager.getCameraVisionModel();
        return visionModel == null || visionModel.isBlank() ? "沿用线路模型" : visionModel.trim();
    }

    private static boolean containsMarker(String text) {
        return text.toLowerCase(Locale.ROOT).contains("camera_look");
    }

    /** 连续带图请求都没有可显示文本时，给出不带错误归因的排查提示。 */
    private static void notifyVisionFailure() {
        notifyPlayer("§c【视觉】连续请求未返回可显示内容，请检查视觉模型名、线路兼容性或稍后重试");
    }

    private static void notifyPlayer(String message) {
        long now = System.currentTimeMillis();
        long last = LAST_NOTIFY_AT.get();
        if (now - last < NOTIFY_COOLDOWN_MS || !LAST_NOTIFY_AT.compareAndSet(last, now)) {
            return;
        }
        try {
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.execute(() -> {
                if (minecraft.player != null) {
                    minecraft.player.displayClientMessage(Component.literal(message), false);
                }
            });
        } catch (Throwable ignored) { Silent.ignore("CameraChatVision", ignored); }
    }
}
