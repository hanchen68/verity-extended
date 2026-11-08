package com.hanchen.veritycustomapi.story;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.client.KeyBindings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Native gameplay HUD and client-only stage cues; no framebuffer, files or paid services. */
@Mod.EventBusSubscriber(modid=OllamaProxyMod.MOD_ID,value=Dist.CLIENT,bus=Mod.EventBusSubscriber.Bus.FORGE)
public final class StoryClientEvents {
    private static int ticks;
    private static net.minecraft.client.multiplayer.ClientLevel world;
    private StoryClientEvents(){}
    @SubscribeEvent(priority=net.minecraftforge.eventbus.api.EventPriority.HIGHEST,receiveCanceled=true)
    public static void interact(net.minecraftforge.event.entity.player.PlayerInteractEvent.EntityInteract e){
        if(!e.getEntity().level().isClientSide||e.getHand()!=net.minecraft.world.InteractionHand.MAIN_HAND||!e.getEntity().getMainHandItem().isEmpty()||e.getEntity().isShiftKeyDown()||!(e.getTarget() instanceof varmite.verity.entity.verity.VerityEntity))return;
        StoryProgress p=StoryClientState.progress;        if(!StoryService.enabled()||!StoryClientState.received||!p.running())return;
        if(e.getTarget().getId()!=StoryClientState.companionId)return;
        e.setCanceled(true);e.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);StoryNetwork.request("talk");
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut e){StoryClientState.clear();world=null;ticks=0;}
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent e){
        if(e.phase!=TickEvent.Phase.END)return;if(!StoryService.enabled()){if(StoryClientState.received)StoryClientState.clear();return;}Minecraft mc=Minecraft.getInstance();if(mc.level==null||mc.player==null)return;
        if(world!=mc.level){world=mc.level;StoryClientState.clear();ticks=0;}
        ticks++;if(mc.getConnection()==null||!StoryNetwork.supported(mc.getConnection().getConnection()))return;
        if((ticks==20||ticks%40==0)&&mc.screen==null)StoryNetwork.request("status");
        while(KeyBindings.STORY_KEY.consumeClick())if(mc.screen==null){StoryClientState.pending=false;mc.setScreen(new StoryDialogueScreen());}
        if(StoryClientState.pending&&mc.screen==null){StoryClientState.pending=false;mc.setScreen(new StoryDialogueScreen());}
        StoryProgress p=StoryClientState.progress;
        if(p.running()&&p.stage==StoryProgress.Stage.RETURN&&mc.level.dimension().location().toString().equals(StoryClientState.dimension)&&ticks%4==0){
            double x=StoryClientState.anchorX,y=StoryClientState.anchorY,z=StoryClientState.anchorZ;
            if(mc.player.distanceToSqr(x,y,z)<4096){double a=ticks*0.09;mc.level.addParticle(ParticleTypes.END_ROD,x+Math.cos(a)*.6,y+.3,z+Math.sin(a)*.6,0,.015,0);}
        }
    }
    @SubscribeEvent public static void pauseMenu(ScreenEvent.Init.Post e){
        if(!StoryService.enabled()||!(e.getScreen() instanceof PauseScreen))return;var screen=e.getScreen();int x=screen.width/2-102,y=screen.height-26;
        // Find a genuinely unused bottom row instead of covering an existing vanilla/mod button.
        while(y>30){boolean overlap=false;for(var listener:screen.children())if(listener instanceof net.minecraft.client.gui.components.AbstractWidget b&&x<b.getX()+b.getWidth()&&x+204>b.getX()&&y<b.getY()+b.getHeight()&&y+20>b.getY()){overlap=true;break;}if(!overlap)break;y-=24;}
        if(y<=30)return;
        e.addListener(Button.builder(Component.literal("剧情 · 未关闭的工单"),b->{StoryClientState.pending=false;Minecraft.getInstance().setScreen(new StoryTerminalScreen(screen));}).bounds(x,y,204,20).build());
    }
    @SubscribeEvent public static void render(RenderGuiEvent.Post e){
        if(!StoryService.enabled())return;
        Minecraft mc=Minecraft.getInstance();StoryProgress p=StoryClientState.progress;if(mc.player==null||mc.options.hideGui||mc.screen instanceof StoryDialogueScreen||!StoryClientState.received||!p.active)return;
        GuiGraphics g=e.getGuiGraphics();int sw=mc.getWindow().getGuiScaledWidth(),sh=mc.getWindow().getGuiScaledHeight();int w=Math.min(248,sw/2),x=sw-w-8,y=10;
        g.fill(x,y,x+w,y+58,0xD9202020);g.fill(x,y,x+2,y+58,0xFFFFFFA0);g.drawString(mc.font,mc.font.plainSubstrByWidth((p.paused?"已暂停 / ":"")+p.title(),w-12),x+7,y+6,0xFFFFFFA0,false);
        String objective=switch(p.stage){case OFFER->"靠近自己的 Verity，右键接受邀请";case WALK->"陪伴 "+Math.min(15,p.togetherSeconds)+"/15秒 · 步行 "+(int)p.walked+"/24格";case FAREWELL->"右键 Verity，和她道别";case RETURN->"回到金色约定标记 · "+markerDistance(mc);case TICKET->"收到异常工单，打开剧情对话";case RESPONSE->"回应她：告别，不等于抛弃";case COMPLETE->"第一章结束，可重读工单";default->p.objective();};
        g.drawString(mc.font,mc.font.plainSubstrByWidth(objective,w-12),x+7,y+23,0xFFE0E0E0,false);
        String hint=KeyBindings.STORY_KEY.getTranslatedKeyMessage().getString()+" 剧情 / Esc 剧情日志";g.drawString(mc.font,mc.font.plainSubstrByWidth(hint,w-12),x+7,y+42,0xFFB0B0B0,false);
        if(mc.screen==null&&!StoryClientState.line.isBlank()&&net.minecraft.Util.getMillis()<StoryClientState.lineUntil){
            int bw=Math.min(Math.max(100,sw/2-20),360),bx=10,by=sh-112;var lines=mc.font.split(Component.literal(StoryClientState.line),bw-20);int count=Math.min(3,lines.size());g.fill(bx,by,bx+bw,by+24+count*11,0xDC202020);g.drawString(mc.font,"Verity · 剧情字幕",bx+10,by+5,0xFFFFFFA0,false);for(int i=0;i<count;i++)g.drawString(mc.font,lines.get(i),bx+10,by+20+i*11,0xFFE0E0E0,false);
        }
    }
    @SubscribeEvent public static void worldMarker(net.minecraftforge.client.event.RenderLevelStageEvent e){
        if(!StoryService.enabled())return;
        Minecraft mc=Minecraft.getInstance();StoryProgress p=StoryClientState.progress;
        if(e.getStage()!=net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_ENTITIES||mc.level==null||mc.player==null||!p.running()||p.stage!=StoryProgress.Stage.RETURN||!mc.level.dimension().location().toString().equals(StoryClientState.dimension))return;
        var camera=e.getCamera();var point=new net.minecraft.world.phys.Vec3(StoryClientState.anchorX,StoryClientState.anchorY+1.6,StoryClientState.anchorZ).subtract(camera.getPosition());if(point.lengthSqr()>4096)return;
        var pose=e.getPoseStack();pose.pushPose();pose.translate(point.x,point.y,point.z);pose.mulPose(camera.rotation());pose.scale(-0.025F,-0.025F,0.025F);
        String text="约定地点 · "+markerDistance(mc);var buffers=mc.renderBuffers().bufferSource();mc.font.drawInBatch(text,-mc.font.width(text)/2F,0,0xFFFFFFA0,false,pose.last().pose(),buffers,net.minecraft.client.gui.Font.DisplayMode.NORMAL,0xB0202020,15728880);pose.popPose();
    }
    private static String markerDistance(Minecraft mc){if(!mc.level.dimension().location().toString().equals(StoryClientState.dimension))return "返回原维度";return (int)Math.sqrt(mc.player.distanceToSqr(StoryClientState.anchorX,StoryClientState.anchorY,StoryClientState.anchorZ))+"格";}
}
