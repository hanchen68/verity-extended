package com.hanchen.veritycustomapi.tts.route;

import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.compat.RuntimePlatform;
import com.hanchen.veritycustomapi.tts.AudioPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.lang.reflect.Method;

/**
 * 本地引擎路线：不联网的合成。
 *
 * 当前挂的是离线模型（ONNX）实现。合成结果交回 {@link AudioPlayer} 播放，
 * 音频输出走哪条路（游戏音频通道 / 自带音频设备）由播放器自己决定，与路线选择无关。
 *
 * 整条路都靠反射调进离线服务：ONNX 运行时不保证存在（Android/FCL 上就没有），
 * 直接引用会把类加载失败带进启动链。
 */
@OnlyIn(Dist.CLIENT)
public final class LocalTtsEngine implements TtsEngine {

    private static final String SERVICE_CLASS = "com.hanchen.veritycustomapi.tts.OfflineTtsService";
    private static final String ORT_ENV_CLASS = "ai.onnxruntime.OrtEnvironment";

    private volatile Boolean available;
    private volatile Class<?> serviceClass;

    @Override
    public TtsRouteKind kind() {
        return TtsRouteKind.LOCAL;
    }

    @Override
    public String id() {
        return "local-offline";
    }

    @Override
    public String displayName() {
        return "本地离线模型";
    }

    @Override
    public boolean isAvailable() {
        Boolean cached = available;
        if (cached != null) return cached;
        synchronized (this) {
            if (available != null) return available;
            available = probe();
            return available;
        }
    }

    private boolean probe() {
        if (RuntimePlatform.isAndroidRuntime()) {
            OllamaProxyMod.LOGGER.warn("检测到 Android/FCL 运行环境，本地离线引擎不可用");
            return false;
        }
        try {
            Class<?> ortEnvClass = Class.forName(ORT_ENV_CLASS);
            serviceClass = Class.forName(SERVICE_CLASS);
            // 类在不算数：原生库也要能真加载起来（Android 上常常在这里失败）
            try {
                ortEnvClass.getMethod("getEnvironment").invoke(null);
            } catch (Throwable error) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                OllamaProxyMod.LOGGER.warn("ONNX Runtime 原生库加载失败，本地离线引擎不可用: {}", cause.getMessage());
                return false;
            }
            OllamaProxyMod.LOGGER.info("ONNX Runtime 原生库加载成功，本地离线引擎可用");
            return true;
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.warn("ONNX Runtime 库未找到，本地离线引擎不可用: {}", error.getMessage());
            serviceClass = null;
            return false;
        }
    }

    @Override
    public void warmUp() {
        if (!isAvailable() || serviceClass == null) return;
        try {
            serviceClass.getMethod("init").invoke(null);
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.warn("本地离线引擎预热失败: {}", error.getMessage());
        }
    }

    @Override
    public boolean speak(String text, int entityId, long session) {
        if (!isAvailable() || serviceClass == null) return false;
        try {
            Method isInitialized = serviceClass.getMethod("isInitialized");
            if (!Boolean.TRUE.equals(isInitialized.invoke(null))) {
                // 预热还没跑完（异步加载模型）：这一次先不接管，让后续候选顶上
                return false;
            }
            Method synthesize = serviceClass.getMethod("synthesize", String.class);
            float[] audio = (float[]) synthesize.invoke(null, text);
            if (audio == null || audio.length == 0) return true;
            AudioPlayer.playAsync(audio, entityId);
            return true;
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.warn("本地离线引擎合成失败: {}", error.getMessage());
            return true;
        }
    }

    @Override
    public void stop() {
        AudioPlayer.stopPlayback();
    }

    @Override
    public void shutdown() {
        if (serviceClass == null) return;
        try {
            serviceClass.getMethod("shutdown").invoke(null);
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.warn("本地离线引擎资源释放失败: {}", error.getMessage());
        }
    }

    /** 供自检使用：清掉可用性缓存。 */
    public void resetCacheForTesting() {
        available = null;
        serviceClass = null;
    }

    /** 采样率配置仍由离线服务读取，这里只是把配置项暴露出来便于界面提示。 */
    public int configuredSampleRate() {
        return ConfigManager.getTtsSampleRate();
    }
}
