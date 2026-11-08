package com.hanchen.veritycustomapi.story;

/** Pixel-space layout shared by rendering and pure JVM size tests. */
public record StoryDialogueLayout(int x,int y,int width,int height,int textX,int textY,int textWidth,int rows,int choiceY,int choiceWidth,int portraitSize) {
    public static StoryDialogueLayout of(int sw,int sh,boolean document){
        int w=Math.max(160,Math.min(document?560:600,sw-24)),x=(sw-w)/2;
        int h=Math.min(document?sh-32:196,sh-40),y=sh-h-16;
        int portrait=document?0:(sw>=340?48:32),tx=x+16+(portrait>0?portrait+14:0),ty=y+36;
        int choiceY=sh-80,rows=Math.max(1,(choiceY-12-ty)/13);
        return new StoryDialogueLayout(x,y,w,h,tx,ty,w-(tx-x)-16,rows,choiceY,w-32,portrait);
    }
}
