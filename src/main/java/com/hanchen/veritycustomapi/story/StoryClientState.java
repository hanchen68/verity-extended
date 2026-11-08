package com.hanchen.veritycustomapi.story;

import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/** World-session snapshot shared by HUD, dialogue and journal. Cleared on disconnect. */
@OnlyIn(Dist.CLIENT)
public final class StoryClientState {
    public static final StoryProgress progress=new StoryProgress();
    public static boolean received,pending;
    public static String message="",line="";
    public static long lineUntil;
    public static int companionId=-1;
    public static double anchorX,anchorY,anchorZ;
    public static String dimension="";
    private StoryClientState(){}
    public static void clear(){received=false;pending=false;progress.stage=StoryProgress.Stage.NEW;progress.active=false;progress.paused=false;line="";message="";companionId=-1;lineUntil=0;}
    public static void accept(CompoundTag tag){
        StoryProgress.Stage stage;try{stage=StoryProgress.Stage.valueOf(tag.getString("stage"));}catch(IllegalArgumentException e){return;}
        boolean change=received&&stage!=progress.stage;
        progress.stage=stage;progress.active=tag.getBoolean("active");progress.paused=tag.getBoolean("paused");progress.walked=tag.getDouble("walked");progress.togetherSeconds=tag.getInt("seconds");progress.response=tag.getString("response");
        companionId=tag.getInt("companion_id");dimension=tag.getString("dimension");anchorX=tag.getDouble("anchor_x");anchorY=tag.getDouble("anchor_y");anchorZ=tag.getDouble("anchor_z");message=tag.getString("message");received=true;
        if(!tag.getString("line").isBlank()){line=tag.getString("line");lineUntil=net.minecraft.Util.getMillis()+12000;}
        if(!progress.active||progress.paused)pending=false;
        else if(tag.getBoolean("open_dialog")||(change&&(stage==StoryProgress.Stage.OFFER||stage==StoryProgress.Stage.TICKET||stage==StoryProgress.Stage.RESPONSE||stage==StoryProgress.Stage.COMPLETE)))pending=true;
        StoryTerminalScreen.receive(tag);
        Minecraft mc=Minecraft.getInstance();
        if(change&&stage==StoryProgress.Stage.OFFER&&mc.screen instanceof StoryTerminalScreen){mc.setScreen(new StoryDialogueScreen());pending=false;}if(mc.screen instanceof StoryDialogueScreen screen){if(change&&(stage==StoryProgress.Stage.WALK||stage==StoryProgress.Stage.RETURN))screen.onClose();else{pending=false;screen.refresh();}}
    }
}
