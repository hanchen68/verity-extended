package com.hanchen.veritycustomapi.client;

import com.hanchen.veritycustomapi.config.VerityUiAnimation;
import com.hanchen.veritycustomapi.config.VerityUiDraw;
import com.hanchen.veritycustomapi.config.VerityUiTheme;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import java.util.List;

/** Reusable directional wheel; visual and pointer geometry share the same animated transform. */
public abstract class HoldActionWheelScreen extends Screen {
    private final KeyMapping binding;
    private final boolean hold;
    private boolean fired;
    private long openedAt,lastFrame;
    private double reveal=1;
    private final double[] highlights=new double[6];
    protected int radius,cx,cy;
    private int selected=-1;
    private List<QuickWheelLayout.Span> strips=List.of();
    protected HoldActionWheelScreen(String title,KeyMapping binding,boolean hold){super(Component.literal(title));this.binding=binding;this.hold=hold;}
    protected abstract String label(int sector);
    protected abstract void choose(int sector);
    protected String pageName(){return title.getString();}
    protected void changePage(int direction){}
    @Override protected void init(){
        radius=QuickWheelLayout.radius(width,height);cx=width/2;cy=height/2;strips=QuickWheelLayout.spans(radius);
        openedAt=net.minecraft.Util.getMillis();lastFrame=openedAt;selected=-1;fired=false;
        // Opening starts in the dead zone, not wherever the previous menu left the pointer.
        GLFW.glfwSetCursorPos(minecraft.getWindow().getWindow(),minecraft.getWindow().getScreenWidth()/2.0,minecraft.getWindow().getScreenHeight()/2.0);
    }
    private boolean physicallyDown(){
        InputConstants.Key key=binding.getKey();long window=minecraft.getWindow().getWindow();
        if(key.getType()==InputConstants.Type.MOUSE)return GLFW.glfwGetMouseButton(window,key.getValue())==GLFW.GLFW_PRESS;
        if(key.getType()==InputConstants.Type.SCANCODE)return binding.isDown();
        return key.getValue()!=GLFW.GLFW_KEY_UNKNOWN&&InputConstants.isKeyDown(window,key.getValue());
    }
    private int direction(double x,double y){
        double scale=0.9+0.1*reveal;
        return QuickWheelLayout.direction((x-cx)/scale,(y-cy)/scale,radius);
    }
    @Override public void mouseMoved(double x,double y){selected=direction(x,y);super.mouseMoved(x,y);}
    @Override public void tick(){
        if(!minecraft.isWindowActive()){onClose();return;}
        if(hold&&!physicallyDown())release();
    }
    private void release(){
        if(fired)return;fired=true;
        int choice=selected;
        minecraft.setScreen(null);
        if(choice>=0)choose(choice);
    }
    @Override public boolean keyReleased(int key,int scan,int modifiers){if(hold&&binding.matches(key,scan)){release();return true;}return super.keyReleased(key,scan,modifiers);}
    @Override public boolean keyPressed(int key,int scan,int modifiers){
        if(key==GLFW.GLFW_KEY_ESCAPE){onClose();return true;}
        if(binding.matches(key,scan)){if(!hold)onClose();return true;}
        if(key>=GLFW.GLFW_KEY_1&&key<=GLFW.GLFW_KEY_6){selected=key-GLFW.GLFW_KEY_1;if(!hold)release();return true;}
        if(key==GLFW.GLFW_KEY_LEFT||key==GLFW.GLFW_KEY_RIGHT){selected=(selected+(key==GLFW.GLFW_KEY_RIGHT?1:5)+6)%6;return true;}
        if(key==GLFW.GLFW_KEY_ENTER){release();return true;}
        return super.keyPressed(key,scan,modifiers);
    }
    @Override public boolean mouseClicked(double x,double y,int button){if(button==1){onClose();return true;}if(button==0){selected=direction(x,y);release();return true;}return true;}
    @Override public boolean mouseScrolled(double x,double y,double delta){if(delta!=0){changePage(delta>0?-1:1);selected=-1;java.util.Arrays.fill(highlights,0);}return true;}
    @Override public void removed(){while(binding.consumeClick()){}super.removed();}
    @Override public void onClose(){fired=true;minecraft.setScreen(null);}
    @Override public boolean isPauseScreen(){return false;}
    @Override public void render(GuiGraphics g,int mx,int my,float partial){
        long now=net.minecraft.Util.getMillis(),dt=VerityUiAnimation.frameDelta(lastFrame,now);lastFrame=now;
        reveal=VerityUiAnimation.reducedMotion()?1:VerityUiAnimation.progress(openedAt,now,150);
        for(int i=0;i<6;i++)highlights[i]=VerityUiAnimation.feedback(highlights[i],i==selected,dt);
        double scale=.9+.1*reveal;
        g.pose().pushPose();g.pose().translate(cx,cy,0);g.pose().scale((float)scale,(float)scale,1);
        for(var strip:strips){int color=VerityUiDraw.blend(0x98202020,0xDD6B6246,highlights[strip.sector()]);color=VerityUiDraw.withAlpha(color,(int)(((color>>>24)&255)*reveal));g.fill(strip.x(),strip.y(),strip.x()+strip.width(),strip.y()+1,color);}
        for(int i=0;i<6;i++){
            double angle=-Math.PI/2+i*Math.PI/3;int x=(int)Math.round(Math.cos(angle)*radius*.7),y=(int)Math.round(Math.sin(angle)*radius*.7);
            String text=font.plainSubstrByWidth(label(i),Math.max(24,(int)(radius*.65)));
            g.drawCenteredString(font,text,x,y-4,i==selected?VerityUiTheme.ACCENT:VerityUiTheme.TEXT);
        }
        // Centre is the cancel dead zone: a small cross says so without a second copy of the label.
        int arm=Math.max(2,radius/24);
        for(int i=-arm;i<=arm;i++){
            g.fill(i,-i-1,i+1,-i+1,VerityUiTheme.MUTED);
            g.fill(i,i-1,i+1,i+1,VerityUiTheme.MUTED);
        }
        g.pose().popPose();
        g.drawCenteredString(font,pageName(),cx,Math.max(3,cy-radius-19),VerityUiTheme.TEXT);
    }
}
