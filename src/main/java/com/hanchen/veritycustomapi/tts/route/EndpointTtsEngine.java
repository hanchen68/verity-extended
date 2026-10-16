package com.hanchen.veritycustomapi.tts.route;

import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.tts.OnlineTtsService;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.lang.reflect.Method;

/**
 * 网络端点路线：把文字发给 HTTP 接口取回音频。
 *
 * 内部有两级：能流式就先流式（边收边播，首字更快），流式条件不满足时退回一次性合成。
 * 这两级对上层是透明的——上层只知道「端点路线接管了这句」。
 *
 * 可用性 = 当前解析得出至少一条可用线路；线路来自云端配置，会变，所以结果只缓存很短时间。
 */
@OnlyIn(Dist.CLIENT)
public final class EndpointTtsEngine implements TtsEngine {

    private static final String STREAM_HELPER_CLASS = "com.hanchen.veritycustomapi.tts.StreamTtsHelper";
    private static final long AVAILABILITY_TTL_MS = 5000L;

    private volatile boolean cachedAvailable;
    private volatile long cachedAt;

    @Override
    public TtsRouteKind kind() {
        return TtsRouteKind.ENDPOINT;
    }

    @Override
    public String id() {
        return "endpoint-http";
    }

    @Override
    public String displayName() {
        return "网络端点接口";
    }

    /**
     * 可用性 = 当前解析得出至少一条带地址的线路。
     *
     * 「有条目」不算数：没配端点时配置快照里仍然会有一条空地址的占位线路，
     * 若把它当可用，降级链就永远走不到本地/原版那一级，用户只会听到一片安静。
     * 所以这里必须要求地址非空——没配就是没配，该让位就让位。
     *
     * 线路来自云端配置，会变，所以结果只缓存很短时间。
     */
    @Override
    public boolean isAvailable() {
        long now = System.currentTimeMillis();
        if (now - cachedAt < AVAILABILITY_TTL_MS) return cachedAvailable;
        boolean available;
        try {
            available = ConfigManager.snapshotTtsRoutes().stream()
                    .anyMatch(route -> route.apiUrl() != null && !route.apiUrl().isBlank());
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.debug("端点线路探测失败: {}", error.getMessage());
            available = false;
        }
        cachedAvailable = available;
        cachedAt = now;
        return available;
    }

    @Override
    public boolean speak(String text, int entityId, long session) {
        if (tryStream(text, entityId, session)) return true;
        OnlineTtsService.speakAsync(text, entityId, session);
        return true;
    }

    /** 流式合成；条件不满足或流式实现不可用时返回 false，交回给一次性合成。 */
    private boolean tryStream(String text, int entityId, long session) {
        try {
            Class<?> helper = Class.forName(STREAM_HELPER_CLASS);
            Method method = helper.getMethod("tryPlay", String.class, String.class, String.class,
                    String.class, String.class, int.class, long.class);
            return Boolean.TRUE.equals(method.invoke(null, text,
                    ConfigManager.getTtsApiUrl(), ConfigManager.getTtsApiKey(),
                    ConfigManager.getTtsApiModel(), ConfigManager.getTtsApiVoice(), entityId, session));
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.debug("流式合成不可用，改用一次性合成: {}", error.getMessage());
            return false;
        }
    }

    @Override
    public void stop() {
        OnlineTtsService.stopPlayback();
    }

    @Override
    public void shutdown() {
        OnlineTtsService.shutdown();
    }

    /** 供自检使用：清掉可用性缓存。 */
    public void resetCacheForTesting() {
        cachedAt = 0L;
        cachedAvailable = false;
    }
}
