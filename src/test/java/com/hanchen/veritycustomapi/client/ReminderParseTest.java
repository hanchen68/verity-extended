package com.hanchen.veritycustomapi.client;

/**
 * 定时提醒解析的纯逻辑回归，不依赖 Forge 配置与 Minecraft 运行时。
 */
public final class ReminderParseTest {
    private ReminderParseTest() {
    }

    public static void main(String[] args) {
        minutesAfter();
        secondsBefore();
        hoursAfter();
        rejectsWithoutTrigger();
        rejectsWithoutDuration();
        rejectsUnrelatedMention();
        formatsDelay();
        System.out.println("Reminder parse checks passed");
    }

    private static void minutesAfter() {
        ReminderManager.Parsed parsed = ReminderManager.parse("20 分钟后提醒我收菜");
        assertNotNull(parsed, "应识别出提醒");
        assertEquals(1_200_000L, parsed.delayMillis(), "20 分钟应换算成 1200000 毫秒");
        assertEquals("收菜", parsed.content(), "正文应只保留待办内容");
    }

    private static void secondsBefore() {
        ReminderManager.Parsed parsed = ReminderManager.parse("提醒我 30 秒后看看锅");
        assertNotNull(parsed, "应识别出提醒");
        assertEquals(30_000L, parsed.delayMillis(), "30 秒应换算成 30000 毫秒");
        assertEquals("看看锅", parsed.content(), "正文不应残留「后」");
    }

    private static void hoursAfter() {
        ReminderManager.Parsed parsed = ReminderManager.parse("1 小时后叫我睡觉");
        assertNotNull(parsed, "应识别出提醒");
        assertEquals(3_600_000L, parsed.delayMillis(), "1 小时应换算成 3600000 毫秒");
        assertEquals("睡觉", parsed.content(), "正文应只保留待办内容");
    }

    private static void rejectsWithoutTrigger() {
        assertNull(ReminderManager.parse("20 分钟后我就回来"), "没有提醒类动词时不应误判");
    }

    private static void rejectsWithoutDuration() {
        assertNull(ReminderManager.parse("提醒我记得买牛奶"), "没有时间量时不应误判");
    }

    private static void rejectsUnrelatedMention() {
        assertNull(ReminderManager.parse("这个计时器设了 5 分钟"), "普通陈述不应触发提醒");
    }

    private static void formatsDelay() {
        assertEquals("30 秒", ReminderManager.formatDelay(30_000L), "不足一分钟按秒显示");
        assertEquals("2 分钟", ReminderManager.formatDelay(120_000L), "分钟级换算");
        assertEquals("1 小时", ReminderManager.formatDelay(3_600_000L), "小时级换算");
    }

    private static void assertNotNull(Object value, String message) {
        if (value == null) {
            throw new AssertionError(message);
        }
    }

    private static void assertNull(Object value, String message) {
        if (value != null) {
            throw new AssertionError(message + " actual=" + value);
        }
    }

    private static void assertEquals(long expected, long actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + " expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertEquals(String expected, String actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + " expected=" + expected + ", actual=" + actual);
        }
    }
}
