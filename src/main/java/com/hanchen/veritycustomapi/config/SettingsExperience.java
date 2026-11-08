package com.hanchen.veritycustomapi.config;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.fml.loading.FMLPaths;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;

/** Local UI preference, not a service setting and never sent to cloud. */
public final class SettingsExperience {
    public enum Mode { UNSET, SIMPLE, FULL }
    private static Mode mode;
    private SettingsExperience(){}
    private static Path file(){return FMLPaths.CONFIGDIR.get().resolve("verity_extended/settings-experience.txt");}
    public static Mode parse(String value){try{return Mode.valueOf(value.trim());}catch(RuntimeException e){return Mode.UNSET;}}
    public static Mode mode(){if(mode==null){try{mode=parse(Files.readString(file(),StandardCharsets.UTF_8));}catch(java.io.IOException e){mode=Mode.UNSET;}}return mode;}
    public static boolean choose(Mode next){
        if(next==null||next==Mode.UNSET)return false;
        try{Path f=file();Files.createDirectories(f.getParent());Path tmp=Files.createTempFile(f.getParent(),"ui-mode-",".tmp");try{Files.writeString(tmp,next.name()+"\n",StandardCharsets.UTF_8);try{Files.move(tmp,f,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,f,StandardCopyOption.REPLACE_EXISTING);}}finally{Files.deleteIfExists(tmp);}mode=next;return true;}
        catch(java.io.IOException e){OllamaProxyMod.LOGGER.warn("无法保存设置界面偏好",e);return false;}
    }
    public static Screen open(Screen parent){return switch(mode()){case SIMPLE->new VeritySettingsHomeScreen(parent);case FULL->new NativeOllamaProxyConfigScreen(parent);case UNSET->new SettingsExperienceScreen(parent);};}
}
