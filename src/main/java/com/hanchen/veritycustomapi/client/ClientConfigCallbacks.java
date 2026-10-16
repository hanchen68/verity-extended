package com.hanchen.veritycustomapi.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 客户端专用的配置回调入口：公共配置类不直接链接客户端类（专用服务器加载会崩），
 * 所有需要回到客户端线程执行的回调统一从这里进。
 */
@OnlyIn(Dist.CLIENT)
public final class ClientConfigCallbacks {

    private ClientConfigCallbacks() {
    }

    /** 在客户端主线程执行任务；客户端未就绪时安全忽略。 */
    public static void runOnClient(Runnable task) {
        if (task == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft != null) {
            minecraft.execute(task);
        }
    }
}
