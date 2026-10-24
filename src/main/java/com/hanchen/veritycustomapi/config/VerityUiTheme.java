package com.hanchen.veritycustomapi.config;

/**
 * 扩展界面统一配色。
 *
 * <p>色系：<b>冷石板灰蓝底 + 青蓝强调</b>（沿用此前的配色，未改色相）。
 *
 * <p><b>两条硬约束</b>（违反就会出视觉 bug）：
 * <ol>
 *   <li>相邻层的 RGB 差必须 ≥15。半透明卡片叠在世界上，差几个值就完全看不出边界
 *       （曾出现 CARD 与 CARD_RAISED 只差 6~8，分组头直接「消失」）。</li>
 *   <li>正文与卡片底色的对比度 ≥4.5:1。按最亮场景（雪地正午）算：
 *       白世界 → 30% 淡幕 → 叠卡片后约 #3D3B42，白字仍有 ~9:1。</li>
 * </ol>
 */
public final class VerityUiTheme {
    /** 全屏底色（主菜单等不透明场合用）。 */
    public static final int BACKGROUND = 0xFF0A0C10;
    /** 面板 / 卡片底色（不透明版）。 */
    public static final int SURFACE = 0xFF181C22;
    /** 侧边栏底色。 */
    public static final int SIDEBAR = 0xFF13161B;
    /** 面板描边。 */
    public static final int BORDER = 0xFF343B46;
    /** 列表分隔线（不透明，画在面板底色之上）。 */
    public static final int SEPARATOR = 0xFF2A3038;
    /** 行分隔线（半透明白，画在任意底色上都很轻）。 */
    public static final int ROW_DIVIDER = 0x1FFFFFFF;
    /** 正文文字。 */
    public static final int TEXT = 0xFFF4F6F8;
    /** 次要文字 / 说明。亮度算过：最亮场景下 4.80:1（原 #9BA5B4 只有 4.37，略偏暗）。 */
    public static final int MUTED = 0xFFA3ADBB;
    /** 主强调色：青蓝。 */
    public static final int ACCENT = 0xFF4FB8DE;
    /** 主强调色（半透明，用于底色块与分隔线）。 */
    public static final int ACCENT_SOFT = 0x664FB8DE;
    /** 分组标题：比 ACCENT 更亮，让分组头一眼能定位。 */
    public static final int SECTION_TITLE = 0xFF8FD8F0;
    /** 成功 / 通过。 */
    public static final int SUCCESS = 0xFF63D69A;
    /** 警告。 */
    public static final int WARNING = 0xFFF2B85C;
    /** 错误 / 危险。比其它状态色更亮——红在深底上的感知亮度天生偏低。 */
    public static final int DANGER = 0xFFF59AA2;

    // ------------------------------------------------------------------
    // 卡片（世界里打开界面时用）
    //
    // 🔴 底色**必须完全不透明**（2026-10-25 用户要求「全部弹窗统一成不透明样式」）。
    // 这里原来是 0xC4（77%），世界和背后的文字会透上来，看起来就是「窗口是透明的」。
    // 「世界透出来」这件事由背景那层 30% 淡幕（ScreenBackgrounds.SCRIM）负责，
    // 卡片本身不需要再半透明——两者叠加的结果就是「又糊又透」，文字可读性明显下降。
    // ------------------------------------------------------------------

    /** 卡片底色：完全不透明，和背景淡幕分工明确。 */
    public static final int CARD = 0xFF161A21;
    /** 抬升层：标题栏、分组头、选中项。比 CARD 亮一档，层次清楚。 */
    public static final int CARD_RAISED = 0xFF282F3A;
    /** 卡片描边：半透明白，勾出边缘让卡片从背景里浮出来。 */
    public static final int CARD_BORDER = 0x52FFFFFF;
    /** 卡片投影：贴在卡片下沿，强化浮起感。 */
    public static final int CARD_SHADOW = 0x55000000;
    /** 行悬停底色。叠在不透明卡片之上，半透明是正确的（否则看不见底下的行）。 */
    public static final int ROW_HOVER = 0x1FFFFFFF;

    // ------------------------------------------------------------------
    // 控件层：原版 Checkbox / Button 是浅色描边的方块，压在深色半透明卡片上
    // 非常突兀。这几个常量给自绘控件用，让它们和卡片是同一套语言。
    // ------------------------------------------------------------------

    /** 控件凹槽底色（开关未选中时的轨道）。 */
    public static final int CONTROL_TRACK = 0x5CFFFFFF;
    /** 输入框底色：比卡片更暗，让文字区沉下去。
     *  完全不透明——半透明时框里的文字会和背后的行文字叠在一起，读不清。 */
    public static final int CONTROL_FIELD = 0xFF0B0E13;
    /** 控件描边。 */
    public static final int CONTROL_BORDER = 0x38FFFFFF;
    /** 开关选中态 / 主按钮底色。 */
    public static final int CONTROL_ON = 0xFF3FA9D4;
    /** 开关滑块。 */
    public static final int CONTROL_KNOB = 0xFFF5F7F9;
    /** 次级按钮底色：只给一层很淡的底（12% 白），**且不描边**。
     *  24% 白 + 描边会在深色卡片上变成「灰盒子」，看起来像另一套软件的按钮。 */
    public static final int BUTTON = 0x1FFFFFFF;
    /** 次级按钮悬停。 */
    public static final int BUTTON_HOVER = 0x33FFFFFF;
    /** 危险按钮底色：淡红，比 DANGER 文字深，字才压得住。
     *  原来浅粉底配浅粉字，两者亮度接近，字几乎看不见。 */
    public static final int BUTTON_DANGER = 0x2AC0485A;

    private VerityUiTheme() {
    }
}
