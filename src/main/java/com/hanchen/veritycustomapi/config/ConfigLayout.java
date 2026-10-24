package com.hanchen.veritycustomapi.config;

/**
 * 配置界面的几何计算。
 *
 * <p>抽自 {@code NativeOllamaProxyConfigScreen}。抽出的理由很实在：
 * 这些方法**全部只是 {@code (width, height, 分类数)} 的函数**，不碰控件、不碰状态，
 * 但原来必须起一整个 Minecraft 才能验证「窄窗口下控件会不会溢出」。
 * 独立成类后可以用纯 JVM 跑边界宽度，把「布局在某个分辨率下崩掉」这类问题提前抓住。
 *
 * <p>🔴 三条不能破的约束（都有测试守着）：
 * <ol>
 *   <li><b>控件不能越出内容区</b>：{@code controlLeft + controlWidth <= contentLeft + contentWidth}。</li>
 *   <li><b>内容区不能压到侧栏</b>：{@code contentLeft >= sidebarLeft + sidebarWidth}。</li>
 *   <li><b>极窄窗口不出现负宽度</b>：所有宽度都 {@code >= 0}，否则 {@code fill} 会抛异常。</li>
 * </ol>
 */
public final class ConfigLayout {

    /** 面板最大宽度：再宽就不好看了，两侧留白。 */
    private static final int MAX_PANEL_WIDTH = 920;
    /** 侧栏列表的顶部起点。 */
    public static final int SIDEBAR_TOP = 44;

    private ConfigLayout() {
    }

    /** 矮或窄到需要压缩留白。 */
    public static boolean isCompact(int width, int height) {
        return width < 680 || height < 400;
    }

    /** 窄到要把「标题 + 值」改成上下两行。 */
    public static boolean isUltraCompact(int width) {
        return width < 430;
    }

    /** 只留给真正放不下的极窄/极矮窗口；普通矮窗口保留左侧列表，改为列表内滚动。 */
    public static boolean usesPagedNavigation(int width, int height) {
        return width < 300 || height < 220;
    }

    public static int outerMargin(int width, int height) {
        return isCompact(width, height) ? 8 : 14;
    }

    public static int contentTop() {
        return 58;
    }

    public static int contentBottom(int height) {
        return height - 50;
    }

    public static int visibleRowsHeight(int height) {
        return contentBottom(height) - contentTop();
    }

    public static int sidebarWidth(int width, int height) {
        if (usesPagedNavigation(width, height)) return 0;
        if (width < 560) return 108;
        return isCompact(width, height) ? 124 : 156;
    }

    public static int sidebarLeft(int width, int height) {
        int usableWidth = Math.max(0, width - outerMargin(width, height) * 2);
        int maxWidth = Math.min(MAX_PANEL_WIDTH, usableWidth);
        return Math.max(outerMargin(width, height), (width - maxWidth) / 2);
    }

    public static int panelRight(int width, int height) {
        return Math.min(width - outerMargin(width, height),
                sidebarLeft(width, height) + MAX_PANEL_WIDTH);
    }

    public static int contentLeft(int width, int height) {
        return sidebarLeft(width, height) + sidebarWidth(width, height)
                + (usesPagedNavigation(width, height) ? 0 : 12);
    }

    public static int contentWidth(int width, int height) {
        return Math.max(0, panelRight(width, height) - contentLeft(width, height));
    }

    /** 标签列宽：占行宽的 2/5，夹在 76~148 之间；超紧凑时整行都给标签。 */
    public static int labelWidth(int width, int rowWidth) {
        if (isUltraCompact(width)) return Math.max(48, rowWidth - 16);
        return Math.max(76, Math.min(148, rowWidth * 2 / 5));
    }

    /** 控件宽：夹在 72~260 之间，且不超过标签右侧的剩余空间。 */
    public static int controlWidth(int width, int height, int rowWidth) {
        if (isUltraCompact(width)) return Math.max(64, rowWidth - 12);
        int label = labelWidth(width, rowWidth);
        int available = rowWidth - label - 18;
        return Math.max(72, Math.min(isCompact(width, height) ? 190 : 260, available));
    }

    /** 控件右对齐到行尾。 */
    public static int controlLeft(int width, int height, int left, int rowWidth) {
        if (isUltraCompact(width)) return left + 6;
        return left + rowWidth - controlWidth(width, height, rowWidth) - 6;
    }

    /** 侧栏每项占高：分类多时自动压缩，保证全部塞得下。 */
    public static int sidebarItemStep(int height, int categoryCount) {
        if (categoryCount <= 0) return 22;
        int available = Math.max(1, contentBottom(height) - SIDEBAR_TOP);
        return Math.max(14, Math.min(22, available / categoryCount));
    }

    public static int sidebarButtonHeight(int height, int categoryCount) {
        return Math.max(13, Math.min(19, sidebarItemStep(height, categoryCount) - 2));
    }

    public static int sidebarViewport(int height) {
        return Math.max(1, contentBottom(height) - SIDEBAR_TOP);
    }

    /**
     * 侧栏可视高度内能否放下全部分类。
     *
     * <p>放不下时侧栏要显示自己的滚动条——否则玩家不知道下面还有分类，
     * 会以为配置项被吞了。
     */
    public static boolean sidebarNeedsScroll(int height, int categoryCount) {
        if (categoryCount <= 0) return false;
        return categoryCount * sidebarItemStep(height, categoryCount) > sidebarViewport(height);
    }
}
