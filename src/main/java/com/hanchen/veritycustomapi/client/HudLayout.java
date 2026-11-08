package com.hanchen.veritycustomapi.client;

/** HUD geometry in scaled GUI pixels, independent of Minecraft rendering. */
public final class HudLayout {
    public record Box(int x,int y,int width,int height) {}
    private HudLayout() {}
    public static Box dialogue(int width,int height,boolean portrait,int lines) {
        int w=Math.max(1,Math.min(268,Math.min(Math.max(1,width-12),Math.max(152,width*2/5))));
        int h=Math.min(Math.max(1,height-12),Math.max(portrait?32:0,12+Math.max(1,Math.min(4,lines))*10)+12);
        int bottom=Math.max(0,height-58); // Above hotbar/health area, never into the central hotbar.
        return new Box(Math.max(0,width-w-6),Math.max(6,bottom-h),w,h);
    }
    public static Box status(int screenWidth,int desiredWidth,boolean quota,boolean size){
        int w=Math.max(1,Math.min(Math.max(1,screenWidth-12),Math.min(Math.max(1,screenWidth/2-12),desiredWidth)));
        return new Box(6,6,w,38+(quota?13:0)+(size?13:0));
    }
    public static int portraitSize(int width){return width<320?24:32;}
    public static int navigationY(int height){return Math.max(12,Math.min(height/2+28,height-80));}
}
