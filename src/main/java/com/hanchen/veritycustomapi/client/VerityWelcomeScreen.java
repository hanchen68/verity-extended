package com.hanchen.veritycustomapi.client;

import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.config.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import java.util.List;

/** First-launch guided setup: opaque, cinematic pixel geometry; all actions remain optional. */
public final class VerityWelcomeScreen extends Screen {
    private static final int GOLD=0xFFE5CC83,INK=0xFF171818,PAPER=0xFFE6E3DB,MUTED=0xFFACADA5;
    private static final String[] TITLES={"与你的同伴相遇","选择对话服务","设置语音","准备出发"};
    private static final String[] COPY={
        "欢迎使用 Verity 扩展增强。先完成几个简单选择，之后随时能在设置里修改。",
        "网页已经配好：粘贴 6 位配对码即可。还没配过：浏览器登录后，再在游戏中选择服务。只选一种方式，不会自动测试。", 
        "文字对话可以直接使用。需要语音时再设置音色和语音输入，不用现在下载模型。",
        "设置会保留。进入世界后就能和同伴对话，之后再决定是否开启自主行动。自主行动会消耗额度，并可能改动世界。"};
    private final Screen parent;
    private int step;
    private long changedAt=net.minecraft.Util.getMillis();
    private String notice="";
    public VerityWelcomeScreen(Screen parent){super(Component.literal("欢迎使用 Verity"));this.parent=parent;}
    private int left(){return Math.max(12,(width-Math.min(660,width-24))/2);}
    private int right(){return width-left();}
    private int bodyLeft(){return width>=560?left()+190:left()+12;}
    @Override protected void init(){
        int x=left()+12,bottom=height-32,w=Math.max(40,Math.min(92,(right()-left()-36)/3));
        addRenderableWidget(VerityUiWidgets.secondaryButton(font,x,bottom,w,20,Component.literal("稍后设置"),b->finish()));
        if(step>0)addRenderableWidget(VerityUiWidgets.secondaryButton(font,right()-2*w-18,bottom,w,20,Component.literal("上一步"),b->move(-1)));
        addRenderableWidget(VerityUiWidgets.primaryButton(font,right()-w-12,bottom,w,20,Component.literal(step==3?"完成":"下一步"),b->{if(step==3)finish();else move(1);}));
        int y=Math.max(100,height-94),bx=bodyLeft(),bw=Math.max(40,right()-bx-12);
        if(step==1){
            addRenderableWidget(VerityUiWidgets.secondaryButton(font,bx,y,bw,20,Component.literal("连接账户"),b->minecraft.setScreen(new InGameCloudSetupScreen(this))));
            addRenderableWidget(VerityUiWidgets.secondaryButton(font,bx,y+24,bw,20,Component.literal("使用自己的 API"),b->openSettings(1)));
        }else if(step==2){
            int half=Math.max(20,(bw-6)/2);
            addRenderableWidget(VerityUiWidgets.secondaryButton(font,bx,y,half,20,Component.literal("语音朗读"),b->openSettings(5)));
            addRenderableWidget(VerityUiWidgets.secondaryButton(font,bx+half+6,y,half,20,Component.literal("语音识别"),b->openSettings(4)));
        }else if(step==3){
            addRenderableWidget(VerityUiWidgets.secondaryButton(font,bx,y,bw,20,Component.literal("打开设置首页"),b->minecraft.setScreen(com.hanchen.veritycustomapi.config.SettingsExperience.open(this))));
        }
    }
    private void openSettings(int page){minecraft.setScreen(NativeOllamaProxyConfigScreen.openPage(this,page));}
    private void move(int delta){step=Math.max(0,Math.min(3,step+delta));changedAt=net.minecraft.Util.getMillis();rebuildWidgets();}
    private void finish(){if(FirstRunGuide.complete())minecraft.setScreen(parent);else notice="无法保存完成记录。可以按 Esc 返回，下次启动可能还会显示。";}
    @Override public void onClose(){finish();}
    @Override public boolean isPauseScreen(){return true;}
    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        ScreenBackgrounds.draw(this,g);
        int l=left(),r=right(),bx=bodyLeft();
        long now=net.minecraft.Util.getMillis();
        double p=VerityUiAnimation.reducedMotion()?1:VerityUiAnimation.progress(changedAt,now,450);
        g.fill(0,0,width,3,GOLD);
        g.drawString(font,"VERITY  /  初次设置",l+12,18,MUTED,false);
        g.drawString(font,"0"+(step+1)+" / 04",r-60,18,GOLD,false);
        g.fill(l+12,36,r-12,37,0xFF454641);
        int segment=Math.max(1,(r-l-24)/4);
        for(int i=0;i<4;i++){
            int length=i<step?segment-4:i==step?(int)((segment-4)*p):0;
            if(length>0)g.fill(l+12+i*segment,36,l+12+i*segment+length,38,GOLD);
        }
        if(width>=560){
            int cx=l+85,cy=height/2,rad=Math.min(52,Math.max(24,height/6));
            // A pixel sphere, not a fake 3-D entity or unverified downloaded asset.
            for(int row=-rad;row<=rad;row++){
                int span=(int)Math.sqrt(Math.max(0,rad*rad-row*row));
                g.fill(cx-span,cy+row,cx+span+1,cy+row+1,VerityUiDraw.blend(0xFF927941,GOLD,(row+rad)/(double)(rad*2)));
            }
            g.fill(cx-18,cy-7,cx-13,cy,INK);
            g.fill(cx+13,cy-7,cx+18,cy,0xFF242521);
            g.fill(cx-10,cy+12,cx+11,cy+15,0xFF242521);
            g.drawCenteredString(font,"初次见面",cx,cy+rad+17,MUTED);
        }
        String heading=font.plainSubstrByWidth(TITLES[step],Math.max(20,r-bx-12));
        g.drawString(font,heading,bx,57,PAPER,false);
        List<FormattedCharSequence> lines=font.split(Component.literal(COPY[step]),Math.max(30,r-bx-16));
        int textBottom=Math.max(103,height-113),y=77;
        for(var line:lines){if(y+9>textBottom)break;g.drawString(font,line,bx,y,MUTED,false);y+=12;}
        if(step==1&&height>=260){
            g.drawString(font,ConfigManager.isCloudConfigLoaded()?"账户已连接":"尚未连接，可以稍后设置",bx,y+8,GOLD,false);
        }
        if(!notice.isBlank())g.drawString(font,font.plainSubstrByWidth(notice,r-l-24),l+12,height-46,VerityUiTheme.DANGER,false);
        super.render(g,mx,my,partial);
    }
}
