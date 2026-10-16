package com.hanchen.veritycustomapi.cloud;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class BillingClientNotifier {
    private BillingClientNotifier() {
    }

    public static void notifyPlayer(String message) {
        notify("Verity 计费", message);
    }

    /** 云端配置/授权类提示（文档 §7.1：401 需要提示配置密钥失效）。 */
    public static void notifyCloud(String message) {
        notify("Verity 云端", message);
    }

    private static void notify(String prefix, String message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) return;
        minecraft.execute(() -> {
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(Component.literal("[" + prefix + "] " + message), false);
            }
        });
    }
}
