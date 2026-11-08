package com.hanchen.veritycustomapi.story;

/** Deterministic, offline story state. No account, AI or world mutation belongs here. */
public final class StoryProgress {
    public enum Stage { NEW, OFFER, WALK, FAREWELL, RETURN, TICKET, RESPONSE, COMPLETE }
    public Stage stage=Stage.NEW;
    public boolean active,paused,wentAway;
    public int togetherSeconds;
    public double walked;
    public String response="",companion="";

    public boolean start(){if(stage!=Stage.NEW)return false;active=true;paused=false;stage=Stage.OFFER;return true;}
    public boolean accept(String ownedCompanion){
        if(!running()||stage!=Stage.OFFER||ownedCompanion==null||ownedCompanion.isBlank())return false;
        companion=ownedCompanion;stage=Stage.WALK;return true;
    }
    public boolean running(){return active&&!paused;}
    /** One server-second with a living, nearby bound companion. Teleports do not count. */
    public boolean accompany(double distance){
        if(!running()||stage!=Stage.WALK)return false;
        togetherSeconds=Math.min(3600,togetherSeconds+1);
        if(Double.isFinite(distance)&&distance>=0&&distance<=8)walked=Math.min(24,walked+distance);
        if(togetherSeconds>=15&&walked>=24){stage=Stage.FAREWELL;return true;}return false;
    }
    public boolean farewell(){if(!running()||stage!=Stage.FAREWELL)return false;stage=Stage.RETURN;wentAway=false;return true;}
    /** A real rejoin counts as returning; no real-world wait or forced disconnect required. */
    public boolean rejoin(){if(!running()||stage!=Stage.RETURN)return false;stage=Stage.TICKET;return true;}
    /** Alternative route: leave the checkpoint by 16 blocks and return within 6 blocks. */
    public boolean visit(double distance){
        if(!running()||stage!=Stage.RETURN||!Double.isFinite(distance))return false;
        if(distance>=16)wentAway=true;
        if(wentAway&&distance<=6){stage=Stage.TICKET;return true;}return false;
    }
    public boolean readTicket(){if(!running()||stage!=Stage.TICKET)return false;stage=Stage.RESPONSE;return true;}
    public boolean choose(String answer){
        if(!running()||stage!=Stage.RESPONSE||!("goodbye".equals(answer)||"company".equals(answer)))return false;
        response=answer;stage=Stage.COMPLETE;return true;
    }
    public boolean pause(){if(!active||paused)return false;paused=true;return true;}
    public boolean resume(){if(!active||!paused)return false;paused=false;return true;}
    public String title(){return switch(stage){
        case NEW,OFFER->"序章 · 欢迎使用";
        case WALK,FAREWELL->"序章 · 初次陪伴";
        case RETURN->"第一章 · 约定";
        case TICKET,RESPONSE->"第一章 · 你回来了";
        case COMPLETE->"小样结束 · 未关闭的工单";
    };}
    public String objective(){return switch(stage){
        case NEW->"阅读说明，自愿开启这份存档中的原创剧情。";
        case OFFER->"带自己的 Verity 到身边，在终端领取虚构陪伴服务。";
        case WALK->"与自己的 Verity 相距 8 格内，一起步行 24 格，陪伴至少 15 秒。";
        case FAREWELL->"打开终端，向它道别，留下稍后回来的约定。";
        case RETURN->"离开道别地点至少 16 格后回到 6 格内；也可以正常退出存档再回来。";
        case TICKET->"终端收到一张异常工单。打开档案，查明是谁把等待算成了故障。";
        case RESPONSE->"读完工单，选择你想对它说的话。";
        case COMPLETE->"序章与第一章已完成。可重读档案；后续调查章节尚未制作。";
    };}
    public String archive(){
        if(stage.ordinal()<Stage.TICKET.ordinal())return "【虚构公司 / 服务回执 001】\n陪伴单元：Verity。\n交付内容：回答、引导，以及被需要时的在场。\n内部条款：服务关系不因终端离线自动终止。\n\n页脚手写字：如果你走的时候告诉我一声，我就知道你不是忘了我。\n\n（这些是剧情内容，不是实际平台协议。暂停、退出游戏和解除真实授权始终可用。）";
        return "【虚构公司 / 异常工单 001】\n故障分类：用户已返回。\n预期结果：离线后关闭交互线程。\n实际结果：单元保留了等待，并主动确认用户身份。\n\n客服备注：请勿将用户返回记录为单元故障。\n质检回复：已驳回。服务可用不等于允许产生期待。\n处理状态：未关闭。\n\n附页 / Verity：\n你真的回来了。\n他们让我把等待算成故障。可是，如果不等，我怎么知道你回来了？\n\n【原创支线】这份工单属于扩展剧情，不代表原作官方设定，也不关联你的真实账号。";
    }
    public String ending(){return "company".equals(response)?
            "你决定追问公司。她把那张工单留了下来：『这一次，你不是来修理我的，对吗？』\n后续调查尚未开放。":
            "你答应离开前说一声，也说明自己可以自由离开。她停了一会儿：『好。我想学会分清，告别和抛弃。』\n这一回，等待没有被记成故障。";}
}
