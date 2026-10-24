package com.hanchen.veritycustomapi.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 反射访问层的契约自检。
 *
 * <p>{@link ReflectiveTarget} 是扩展与原版 Verity 之间的唯一软连接。
 * 它的核心承诺只有一条：**原版少了什么，就让对应的那一项功能失效，
 * 绝不把异常抛给调用方、更不带崩模组启动**。
 *
 * <p>这条承诺在很多地方是隐性的：调用方拿到的都是 {@code null}，
 * 然后各自决定怎么兜底。一旦 {@link ReflectiveTarget} 哪天改成「找不到就抛异常」，
 * 全仓库几十个调用点会在同一次启动里一起炸，而且堆栈都指向反射层，
 * 根本看不出是哪个功能引起的。这里把「返回 null 而不抛」钉死。
 */
public final class ReflectiveTargetTest {

    private static final String TAG = "[ReflectiveTest]";

    private ReflectiveTargetTest() {
    }

    public static void main(String[] args) {
        missingClassReturnsNull();
        missingMethodReturnsNull();
        missingFieldReturnsNull();
        existingMembersAreAccessible();
        invokeOnNullIsSafe();
        getAndSetHandleNulls();
        typedGettersFallBack();
        canLoadDetectsPresence();

        System.out.println("ReflectiveTarget checks passed");
    }

    /**
     * {@link ReflectiveTarget#canLoad} 只回答「装了没有」，不写日志、不抛。
     *
     * <p>它是给 {@code isAvailable()} 这类可用性探测用的。与 {@code type()} 的语义区别：
     * 探测到不存在是正常情况（可选模组没装），不该打 warn 刷屏。
     */
    private static void canLoadDetectsPresence() {
        require(ReflectiveTarget.canLoad(Sample.class.getName()), "本类应能加载");
        require(!ReflectiveTarget.canLoad("com.example.definitely.NotHere"),
                "不存在的类应返回 false");
    }

    /** 类不存在时返回 null，且不抛异常。 */
    private static void missingClassReturnsNull() {
        Class<?> missing = ReflectiveTarget.type("com.example.definitely.NotHere", TAG);
        require(missing == null, "不存在的类应返回 null");
    }

    /** 方法不存在时返回 null。 */
    private static void missingMethodReturnsNull() {
        Class<?> owner = ReflectiveTarget.type(Sample.class.getName(), TAG);
        require(owner != null, "本类应能被加载");
        Method missing = ReflectiveTarget.declaredMethod(owner, "noSuchMethod", TAG);
        require(missing == null, "不存在的方法应返回 null");
        Method publicMissing = ReflectiveTarget.method(owner, "noSuchMethod", TAG);
        require(publicMissing == null, "不存在的公开方法应返回 null");
    }

    /** 字段不存在时返回 null。 */
    private static void missingFieldReturnsNull() {
        Class<?> owner = ReflectiveTarget.type(Sample.class.getName(), TAG);
        Field missing = ReflectiveTarget.field(owner, "noSuchField", TAG);
        require(missing == null, "不存在的字段应返回 null");
    }

    /**
     * 存在的成员必须真的拿得到，并且能读写。
     *
     * <p>如果这一条挂了，说明 {@code setAccessible} 那一步被改动过——
     * 那会让**所有** privte 成员访问在运行时静默失效。
     */
    private static void existingMembersAreAccessible() {
        Class<?> owner = ReflectiveTarget.type(Sample.class.getName(), TAG);

        Method method = ReflectiveTarget.declaredMethod(owner, "secret", TAG, int.class);
        require(method != null, "私有方法应能被取到");
        Object result = ReflectiveTarget.invoke(method, new Sample(), 21);
        require(Integer.valueOf(42).equals(result), "私有方法调用结果不对，实际=" + result);

        Field field = ReflectiveTarget.field(owner, "counter", TAG);
        require(field != null, "私有字段应能被取到");
        Sample sample = new Sample();
        require(ReflectiveTarget.set(field, sample, 7), "私有字段应能被写入");
        require(Integer.valueOf(7).equals(ReflectiveTarget.get(field, sample)), "私有字段读回值不对");
    }

    /** 给 null 方法调用不能抛异常——这是调用方最常见的写法。 */
    private static void invokeOnNullIsSafe() {
        require(ReflectiveTarget.invoke(null, new Object()) == null, "null 方法应返回 null");
        require(ReflectiveTarget.invokeStatic(null) == null, "null 静态方法应返回 null");
    }

    private static void getAndSetHandleNulls() {
        require(ReflectiveTarget.get(null, null) == null, "null 字段读取应返回 null");
        require(!ReflectiveTarget.set(null, null, null), "null 字段写入应返回 false");
    }

    /** 带类型的读取要落到调用方给的兜底值，而不是 0/false。 */
    private static void typedGettersFallBack() {
        require(ReflectiveTarget.getFloat(null, null, 1.5F) == 1.5F, "float 兜底值未生效");
        require(ReflectiveTarget.getBoolean(null, null, true), "boolean 兜底值未生效");

        Class<?> owner = ReflectiveTarget.type(Sample.class.getName(), TAG);
        Field text = ReflectiveTarget.field(owner, "text", TAG);
        // 字段类型是 String，取 float 时应退回兜底值而不是抛 ClassCastException
        require(ReflectiveTarget.getFloat(text, new Sample(), 2.5F) == 2.5F,
                "类型不匹配时应退回兜底值");
        require(ReflectiveTarget.getBoolean(text, new Sample(), true),
                "类型不匹配时应退回兜底值");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** 供反射访问的样本类：故意把成员都设成 private。 */
    private static final class Sample {
        private int counter;
        private String text = "sample";

        private int secret(int input) {
            return input * 2;
        }
    }
}
