package com.hanchen.veritycustomapi.config;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.compat.ForgePaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * MiMo 音色的 JSON 持久化。
 *
 * <p>抽自 {@code ConfigManager}。**为什么要 JSON 而不是 TOML**：Forge 的 TOML
 * 在中文 Windows 上按 GBK 写盘，音色名里一旦出现中文（「苏打」这种）就会变成乱码，
 * 下次启动读回来是个问号串。JSON 用 UTF-8 显式读写，绕开这个问题。
 *
 * <p>🔴 三个字段必须一起缓存、一起失效：
 * {@code cachedVoice} / {@code cachedClone} / {@code loaded}。
 * 只缓存 voice 不缓存 clone，会导致「同一个音色上一轮当克隆价、这一轮当普通价」，
 * 计费随刷新时机不同而变。
 */
public final class MimoVoiceStore {

    private static final Path STORE = ForgePaths.config("verity_extended", "mimo_voice.json");
    private static final Path LEGACY_STORE = Paths.get("config", "verity_extended", "mimo_voice.json");
    private static final Gson GSON = new Gson();

    private static volatile String cachedVoice;
    private static volatile Boolean cachedClone;
    /** 是否真的读过盘。用布尔而不是「cachecVoice != null」判断，因为「读到空」也是有效结果。 */
    private static volatile boolean loaded;

    private MimoVoiceStore() {
    }

    /** 读当前保存的音色；没有或读失败返回 {@code null}。 */
    public static String load() {
        if (loaded) return cachedVoice;
        synchronized (MimoVoiceStore.class) {
            if (loaded) return cachedVoice;
            try {
                ForgePaths.migrateRelative(LEGACY_STORE, STORE);
                if (!Files.exists(STORE)) {
                    Files.createDirectories(STORE.getParent());
                    return null;
                }
                String json = new String(Files.readAllBytes(STORE), StandardCharsets.UTF_8);
                JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                cachedVoice = obj.has("voice") && !obj.get("voice").isJsonNull()
                        ? obj.get("voice").getAsString() : null;
                cachedClone = obj.has("clone") && !obj.get("clone").isJsonNull()
                        ? obj.get("clone").getAsBoolean() : null;
            } catch (Throwable error) {
                cachedVoice = null;
            } finally {
                // 无论成功失败都标记已读：失败时反复重试读盘没有意义，反而每次调用都卡一下磁盘
                loaded = true;
            }
            return cachedVoice;
        }
    }

    /** 音色是否被标记为「克隆音色」（克隆音色按克隆价计费）。 */
    public static boolean isClone(String voice) {
        load();
        return voice != null && voice.equals(cachedVoice) && Boolean.TRUE.equals(cachedClone);
    }

    /**
     * 保存音色。
     *
     * <p>同时写一份到 Forge 配置（尽力而为）：某些代码路径读的是 TOML 里的值，
     * 只写 JSON 会让两条读取路径看到不同结果。
     *
     * <p>注意 {@code set()} 在 try **之外**——与原实现一致。配置未加载时它会抛，
     * 这个异常本来就该让调用方看见（静默吞掉会让「设置没生效」变成无迹可寻的问题）。
     */
    public static void save(String voice, boolean clone) {
        cachedVoice = voice;
        cachedClone = clone;
        loaded = true;
        if (voice != null && !voice.isBlank()) {
            ModConfig.BUILTIN_MIMO_TTS_VOICE.set(voice);
        }
        try {
            Files.createDirectories(STORE.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("voice", voice != null ? voice : "");
            obj.addProperty("clone", clone);
            Files.write(STORE, GSON.toJson(obj).getBytes(StandardCharsets.UTF_8));
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.warn("音色 JSON 写入失败: {}", error.getMessage());
        }
    }
}
