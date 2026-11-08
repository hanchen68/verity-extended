package com.hanchen.veritycustomapi.client;

import java.util.ArrayList;
import java.util.List;

/** Pure pixel geometry shared by wheel drawing and pointer selection. */
public final class QuickWheelLayout {
    public record Span(int x,int y,int width,int sector){}
    private QuickWheelLayout(){}
    public static int radius(int width,int height){return Math.max(24,Math.min(94,Math.min((width-24)/2,(height-108)/2)));}
    public static int sector(double x,double y,int radius){
        double d=x*x+y*y;
        if(d<radius*radius*0.17||d>radius*radius)return -1;
        double angle=(Math.atan2(y,x)+Math.PI/2+Math.PI/6+Math.PI*2)%(Math.PI*2);
        double part=angle/(Math.PI/3);double edge=part-Math.floor(part);
        if(edge<0.025||edge>0.975)return -1;
        return (int)part;
    }
    /** Directional gesture remains selected past the visible outer edge; centre cancels. */
    public static int direction(double x,double y,int radius){
        double distance=Math.sqrt(x*x+y*y);
        if(distance<radius*.42)return -1;
        double angle=(Math.atan2(y,x)+Math.PI/2+Math.PI/6+Math.PI*2)%(Math.PI*2);
        return (int)(angle/(Math.PI/3));
    }
    public static List<Span> spans(int radius){
        List<Span> result=new ArrayList<>();
        for(int y=-radius;y<=radius;y++){
            int start=-radius,previous=sector(start,y,radius);
            for(int x=-radius+1;x<=radius+1;x++){
                int current=x>radius?-1:sector(x,y,radius);
                if(current!=previous||x==radius+1){if(previous>=0)result.add(new Span(start,y,x-start,previous));start=x;previous=current;}
            }
        }
        return List.copyOf(result);
    }
}
