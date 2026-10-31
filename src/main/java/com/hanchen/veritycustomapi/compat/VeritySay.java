package com.hanchen.veritycustomapi.compat;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

/**
 * 让 Verity「开口说一句话」。
 *
 * <p><b>必须用原版回复的格式</b>：聊天栏里是 {@code <名字> 台词}，名字取原版配置的
 * 自定义名（玩家可能把 Verity 改名了）。这个前缀是三处下游的识别依据：
 * <ul>
 *   <li>{@code TtsChatListener} —— 剥掉前缀后朗读；</li>
 *   <li>{@code VerityDialogueFeed} —— 把台词塞进 HUD 头顶气泡；</li>
 *   <li>{@code VeritySpeaker} —— 统一前缀识别（含自定义名与 {@code @玩家名:} 标记）。</li>
 * </ul>
 *
 * <p>🔴 用 {@code [Verity]} 这种自己发明的前缀，上面三处全都认不出来：
 * 表现就是「聊天栏冒出一行灰字，Verity 既没张嘴也没出声」——
 * 2026-10-31 实录：主动提议「附近好像有矿，要我挖吗」就是这个问题。
 */
public final class VeritySay {

    private VeritySay() {
    }

    /** 以 Verity 的身份对玩家说一句话；空文本直接忽略。 */
    public static void to(Player player, String text) {
        if (text == null || text.isBlank()) return;
        to(player, Component.literal(text.trim()));
    }

    /**
     * 同上，但台词本身是 {@link Component}。
     *
     * <p>方块/物品名必须走这条：{@code Component.translatable(方块翻译键)} 由**客户端**渲染，
     * 服务端拼字符串只能拿到 {@code wheat} 这种 ID 或英文名——那正是「种 wheat完成」的来历。
     */
    public static void to(Player player, Component body) {
        if (player == null || body == null) return;
        player.sendSystemMessage(Component.literal("<" + VeritySpeaker.currentName() + "> ").append(body));
    }
}
