package com.hanchen.veritycustomapi.cloud;

import net.minecraft.world.entity.player.Player;

/** Compatibility copy provider. Real account operations must not impersonate fictional company policy. */
public final class CloudServiceLore {
    private CloudServiceLore(){}
    public static String disconnectTitle(){return "取消这台设备的授权？";}
    public static String disconnectBody(){return "解除这台设备的云端授权，清除本机登录信息，并恢复本机设置。不会删除账号，其他设备不受影响；本页未保存的修改将丢弃。";}
    public static String termsLine(){return "设备授权只关联当前设备。剧情中的虚构公司与实际平台账户无关。";}
    /** Kept for existing callers. No fictional speech or paid TTS on account disconnection. */
    public static void onDisconnected(Player player){}
}
