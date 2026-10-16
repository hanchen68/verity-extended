package com.hanchen.veritycustomapi.compat;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.ForgeConfigSpec;

import java.lang.reflect.Field;

/**
 * 原版 Verity「个性化」功能的桥接层。
 * 原版配置界面的入口被扩展接管了，这里改用反射调原版实现，
 * 让扩展的皮肤页复用它的色相预览、自定义颜色/名称/人格，也能再打开原版配置界面。
 */
public final class OriginalVerityPersonalization {

    private static final String CONFIG_CLASS = "varmite.verity.VerityConfig";
    private static final String PREVIEW_CLASS = "varmite.verity.client.VerityPreviewTexture";
    private static final String CONFIG_UI_CLASS = "varmite.verity.VerityConfigUI";

    /** 原版预览纹理的注册 ID 缓存，避免每次渲染都反射一次。 */
    private static ResourceLocation cachedPreviewId;
    private static boolean previewIdResolved;

    private OriginalVerityPersonalization() {
    }

    // ==================== 预览纹理 ====================

    /**
     * 原版预览纹理的 ID（verity:dynamic/color_preview）。
     * 原版客户端启动时已经注册了这个动态纹理；拿不到就返回 null。
     */
    public static ResourceLocation previewTextureId() {
        if (previewIdResolved) return cachedPreviewId;
        previewIdResolved = true;
        Object result = invokeStatic(PREVIEW_CLASS, "id", new Class<?>[0]);
        if (result instanceof ResourceLocation id) {
            cachedPreviewId = id;
        }
        return cachedPreviewId;
    }

    /** 让原版预览纹理按指定色相重新着色。 */
    public static void applyHue(int hue) {
        invokeStatic(PREVIEW_CLASS, "applyHue", new Class<?>[]{int.class}, hue);
    }

    // ==================== 原版配置项 ====================

    /** 原版自定义颜色（色相），0 表示原始颜色。 */
    public static int getHue() {
        return readInt("COLOR", 0);
    }

    /** 写入原版色相并立刻刷新预览纹理。 */
    public static void setHue(int hue) {
        int clamped = Math.max(0, Math.min(360, hue));
        writeConfig("COLOR", clamped);
        applyHue(clamped);
    }

    public static String getCustomName() {
        return readString("VERITY_CUSTOM_NAME", "Verity");
    }

    public static void setCustomName(String name) {
        writeConfig("VERITY_CUSTOM_NAME", name == null ? "" : name);
    }

    public static String getPersonality() {
        return readString("PERSONALITY", "");
    }

    public static void setPersonality(String value) {
        writeConfig("PERSONALITY", value == null ? "" : value);
    }

    // ==================== 打开原版界面 ====================

    /** 调用原版 {@code VerityConfigUI.createYACLScreen} 打开原版配置界面。 */
    public static boolean openOriginalConfigScreen(Screen parent) {
        try {
            Class<?> uiClass = Class.forName(CONFIG_UI_CLASS);
            Object screen = uiClass.getMethod("createYACLScreen", Screen.class).invoke(null, parent);
            if (screen instanceof Screen target) {
                Minecraft.getInstance().setScreen(target);
                return true;
            }
            OllamaProxyMod.LOGGER.warn("[原版个性化] createYACLScreen 返回值不是 Screen");
        } catch (Throwable t) {
            OllamaProxyMod.LOGGER.warn("[原版个性化] 打开原版配置界面失败: {}", t.toString());
        }
        return false;
    }

    // ==================== 反射工具 ====================

    private static Object getConfigValue(String fieldName) {
        try {
            Field field = Class.forName(CONFIG_CLASS).getField(fieldName);
            return field.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int readInt(String fieldName, int fallback) {
        Object configValue = getConfigValue(fieldName);
        if (configValue == null) return fallback;
        try {
            Object value = configValue.getClass().getMethod("get").invoke(configValue);
            return value instanceof Number number ? number.intValue() : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static String readString(String fieldName, String fallback) {
        Object configValue = getConfigValue(fieldName);
        if (configValue == null) return fallback;
        try {
            Object value = configValue.getClass().getMethod("get").invoke(configValue);
            return value instanceof String text ? text : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** 写入原版配置项并立即落盘，避免游戏退出时丢失。 */
    private static void writeConfig(String fieldName, Object value) {
        Object configValue = getConfigValue(fieldName);
        if (configValue == null) return;
        try {
            configValue.getClass().getMethod("set", Object.class).invoke(configValue, value);
            Object spec = getConfigValue("SPEC");
            if (spec instanceof ForgeConfigSpec forgeSpec) {
                forgeSpec.save();
            }
        } catch (Throwable t) {
            OllamaProxyMod.LOGGER.warn("[原版个性化] 写入原版配置 {} 失败: {}", fieldName, t.toString());
        }
    }

    private static Object invokeStatic(String className, String method, Class<?>[] parameterTypes, Object... args) {
        try {
            Class<?> target = Class.forName(className);
            return target.getMethod(method, parameterTypes).invoke(null, args);
        } catch (Throwable t) {
            return null;
        }
    }
}
