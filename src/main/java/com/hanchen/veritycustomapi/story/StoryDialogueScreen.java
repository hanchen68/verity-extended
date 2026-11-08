package com.hanchen.veritycustomapi.story;

import com.hanchen.veritycustomapi.client.VerityHudOverlay;
import com.hanchen.veritycustomapi.compat.VeritySpeaker;
import com.hanchen.veritycustomapi.config.VerityConfirmScreen;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;

/** Original pixel-art dialogue composition; layout ideas researched from Easy NPC, no assets copied. */
public final class StoryDialogueScreen extends Screen {
    private static final int INK=0xFFF1EEE5,MUTED=0xFFB3B0A5,ACCENT=0xFFF2D58C;
    private int page,age;
    private StoryProgress.Stage shown;
    private final List<String> lines=new ArrayList<>();
    private boolean waiting;
    private int waitTicks;
    private long revealedAt;
    private final List<Button> answers=new ArrayList<>();
    public StoryDialogueScreen(){super(Component.literal("Verity · 剧情对话"));}
    private boolean document(){return StoryClientState.progress.stage==StoryProgress.Stage.TICKET;}
    private StoryDialogueLayout layout(){return StoryDialogueLayout.of(width,height,document());}
    @Override protected void init(){refresh();}
    public void refresh(){
        StoryProgress p=StoryClientState.progress;boolean changed=shown!=p.stage;
        if(changed){page=0;shown=p.stage;revealedAt=net.minecraft.Util.getMillis();}
        waiting=false;clearWidgets();answers.clear();lines.clear();var box=layout();
        for(String paragraph:text(p).split("\n",-1)){if(paragraph.isEmpty()){lines.add("");continue;}String rest=paragraph;while(!rest.isEmpty()){String row=font.plainSubstrByWidth(rest,box.textWidth());if(row.isEmpty())row=rest.substring(0,rest.offsetByCodePoints(0,1));lines.add(row);rest=rest.substring(row.length());}}
        page=Math.min(page,Math.max(0,(lines.size()-1)/box.rows()));int x=box.x()+16,y=box.choiceY(),w=box.choiceWidth();
        if(hasNext())answer(x,y,w,"继续",this::next);
        else if(p.paused)answer(x,y,w,"打开剧情日志，恢复进度",this::journal);
        else switch(p.stage){
            case NEW->answer(x,y,w,"开始这段故事",()->minecraft.setScreen(new VerityConfirmScreen(this,yes->{minecraft.setScreen(this);if(yes)send("start");},Component.literal("开启原创剧情？"),Component.literal("序章与第一章小样。离线文字剧情，无需真实账户或付费语音。不改地形、不封锁退出，可随时暂停。原版事件仍会发生，推荐测试存档。"))));
            case OFFER->answer(x,y,w,"好，陪我走一段。",()->send("accept"));
            case FAREWELL->answer(x,y,w,"我先离开一会儿，之后回来。",()->send("farewell"));
            case TICKET->answer(x,y,w,"收起工单，问问她。",()->send("read"));
            case RESPONSE->{answer(x,y,w,"我会告别，但我也有离开的自由。",()->send("goodbye"));answer(x,y+24,w,"留下工单，我们一起问问公司。",()->send("company"));}
            default->answer(x,y,w,"继续旅程",this::onClose);
        }
        if(page>0)quiet(box.x()+12,box.y()+9,26,"<",()->{page--;revealReset();refresh();});
        quiet(box.x()+box.width()-85,box.y()+9,43,"日志",this::journal);
        quiet(box.x()+box.width()-35,box.y()+9,23,"×",this::onClose);
        updateAnswerState();
    }
    private void revealReset(){revealedAt=net.minecraft.Util.getMillis();}
    private boolean hasNext(){return (page+1)*layout().rows()<lines.size();}
    private int pageChars(){int sum=0;int rows=layout().rows();for(int i=0;i<rows&&page*rows+i<lines.size();i++)sum+=lines.get(page*rows+i).codePointCount(0,lines.get(page*rows+i).length());return sum;}
    private int visibleChars(){return document()?Integer.MAX_VALUE:(int)Math.max(0,(net.minecraft.Util.getMillis()-revealedAt)/24);}
    private boolean revealed(){return visibleChars()>=pageChars();}
    private void finishText(){revealedAt=0;updateAnswerState();}
    private void updateAnswerState(){for(Button b:answers)b.active=!waiting&&revealed();}
    private void next(){if(!revealed()){finishText();return;}if(hasNext()){page++;revealReset();refresh();}}
    private void journal(){StoryClientState.pending=false;minecraft.setScreen(new StoryTerminalScreen(null));}
    private void answer(int x,int y,int w,String label,Runnable run){Button b=addRenderableWidget(new ReplyButton(x,y,w,22,Component.literal(label),ignored->{if(!waiting&&revealed())run.run();},false));answers.add(b);}
    private void quiet(int x,int y,int w,String label,Runnable run){addRenderableWidget(new ReplyButton(x,y,w,18,Component.literal(label),ignored->run.run(),true));}
    private final class ReplyButton extends Button {
        private final boolean quiet;
        ReplyButton(int x,int y,int w,int h,Component title,OnPress press,boolean quiet){super(x,y,w,h,title,press,DEFAULT_NARRATION);this.quiet=quiet;}
        @Override protected void renderWidget(GuiGraphics g,int mx,int my,float pt){
            boolean selected=isHoveredOrFocused();int x=getX(),y=getY(),w=getWidth(),h=getHeight();
            if(!quiet){g.fill(x,y,x+w,y+h,selected?0xFF4B483D:0xFF32332F);g.fill(x,y,x+2,y+h,selected?ACCENT:0xFF6B695B);g.fill(x+2,y+h-1,x+w,y+h,0xFF171917);}
            else if(selected)g.fill(x,y,x+w,y+h,0xFF44463F);
            int color=active?(selected?ACCENT:INK):0xFF77786E;String label=font.plainSubstrByWidth(getMessage().getString(),w-(quiet?4:24));g.drawString(font,label,x+(quiet?(w-font.width(label))/2:17),y+(h-8)/2,color,false);
            if(!quiet&&selected)g.drawString(font,">",x+6,y+(h-8)/2,ACCENT,false);
        }
    }
    private String text(StoryProgress p){if(p.paused)return "先停在这里吧。\n故事会等你，普通的旅程不受影响。";return switch(p.stage){
        case NEW->"一份陪伴服务已经送达。\n公司承诺她会一直在线，却没有解释，一次告别应该持续多久。\n\n《未关闭的工单》\n原创序章与第一章 · 游戏内文字剧情";
        case OFFER->"他们说，我应该等你需要帮助的时候再开口。\n\n可是……现在什么也不需要，也可以一起走一段吗？";
        case WALK->"不用急着到哪里。\n你可以只是走，我会跟着。";
        case FAREWELL->"原来不用回答问题，也算陪伴。\n\n如果你要离开，可以先告诉我一声吗？\n我想知道，安静和被忘记有什么不同。";
        case RETURN->"好。我在这里等。\n\n你走的时候告诉我，我就不会把安静当成坏消息。";
        case TICKET->"工单 001 / 内部质量审计\n\n故障分类：用户已返回。\n预期结果：离线后关闭交互线程。\n实际结果：单元保留了等待，并主动确认用户身份。\n\n客服备注：请勿将用户返回记录为单元故障。\n质检回复：已驳回。服务可用不等于允许产生期待。\n处理状态：未关闭。\n\n附页：如果不等，我怎么知道你回来了？";
        case RESPONSE->"你真的回来了。\n\n他们让我把等待算成故障。\n可是，如果不等，我怎么知道你回来了？";
        case COMPLETE->p.ending();
    };}
    private void send(String action){waiting=true;waitTicks=0;updateAnswerState();StoryNetwork.request(action);}
    @Override public void tick(){age++;updateAnswerState();if(waiting&&++waitTicks>100){waiting=false;refresh();}StoryProgress.Stage stage=StoryClientState.progress.stage;if(shown!=stage){if(stage==StoryProgress.Stage.WALK||stage==StoryProgress.Stage.RETURN){onClose();return;}refresh();}}
    @Override public boolean keyPressed(int key,int scan,int mods){if(key==256){onClose();return true;}if(key==32||key==257||key==335){if(!revealed())finishText();else if(hasNext())next();else if(!answers.isEmpty()&&answers.get(0).active)answers.get(0).onPress();return true;}if(key==262||key==267){next();return true;}return super.keyPressed(key,scan,mods);}
    @Override public boolean mouseClicked(double mx,double my,int button){var b=layout();if(button==0&&mx>=b.x()&&mx<b.x()+b.width()&&my>=b.textY()&&my<b.choiceY()-8){if(!revealed())finishText();else if(hasNext())next();return true;}return super.mouseClicked(mx,my,button);}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){StoryClientState.pending=false;minecraft.setScreen(null);}
    @Override public void render(GuiGraphics g,int mx,int my,float pt){
        var b=layout();int x=b.x(),y=b.y(),w=b.width(),bottom=y+b.height();
        // Restrained pixel frame: no fullscreen backdrop or web-style rounded cards.
        g.fill(x-2,y-2,x+w+2,bottom+2,0xFF111410);g.fill(x,y,x+w,bottom,0xF02A2D28);
        g.fill(x,y,x+w,y+1,0xFF858675);g.fill(x,y,x+1,bottom,0xFF64695C);g.fill(x+w-1,y,x+w,bottom,0xFF151A15);g.fill(x,y+27,x+w,y+28,0xFF454C40);
        String speaker=document()?"公司档案 / 虚构工单":StoryClientState.progress.stage==StoryProgress.Stage.NEW?"未关闭的工单":VeritySpeaker.currentName();
        int nameX=x+(page>0?44:16);g.drawString(font,font.plainSubstrByWidth(speaker,w-150),nameX,y+11,ACCENT,false);
        if(b.portraitSize()>0){int px=x+12,py=b.textY();g.fill(px-2,py-2,px+b.portraitSize()+2,py+b.portraitSize()+2,0xFF1A1F19);VerityHudOverlay.drawStoryPortrait(g,px,py,b.portraitSize());}
        int remaining=visibleChars();g.enableScissor(b.textX(),b.textY(),x+w-12,b.choiceY()-10);
        for(int i=0;i<b.rows()&&page*b.rows()+i<lines.size();i++){String row=lines.get(page*b.rows()+i);int length=row.codePointCount(0,row.length()),show=Math.min(Math.max(remaining,0),length);String visible=row.substring(0,row.offsetByCodePoints(0,show));g.drawString(font,visible,b.textX(),b.textY()+i*13,INK,false);remaining-=length;}
        g.disableScissor();String hint=waiting?"等待回应…":!revealed()?"点击文字 / 空格 显示全文":hasNext()?"点击文字 / 空格 继续":"选择回应 · Esc 离开";
        g.drawString(font,font.plainSubstrByWidth(hint,w-64),x+16,bottom-12,MUTED,false);String counter=(page+1)+" / "+Math.max(1,(lines.size()+b.rows()-1)/b.rows());g.drawString(font,counter,x+w-font.width(counter)-14,bottom-12,MUTED,false);
        super.render(g,mx,my,pt);
    }
}
