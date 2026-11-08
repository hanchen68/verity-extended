package com.hanchen.veritycustomapi.client;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Per-installation first launch, never per-world; no account information stored. */
@Mod.EventBusSubscriber(modid=OllamaProxyMod.MOD_ID,value=Dist.CLIENT,bus=Mod.EventBusSubscriber.Bus.FORGE)
public final class FirstRunGuide {
    private static boolean checked,shown;
    private static int titleTicks;
    private FirstRunGuide(){}
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event){
        if(event.phase!=TickEvent.Phase.END||shown)return;
        if(!checked){checked=true;if(com.hanchen.veritycustomapi.config.SettingsExperience.mode()!=com.hanchen.veritycustomapi.config.SettingsExperience.Mode.UNSET){shown=true;return;}}
        Minecraft mc=Minecraft.getInstance();
        if(mc.level!=null||!(mc.screen instanceof TitleScreen)||mc.getOverlay()!=null){titleTicks=0;return;}
        if(++titleTicks<30)return;
        shown=true;
        mc.setScreen(new com.hanchen.veritycustomapi.config.SettingsExperienceScreen(mc.screen));
    }
    /**
     * 兼容旧调用点（VerityWelcomeScreen 的「完成/稍后设置」）。
     *
     * <p>🔴 5.2 起「是否再弹引导」只由 {@link com.hanchen.veritycustomapi.config.SettingsExperience}
     * 的选择结果决定，不再看 onboarding-v1.done 这个旧标记。这里保留只是为了不打断老调用方的编译，
     * **不会**再把玩家标记成「已完成」，否则从欢迎页退出会被误判为老用户。
     */
    public static boolean complete(){
        shown=true;return true;
    }
}
