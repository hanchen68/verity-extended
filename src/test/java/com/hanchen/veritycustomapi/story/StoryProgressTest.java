package com.hanchen.veritycustomapi.story;

import java.nio.file.Files;
import java.nio.file.Path;

public final class StoryProgressTest {
    private static int checks;
    public static void main(String[] args)throws Exception{
        net.minecraft.SharedConstants.tryDetectVersion();net.minecraft.server.Bootstrap.bootStrap();
        StoryProgress p=new StoryProgress();check(!p.running(),"opt in only");check(!p.readTicket()&&!p.choose("company"),"no stage skipping");check(p.start(),"start");check(!p.start(),"idempotent start");check(!p.accept(""),"companion required");check(p.accept("own-companion"),"accept");
        check(p.pause(),"pause");for(int i=0;i<50;i++)p.accompany(4);check(p.walked==0&&p.togetherSeconds==0,"pause freezes progress");check(p.resume(),"resume");
        p.accompany(Double.NaN);p.accompany(Double.POSITIVE_INFINITY);p.accompany(-1);p.accompany(1000);check(p.walked==0,"invalid or teleport distance ignored");
        for(int i=0;i<15;i++)p.accompany(2);check(p.stage==StoryProgress.Stage.FAREWELL,"walk threshold");check(!p.accompany(2),"no repeated stage award");
        check(p.farewell(),"farewell");check(!p.visit(1),"cannot immediately return");check(!p.visit(16)&&p.wentAway,"leave checkpoint");check(p.visit(6),"return checkpoint");check(p.archive().contains("故障分类：用户已返回"),"ticket unlocked");check(p.readTicket(),"read");check(!p.choose("arbitrary"),"choices allowlisted");check(p.choose("goodbye"),"ending one");check(p.ending().contains("自由离开"),"free to leave");check(!p.choose("company"),"ending immutable");
        StoryProgress other=new StoryProgress();check(other.stage==StoryProgress.Stage.NEW&&!other.active,"player isolation");other.start();other.accept("v");for(int i=0;i<15;i++)other.accompany(2);other.farewell();other.pause();check(!other.rejoin(),"paused rejoin does not advance");other.resume();check(other.rejoin(),"normal rejoin alternative");other.readTicket();check(other.choose("company")&&other.ending().contains("尚未开放"),"ending two honest scope");
        String base="src/main/java/com/hanchen/veritycustomapi/";
        String service=Files.readString(Path.of(base+"story/StoryService.java"));String data=Files.readString(Path.of(base+"story/StoryData.java"));String net=Files.readString(Path.of(base+"story/StoryNetwork.java"));String ui=Files.readString(Path.of(base+"story/StoryTerminalScreen.java"));String lore=Files.readString(Path.of(base+"cloud/CloudServiceLore.java"));
        check(service.contains("ServerVerityRegistry.owns(player,v)"),"companion ownership");check(service.contains("getEntity(java.util.UUID.fromString"),"bound UUID lookup");check(service.contains("pendingRejoin"),"paused rejoin remembered");check(service.contains("isSwimming()")&&service.contains("isFallFlying()"),"walking only");
        check(data.contains("overworld().getDataStorage()")&&data.contains("t.putUUID(\"player\""),"per-save per-player persistence");check(data.contains("schema"),"persistence version");
        check(net.contains("getSender()")&&!net.contains("readUUID("),"sender authoritative");check(net.contains("PLAY_TO_SERVER")&&net.contains("PLAY_TO_CLIENT")&&net.contains("DistExecutor.unsafeRunWhenOn"),"direction and dedicated-server guard");check(net.contains("verity_story_last_packet"),"request limit");
        check(ui.contains("请先读到末页")&&ui.contains("readToEnd"),"ticket reading gate");check(ui.contains("恢复剧情")&&ui.contains("暂停剧情")&&ui.contains("重置自己的剧情"),"controls accessible");check(ui.contains("confirming")&&ui.contains("if(confirming)return"),"confirm does not double request");
        for(String banned:new String[]{"queryAiAsync(","api.disconnect(","saveAuthorization(","CameraCaptureService","runStt(","setBlock(","discard()","VeritySay.to("})check(!service.contains(banned),"offline non-destructive service: "+banned);
        check(!lore.contains("不提供账号注销")&&!lore.contains("VeritySay.to("),"real-account separation");
        check(!ui.contains("setScreen(null)"),"return to caller");
        for(int h:new int[]{180,240,360,720})for(boolean paused:new boolean[]{false,true}){int top=paused?66:44;int visible=Math.max(1,(h-top-79)/11);check(top+visible*11<=h-79,"text/footer geometry");}
        check(ui.contains("\"上翻\"")&&ui.contains("\"下翻\""),"touch-accessible reading controls");
        java.util.UUID first=java.util.UUID.randomUUID(),second=java.util.UUID.randomUUID();
        StoryData stored=new StoryData();StoryData.Entry entry=stored.create(first);entry.progress.start();entry.progress.accept("bound");entry.progress.togetherSeconds=9;entry.progress.walked=12;entry.progress.pause();entry.anchorX=23;entry.dimension="minecraft:overworld";
        StoryData restored=StoryData.load(stored.save(new net.minecraft.nbt.CompoundTag()));
        check(restored.find(first).progress.paused&&restored.find(first).progress.walked==12,"NBT roundtrip keeps pause/progress");check(restored.find(first).progress.companion.equals("bound"),"NBT keeps bound companion");check(restored.find(second)==null,"NBT player isolation");check(new StoryData().find(first)==null,"save isolation");
        restored.create(second).progress.start();restored.reset(first);check(restored.find(first)==null&&restored.find(second)!=null,"reset only sender");
        String client=Files.readString(Path.of(base+"story/StoryClientEvents.java")),dialogue=Files.readString(Path.of(base+"story/StoryDialogueScreen.java")),state=Files.readString(Path.of(base+"story/StoryClientState.java"));
        check(client.contains("RenderGuiEvent.Post")&&client.contains("陪伴 "),"in-world objective HUD");check(client.contains("PauseScreen")&&client.contains("STORY_KEY.consumeClick()"),"ingame menu and configurable shortcut");check(client.contains("getTarget().getId()!=StoryClientState.companionId"),"right click only own companion");check(client.contains("RenderLevelStageEvent.Stage.AFTER_ENTITIES")&&client.contains("约定地点"),"world checkpoint label");check(dialogue.contains("一起")||dialogue.contains("追问公司"),"branch choices in dialogue");check(!dialogue.contains("ScreenBackgrounds.draw")&&dialogue.contains("isPauseScreen(){return false;}"),"world visible in dialogue");check(state.contains("StoryTerminalScreen.receive")&&state.contains("pending=false;screen.refresh()"),"shared state and no repeated popup");check(net.contains("verity_story_last_status"),"poll must not swallow action");
        for(int sw:new int[]{240,320,427,640,960})for(int sh:new int[]{180,240,360,540})for(boolean document:new boolean[]{false,true}){
            var b=StoryDialogueLayout.of(sw,sh,document);check(b.x()>=0&&b.x()+b.width()<=sw,"dialogue stays on screen");check(b.y()>=0&&b.y()+b.height()<=sh,"vertical frame");check(b.textWidth()>40&&b.textY()+b.rows()*13<=b.choiceY()-8,"text does not touch choices");check(b.choiceY()+46<=b.y()+b.height()-14,"stacked choices fit");
        }
        check(dialogue.contains("drawStoryPortrait")&&dialogue.contains("visibleChars()")&&dialogue.contains("offsetByCodePoints"),"portrait unicode typewriter");check(dialogue.contains("ReplyButton")&&!dialogue.contains("VerityUiWidgets.secondaryButton"),"distinct narrative answers");
        // Archived: default off, and every entry point must respect the switch.
        String modConfig=Files.readString(Path.of(base+"config/ModConfig.java")),menu=Files.readString(Path.of(base+"config/NativeOllamaProxyConfigScreen.java"));
        check(modConfig.contains(".define(\"storyModeEnabled\", false)"),"story mode must default to disabled");
        check(service.contains("public static boolean enabled()"),"story needs a single enable gate");
        check(service.contains("if(!enabled()){StoryNetwork.send")&&service.contains("if(!enabled())return;"),"server actions and interaction gated");
        check(client.contains("if(!StoryService.enabled())"),"client HUD, key and pause entry gated");
        check(!menu.contains("action(\"剧情模式 · 未关闭的工单\"")||menu.contains("if (com.hanchen.veritycustomapi.story.StoryService.enabled())"),"overview entry hidden by default");
        check(menu.contains("ModConfig.STORY_MODE_ENABLED::get"),"opt-in switch must be reachable in config UI");
        System.out.println("Story prototype checks passed: "+checks);
    }
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
}
