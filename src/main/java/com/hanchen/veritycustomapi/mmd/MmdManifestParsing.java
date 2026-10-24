package com.hanchen.veritycustomapi.mmd;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.function.Predicate;

/**
 * MMD 项目清单（{@code manifest.json}）的解析与路径安全校验。
 *
 * <p>抽自 {@code MmdPlaybackManager}。清单是玩家自己写的 JSON，字段类型、
 * 相对路径都可能不规范，所以这里的原则是**能兜就兜、兜不住就回退**：
 * 读不出清单就当作「没有清单」，退回自动识别，绝不让整个项目加载失败。
 */
public final class MmdManifestParsing {
    private static final Logger LOGGER = OllamaProxyMod.LOGGER;

    private MmdManifestParsing() {
    }

    /** 取字符串字段；字段不存在或不是基本类型都返回 null。 */
    public static String jsonString(JsonObject root, String name) {
        JsonElement value = root.get(name);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    /** 取正浮点字段；缺失、非数字、NaN/Inf、非正数都退回 {@code fallback}。 */
    public static float jsonFloat(JsonObject root, String name, float fallback) {
        JsonElement value = root.get(name);
        if (value == null || !value.isJsonPrimitive()) return fallback;
        try {
            float parsed = value.getAsFloat();
            return Float.isFinite(parsed) && parsed > 0.0f ? parsed : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    /**
     * 把清单里写的相对文件名解析成项目目录内的绝对路径。
     *
     * <p>🔴 这里同时做两件事，缺一不可：
     * <ol>
     *   <li><b>防目录穿越</b>：{@code ../../evil.vmd} 这种必须拒绝——
     *       清单是玩家/网络下发的文件，不能让它指到项目目录之外；</li>
     *   <li><b>校验类型</b>：交给调用方传进来的 {@code validator} 判断是不是 .vmd/.vpd。</li>
     * </ol>
     *
     * @return 合法路径；名字为空、越界或类型不对时返回 null
     */
    public static Path resolveManifestFile(Path directory, String name,
                                           Predicate<Path> validator, String field) {
        if (name == null || name.isBlank()) return null;
        Path normalizedDirectory = directory.toAbsolutePath().normalize();
        Path selected = normalizedDirectory.resolve(name).normalize();
        if (!selected.getParent().equals(normalizedDirectory) || !validator.test(selected)) {
            LOGGER.warn("manifest.json 的 {} 文件无效或越出项目目录: {}", field, name);
            return null;
        }
        return selected;
    }
}
