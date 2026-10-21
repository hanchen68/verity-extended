package com.hanchen.veritycustomapi.stt;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.function.Consumer;

/**
 * Android/FCL 系统语音识别回退。
 *
 * <p>不依赖扩展的在线 STT 配置，直接调用手机自带的
 * {@code android.speech.SpeechRecognizer}。桌面端不会加载这个类。
 */
@OnlyIn(Dist.CLIENT)
public final class AndroidSystemSpeechRecognizer {
    private static final long RESULT_TIMEOUT_MS = 8_000L;

    private final Consumer<String> onResult;
    private final Consumer<String> onError;
    private volatile Object speechRecognizer;
    private volatile boolean active;
    private volatile long startedAt;

    public AndroidSystemSpeechRecognizer(Consumer<String> onResult, Consumer<String> onError) {
        this.onResult = onResult;
        this.onError = onError;
    }

    public boolean isActive() {
        return active;
    }

    /**
     * 在客户端主线程创建并启动系统识别器。
     * 返回 false 表示当前环境连 Android 类都找不到。
     */
    public boolean start() {
        if (active) return true;
        try {
            Class.forName("android.speech.SpeechRecognizer");
        } catch (Throwable t) {
            return false;
        }
        active = true;
        startedAt = System.currentTimeMillis();
        runOnAndroidMain(() -> {
            try {
                Object context = getAndroidContext();
                if (context == null) {
                    fail("无法获取 Android 上下文");
                    return;
                }
                Class<?> contextClass = Class.forName("android.content.Context");
                Class<?> intentClass = Class.forName("android.content.Intent");
                Class<?> listenerClass = Class.forName("android.speech.RecognitionListener");
                Class<?> speechClass = Class.forName("android.speech.SpeechRecognizer");

                Object recognizer = speechClass
                        .getMethod("createSpeechRecognizer", contextClass)
                        .invoke(null, context);
                if (recognizer == null) {
                    fail("手机系统没有可用的语音识别服务");
                    return;
                }
                Object listener = Proxy.newProxyInstance(
                        listenerClass.getClassLoader(),
                        new Class<?>[]{listenerClass},
                        new ListenerHandler());
                speechClass.getMethod("setRecognitionListener", listenerClass)
                        .invoke(recognizer, listener);
                speechClass.getMethod("startListening", intentClass)
                        .invoke(recognizer, createIntent(intentClass));
                speechRecognizer = recognizer;
                OllamaProxyMod.LOGGER.info("Android 系统语音识别已启动");
                scheduleTimeout();
            } catch (Throwable t) {
                fail("启动系统语音识别失败: " + shortMessage(t));
            }
        });
        return true;
    }

    /** 松开语音键：让系统识别器收尾，结果通过回调返回。 */
    public void stop() {
        if (!active) return;
        runOnAndroidMain(() -> {
            Object recognizer = speechRecognizer;
            if (recognizer == null) {
                fail("系统语音识别尚未启动");
                return;
            }
            try {
                recognizer.getClass().getMethod("stopListening").invoke(recognizer);
            } catch (Throwable t) {
                fail("停止系统语音识别失败: " + shortMessage(t));
            }
        });
    }

    /** 退出世界/切换配置时彻底取消，不再回调。 */
    public void cancel() {
        active = false;
        runOnAndroidMain(this::destroyRecognizer);
    }

    private void scheduleTimeout() {
        Thread timeout = new Thread(() -> {
            try {
                Thread.sleep(RESULT_TIMEOUT_MS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
            if (active && System.currentTimeMillis() - startedAt >= RESULT_TIMEOUT_MS) {
                runOnAndroidMain(() -> fail("系统语音识别超时，请重试"));
            }
        }, "Android-Speech-Timeout");
        timeout.setDaemon(true);
        timeout.start();
    }

    private Object createIntent(Class<?> intentClass) throws Exception {
        Class<?> recognizerIntent = Class.forName("android.speech.RecognizerIntent");
        String action = (String) recognizerIntent.getField("ACTION_RECOGNIZE_SPEECH").get(null);
        Object intent = intentClass.getConstructor(String.class).newInstance(action);

        String languageModel = (String) recognizerIntent.getField("EXTRA_LANGUAGE_MODEL").get(null);
        String freeForm = (String) recognizerIntent.getField("LANGUAGE_MODEL_FREE_FORM").get(null);
        intentClass.getMethod("putExtra", String.class, String.class)
                .invoke(intent, languageModel, freeForm);

        String language = (String) recognizerIntent.getField("EXTRA_LANGUAGE").get(null);
        intentClass.getMethod("putExtra", String.class, String.class)
                .invoke(intent, language, "zh-CN");
        intentClass.getMethod("putExtra", String.class, int.class)
                .invoke(intent, "android.speech.extra.MAX_RESULTS", 1);
        intentClass.getMethod("putExtra", String.class, boolean.class)
                .invoke(intent, "android.speech.extra.PARTIAL_RESULTS", false);
        return intent;
    }

    private Object getAndroidContext() {
        // 大多数 Android Java 启动器都能通过 ActivityThread 拿到 Application。
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object application = activityThread.getMethod("currentApplication").invoke(null);
            if (application != null) return application;
        } catch (Throwable ignored) {
        }
        try {
            Class<?> appGlobals = Class.forName("android.app.AppGlobals");
            return appGlobals.getMethod("getInitialApplication").invoke(null);
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void handleResults(Object bundle) {
        try {
            Class<?> speechClass = Class.forName("android.speech.SpeechRecognizer");
            String key = (String) speechClass.getField("RESULTS_RECOGNITION").get(null);
            Object value = bundle.getClass().getMethod("getStringArrayList", String.class)
                    .invoke(bundle, key);
            if (value instanceof List<?> list && !list.isEmpty()) {
                String text = String.valueOf(list.get(0));
                if (text != null && !text.isBlank()) {
                    succeed(text);
                    return;
                }
            }
            fail("没有识别到有效语音");
        } catch (Throwable t) {
            fail("读取语音识别结果失败: " + shortMessage(t));
        }
    }

    private void succeed(String text) {
        if (!active) return;
        active = false;
        destroyRecognizer();
        OllamaProxyMod.LOGGER.info("Android 系统语音识别成功: {}", text);
        onResult.accept(text);
    }

    private void fail(String reason) {
        if (!active) return;
        active = false;
        destroyRecognizer();
        OllamaProxyMod.LOGGER.warn("Android 系统语音识别失败: {}", reason);
        onError.accept(reason);
    }

    private void destroyRecognizer() {
        Object recognizer = speechRecognizer;
        speechRecognizer = null;
        if (recognizer == null) return;
        try {
            recognizer.getClass().getMethod("destroy").invoke(recognizer);
        } catch (Throwable ignored) {
        }
    }

    /** SpeechRecognizer 只能跑在 Android 主 Looper 上，不能直接用 Minecraft 渲染线程。 */
    private static void runOnAndroidMain(Runnable task) {
        try {
            Class<?> looperClass = Class.forName("android.os.Looper");
            Object mainLooper = looperClass.getMethod("getMainLooper").invoke(null);
            if (mainLooper == null) {
                task.run();
                return;
            }
            Class<?> handlerClass = Class.forName("android.os.Handler");
            Object handler = handlerClass.getConstructor(looperClass).newInstance(mainLooper);
            handlerClass.getMethod("post", Runnable.class).invoke(handler, task);
        } catch (Throwable t) {
            task.run();
        }
    }

    private static String shortMessage(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }

    private final class ListenerHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("onResults".equals(name)) {
                if (args != null && args.length > 0 && args[0] != null) {
                    handleResults(args[0]);
                } else {
                    fail("系统语音识别没有返回结果");
                }
            } else if ("onError".equals(name)) {
                int code = args != null && args.length > 0 && args[0] instanceof Integer i ? i : -1;
                fail(errorMessage(code));
            } else if ("onPartialResults".equals(name)) {
                // 按键说话时不需要中间结果，等最终结果。
            }
            return null;
        }
    }

    private static String errorMessage(int code) {
        return switch (code) {
            case 6 -> "手机语音识别网络错误";
            case 7 -> "没有识别到语音，请靠近麦克风重说";
            case 8 -> "系统语音识别忙，请稍后再试";
            case 9 -> "没有麦克风权限，请在 FCL/手机设置中授予录音权限";
            case 12 -> "系统缺少中文语音识别服务";
            default -> "系统语音识别失败（错误码 " + code + "）";
        };
    }
}
