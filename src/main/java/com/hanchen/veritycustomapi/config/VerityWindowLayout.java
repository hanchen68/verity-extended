package com.hanchen.veritycustomapi.config;

/**
 * Shared window geometry. Content surfaces stay opaque, but never fill the whole screen:
 * the world remains visible around them, matching the compact wheel.
 */
public final class VerityWindowLayout {
    private VerityWindowLayout(){}
    /** Inset grows with the window so very small screens are not squeezed; 10–34 px a side. */
    public static int margin(int width,int height){
        int byWidth=width/18,byHeight=height/12;
        return Math.max(10,Math.min(34,Math.min(byWidth,byHeight)));
    }
    public static int panelLeft(int width,int height,int maxWidth){
        int usable=Math.max(80,width-margin(width,height)*2);
        return (width-Math.min(maxWidth,usable))/2;
    }
    public static int panelRight(int width,int height,int maxWidth){return width-panelLeft(width,height,maxWidth);}
    public static int panelTop(int height){return Math.max(6,Math.min(18,height/28));}
    public static int panelBottom(int height){return height-panelTop(height);}
    /** Secondary modals stay smaller so the page behind still reads as context. */
    public static int compactLeft(int width,int height,int maxWidth){return panelLeft(width,height,Math.min(maxWidth,Math.max(160,width*2/3)));}
    public static int compactRight(int width,int height,int maxWidth){return width-compactLeft(width,height,maxWidth);}
}
