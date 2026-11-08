package com.hanchen.veritycustomapi.config;

/** Information architecture, independent of rendering and configuration persistence. */
public final class ConfigNavigation {
    private static final String[] GROUPS={"开始使用","对话与声音","伙伴与世界","外观与表现","界面与记录","连接与维护"};
    private static final int[][] PAGES={{0},{1,4,5},{2,7,8,10},{6,12},{13},{11,3,9,14}};
    private static final String[][] TABS={{"概览"},{"对话与记忆","语音输入","语音输出"},{"世界交互","伙伴互动","事件回应","建造"},{"皮肤与动作","MMD"},{"显示与记录"},{"账户","多人设置","视觉来源","高级诊断"}};
    private ConfigNavigation(){}
    public static int count(){return GROUPS.length;}
    public static String title(int group){return GROUPS[group];}
    public static int group(int page){for(int g=0;g<PAGES.length;g++)for(int p:PAGES[g])if(p==page)return g;return 0;}
    public static int[] pages(int group){return PAGES[group].clone();}
    public static String tab(int group,int index){return TABS[group][index];}
    public static boolean collapsedByDefault(String section){
        return section.contains("高级") || section.contains("协议") || section.contains("后端")
                || section.equals("云端连接") || section.equals("朗读路线与语言")
                || section.equals("能力边界") || section.equals("常用玩法") || section.equals("游戏日志");
    }
}
