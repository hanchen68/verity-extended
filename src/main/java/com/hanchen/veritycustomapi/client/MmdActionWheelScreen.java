package com.hanchen.veritycustomapi.client;

import com.hanchen.veritycustomapi.mmd.MmdPlaybackManager;

/** N-key immediate playback actions; browsing/editing projects remains a deliberate separate action. */
public final class MmdActionWheelScreen extends HoldActionWheelScreen {
    private final MmdPlaybackManager manager=MmdPlaybackManager.get();
    public MmdActionWheelScreen(){super("动画控制",KeyBindings.MMD_CONTROL_KEY,true);}
    @Override protected String label(int sector){return switch(sector){
        case 0->"选择舞蹈";
        case 1->"停止播放";
        case 2->manager.isFreezeOnPlay()?"解除定身":"播放定身";
        case 3->"正常速度";
        case 4->"减慢";
        default->"加快";
    };}
    @Override protected void choose(int sector){switch(sector){
        case 0->minecraft.setScreen(new MmdPlaybackScreen(null));
        case 1->manager.stopPlaybackForAllDemons();
        case 2->manager.setFreezeOnPlay(!manager.isFreezeOnPlay());
        case 3->manager.setPlaybackSpeed(1.0F);
        case 4->manager.setPlaybackSpeed(Math.max(.1F,manager.getPlaybackSpeed()-.25F));
        case 5->manager.setPlaybackSpeed(Math.min(10F,manager.getPlaybackSpeed()+.25F));
        default->{}
    }}
}
