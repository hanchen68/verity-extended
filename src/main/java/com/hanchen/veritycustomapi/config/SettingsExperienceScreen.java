package com.hanchen.veritycustomapi.config;

import com.hanchen.veritycustomapi.client.ScreenBackgrounds;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * One explicit choice, two audiences.
 *
 * <p>🔴 返回链路：这个界面既可能从主菜单/模组列表直接打开（首次），
 * 也可能是玩家在设置里点「切换新手/完整菜单」打开的。
 * 后一种情况下 {@code parent} 是**另一个设置界面**，如果直接把新菜单的父屏设成它，
 * 返回就会回到旧菜单、再点切换 → 形成 设置→选择→设置 的环。
 * 所以切换场景下必须**跨过设置界面，回到它外面的那一屏**。
 *
 * <p>🔴 不能根据「是否有授权/是否有旧引导标记」猜玩家水平——只认玩家自己选的结果。
 */
public final class SettingsExperienceScreen extends Screen {
    private final Screen parent;
    private String message="只改变菜单的显示方式，不会重置或上传你的配置。";

    public SettingsExperienceScreen(Screen parent){super(Component.literal("选择你的设置方式"));this.parent=parent;}

    /** 从设置界面点「切换」进来的，返回时要跳过那一屏。 */
    private boolean nested(){
        return parent instanceof VeritySettingsHomeScreen || parent instanceof NativeOllamaProxyConfigScreen;
    }

    /**
     * 选择完成后新菜单的父屏。
     *
     * <p>切换场景下用「外层界面」：简化首页自己知道它的 parent，
     * 完整菜单的 parent 就是主菜单/模组列表那一屏。这样新菜单返回一次就离开设置。
     */
    private Screen hostForChosenMenu(){
        if(!nested())return parent;
        if(parent instanceof VeritySettingsHomeScreen home)return home.parentScreen();
        return ((NativeOllamaProxyConfigScreen) parent).parentScreenRef();
    }

    @Override protected void init(){
        int l=left(),w=span();
        addRenderableWidget(VerityUiWidgets.secondaryButton(font,l,54,w,24,
                Component.literal("第一次使用 · 简化设置"),b->choose(SettingsExperience.Mode.SIMPLE)));
        addRenderableWidget(VerityUiWidgets.secondaryButton(font,l,96,w,24,
                Component.literal("已配置过 / 熟悉设置 · 完整菜单"),b->choose(SettingsExperience.Mode.FULL)));
        addRenderableWidget(VerityUiWidgets.secondaryButton(font,l,height-28,w,20,
                Component.literal(nested()?"返回，保持当前菜单":"稍后选择，先开始游戏"),b->onClose()));
    }

    private void choose(SettingsExperience.Mode next){
        if(!SettingsExperience.choose(next)){message="选择未能保存，请检查配置目录权限；可稍后再选。";return;}
        Screen host=hostForChosenMenu();
        minecraft.setScreen(next==SettingsExperience.Mode.SIMPLE
                ? new VeritySettingsHomeScreen(host)
                : new NativeOllamaProxyConfigScreen(host));
    }

    private int left(){return Math.max(12,(width-Math.min(430,width-24))/2);}
    private int span(){return width-2*left();}

    @Override public boolean isPauseScreen(){return true;}

    @Override public void onClose(){
        // 切换场景：回到原来的菜单（菜单本身已是 SELECTED 状态，不需要重建）。
        if(nested()&&parent!=null){minecraft.setScreen(parent);return;}
        minecraft.setScreen(parent);
    }

    @Override public void render(GuiGraphics g,int mx,int my,float pt){
        ScreenBackgrounds.draw(this,g);int l=left(),w=span();
        g.drawString(font,"你希望怎样设置 Verity？",l,16,VerityUiTheme.TEXT,false);
        g.drawString(font,"以后可在任一菜单中重新选择",l,33,VerityUiTheme.MUTED,false);
        g.drawString(font,font.plainSubstrByWidth("新手：先连接，再按需要调整，少看复杂参数。",w),l,82,VerityUiTheme.MUTED,false);
        g.drawString(font,font.plainSubstrByWidth("熟悉的玩家：直接进入完整分类，原有设置不变。",w),l,124,VerityUiTheme.MUTED,false);
        if(height>=210){int y=145;for(var line:font.split(Component.literal(message),w)){if(y+10>height-36)break;g.drawString(font,line,l,y,VerityUiTheme.MUTED,false);y+=11;}}
        super.render(g,mx,my,pt);
    }
}
