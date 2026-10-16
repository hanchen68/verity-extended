package com.hanchen.veritycustomapi;

/**
 * AI 建造请求的识别自检。
 *
 * 这个判定决定建造协议注不注入，也就是玩家「说了有没有反应」。
 * 最早只认句首固定前缀，「你能帮我建个房子吗」这类自然说法全漏掉；
 * 放宽到整句意图之后，又得防住「我建议你休息」这种带「建」的普通句子被误判。
 * 两边的边界都在这测。
 */
public final class BuildingIntentTest {
    private BuildingIntentTest() {
    }

    public static void main(String[] args) {
        // 句首显式前缀，原有行为不能丢
        assertTrue("建造：一座木屋");
        assertTrue("建筑: 小别墅");
        assertTrue("请建造一个塔");
        assertTrue("帮我建个房子");
        assertTrue("给我盖座桥");
        assertTrue("build: a tower");
        assertTrue("build a house");

        // 自然说法，这次优化主要针对这些
        assertTrue("你能帮我建个房子吗");
        assertTrue("我想建一栋别墅");
        assertTrue("给我造座塔");
        assertTrue("帮我盖个小木屋");
        assertTrue("在这儿修个桥吧");
        assertTrue("搭个棚子");
        assertTrue("做个火箭");
        assertTrue("给我弄个雕像");
        assertTrue("重建家园");
        assertTrue("can you build me a house");
        assertTrue("please make a bridge here");
        assertTrue("i want to construct a castle");

        // 原版会把消息包成 [dd-MM-yy HH:mm:ss] <名字> 内容，得能穿透这层
        assertTrue("[02-10-26 03:00:00] <Steve> 帮我建个房子");
        assertTrue("[02-10-26 03:00:00] Steve 帮我建个房子");
        assertTrue("[02-10-26 03:00:00] 你能帮我建个房子吗");

        // 下面这些含「建/搭/做」但不是建造请求，一个都不能触发
        assertFalse("我建议你休息一下");
        assertFalse("这个搭配很好看");
        assertFalse("这个模型做得不错");
        assertFalse("你在做什么");
        assertFalse("今天天气不错");
        assertFalse("我家里有事");
        assertFalse("制造一点噪音");
        assertFalse("盖饭好吃吗");
        assertFalse("");
        assertFalse(null);

        System.out.println("Building intent checks passed");
    }

    private static void assertTrue(String message) {
        if (!OllamaProxyServer.ChatProxyHandler.isExplicitBuildingRequest(message)) {
            throw new AssertionError("expected building request: " + message);
        }
    }

    private static void assertFalse(String message) {
        if (OllamaProxyServer.ChatProxyHandler.isExplicitBuildingRequest(message)) {
            throw new AssertionError("expected NOT building request: " + message);
        }
    }
}
