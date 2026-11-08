package com.hanchen.veritycustomapi.config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import java.util.function.Consumer;

/** Minecraft-native controls; legacy factory names retained to preserve all call sites. */
@OnlyIn(Dist.CLIENT)
public final class VerityUiWidgets {
    private VerityUiWidgets() {}
    public static Button roundedButton(Font font,int x,int y,int width,int height,Component label,
                                       int background,int textColor,Button.OnPress onPress) {
        return new Button(x,y,width,height,label,onPress,narration -> narration.get().copy()) {
            private double hover;
            private long frameAt,pressedAt;
            @Override public void onPress() {
                pressedAt=net.minecraft.Util.getMillis();
                super.onPress();
            }
            @Override protected void renderWidget(GuiGraphics g,int mouseX,int mouseY,float partialTick) {
                long now=net.minecraft.Util.getMillis();
                hover=VerityUiAnimation.feedback(hover,active&&isHoveredOrFocused(),VerityUiAnimation.frameDelta(frameAt,now));
                frameAt=now;
                if ((background >>> 24) == 0) {
                    // Category navigation is a list, not a column of raised buttons.
                    if (hover>0.001D) {
                        g.fill(getX(),getY(),getX()+getWidth(),getY()+getHeight(),
                                VerityUiDraw.blend(VerityUiTheme.SIDEBAR,VerityUiTheme.ROW_HOVER,hover));
                    }
                    if(active&&isHoveredOrFocused())g.fill(getX(),getY(),getX()+2,getY()+getHeight(),VerityUiTheme.ACCENT);
                    String text=font.plainSubstrByWidth(getMessage().getString(),Math.max(1,getWidth()-12));
                    g.drawString(font,text,getX()+6,getY()+(getHeight()-font.lineHeight)/2,
                            active?(isHoveredOrFocused()?VerityUiTheme.ACCENT:VerityUiTheme.TEXT):0xFFA0A0A0,false);
                } else {
                    // Vanilla skin, focus ring, disabled state, narration and resource-pack support.
                    super.renderWidget(g,mouseX,mouseY,partialTick);
                    // A fine opaque border settles in; resource-pack button texture stays intact.
                    if(active&&hover>0.001D) {
                        int edge=VerityUiDraw.blend(VerityUiTheme.CONTROL_BORDER,VerityUiTheme.ACCENT,hover);
                        g.fill(getX()+2,getY()+getHeight()-2,getX()+getWidth()-2,getY()+getHeight()-1,edge);
                    }
                }
                if(active&&!VerityUiAnimation.reducedMotion()&&pressedAt>0&&now-pressedAt<100) {
                    int edge=VerityUiDraw.blend(VerityUiTheme.ACCENT,VerityUiTheme.CONTROL_BORDER,
                            VerityUiAnimation.progress(pressedAt,now,100));
                    g.fill(getX()+1,getY()+1,getX()+getWidth()-1,getY()+2,edge);
                }
            }
        };
    }
    public static Button primaryButton(Font font,int x,int y,int width,int height,Component label,Button.OnPress onPress) {
        return roundedButton(font,x,y,width,height,label,VerityUiTheme.BUTTON,VerityUiTheme.TEXT,onPress);
    }
    public static Button secondaryButton(Font font,int x,int y,int width,int height,Component label,Button.OnPress onPress) {
        return roundedButton(font,x,y,width,height,label,VerityUiTheme.BUTTON,VerityUiTheme.TEXT,onPress);
    }
    public static Button dangerButton(Font font,int x,int y,int width,int height,Component label,Button.OnPress onPress) {
        return roundedButton(font,x,y,width,height,label,VerityUiTheme.BUTTON,VerityUiTheme.DANGER,onPress);
    }
    public static Checkbox pillSwitch(int x,int y,int width,int height,boolean selected,Consumer<Boolean> onToggle) {
        return new Checkbox(x,y,width,height,Component.empty(),selected) {
            private double check=selected?1.0D:0.0D;
            private long frameAt;
            @Override public void renderWidget(GuiGraphics g,int mouseX,int mouseY,float partialTick) {
                long now=net.minecraft.Util.getMillis();
                check=VerityUiAnimation.feedback(check,selected(),VerityUiAnimation.frameDelta(frameAt,now));
                frameAt=now;
                VerityUiDraw.switchPill(g,getX(),getY(),getWidth(),getHeight(),selected(),active&&isHoveredOrFocused(),check);
            }
            @Override public void onPress() {
                super.onPress(); if(onToggle!=null)onToggle.accept(selected());
            }
        };
    }
    public static EditBox roundedEditBox(Font font,int x,int y,int width,int height,Component narration) {
        // Native geometry: cursor, click coordinates and text padding now use the same origin.
        EditBox box=new EditBox(font,x,y,width,height,narration);
        box.setBordered(true); box.setTextColor(VerityUiTheme.TEXT); box.setTextColorUneditable(VerityUiTheme.MUTED);
        return box;
    }
    public static void drawFieldBackground(GuiGraphics g,int x,int y,int width,int height,boolean focused,Runnable drawBody) {
        g.fill(x,y,x+width,y+height,focused?VerityUiTheme.TEXT:VerityUiTheme.CONTROL_BORDER);
        g.fill(x+1,y+1,x+width-1,y+height-1,VerityUiTheme.CONTROL_FIELD);
        g.enableScissor(x+1,y+1,x+width-1,y+height-1);
        g.pose().pushPose();
        try { g.pose().translate(4,Math.max(1,(height-Minecraft.getInstance().font.lineHeight)/2),0); drawBody.run(); }
        finally { g.pose().popPose(); g.disableScissor(); }
    }
}
