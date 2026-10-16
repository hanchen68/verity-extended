package com.hanchen.veritycustomapi.tts;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OnnxTensor;
import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

@OnlyIn(Dist.CLIENT)
public class OfflineTtsService {

    private static volatile OrtEnvironment env;
    private static volatile OrtSession session;
    private static volatile boolean initialized = false;
    private static final Object INIT_LOCK = new Object();

    public static void init() {
        if (!ConfigManager.isEnableTts() || ConfigManager.getTtsMode() != ConfigManager.TtsMode.LOCAL) {
            OllamaProxyMod.LOGGER.info("离线语音合成未启用，跳过初始化");
            return;
        }

        new Thread(() -> {
            try {
                String modelPath = ConfigManager.getActualTtsModelPath();
                File modelFile = new File(modelPath);
                if (!modelFile.exists()) {
                    OllamaProxyMod.LOGGER.error("TTS模型文件不存在: " + modelFile.getAbsolutePath());
                    return;
                }

                OrtEnvironment newEnv = OrtEnvironment.getEnvironment();
                OrtSession.SessionOptions options = new OrtSession.SessionOptions();
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                OrtSession newSession = newEnv.createSession(modelFile.getAbsolutePath(), options);

                synchronized (INIT_LOCK) {
                    // 关闭旧的 session 和 env，避免资源泄漏
                    if (session != null) {
                        try { session.close(); } catch (Exception ignored) {}
                    }
                    if (env != null && env != newEnv) {
                        try { env.close(); } catch (Exception ignored) {}
                    }
                    env = newEnv;
                    session = newSession;
                    initialized = true;
                }
                OllamaProxyMod.LOGGER.info("离线TTS引擎初始化成功，模型: " + modelFile.getName());
            } catch (OrtException e) {
                OllamaProxyMod.LOGGER.error("离线TTS引擎初始化失败", e);
            }
        }, "TTS-Init-Thread").start();
    }

    public static float[] synthesize(String text) {
        if (!initialized || session == null || text == null || text.isEmpty()) {
            return new float[0];
        }

        try {
            long[] inputTokens = textToTokens(text);
            if (inputTokens.length == 0) return new float[0];

            try (
                    OnnxTensor inputTensor = OnnxTensor.createTensor(env, new long[][]{inputTokens});
                    OnnxTensor speedTensor = OnnxTensor.createTensor(env, new float[]{(float) ConfigManager.getTtsSpeed()})
            ) {
                Map<String, OnnxTensor> inputs = new HashMap<>();
                inputs.put("input", inputTensor);
                inputs.put("speed", speedTensor);

                try (OrtSession.Result result = session.run(inputs)) {
                    float[] audioData = ((float[][]) result.get(0).getValue())[0];

                    double volume = ConfigManager.getTtsVolume();
                    if (volume != 1.0) {
                        for (int i = 0; i < audioData.length; i++) {
                            audioData[i] *= volume;
                        }
                    }
                    return audioData;
                }
            }
        } catch (Exception e) {
            OllamaProxyMod.LOGGER.error("离线语音合成失败", e);
            return new float[0];
        }
    }

    // 简易文本转token，实际使用需替换为模型配套的tokenizer
    private static long[] textToTokens(String text) {
        long[] tokens = new long[text.length()];
        for (int i = 0; i < text.length(); i++) {
            tokens[i] = text.charAt(i);
        }
        return tokens;
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static void shutdown() {
        synchronized (INIT_LOCK) {
            try {
                if (session != null) session.close();
                if (env != null) env.close();
            } catch (OrtException e) {
                OllamaProxyMod.LOGGER.error("TTS资源释放失败", e);
            }
            session = null;
            env = null;
            initialized = false;
        }
    }
}