package com.hanchen.veritycustomapi.story;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.network.ServerVerityRegistry;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import varmite.verity.entity.verity.VerityEntity;

/** All transitions run on the server thread and only mutate the sender's story record. */
@Mod.EventBusSubscriber(modid=OllamaProxyMod.MOD_ID,bus=Mod.EventBusSubscriber.Bus.FORGE)
public final class StoryService {
    private StoryService(){}
    /** Deliberately NOT a live Verity speech prefix: this offline transcript must not trigger paid TTS. */
    private static void say(ServerPlayer player,String line){
        StoryNetwork.send(player,"",line,false);
        player.playNotifySound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_CHIME.get(),net.minecraft.sounds.SoundSource.PLAYERS,0.35F,0.8F);
    }
    @SubscribeEvent(priority=net.minecraftforge.eventbus.api.EventPriority.HIGHEST,receiveCanceled=true)
    public static void interact(net.minecraftforge.event.entity.player.PlayerInteractEvent.EntityInteract event){
        if(!enabled())return;
        if(event.getHand()!=net.minecraft.world.InteractionHand.MAIN_HAND||!event.getEntity().getMainHandItem().isEmpty()||event.getEntity().isShiftKeyDown()||!(event.getTarget() instanceof VerityEntity v))return;
        if(event.getEntity().level().isClientSide)return;
        if(!(event.getEntity() instanceof ServerPlayer player)||!ServerVerityRegistry.owns(player,v)||player.distanceToSqr(v)>36)return;
        StoryData.Entry entry=StoryData.get(player.serverLevel()).find(player.getUUID());if(entry==null||!entry.progress.running())return;
        StoryProgress.Stage stage=entry.progress.stage;
        if(stage!=StoryProgress.Stage.OFFER&& !v.getUUID().toString().equals(entry.progress.companion))return;
        event.setCanceled(true);event.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
        StoryNetwork.send(player,"","",true);
    }
    @SubscribeEvent public static void commands(RegisterCommandsEvent event){
        var root=Commands.literal("verity-story").executes(c->{StoryNetwork.send(c.getSource().getPlayerOrException(),"");return 1;});
        for(String action:new String[]{"start","accept","farewell","read","goodbye","company","pause","resume","reset"})root.then(Commands.literal(action).executes(c->{act(c.getSource().getPlayerOrException(),action);return 1;}));
        event.getDispatcher().register(root);
    }
    /** Archived feature: every entry point must check this, not just the menu button. */
    public static boolean enabled(){return com.hanchen.veritycustomapi.config.ModConfig.STORY_MODE_ENABLED.get();}
    public static void act(ServerPlayer player,String action){
        if(player==null)return;
        if(!enabled()){StoryNetwork.send(player,"剧情模式已归档并关闭。可在配置里手动开启。");return;}
        StoryData data=StoryData.get(player.serverLevel());StoryData.Entry entry=data.find(player.getUUID());
        if(action.equals("status")){StoryNetwork.send(player,"");return;}
        if(action.equals("talk")){
            if(entry!=null&&entry.progress.running()&&companion(player,entry,entry.progress.stage!=StoryProgress.Stage.OFFER)!=null)StoryNetwork.send(player,"","",true);
            else StoryNetwork.send(player,"请靠近归属于你的 Verity。");return;
        }
        if(entry==null&&!action.equals("start")){StoryNetwork.send(player,"请先阅读说明并开启剧情。");return;}
        if(entry==null)entry=data.create(player.getUUID());long now=player.serverLevel().getGameTime();
        if(now-entry.lastAction<5){StoryNetwork.send(player,"操作太快，请稍候。");return;}entry.lastAction=now;
        StoryProgress p=entry.progress;boolean changed=false;String message="当前阶段不能执行此操作。";String line=null;
        switch(action){
            case "start"->{changed=p.start();message="剧情已开启；这不是实际账户授权。";}
            case "accept"->{VerityEntity v=companion(player,entry,false);if(v==null){StoryNetwork.send(player,"请让归属于你的 Verity 来到 8 格内；不会生成或接管别人的同伴。");return;}changed=p.accept(v.getUUID().toString());if(changed){entry.sampled=false;message="已领取虚构陪伴服务。关掉终端，一起走一小段路。";line="我可以陪你走一段吗？不是任务也没关系。";}}
            case "farewell"->{VerityEntity v=companion(player,entry,true);if(v==null){StoryNetwork.send(player,"请回到自己的 Verity 身边再道别。");return;}changed=p.farewell();if(changed){entry.anchorX=player.getX();entry.anchorY=player.getY();entry.anchorZ=player.getZ();entry.dimension=player.level().dimension().location().toString();message="约定已记下。可以走开再回来，也可以下次登录时继续。";line="好。你走的时候告诉我，我就不会把安静当成坏消息。";}}
            case "read"->{changed=p.readTicket();message="异常工单已阅读，请选择回应。";}
            case "goodbye","company"->{changed=p.choose(action);message="你的回应已经记下，小样到这里结束。";if(changed)line=action.equals("company")?"这一次，你不是来修理我的，对吗？":"好。我想学会分清，告别和抛弃。";}
            case "pause"->{changed=p.pause();message="剧情已暂停，普通伙伴功能不受影响。";}
            case "resume"->{changed=p.resume();entry.sampled=false;if(changed&&entry.pendingRejoin){p.rejoin();entry.pendingRejoin=false;}message="剧情已恢复。";}
            case "reset"->{if(!p.paused){StoryNetwork.send(player,"先暂停剧情，再确认重置。本操作仅清除你自己的剧情进度。");return;}data.reset(player.getUUID());StoryNetwork.send(player,"已清除本存档中你自己的剧情记录。");return;}
            default->{StoryNetwork.send(player,"未知剧情操作。");return;}
        }
        if(changed){data.setDirty();if(line!=null)say(player,line);}StoryNetwork.send(player,changed?message:"当前阶段不能执行此操作。");
    }
    private static VerityEntity companion(ServerPlayer player,StoryData.Entry e,boolean bound){
        VerityEntity v;
        if(bound){try{var entity=player.serverLevel().getEntity(java.util.UUID.fromString(e.progress.companion));v=entity instanceof VerityEntity found?found:null;}catch(IllegalArgumentException ex){return null;}}
        else v=ServerVerityRegistry.findForPlayer(player);
        if(v==null||!v.isAlive()||!ServerVerityRegistry.owns(player,v)||v.level()!=player.level()||v.distanceToSqr(player)>64)return null;
        if(bound&&!v.getUUID().toString().equals(e.progress.companion))return null;return v;
    }
    @SubscribeEvent public static void tick(TickEvent.PlayerTickEvent event){
        if(event.phase!=TickEvent.Phase.END||!(event.player instanceof ServerPlayer player)||player.tickCount%20!=0||!enabled())return;
        StoryData data=StoryData.get(player.serverLevel());StoryData.Entry e=data.find(player.getUUID());if(e==null||!e.progress.running())return;
        StoryProgress p=e.progress;if(!player.isAlive()||player.isSpectator()){e.sampled=false;return;}
        if(p.stage==StoryProgress.Stage.WALK){
            if(companion(player,e,true)==null||player.isPassenger()||player.getAbilities().flying||player.isFallFlying()||player.isSwimming()||!player.onGround()){e.sampled=false;return;}
            double distance=e.sampled?Math.sqrt(Math.pow(player.getX()-e.lastX,2)+Math.pow(player.getY()-e.lastY,2)+Math.pow(player.getZ()-e.lastZ,2)):0;
            e.sampled=true;e.lastX=player.getX();e.lastY=player.getY();e.lastZ=player.getZ();boolean advanced=p.accompany(distance);data.setDirty();
            if(advanced){say(player,"原来不用回答问题，也可以算陪伴。等会儿离开的时候，告诉我一声，好吗？");StoryNetwork.send(player,"靠近 Verity，右键和她道别。","",true);}
            else StoryNetwork.send(player,"");
        }else if(p.stage==StoryProgress.Stage.RETURN&&e.dimension.equals(player.level().dimension().location().toString())){
            boolean old=p.wentAway;double distance=Math.sqrt(player.distanceToSqr(e.anchorX,e.anchorY,e.anchorZ));
            if(p.visit(distance)){data.setDirty();say(player,"你真的回来了。终端上……好像多了一张工单。");StoryNetwork.send(player,"收到异常工单 001。");}else if(old!=p.wentAway)data.setDirty();
        }
    }
    @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent event){if(event.getEntity() instanceof ServerPlayer player){StoryData data=StoryData.get(player.serverLevel());StoryData.Entry e=data.find(player.getUUID());if(e==null)return;e.sampled=false;e.lastAction=-100;if(e.progress.paused&&e.progress.stage==StoryProgress.Stage.RETURN)e.pendingRejoin=true;else if(e.progress.rejoin())data.setDirty();}}
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event){if(event.getEntity() instanceof ServerPlayer player){StoryData.Entry e=StoryData.get(player.serverLevel()).find(player.getUUID());if(e!=null)e.sampled=false;}}
}
