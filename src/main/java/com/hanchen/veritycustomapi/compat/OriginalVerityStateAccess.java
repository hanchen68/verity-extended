package com.hanchen.veritycustomapi.compat;

import com.hanchen.veritycustomapi.util.Silent;

import com.hanchen.veritycustomapi.OllamaProxyMod;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 用反射读原版 Verity 的运行时状态（Karma、实体引用、空闲/孤独计时器、恶魔与已生成标志）。
 * 反射失败时一律静默返回默认值，不往外抛。
 */
public final class OriginalVerityStateAccess {

    private static Class<?> worldSpawnDataClass;
    private static Class<?> verityStateClass;
    private static Field karmaField;
    private static Field verityEntityField;
    private static Field hasSpawnedField;
    private static Field idleChatTimerField;
    private static Field lonelinessTimerField;
    private static Field isMonstrousField;
    private static Field followPlayerField;
    private static Method entityIsMonstrous;
    private static Method entitySetMonstrous;
    private static boolean initialized = false;

    private OriginalVerityStateAccess() {}

    private static synchronized void init() {
        if (initialized) return;
        initialized = true;

        // 类与字段都独立解析：任一版本差异都不能中断其它兼容功能的初始化。
        worldSpawnDataClass = findClass("varmite.verity.event.WorldSpawnData");
        karmaField = findField(worldSpawnDataClass, "verityKarma");

        verityStateClass = findClass("varmite.verity.entity.VerityState");
        verityEntityField = findField(verityStateClass, "verityEntity");
        hasSpawnedField = findField(verityStateClass, "hasSpawned");
        idleChatTimerField = findField(verityStateClass, "idleChatTimer");
        lonelinessTimerField = findField(verityStateClass, "lonelinessTimer");
        isMonstrousField = findField(verityStateClass, "isMonstrous");
        followPlayerField = findField(verityStateClass, "followPlayer");

        // 6.0.0-beta.9 起恶魔标志搬到了实体自己身上，全局字段没了就走这条
        Class<?> verityEntityClass = findClass("varmite.verity.entity.verity.VerityEntity");
        entityIsMonstrous = findMethod(verityEntityClass, "isMonstrous");
        entitySetMonstrous = findMethod(verityEntityClass, "setMonstrous", boolean.class);

        OllamaProxyMod.LOGGER.info("[StateAccess] 原版 Verity 状态访问已就绪，跟随开关={}，恶魔形态={}",
                followPlayerField != null ? "可用" : "不可用",
                isMonstrousField != null || entitySetMonstrous != null ? "可用" : "不可用");
    }

    private static Class<?> findClass(String name) {
        return com.hanchen.veritycustomapi.compat.ReflectiveTarget.type(name, "[StateAccess]");
    }

    /** 取方法，取不到只让这一个功能失效；这类缺失在版本差异里很常见，不打 warn 免得刷屏。 */
    private static Method findMethod(Class<?> owner, String name, Class<?>... params) {
        return com.hanchen.veritycustomapi.compat.ReflectiveTarget.method(owner, name, "[StateAccess]", params);
    }

    /**
     * 取单个字段，取不到只让这一个功能失效。
     * 原版各版本字段会增减（比如 6.0.0-beta.9 就去掉了 isMonstrous），一个个独立解析免得连带失效。
     */
    private static Field findField(Class<?> owner, String name) {
        return com.hanchen.veritycustomapi.compat.ReflectiveTarget.field(owner, name, "[StateAccess]");
    }

    // ========== Karma ==========

    /** 获取当前 Karma 值 */
    public static float getKarma() {
        init();
        try {
            if (karmaField != null) return karmaField.getFloat(null);
        } catch (Exception e) {
            OllamaProxyMod.LOGGER.debug("[StateAccess] getKarma 失败: {}", e.getMessage());
        }
        return 0f;
    }

    /** 设置 Karma 值 */
    public static void setKarma(float value) {
        init();
        try {
            if (karmaField != null) karmaField.setFloat(null, value);
        } catch (Exception e) {
            OllamaProxyMod.LOGGER.debug("[StateAccess] setKarma 失败: {}", e.getMessage());
        }
    }

    /** 增加 Karma */
    public static void addKarma(float delta) {
        setKarma(getKarma() + delta);
    }

    /** 同步 Karma 到客户端（需要 ServerLevel） */
    public static void syncKarma(Object serverLevel, float karma) {
        try {
            Class<?> modEventsClass = Class.forName("varmite.verity.event.ModEvents");
            java.lang.reflect.Method syncMethod = modEventsClass.getDeclaredMethod(
                    "updateAndSyncKarma", net.minecraft.server.level.ServerLevel.class, float.class);
            syncMethod.setAccessible(true);
            syncMethod.invoke(null, serverLevel, karma);
        } catch (Exception e) {
            OllamaProxyMod.LOGGER.debug("[StateAccess] syncKarma 失败: {}", e.getMessage());
        }
    }

    // ========== Verity 实体 ==========

    /** 获取 Verity 实体对象（可能为 null） */
    public static Object getVerityEntity() {
        init();
        try {
            if (verityEntityField != null) return verityEntityField.get(null);
        } catch (Exception ignored) { Silent.ignore("OriginalVerityStateAccess", ignored); }
        return null;
    }

    /** 跨维度传送会创建新实体实例，需要同步回原版维护的静态引用。 */
    public static void setVerityEntity(Object entity) {
        init();
        try {
            if (verityEntityField != null) verityEntityField.set(null, entity);
        } catch (Exception e) {
            OllamaProxyMod.LOGGER.debug("[StateAccess] setVerityEntity 失败: {}", e.getMessage());
        }
    }

    /** 获取 Verity 实体名称 */
    public static String getVerityEntityName() {
        Object entity = getVerityEntity();
        if (entity == null) return "Verity";
        try {
            java.lang.reflect.Method getName = entity.getClass().getMethod("getName");
            Object name = getName.invoke(entity);
            return name != null ? name.toString() : "Verity";
        } catch (Exception ignored) { Silent.ignore("OriginalVerityStateAccess", ignored); }
        return "Verity";
    }

    // ========== 状态标志 ==========

    public static boolean hasVeritySpawned() {
        init();
        try {
            if (hasSpawnedField != null) return hasSpawnedField.getBoolean(null);
        } catch (Exception ignored) { Silent.ignore("OriginalVerityStateAccess", ignored); }
        return false;
    }

    /**
     * 设置原版「已生成」标志。
     * 清除所有 Verity 实体时必须一并置 false，否则原版 onPlayerChat 只判此标志，会拿 null 实体查 AI 触发 NPE。
     */
    public static void setHasSpawned(boolean value) {
        init();
        try {
            if (hasSpawnedField != null) hasSpawnedField.setBoolean(null, value);
        } catch (Exception ignored) { Silent.ignore("OriginalVerityStateAccess", ignored); }
    }

    /**
     * 是否处于恶魔形态。
     *
     * 6.0.x 之前是 VerityState 上的全局静态字段；6.0.0-beta.9 起那个字段没了，
     * 改成实体自己的 SynchedEntityData（VerityEntity#isMonstrous）。
     * 两条路都试，取不到就当 false。
     */
    public static boolean isMonstrous() {
        init();
        try {
            if (isMonstrousField != null) return isMonstrousField.getBoolean(null);
        } catch (Exception ignored) { Silent.ignore("OriginalVerityStateAccess", ignored); }
        Object entity = currentVerityEntity();
        if (entity != null && entityIsMonstrous != null) {
            try {
                return Boolean.TRUE.equals(entityIsMonstrous.invoke(entity));
            } catch (Exception ignored) { Silent.ignore("OriginalVerityStateAccess", ignored); }
        }
        return false;
    }

    /** 切换恶魔形态；旧版写全局字段，新版写实体自己的同步数据。 */
    public static void setMonstrous(boolean value) {
        init();
        if (isMonstrousField != null) {
            try {
                isMonstrousField.setBoolean(null, value);
                return;
            } catch (Exception e) {
                OllamaProxyMod.LOGGER.debug("[StateAccess] setMonstrous 字段写入失败: {}", e.getMessage());
            }
        }
        Object entity = currentVerityEntity();
        if (entity == null || entitySetMonstrous == null) return;
        try {
            entitySetMonstrous.invoke(entity, value);
        } catch (Exception e) {
            OllamaProxyMod.LOGGER.debug("[StateAccess] setMonstrous 实体写入失败: {}", e.getMessage());
        }
    }

    /** 原版当前那只 Verity 实体，没有就返回 null。 */
    private static Object currentVerityEntity() {
        if (verityEntityField == null) return null;
        try {
            return verityEntityField.get(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static boolean isFollowingPlayer() {
        init();
        try {
            if (followPlayerField != null) return followPlayerField.getBoolean(null);
        } catch (Exception ignored) { Silent.ignore("OriginalVerityStateAccess", ignored); }
        return false;
    }

    /** @return 是否真的写入成功；原版版本没有该字段时返回 false，调用方据此给出提示。 */
    public static boolean setFollowingPlayer(boolean value) {
        init();
        if (followPlayerField == null) {
            return false;
        }
        try {
            followPlayerField.setBoolean(null, value);
            return true;
        } catch (Exception e) {
            OllamaProxyMod.LOGGER.debug("[StateAccess] setFollowingPlayer 失败: {}", e.getMessage());
            return false;
        }
    }

    // ========== 计时器 ==========

    public static int getIdleChatTimer() {
        init();
        try {
            if (idleChatTimerField != null) return idleChatTimerField.getInt(null);
        } catch (Exception ignored) { Silent.ignore("OriginalVerityStateAccess", ignored); }
        return 3600;
    }

    public static void setIdleChatTimer(int value) {
        init();
        try {
            if (idleChatTimerField != null) idleChatTimerField.setInt(null, value);
        } catch (Exception ignored) { Silent.ignore("OriginalVerityStateAccess", ignored); }
    }

    public static int getLonelinessTimer() {
        init();
        try {
            if (lonelinessTimerField != null) return lonelinessTimerField.getInt(null);
        } catch (Exception ignored) { Silent.ignore("OriginalVerityStateAccess", ignored); }
        return 3000;
    }

    public static void setLonelinessTimer(int value) {
        init();
        try {
            if (lonelinessTimerField != null) lonelinessTimerField.setInt(null, value);
        } catch (Exception ignored) { Silent.ignore("OriginalVerityStateAccess", ignored); }
    }

    /** 触发 Verity 立即说话（将空闲计时器设为0） */
    public static void triggerIdleChat() {
        setIdleChatTimer(0);
    }
}
