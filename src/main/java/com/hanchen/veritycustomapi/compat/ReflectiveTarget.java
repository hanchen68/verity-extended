package com.hanchen.veritycustomapi.compat;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 访问原版 Verity 私有成员的统一入口。
 *
 * <p><b>为什么要有这个类</b>：扩展要兼容多个原版版本，而各版本类名、方法名、字段名
 * 会增删（例如 6.0.0-beta.9 去掉了 {@code isMonstrous}）。以前每个 compat 类各写一套
 * {@code Class.forName + getDeclaredMethod + setAccessible + try/catch}，
 * 结果是：**同一个坑踩了十几次，日志格式还不统一**，出问题时很难看出是哪个功能缺了哪一项。
 *
 * <p><b>核心约定</b>：
 * <ul>
 *   <li>取不到就返回 {@code null}，**绝不抛异常**——原版少一个方法只应让对应功能失效，
 *       不能带崩整个模组启动；</li>
 *   <li>类缺失打 {@code warn}（版本不兼容是大事），方法与字段缺失打 {@code debug}
 *       （版本间增删很常见，打 warn 会刷屏）；</li>
 *   <li>所有日志都带 {@code tag}，一眼能看出是哪块功能在降级。</li>
 * </ul>
 *
 * <p>🔴 <b>禁用 {@code Class.forName} 做「原版是否装了某功能」的探测</b>：
 * 加载第三方类会触发静态初始化，可能直接把启动带崩。判定结构请用
 * {@link VerityStructureDetector} 的字节扫描。本类只用于「已经确定存在」的成员访问。
 */
public final class ReflectiveTarget {
    private static final Logger LOGGER = OllamaProxyMod.LOGGER;

    private ReflectiveTarget() {
    }

    /**
     * 按全名取类。
     *
     * @param tag 日志标签，如 {@code "[StateAccess]"}
     * @return 类；不存在或加载失败时返回 null
     */
    public static Class<?> type(String name, String tag) {
        try {
            return Class.forName(name);
        } catch (Throwable missing) {
            LOGGER.warn("{} 当前 Verity 版本没有类 {}，相关功能将不可用", tag, name);
            return null;
        }
    }

    /**
     * 取<b>公开</b>方法（含继承来的）。
     *
     * <p>与原版 API 走 public 一致；需要 private 时用 {@link #declaredMethod}。
     */
    public static Method method(Class<?> owner, String name, String tag, Class<?>... params) {
        if (owner == null) return null;
        try {
            Method found = owner.getMethod(name, params);
            found.setAccessible(true);
            return found;
        } catch (Throwable missing) {
            LOGGER.debug("{} 当前 Verity 版本没有方法 {}，将使用兜底路径", tag, name);
            return null;
        }
    }

    /** 取<b>本类声明</b>的方法（private / protected 也能拿到）。 */
    public static Method declaredMethod(Class<?> owner, String name, String tag, Class<?>... params) {
        if (owner == null) return null;
        try {
            Method found = owner.getDeclaredMethod(name, params);
            found.setAccessible(true);
            return found;
        } catch (Throwable missing) {
            LOGGER.debug("{} 当前 Verity 版本没有方法 {}，将使用兜底路径", tag, name);
            return null;
        }
    }

    /**
     * 取字段（本类声明，含 private）。
     *
     * <p>逐个字段独立解析是刻意的：某个字段消失只让那一个功能失效，
     * 不要因为一个字段缺失就放弃整组。
     */
    public static Field field(Class<?> owner, String name, String tag) {
        if (owner == null) return null;
        try {
            Field found = owner.getDeclaredField(name);
            found.setAccessible(true);
            return found;
        } catch (Throwable missing) {
            LOGGER.warn("{} 当前 Verity 版本没有字段 {}，相关功能将不可用", tag, name);
            return null;
        }
    }

    /** 调用方法；失败返回 null 并记 debug（调用失败通常是版本差异，不该刷 warn）。 */
    public static Object invoke(Method method, Object target, Object... args) {
        if (method == null) return null;
        try {
            return method.invoke(target, args);
        } catch (Throwable error) {
            LOGGER.debug("反射调用 {} 失败: {}", method.getName(), error.toString());
            return null;
        }
    }

    /** 调用静态方法。 */
    public static Object invokeStatic(Method method, Object... args) {
        return invoke(method, null, args);
    }

    /** 读字段；失败返回 null。 */
    public static Object get(Field field, Object target) {
        if (field == null) return null;
        try {
            return field.get(target);
        } catch (Throwable error) {
            LOGGER.debug("反射读取 {} 失败: {}", field.getName(), error.toString());
            return null;
        }
    }

    /**
     * 写字段。
     *
     * @return 是否写入成功（调用方据此决定要不要回退到别的路径）
     */
    public static boolean set(Field field, Object target, Object value) {
        if (field == null) return false;
        try {
            field.set(target, value);
            return true;
        } catch (Throwable error) {
            LOGGER.debug("反射写入 {} 失败: {}", field.getName(), error.toString());
            return false;
        }
    }

    /** 读 float 字段；取不到时返回 {@code fallback}。 */
    public static float getFloat(Field field, Object target, float fallback) {
        Object value = get(field, target);
        return value instanceof Number number ? number.floatValue() : fallback;
    }

    /** 读 boolean 字段；取不到时返回 {@code fallback}。 */
    public static boolean getBoolean(Field field, Object target, boolean fallback) {
        Object value = get(field, target);
        return value instanceof Boolean flag ? flag : fallback;
    }
}
