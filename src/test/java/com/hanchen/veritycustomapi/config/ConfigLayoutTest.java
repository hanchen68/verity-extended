package com.hanchen.veritycustomapi.config;

/**
 * 配置界面几何计算的边界自检。
 *
 * <p>为什么值得单独测：布局代码在 3000 行的屏幕类里时，
 * 「某个分辨率下控件溢出 / 内容区压到侧栏 / 宽度算成负数」这类问题
 * 只能靠把游戏窗口拖到那个尺寸、肉眼去看。这几种尺寸以前都出过问题，
 * 现在用纯 JVM 把边界钉住。
 *
 * <p>扫的是**宽 × 高 的组合矩阵**，而不是几个手挑的尺寸——
 * 手挑的尺寸永远会漏掉「刚好卡在阈值上」的那一档（429/430、679/680、299/300 这些）。
 */
public final class ConfigLayoutTest {

    private ConfigLayoutTest() {
    }

    public static void main(String[] args) throws Exception {
        noNegativeWidths();
        controlsStayInsideContentArea();
        contentDoesNotOverlapSidebar();
        thresholdsBehaveAsDocumented();
        sidebarScrollsWhenCategoriesOverflow();
        hintTooltipIsForcedOpaque();
        popupSurfacesAreOpaque();
        personaSettingsAreTogether();

        System.out.println("ConfigLayout checks passed");
    }

    /**
     * 说明浮层必须**画出来是不透明的**。
     *
     * <p>底色写 {@code 0xFF0E1116}（alpha 全满）并不足以保证不透明：
     * 最终 alpha = 顶点色 alpha × ColorModulator（shader color）的 alpha，
     * 上游任何一处把 shader color 调低过、或异常路径没复原，
     * 浮层就会和背后的正文混在一起。检查代码只会看到不透明色，怎么查都查不出来。
     *
     * <p>所以这条守的是**做法**而不是颜色值：
     * <ol>
     *   <li>画底色前先把 shader color 归位；</li>
     *   <li>底色要在 {@code disableBlend()} 区间内绘制，**并且在同一区间内 flush**
     *       ——{@code GuiGraphics.fill} 是攒批的，真正光栅化发生在 flush 那一刻，
     *       只把 fill 包进去、让它到混合开启时才提交，等于没改。</li>
     * </ol>
     */
    /**
     * 所有「承载文字的面」必须完全不透明。
     *
     * <p>2026-10-25 用户报「基本上所有菜单都有透明的问题」：根因是
     * {@code VerityUiTheme.CARD = 0xC4...}（77%）——所有界面的卡片都半透明，
     * 背后的世界和文字直接透上来。一个常量决定了整个模组所有窗口的观感，
     * 所以把它钉在这里：只要有人把 alpha 改回非 FF，自检立刻红。
     *
     * <p>允许半透明的只有「叠在不透明面之上的装饰」：描边、投影、行悬停、
     * 控件轨道——它们下面是实心的，透出来是层次而不是杂乱。
     */
    private static void popupSurfacesAreOpaque() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/hanchen/veritycustomapi/config/VerityUiTheme.java"),
                java.nio.charset.StandardCharsets.UTF_8);

        for (String name : new String[]{"CARD", "CARD_RAISED", "CONTROL_FIELD",
                "SURFACE", "BACKGROUND", "SIDEBAR"}) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("int " + name + "\\s*=\\s*0x([0-9A-Fa-f]{8})")
                    .matcher(src);
            require(m.find(), "找不到主题色 " + name);
            String alpha = m.group(1).substring(0, 2);
            require("FF".equalsIgnoreCase(alpha),
                    name + " 的 alpha 是 0x" + alpha + "，不是全不透明——"
                            + "卡片会透出背后的内容，看起来就是「窗口是透明的」");
        }
    }

    /**
     * 人设相关的设置必须都在「对话与记忆 → 人设」这一处。
     *
     * <p>2026-10-25 玩家反馈「自定义人设根本不能正常加载」：`人设预设` 在对话与记忆页，
     * 而 `自定义人设（覆盖预设）` 输入框被放在**高级与诊断**页——玩家在预设旁边根本找不到它，
     * 只会改预设，自然觉得自定义人设不生效。
     *
     * <p>同时「原版人设」这个预设已从下拉里移除：它的语义是「什么都不注入」，
     * 和「启用自定义人设」开关正好互相抵消，留着只会让人以为开关坏了。
     */
    private static void personaSettingsAreTogether() throws Exception {
        String screen = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/hanchen/veritycustomapi/config/NativeOllamaProxyConfigScreen.java"),
                java.nio.charset.StandardCharsets.UTF_8);

        int chat = screen.indexOf("private void aiChatCategory()");
        int advanced = screen.indexOf("private void advancedCategory()");
        require(chat > 0 && advanced > 0, "找不到 aiChatCategory / advancedCategory");

        String chatBody = screen.substring(chat, Math.min(screen.length(), chat + 12000));
        require(chatBody.contains("自定义人设（覆盖预设）"),
                "「自定义人设（覆盖预设）」不在对话与记忆页的人设小节里——玩家找不到它");

        String advancedBody = screen.substring(advanced, Math.min(screen.length(), advanced + 6000));
        require(!advancedBody.contains("自定义人设（覆盖预设）"),
                "「自定义人设（覆盖预设）」还留在高级与诊断页，会和人设预设分开");

        // 「原版人设」不再出现在可选列表里
        String display = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/hanchen/veritycustomapi/config/ConfigDisplay.java"),
                java.nio.charset.StandardCharsets.UTF_8);
        require(display.contains("ORIGINAL_VERITY"),
                "ConfigDisplay 里没有过滤掉 ORIGINAL_VERITY");
        require(display.contains("selectablePresets"),
                "缺少 selectablePresets()：下拉会把「原版人设」一起列出来");
        require(screen.contains("migrateOriginalPreset"),
                "缺少老配置迁移：存着 ORIGINAL_VERITY 的玩家会看到错位的预设显示");

        // 输入框草稿：重建后不能丢掉玩家刚打的字
        require(screen.contains("textDrafts"),
                "文本输入没有草稿缓存：界面一重建（点开关/滚出视野）玩家刚打的字就没了");
        require(screen.contains("setResponder"),
                "输入框没有 responder，草稿缓存不会随输入更新");

        // 示例人设文件：选到「外部自定义文件」时要自动生成，别让玩家面对一个不存在的路径
        String manager = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/hanchen/veritycustomapi/ConfigManager.java"),
                java.nio.charset.StandardCharsets.UTF_8);
        require(manager.contains("ensureCustomPromptFile"),
                "ConfigManager 没有「确保人设文件存在」的方法");
        require(manager.contains("CUSTOM_PROMPT_TEMPLATE"),
                "没有示例人设模板内容——生成出来的文件会是空的");
        require(screen.contains("ensureCustomPromptFile"),
                "配置界面没有接上示例文件生成：玩家选到「外部自定义文件」仍然面对空路径");
    }

    private static void hintTooltipIsForcedOpaque() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/hanchen/veritycustomapi/config/NativeOllamaProxyConfigScreen.java"),
                java.nio.charset.StandardCharsets.UTF_8);

        int start = src.indexOf("private void drawHintTooltip");
        require(start > 0, "找不到 drawHintTooltip");
        int end = src.indexOf("@Override", start);
        String body = end > start ? src.substring(start, end) : src.substring(start);

        require(body.contains("RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F)"),
                "浮层绘制前没有把 shader color 归位，可能被上游残留的 alpha 拖成半透明");
        require(body.contains("RenderSystem.disableBlend()"),
                "浮层底色没有关闭混合绘制，无法保证不透明");
        require(body.contains("0xFF0E1116"), "浮层底色不是全不透明色");

        // disableBlend 与 enableBlend 之间必须同时包含底色填充和 flush
        int off = body.indexOf("RenderSystem.disableBlend()");
        int on = body.indexOf("RenderSystem.enableBlend()", off);
        require(off > 0 && on > off, "disableBlend / enableBlend 没有成对出现");
        String guarded = body.substring(off, on);
        // 🔴 底色必须用 fillOpaqueNow（立即提交 + 关闭混合），不能只用 graphics.fill。
        // graphics.fill 只把顶点塞进 RenderType.gui() 的缓冲，而那个 RenderType 自带
        // TRANSLUCENT_TRANSPARENCY —— flush 时混合会被重新打开，
        // 「顶点 alpha = FF」不等于「最终像素不透明」，实测确实会透出背后的行文字。
        require(guarded.contains("VerityUiDraw.fillOpaqueNow("),
                "浮层底色没有用立即提交的不透明绘制，仍会受混合状态影响");
        require(guarded.contains("flush()"),
                "关闭混合的区间里没有 flush——fill 是攒批的，不在这里提交就等于没关混合");
        require(body.contains("RenderSystem.defaultBlendFunc()"),
                "浮层绘制后没有复原混合函数，可能被某个页面的控件改坏");

        // 🔴 浮层必须由 ScreenEvent.Render.Post 触发，不能在界面 render() 里画。
        // Forge 的 drawScreenInternal 是 Pre → renderWithTooltip → Post，
        // Post 才是真正的最后一层；放 render() 末尾实测会被行文字盖住。
        String handler = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/hanchen/veritycustomapi/client/ClientEventHandler.java"),
                java.nio.charset.StandardCharsets.UTF_8);
        require(handler.contains("ScreenEvent.Render.Post"),
                "没有订阅 ScreenEvent.Render.Post，浮层无法保证画在所有内容之上");
        require(handler.contains("renderHintOverlay"),
                "Render.Post 里没有调用 renderHintOverlay");
        // 界面 render() 里不能再直接调 drawHintTooltip（否则会画两遍、且被盖住）
        String screen = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/hanchen/veritycustomapi/config/NativeOllamaProxyConfigScreen.java"),
                java.nio.charset.StandardCharsets.UTF_8);
        int renderMethod = screen.indexOf("public void render(GuiGraphics graphics");
        int renderEnd = screen.indexOf("public void renderHintOverlay", renderMethod);
        String renderBody = renderEnd > renderMethod
                ? screen.substring(renderMethod, renderEnd) : "";
        require(!renderBody.contains("drawHintTooltip(graphics, mouseX, mouseY)"),
                "界面 render() 里还在直接画浮层——会被后续批次盖住，应该交给 Render.Post");
    }

    /** 所有宽度都必须非负：{@code GuiGraphics.fill} 拿到 left>right 会抛异常。 */
    private static void noNegativeWidths() {
        for (int w = 200; w <= 2600; w += 7) {
            for (int h = 150; h <= 1500; h += 13) {
                int contentWidth = ConfigLayout.contentWidth(w, h);
                if (contentWidth < 0) {
                    throw new AssertionError("contentWidth 为负: w=" + w + ", h=" + h + ", got=" + contentWidth);
                }
                int rowWidth = contentWidth;
                if (ConfigLayout.labelWidth(w, rowWidth) < 0) {
                    throw new AssertionError("labelWidth 为负: w=" + w + ", h=" + h);
                }
                if (ConfigLayout.controlWidth(w, h, rowWidth) < 0) {
                    throw new AssertionError("controlWidth 为负: w=" + w + ", h=" + h);
                }
            }
        }
    }

    /**
     * 控件不能越出内容区右边界。
     *
     * <p>这条以前真的破过：窄窗口下控件宽按固定 260 算，直接冲到卡片外面，
     * 视觉上像是「输入框跑到侧栏上了」。
     */
    private static void controlsStayInsideContentArea() {
        for (int w = 220; w <= 2600; w += 11) {
            for (int h = 200; h <= 1400; h += 17) {
                int contentLeft = ConfigLayout.contentLeft(w, h);
                int contentWidth = ConfigLayout.contentWidth(w, h);
                int controlLeft = ConfigLayout.controlLeft(w, h, contentLeft, contentWidth);
                int controlWidth = ConfigLayout.controlWidth(w, h, contentWidth);
                int right = controlLeft + controlWidth;
                int limit = contentLeft + contentWidth;
                // 允许 8px 容差：控件右侧本来就要留一点内边距
                if (right > limit + 8) {
                    throw new AssertionError("控件越出内容区: w=" + w + ", h=" + h
                            + ", controlRight=" + right + ", contentRight=" + limit);
                }
                if (controlLeft < contentLeft - 8) {
                    throw new AssertionError("控件越出内容区左侧: w=" + w + ", h=" + h
                            + ", controlLeft=" + controlLeft + ", contentLeft=" + contentLeft);
                }
            }
        }
    }

    /** 内容区左边界不能压到侧栏右边界上。 */
    private static void contentDoesNotOverlapSidebar() {
        for (int w = 220; w <= 2600; w += 11) {
            for (int h = 200; h <= 1400; h += 17) {
                int sidebarRight = ConfigLayout.sidebarLeft(w, h) + ConfigLayout.sidebarWidth(w, h);
                int contentLeft = ConfigLayout.contentLeft(w, h);
                if (contentLeft < sidebarRight) {
                    throw new AssertionError("内容区压到侧栏: w=" + w + ", h=" + h
                            + ", contentLeft=" + contentLeft + ", sidebarRight=" + sidebarRight);
                }
            }
        }
    }

    /** 阈值两侧的行为要明确：正好等于阈值时不算紧凑。 */
    private static void thresholdsBehaveAsDocumented() {
        require(!ConfigLayout.isCompact(680, 400), "宽 680 高 400 不应算紧凑");
        require(ConfigLayout.isCompact(679, 400), "宽 679 应算紧凑");
        require(ConfigLayout.isCompact(680, 399), "高 399 应算紧凑");
        require(!ConfigLayout.isUltraCompact(430), "宽 430 不应算超紧凑");
        require(ConfigLayout.isUltraCompact(429), "宽 429 应算超紧凑");
        require(!ConfigLayout.usesPagedNavigation(300, 220), "300x220 不应翻页导航");
        require(ConfigLayout.usesPagedNavigation(299, 220), "宽 299 应翻页导航");
        require(ConfigLayout.usesPagedNavigation(300, 219), "高 219 应翻页导航");
        // 翻页导航时没有侧栏
        require(ConfigLayout.sidebarWidth(280, 219) == 0, "翻页导航时侧栏宽度必须为 0");
    }

    /** 分类多到放不下时，侧栏必须允许滚动，否则玩家看不到后面的分类。 */
    private static void sidebarScrollsWhenCategoriesOverflow() {
        require(!ConfigLayout.sidebarNeedsScroll(600, 3), "3 个分类不该需要滚动");
        require(ConfigLayout.sidebarNeedsScroll(240, 14), "14 个分类在矮窗口里应需要滚动");
        require(!ConfigLayout.sidebarNeedsScroll(600, 0), "0 个分类不需要滚动");
        // 极端：1 个分类但窗口极矮，仍要给出合法数值
        int step = ConfigLayout.sidebarItemStep(150, 1);
        require(step >= 14, "每项高度下限 14，实际 " + step);
        int buttonHeight = ConfigLayout.sidebarButtonHeight(150, 1);
        require(buttonHeight >= 13, "按钮高度下限 13，实际 " + buttonHeight);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
