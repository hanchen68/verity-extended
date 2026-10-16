package com.hanchen.veritycustomapi.compat;

/**
 * 台词前缀解析的回归。
 *
 * 要守住两件事。一是聊天栏里各种形态的 Verity 前缀都得剥掉，HUD 台词和 TTS 朗读都靠它。
 * 二是服务端多人适配加的 {@code @玩家名:} 标记也得剥——它只在聊天栏用来区分回复对象，
 * TTS 不该把玩家名念出来；更要紧的是 {@code TtsManager} 按文本去重，
 * 聊天栏那一路和服务端 TTS 包那一路必须拿到同一段文字，否则同一句话会被念两遍。
 */
public final class VeritySpeakerTest {
    private VeritySpeakerTest() {
    }

    public static void main(String[] args) {
        // 各种前缀形态
        assertEquals("你好呀。", VeritySpeaker.stripPrefix("<Verity> 你好呀。"));
        assertEquals("你好呀。", VeritySpeaker.stripPrefix("[Verity] 你好呀。"));
        assertEquals("你好呀。", VeritySpeaker.stripPrefix("【Verity】你好呀。"));
        assertEquals("你好呀。", VeritySpeaker.stripPrefix("Verity: 你好呀。"));
        assertEquals("你好呀。", VeritySpeaker.stripPrefix("§b【Verity】你好呀。"));
        assertEquals("你好呀。", VeritySpeaker.stripPrefix("  <Verity>   你好呀。  "));

        // 服务端适配加的 @玩家 标记也得剥（不然 TTS 会念玩家名，去重也会失效念两遍）
        assertEquals("你好呀。", VeritySpeaker.stripPrefix("<Verity> @Steve: 你好呀。"));
        assertEquals("你好呀。", VeritySpeaker.stripPrefix("<Verity> @Steve：你好呀。"));
        assertEquals("你好呀。", VeritySpeaker.stripPrefix("<Verity> @Steve :  你好呀。"));
        assertEquals("你好呀。", VeritySpeaker.stripPrefix("<Verity> @中文玩家名: 你好呀。"));

        // 实测：实体的 getName() 可能渲染成 "Verity?"（名字键没解析时多一个问号）。
        // 只认 "<Verity>" 会残留 "?>"，连带 @玩家名 标记也剥不掉 ——
        // 线上就是 TTS 把玩家名念出来，而且去重失效同一句念两遍。
        assertEquals("唔，你离我好远。", VeritySpeaker.stripPrefix("<Verity?> @Hanchen_68: 唔，你离我好远。"));
        assertEquals("唔，你离我好远。", VeritySpeaker.stripPrefix("<Verity?> 唔，你离我好远。"));
        assertEquals("唔，你离我好远。", VeritySpeaker.stripPrefix("<Verity !> @Steve: 唔，你离我好远。"));
        assertNull(VeritySpeaker.stripPrefix("<Verity?>"));

        // 不是 Verity 的发言
        assertNull(VeritySpeaker.stripPrefix("<Steve> 你好呀。"));
        assertNull(VeritySpeaker.stripPrefix("普通聊天内容"));
        assertNull(VeritySpeaker.stripPrefix("<Verity>"));
        assertNull(VeritySpeaker.stripPrefix("<Verity> @Steve:"));
        assertNull(VeritySpeaker.stripPrefix(""));
        assertNull(VeritySpeaker.stripPrefix(null));

        // 正文里出现的 @ 不该被误伤（只剥行首标记）
        assertEquals("请 @Steve 帮个忙。", VeritySpeaker.stripPrefix("<Verity> 请 @Steve 帮个忙。"));
        // 行首 @ 但不像玩家名（带空格）时保留
        assertEquals("@ 这是一个符号", VeritySpeaker.stripPrefix("<Verity> @ 这是一个符号"));

        System.out.println("Verity speaker prefix checks passed");
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertNull(String actual) {
        if (actual != null) {
            throw new AssertionError("expected=null, actual=" + actual);
        }
    }
}
