package com.hanchen.veritycustomapi.config;

import net.minecraft.client.Minecraft;

/** True when no level is loaded, i.e. the title screen has no world to show behind menus. */
public final class VerityMenuContext {
    private VerityMenuContext(){}
    public static boolean fullScreenWindow(Minecraft minecraft){
        return minecraft==null||minecraft.level==null;
    }
}
