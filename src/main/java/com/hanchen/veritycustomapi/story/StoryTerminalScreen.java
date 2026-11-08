package com.hanchen.veritycustomapi.story;

import com.hanchen.veritycustomapi.client.ScreenBackgrounds;
import com.hanchen.veritycustomapi.config.VerityConfirmScreen;
import com.hanchen.veritycustomapi.config.VerityUiTheme;
import com.hanchen.veritycustomapi.config.VerityUiWidgets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import java.util.ArrayList;
import java.util.List;

/** Minecraft-native, scrollable fictional terminal; always offers an ordinary return button. */
@OnlyIn(Dist.CLIENT)
public final class StoryTerminalScreen extends Screen {
    private final Screen parent;
    private final StoryProgress progress=new StoryProgress();
    private boolean received,waiting,archive,readToEnd;
    private String message="正在读取本存档的剧情进度…";
    private int scroll,requestTick,ticks;
    private long syncTick;
    private boolean confirming;
    private final List<FormattedCharSequence> lines=new ArrayList<>();
    public StoryTerminalScreen(Screen parent){super(Component.literal("未关闭的工单"));this.parent=parent;}
    private int left(){return Math.max(12,(width-Math.min(600,width-24))/2);}
    private int span(){return width-2*left();}
    private boolean supported(){return minecraft!=null&&minecraft.getConnection()!=null&&StoryNetwork.supported(minecraft.getConnection().getConnection());}
    @Override protected void init(){controls();if(confirming)return;if(supported())send("status");else{message="请进入安装了本扩展的世界或服务器。无需连接云端账户。";controls();}}
    public static void receive(CompoundTag tag){Minecraft mc=Minecraft.getInstance();if(mc.screen instanceof StoryTerminalScreen screen)screen.accept(tag);}
    private void accept(CompoundTag tag){
        try{progress.stage=StoryProgress.Stage.valueOf(tag.getString("stage"));}catch(IllegalArgumentException e){message="剧情状态无法识别，请更新扩展。";waiting=false;return;}
        progress.active=tag.getBoolean("active");progress.paused=tag.getBoolean("paused");
        if(progress.stage==StoryProgress.Stage.NEW&&!progress.active){archive=false;readToEnd=false;scroll=0;}
        progress.togetherSeconds=tag.getInt("seconds");progress.walked=tag.getDouble("walked");progress.response=tag.getString("response");
        if(!tag.getString("message").isBlank())message=tag.getString("message");else message=progress.paused?"剧情已暂停。":"进度保存在本存档，随时可以离开。";
        received=true;waiting=false;syncTick=ticks;controls();
    }
    private Button button(int x,int y,int w,String label,Runnable action){return addRenderableWidget(VerityUiWidgets.secondaryButton(font,x,y,w,20,Component.literal(label),b->action.run()));}
    private String body(){
        if(!received)return "原创剧情小样 · 序章＋第一章\n\n这里是一份虚构工单终端，不是云端账户后台。无需 API、额度或摄像头。\n请先进入存档，再开启剧情。";
        if(archive)return progress.archive();
        if(progress.stage==StoryProgress.Stage.NEW)return "原创剧情小样 · 序章＋第一章\n\n你领到了一份普通的陪伴服务。公司只承诺它会一直在线，却没有解释它该如何理解一次告别。\n\n本小样：领取虚构服务 → 陪它走一段 → 道别 → 再次回来 → 阅读异常工单。\n\n完全离线，不调用 AI、不收费、不读取现实资料，也不打开麦克风或摄像头。不会改地形、生成敌人、封锁退出或修改真实账户。\n\n这里只暂停本扩展的剧情进度，不关闭原版本来已有的事件。推荐在测试存档体验。\n\n随时可暂停；进度按存档和玩家单独保存。后续章节尚未制作。";
        String text=progress.title()+"\n\n当前目标："+progress.objective();
        if(progress.stage==StoryProgress.Stage.WALK)text+="\n\n陪伴："+Math.min(15,progress.togetherSeconds)+" / 15 秒\n步行："+(int)Math.min(24,progress.walked)+" / 24 格\n\n请关闭终端，在自己同伴的 8 格内步行。飞行、骑乘与远距离瞬移不计入。";
        if(progress.stage==StoryProgress.Stage.RETURN)text+="\n\n不必真的等一天。走开再回来，就算兑现一次约定。正常退出游戏也始终可用。";
        if(progress.stage==StoryProgress.Stage.COMPLETE)text+="\n\n"+progress.ending();
        if(progress.paused)text="【已暂停】\n恢复后才会继续记录目标。\n\n"+text;
        return text;
    }
    private int visible(){return Math.max(1,(height-66-79)/11);}
    private void layoutText(){lines.clear();for(String line:body().split("\n",-1)){if(line.isEmpty())lines.add(FormattedCharSequence.EMPTY);else lines.addAll(font.split(Component.literal(line),Math.max(40,span()-20)));}scroll=Math.max(0,Math.min(scroll,Math.max(0,lines.size()-visible())));if(archive&&scroll+visible()>=lines.size())readToEnd=true;}
    private void controls(){
        clearWidgets();int l=left(),w=span();layoutText();
        int half=(w-4)/2,y=height-51;
        Button main=button(l,y,half,mainLabel(),this::main);main.active=received&&!waiting&&supported()&&(!progress.paused||progress.active);
        if(archive){button(l+half+4,y,half,"返回目标",()->{archive=false;scroll=0;controls();});}
        else if(progress.stage==StoryProgress.Stage.RESPONSE&&!progress.paused){Button b=button(l+half+4,y,half,"一起追问公司",()->send("company"));b.active=!waiting;}
        else if(progress.active){Button b=button(l+half+4,y,half,"阅读档案",()->{archive=true;readToEnd=false;scroll=0;controls();});b.active=!waiting;}
        button(l,height-27,Math.max(44,(w-8)/3),"返回",this::onClose);
        Button refresh=button(l+(w-8)/3+4,height-27,(w-8)/3,"刷新进度",()->send("status"));refresh.active=supported()&&!waiting;
        if(progress.active){Button pause=button(l+2*((w-8)/3+4),height-27,(w-8)/3,progress.paused?"恢复剧情":"暂停剧情",()->send(progress.paused?"resume":"pause"));pause.active=!waiting;}
        if(lines.size()>visible()){
            button(l+w-104,40,50,"上翻",()->{scroll-=visible();controls();});
            button(l+w-50,40,50,"下翻",()->{scroll+=visible();controls();});
        }
        if(progress.paused)button(l,40,Math.min(w-108,120),"重置自己的剧情",()->ask("重置剧情进度？","只清除本存档中你自己的剧情记录，不影响物品、伙伴、世界和真实账户。",()->send("reset")));
    }
    private String mainLabel(){if(progress.paused)return "剧情已暂停";if(archive)return progress.stage==StoryProgress.Stage.TICKET?(readToEnd?"已读，继续":"请先读到末页"):"档案已打开";return switch(progress.stage){case NEW->"开启原创剧情";case OFFER->"领取陪伴服务";case WALK->"返回游戏，开始陪伴";case FAREWELL->"向它道别";case RETURN->"返回游戏，兑现约定";case TICKET->"打开异常工单";case RESPONSE->"约定告别，保留自由";case COMPLETE->"重读异常工单";};}
    private void main(){
        if(progress.paused)return;
        if(archive){if(progress.stage==StoryProgress.Stage.TICKET&&readToEnd){archive=false;scroll=0;send("read");}return;}
        switch(progress.stage){
            case NEW->ask("开启原创剧情小样？","这是虚构公司终端，不关联真实账户。序章与第一章已开放，随时可暂停。不会关闭原版已有事件，推荐使用测试存档。",()->send("start"));
            case OFFER->send("accept");
            case WALK,RETURN->onClose();
            case FAREWELL->send("farewell");
            case TICKET,COMPLETE->{archive=true;readToEnd=false;scroll=0;controls();}
            case RESPONSE->send("goodbye");
        }
    }
    private void ask(String title,String text,Runnable yes){confirming=true;minecraft.setScreen(new VerityConfirmScreen(this,ok->{minecraft.setScreen(this);confirming=false;if(ok)yes.run();else controls();},Component.literal(title),Component.literal(text)));}
    private void send(String action){if(!supported()){message="服务器未提供剧情通道，未修改进度。";waiting=false;controls();return;}waiting=true;requestTick=ticks;StoryNetwork.request(action);controls();}
    @Override public void tick(){ticks++;if(waiting&&ticks-requestTick>100){waiting=false;message="未收到剧情响应，可以刷新或返回；未确认的操作不会在本地冒充成功。";controls();}if(!waiting&&received&&progress.stage==StoryProgress.Stage.WALK&&ticks-syncTick>=40)send("status");}
    @Override public boolean mouseScrolled(double x,double y,double delta){scroll+=delta<0?3:-3;layoutText();controls();return true;}
    @Override public boolean keyPressed(int key,int scan,int mods){if(key==266||key==267){scroll+=key==267?visible():-visible();layoutText();controls();return true;}return super.keyPressed(key,scan,mods);}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){minecraft.setScreen(parent);}
    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        ScreenBackgrounds.draw(this,g);int l=left(),w=span();g.drawString(font,"未关闭的工单 · 剧情终端",l,12,VerityUiTheme.TEXT,false);g.drawString(font,"原创支线 / 虚构公司 / 非真实账户服务",l,26,VerityUiTheme.MUTED,false);
        layoutText();int top=66;g.enableScissor(l,top,l+w,height-79);for(int i=0;i<visible()&&scroll+i<lines.size();i++)g.drawString(font,lines.get(scroll+i),l+6,top+i*11,VerityUiTheme.TEXT,false);g.disableScissor();
        if(lines.size()>visible())g.drawString(font,(scroll+1)+"–"+Math.min(lines.size(),scroll+visible())+" / "+lines.size()+" · 滚轮 / PgDn",l,height-77,VerityUiTheme.MUTED,false);
        g.drawString(font,font.plainSubstrByWidth(waiting?"等待服务器确认…":message,w),l,height-65,VerityUiTheme.MUTED,false);super.render(g,mx,my,partial);
    }
}
