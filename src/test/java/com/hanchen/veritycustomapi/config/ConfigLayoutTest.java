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
        cloudAuthPrefersAccountConnection();
        simplifiedNavigationAndRealPairing();
        cloudDisconnectKeepsLoreHonest();
        serviceEditorSupportsBuiltinAndSetupBeforeTest();

        java.util.Set<Integer> pages=new java.util.HashSet<>();
        require(ConfigNavigation.count()==6,"一级导航应精简为六组");
        for(int group=0;group<ConfigNavigation.count();group++) {
            int[] leaves=ConfigNavigation.pages(group);
            for(int i=0;i<leaves.length;i++) {
                require(pages.add(leaves[i]),"页面不可在不同入口重复出现");
                require(ConfigNavigation.group(leaves[i])==group,"页面归属错误");
                require(!ConfigNavigation.tab(group,i).isBlank(),"二级页签必须有名称");
            }
        }
        require(pages.size()==15,"原有十五个功能页面必须全部可达");
        require(ConfigNavigation.collapsedByDefault("语音朗读高级"),"高级参数应默认收起");
        require(!ConfigNavigation.collapsedByDefault("人设"),"常用设置不应默认隐藏");
        String menu=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/hanchen/veritycustomapi/config/NativeOllamaProxyConfigScreen.java"));
        for(String cmd:new String[]{"status","on","off","stop","resume","hold"})
            require(menu.contains("verity-autonomy "+cmd),"已有自主命令缺菜单入口："+cmd);
        require(menu.contains("ModConfig.SERVER_VERITY_BALL_RIDE::get"),"缺骑乘开关");
        require(menu.contains("ModConfig.SERVER_VERITY_HEAD_PERCH::get"),"缺站头开关");
        require(!menu.contains("sendExistingCommand(\"verity-autonomy selftest\")"),"破坏性自测不得变成无确认的普通菜单按钮");
        String background=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/hanchen/veritycustomapi/client/ScreenBackgrounds.java"));
        require(background.contains("SCRIM = 0xC8101010"),"世界内窗口四周应使用半透明暗幕保留世界画面");
        require(background.contains("fillOpaqueNow"),"主菜单没有世界时应使用全屏不透明背景");
        require(background.contains("fullScreenWindow"),"背景须按是否已载入世界区分");
        require(!background.contains("screen.renderBackground(graphics)"),"不得回退到原版背景或全景图");
        for(int w:new int[]{320,427,640,960,1280})for(int h:new int[]{240,360,540,720}) {
            int m=VerityWindowLayout.margin(w,h);
            require(m>=10&&m<=34,"窗口边距越界");
            int l=VerityWindowLayout.panelLeft(w,h,900),r=VerityWindowLayout.panelRight(w,h,900);
            require(l>=m-1&&r<=w-m+1,"窗口未在屏幕内居中留边");
            require(VerityWindowLayout.panelTop(h)>=6&&VerityWindowLayout.panelBottom(h)<=h-6,"上下边距不足");
        }
        require(VerityWindowLayout.compactLeft(640,360,900)>VerityWindowLayout.panelLeft(640,360,900),"次级弹窗应比主窗口更窄");
        for(int h:new int[]{240,300,360,480,720}) {
            int panelBottom=VerityWindowLayout.panelBottom(h);
            require(ConfigLayout.contentBottom(h)<=panelBottom-40,"内容区必须给底部按钮留空间");
            require(ConfigLayout.contentBottom(h)>ConfigLayout.contentTop(),"内容区不得被压成负高度");
            require(panelBottom-26+20<=panelBottom,"底部按钮不得越出面板");
        }
        for(int w:new int[]{240,320,427,640,960})for(int h:new int[]{180,240,360,540})
            for(boolean portrait:new boolean[]{false,true})for(int lines=1;lines<=4;lines++) {
                var box=com.hanchen.veritycustomapi.client.HudLayout.dialogue(w,h,portrait,lines);
                require(box.x()>=0 && box.y()>=0 && box.x()+box.width()<=w,"对话HUD横向越界");
                require(box.y()+box.height()<=h-50,"对话HUD侵入快捷栏区域");
                require(com.hanchen.veritycustomapi.client.HudLayout.navigationY(h)<=h-80,"导航侵入底部状态条");
            }
        for(int w:new int[]{240,320,427,640,960})for(boolean quota:new boolean[]{false,true})for(boolean size:new boolean[]{false,true}) {
            var badge=com.hanchen.veritycustomapi.client.HudLayout.status(w,180,quota,size);
            require(badge.x()+badge.width()<=w/2,"状态角标不应占过半屏");
            require(badge.height()==38+(quota?13:0)+(size?13:0),"状态角标空行未收起");
        }
        for(int r:new int[]{36,66,94}) {
            require(com.hanchen.veritycustomapi.client.QuickWheelLayout.sector(0,0,r)==-1,"轮盘中心不可误触行动");
            require(com.hanchen.veritycustomapi.client.QuickWheelLayout.sector(r+1,0,r)==-1,"轮盘外不可触发行动");
            for(int i=0;i<6;i++) {
                double angle=-Math.PI/2+i*Math.PI/3;
                require(com.hanchen.veritycustomapi.client.QuickWheelLayout.sector(Math.cos(angle)*r*.7,Math.sin(angle)*r*.7,r)==i,"轮盘扇区映射错误");
            }
            for(var strip:com.hanchen.veritycustomapi.client.QuickWheelLayout.spans(r))require(strip.width()>0&&strip.sector()>=0&&strip.sector()<6,"轮盘绘制跨度错误");
        }
        for(int i=0;i<6;i++) {
            double angle=-Math.PI/2+i*Math.PI/3;
            require(com.hanchen.veritycustomapi.client.QuickWheelLayout.direction(Math.cos(angle)*400,Math.sin(angle)*400,94)==i,"指向外圈仍应选中对应方向");
        }
        require(com.hanchen.veritycustomapi.client.QuickWheelLayout.direction(0,0,94)==-1,"松开中心不应执行");
        String wheel=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/hanchen/veritycustomapi/client/HoldActionWheelScreen.java"));
        require(wheel.contains("physicallyDown()")&&wheel.contains("keyReleased")&&wheel.contains("if(fired)return"),"按住轮盘必须检测物理释放并且只执行一次");
        require(wheel.contains("isWindowActive()")&&wheel.contains("progress(openedAt,now,150)"),"失焦应取消，展开应使用时间动画");
        String quick=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/hanchen/veritycustomapi/client/QuickCommandScreen.java"));
        require(!quick.contains("ScreenBackgrounds.draw"),"快捷轮盘不应使用全屏遮罩");
        require(quick.contains("VerityConfirmScreen"),"危险操作必须确认");
        String hud=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/hanchen/veritycustomapi/client/VerityHudOverlay.java"));
        require(!hud.contains("好感成长 +"),"体型变化不应误标成好感");
        require(hud.contains("drawBadgeMetric")&&hud.contains("boolean showSize"),"状态指标须对齐并按需显示体型");
        require(VerityUiAnimation.progress(100,100,150)==0.0D,"动画起点异常");
        require(VerityUiAnimation.progress(100,250,150)==1.0D,"动画应按时间结束");
        require(VerityUiAnimation.progress(100,90,150)==0.0D,"时钟倒退不应越界");
        require(VerityUiAnimation.progress(100,250,0)==1.0D,"零时长须即时完成");
        require(VerityUiAnimation.frameDelta(100,200)<=50,"掉帧不得产生无限步进");
        String guideTrigger=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/hanchen/veritycustomapi/client/FirstRunGuide.java"));
        require(guideTrigger.contains("mc.screen instanceof TitleScreen") && guideTrigger.contains("mc.level!=null"),"引导仅在首次主菜单触发，不在进入存档触发");
        require(guideTrigger.contains("SettingsExperience.mode()") && java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/hanchen/veritycustomapi/config/SettingsExperience.java")).contains("StandardCopyOption.ATOMIC_MOVE"),"首次菜单选择须持久记录，不能依赖旧引导标记");
        String guide=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/hanchen/veritycustomapi/client/VerityWelcomeScreen.java"));
        require(guide.contains("稍后设置") && guide.contains("FirstRunGuide.complete()"),"引导应允许跳过并记住选择");
        require(!guide.contains("sendCommand(") && !guide.contains("sendChat("),"引导不能自动触发付费请求或世界改动");
        String mainMenu=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/hanchen/veritycustomapi/config/NativeOllamaProxyConfigScreen.java"));
        for(String jargon:new String[]{"\"云端下发\"","\"配置项\"","模型自主决策","将更新云端配置","把背包物品统计写进提示词"})
            require(!mainMenu.contains(jargon),"菜单仍有内部术语："+jargon);
        String serviceCopy=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/hanchen/veritycustomapi/config/PlayerServiceConfigScreen.java"));
        require(!serviceCopy.contains("草稿") && !serviceCopy.contains("上游"),"自定义服务页仍有内部术语");
        // 自主开关：必须显示真实状态，不能退化回「开/关两个盲按钮」。
        require(mainMenu.contains("currentAutonomy()") && mainMenu.contains("toggleAutonomy("),
                "自主菜单须按真实状态渲染并单键切换");
        require(!mainMenu.contains("action(\"开启自主\"") || mainMenu.contains("!autonomy.enabled()"),
                "开启按钮必须受状态控制，不能常驻显示");
        require(mainMenu.contains("ClientAutonomyState.get("),
                "菜单状态须取服务端下发的快照");
        require(mainMenu.contains("tickAutonomySync()") && mainMenu.contains("lastAutonomySignature"),
                "状态变化须触发刷新，且不能每 tick 重建");
        require(mainMenu.contains("开关会记住"),"须说明开关会随存档保留");
        String net=java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/hanchen/veritycustomapi/network/AutonomyNetwork.java"));
        require(net.contains("owns(player, verity)"),"状态只应下发给同伴主人");
        require(net.contains("PLAY_TO_CLIENT") && net.contains("PLAY_TO_SERVER"),"通道须双向注册");
        String logout=java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/hanchen/veritycustomapi/client/ClientEventHandler.java"));
        require(logout.contains("ClientAutonomyState.clear()"),"退出世界须清理自主状态缓存");
        // 内置语音开关：云端接管时读的是云端值，界面不能再显示/写本机 TOML。
        require(mainMenu.contains("builtinSttManagedByCloud()") && mainMenu.contains("builtinTtsManagedByCloud()"),
                "内置语音开关须判断是否由云端接管");
        require(!mainMenu.contains("else if (ModConfig.ENABLE_BUILTIN_TTS_APIS.get())"),
                "内置朗读分组仍按本机值判断，云端接管时会显示错误状态");
        require(mainMenu.contains("ConfigManager.isEnableBuiltinSttApis()"),
                "识别分组须按真实生效值判断");
        String cfg=java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/hanchen/veritycustomapi/ConfigManager.java"));
        require(cfg.contains("public static BuiltinApiConfig getCloudBuiltinApi()"),
                "界面需要只读访问云端内置配置");
        // 摄像头：采集失败必须能说出原因，且脚本不能裸奔（选中掉线设备会直接抛异常）。
        String camera=java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/hanchen/veritycustomapi/client/CameraCaptureService.java"));
        require(camera.contains("Write-Output ('ERR '"),"采集脚本必须捕获初始化异常并回报原因");
        require(camera.contains("IsEnabled"),"自动选设备必须跳过当前未连接的设备");
        require(camera.contains("scriptError(") && camera.contains("explain("),"原始脚本错误须转成可读提示");
        require(camera.contains("画面采集测试失败"),"采集失败必须写日志，否则无从排查");
        require(mainMenu.contains("warning(\"上次测试结果\""),"测试结果必须常驻显示，不能只放进悬停提示");
        // 旧的自定义 API 输入必须折叠，且在云端接管时标明为本机备用。
        require(mainMenu.contains("\"本机备用对话服务（云端已接管）\""),"旧对话输入未标记为本机备用");
        require(mainMenu.contains("\"本机备用语音识别（云端已接管）\""),"旧识别输入未标记为本机备用");
        require(mainMenu.contains("\"本机备用语音朗读（云端已接管）\""),"旧朗读输入未标记为本机备用");
        require(!mainMenu.contains("section(\"自定义对话服务\")"),"旧对话输入仍是常驻分组");
        require(!mainMenu.contains("section(\"自定义在线识别\")"),"旧识别输入仍是常驻分组");
        require(mainMenu.contains("ConfigManager.getCloudApiKey().isBlank()"),"折叠未按云端是否接管区分");
        for (int w : new int[]{240,320,427,640,960,1920}) for (int h : new int[]{180,240,300,360,720}) {
            for (boolean title : new boolean[]{false,true}) {
                HealthCheckLayout check=HealthCheckLayout.of(w,h,title);
                require(check.left()>=0 && check.right()<=w && check.top()>=0 && check.bottom()<=h,"检查窗口越界");
                require(check.footerY()+20<=check.bottom(),"检查按钮越出窗口");
                require(check.detailTop()<=check.detailBottom() && check.detailBottom()<check.footerY(),"检查详情压到按钮");
                require(check.rowTop()+check.rowHeight()*3<check.detailBottom(),"三项检查挤掉详情区");
                if(title) require(check.left()==0 && check.right()==w && check.top()==0,"主菜单检查应铺满窗口");
            }
        }
        String health=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/com/hanchen/veritycustomapi/config/ConfigHealthCheckScreen.java"));
        require(!health.contains("autoCloseTicks"),"结果不可自动关闭");
        require(!health.contains("基础检查") && !health.contains("testSelected("),"不得保留只查连接的基础检查");
        require(health.contains("全部测试") && health.contains("startAll"),"须一键依次真实测试三项");
        require(health.contains("queue") && health.contains("next(run") ,"三项须顺序执行");
        require(health.contains("LiveServiceTest.runChat") && health.contains("LiveServiceTest.runTts")
                && health.contains("LiveServiceTest.runStt"),"三项服务都应发起真实测试");
        require(health.contains("VerityConfirmScreen") && health.contains("DISCLAIMER"),"真实测试须先确认额度消耗");
        require(health.contains("cancel()") && health.contains("removed()"),"离屏须中断测试，避免继续消耗额度");
        require(health.contains("minecraft.screen != this") && health.contains("run != generation"),"旧回调不可更新关闭的页面");
        // Auto-run after authorizing/saving is the whole point of "real test every time".
        require(health.contains("boolean autoStart"),"检测页须支持保存后自动开测");
        require(health.contains("confirmExport") && health.contains("supplyAsync"),"诊断导出须确认并异步执行");
        int saveStart=mainMenu.indexOf("private void saveAndClose()");
        int saveEnd=mainMenu.indexOf("// ==================== Render",saveStart);
        require(saveStart>=0 && saveEnd>saveStart,"无法找到保存流程");
        require(!mainMenu.substring(saveStart,saveEnd).contains("new ConfigHealthCheckScreen"),"保存设置不应强制发起连接检查");
        require(mainMenu.contains("action(\"服务真实测试\""),"必须保留明确的主动检测入口");
        nativeEnhancedTheme();
        System.out.println("ConfigLayout and native-enhanced UI checks passed");
    }

    private static void nativeEnhancedTheme() throws Exception {
        var base=java.nio.file.Path.of("src/main/java/com/hanchen/veritycustomapi");
        String widgets=java.nio.file.Files.readString(base.resolve("config/VerityUiWidgets.java"));
        require(widgets.contains("super.renderWidget(g,mouseX,mouseY,partialTick)"),"普通按钮必须用原版贴图与焦点状态");
        require(!widgets.contains("int lift"),"按钮文字不应在悬停时上浮");
        require(widgets.contains("box.setBordered(true)"),"输入框应保留原版边框与点击坐标");
        String main=java.nio.file.Files.readString(base.resolve("config/NativeOllamaProxyConfigScreen.java"));
        require(main.contains("int enterOffset = 0"),"入场不应移动绘制而保留旧点击框");
        require(main.contains("return VerityUiWidgets.roundedButton"),"配置页须复用统一控件");
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

    /**
     * 云端授权：两条路并存，账户页必须让老用户找得到熟悉的配对码。
     *
     * <p>🔴 决定变更（2026-11-08）：上一版把配对码彻底移出账户页，理由是「新玩家会以为必须先搞六位码」。
     * 实际反馈正好相反——**老用户找不到这条路，以为功能没了**，于是「很多人不会用云端」。
     * 现在账户页同时给出两种方式并写清区别：账户连接更省事，配对码适合「先在网页全配好」的老流程。
     * 高级诊断里的完整旧入口保留不动。
     */
    private static void cloudAuthPrefersAccountConnection() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/hanchen/veritycustomapi/config/NativeOllamaProxyConfigScreen.java"));

        // 账户页必须用整行大按钮放主入口
        String cloud = methodBody(src, "private void cloudCategory()");
        require(cloud.contains("primaryAction("), "账户页必须用整行大按钮承载「连接账户」");
        require(cloud.contains("\"连接账户\""), "账户页缺少「连接账户」主按钮文案");

        // 老流程必须能在账户页直接找到，而不是只有折叠区里才有
        require(cloud.contains("配对码"), "账户页必须提配对码：老用户找不到这条路就等于功能消失");
        require(cloud.contains("老方式"), "账户页要说明这是老方式，避免新玩家以为必须先拿码");
        require(cloud.contains("InGameCloudSetupScreen"), "账户页的配对码入口要能真的进到填码界面");

        // 旧方式仍完整可用，且高级诊断里也保留一份
        String legacy = methodBody(src, "private void legacyPairingSection()");
        require(legacy.contains("exchangePairingCode"), "旧配对码兑换逻辑被删了，老玩家将无法授权");
        require(legacy.contains("提交配对码"), "旧配对码提交按钮被删了，折叠区会变成空壳");
        require(methodBody(src, "private void systemCategory()").contains("legacyPairingSection()"),
                "旧配对码必须挂在「高级诊断」页");
        // 账户页不得直接暴露兑换实现，避免两个入口各自维护一套逻辑
        require(!cloud.contains("exchangePairingCode"), "账户页应跳到填码界面，不重复实现兑换逻辑");

        // 新方式必须能独立完成授权（设备码流程）
        String setup = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/hanchen/veritycustomapi/config/InGameCloudSetupScreen.java"));
        require(setup.contains("requestDeviceCode"), "新授权方式缺少申请设备码步骤");
        require(setup.contains("pollDeviceToken"), "新授权方式缺少轮询换取凭据步骤");
        require(setup.contains("saveAuthorization"), "新授权方式没有保存凭据，等于授权不生效");
    }

    /** 取一个方法的源码体（按大括号配平，够用即可）。 */
    private static String methodBody(String src, String signature) {
        int start = src.indexOf(signature);
        if (start < 0) return "";
        int brace = src.indexOf('{', start);
        if (brace < 0) return "";
        int depth = 0;
        for (int i = brace; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return src.substring(brace, i + 1);
            }
        }
        return src.substring(brace);
    }

    /** Builtin selections belong in the source editor; authorization is not a service test. */
    private static void serviceEditorSupportsBuiltinAndSetupBeforeTest() throws Exception {
        String base="src/main/java/com/hanchen/veritycustomapi/config/";
        String editor=java.nio.file.Files.readString(java.nio.file.Path.of(base+"PlayerServiceConfigScreen.java"));
        String connect=java.nio.file.Files.readString(java.nio.file.Path.of(base+"InGameCloudSetupScreen.java"));
        require(editor.contains("builtinControls(l,w)"),"选择内置来源后必须直接显示模型列表");
        require(editor.contains("api.catalog(key)")&&editor.contains("api.preferences(key)"),"必须读取真实可选模型及已保存选择");
        require(editor.contains("api.savePreferences(requestKey,expected,choices)"),"模型选择必须实际保存，不能只是界面切换");
        require(editor.contains("ModSetupClient.appliedVersion(saved,runtime)"),"保存模型后必须核对运行配置版本");
        require(editor.contains("PlayerConfigPatch.delivered(saved,runtime,body)"),"自定义配置仍须核对实际下发");
        require(editor.contains("voice_id")&&editor.contains("route_id"),"朗读模型与音色必须分别可选");
        require(editor.contains("showAdvanced")&&editor.contains("visibleFields()")&&editor.contains("更多参数"),"常用参数优先，低频参数可展开");
        require(editor.contains("bool(f.constraint(),\"required\")"),"服务端必填字段不得被高级折叠隐藏");
        require(editor.contains("drafts")&&editor.contains("allFields()")&&editor.contains("invalidFields.isEmpty()"),"切页与折叠必须保留输入并阻止无效保存");
        require(editor.contains("secretSet(f)")&&editor.contains("patch.secret(f.path(),v)"),"密钥保持只写，留空不覆盖");
        require(editor.contains("ConfigHealthCheckScreen(this,false)"),"连接测试必须由用户主动发起");
        require(!editor.contains("ConfigHealthCheckScreen(parent, true)"),"保存不能强制跳转自动测试");
        String tick=methodBody(connect,"@Override public void tick()");
        require(connect.contains("route=Route.CONNECTED")&&connect.contains("syncCloudConfigNow"),"授权成功应进入已连接状态并读取网页设置，不强迫重配");
        require(connect.contains("new CloudConfigFetcher().exchangeCode"),"配对码必须实际兑换，不能仅跳回浏览器登录");
        require(!tick.contains("ConfigHealthCheckScreen"),"首次授权不能在未配置服务时自动测试");
        // At supported compact sizes the last row must stay above status/footer controls.
        for(int height:new int[]{240,270,320,360,480,720})for(boolean builtin:new boolean[]{false,true}){
            int top=builtin?112:100,step=builtin?24:40;
            int rows=Math.max(1,(height-78-top)/step);
            require(top+(rows-1)*step+20<=height-78,"服务列表侵入底部操作区："+height);
        }
    }

    /**
     * 账户文案可以带「那家公司」的冷意，但取消授权必须真的成功。
     *
     * <p>原版设定里那家公司不提供账号注销，玩家怎么反抗都没用。把这种味道放进文案没问题，
     * 但一旦做成「点了取消却没取消」，就不是氛围而是坏功能——玩家会以为云端设置坏了。
     */
    private static void cloudDisconnectKeepsLoreHonest() throws Exception {
        String lore = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/hanchen/veritycustomapi/cloud/CloudServiceLore.java"));

        // 台词必须走统一说话接口，否则聊天栏只有灰字，Verity 不张嘴也不出声
        require(!lore.contains("VeritySay.to("), "真实账户操作不得触发剧情台词或付费朗读");
        require(!lore.contains("sendSystemMessage("), "不得自己拼前缀发消息");

        // 🔴 参数必须是 Player：断开发生在客户端界面里，手上只有 LocalPlayer；
        // 写成 ServerPlayer 会让台词永远不触发（编译期不报错，因为调用点传的是 Player）。
        require(lore.contains("public static void onDisconnected(Player player)"),
                "onDisconnected 必须接收 Player，写 ServerPlayer 会导致台词永不触发");
        require(!lore.contains("instanceof ServerPlayer"),
                "不得把 onDisconnected 限制成 ServerPlayer：客户端界面拿不到");

        // 文案要写清真实后果，不能靠模糊制造气氛
        require(lore.contains("其他设备不受影响"), "取消授权必须说明只影响本设备");
        require(lore.contains("不会删除账号")&&!lore.contains("不提供账号注销"), "真实平台不得伪装成剧情公司的限制条款");

        // 服务条款要出现在玩家真正会看的账户页，不能只藏在高级诊断的旧配对码区
        String menu = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/hanchen/veritycustomapi/config/NativeOllamaProxyConfigScreen.java"));
        require(methodBody(menu, "private void cloudCategory()").contains("CloudServiceLore.termsLine()"),
                "服务条款必须在账户主页面显示，放进折叠的旧配对码区等于没人看得到");

        // 三处断开入口都要接上这句台词
        for (String file : new String[]{
                "src/main/java/com/hanchen/veritycustomapi/config/PlayerServiceConfigScreen.java",
                "src/main/java/com/hanchen/veritycustomapi/config/NativeOllamaProxyConfigScreen.java"}) {
            String src = java.nio.file.Files.readString(java.nio.file.Path.of(file));
            require(src.contains("CloudServiceLore.onDisconnected("),
                    file + " 的断开流程没有接上 CloudServiceLore.onDisconnected");
            // 真的断开：清凭据 + 停云端，一个都不能少
            require(src.contains("clearAuthorization()") || src.contains("clearCredentials()"),
                    file + " 断开时必须真的清凭据");
        }
    }

    private static void simplifiedNavigationAndRealPairing() throws Exception {
        String base="src/main/java/com/hanchen/veritycustomapi/";
        String connect=java.nio.file.Files.readString(java.nio.file.Path.of(base+"config/InGameCloudSetupScreen.java"));
        String home=java.nio.file.Files.readString(java.nio.file.Path.of(base+"config/VeritySettingsHomeScreen.java"));
        String client=java.nio.file.Files.readString(java.nio.file.Path.of(base+"client/ClientEventHandler.java"));
        String editor=java.nio.file.Files.readString(java.nio.file.Path.of(base+"config/PlayerServiceConfigScreen.java"));
        require(home.contains("连接与云端服务")&&home.contains("全部高级设置"),"simple home must preserve advanced reachability");
        require(SettingsExperience.parse("SIMPLE\n")==SettingsExperience.Mode.SIMPLE,"simple preference roundtrip");
        require(SettingsExperience.parse("FULL")==SettingsExperience.Mode.FULL,"full preference roundtrip");
        require(SettingsExperience.parse("invalid")==SettingsExperience.Mode.UNSET,"invalid preference must ask again");
        String first=java.nio.file.Files.readString(java.nio.file.Path.of(base+"client/FirstRunGuide.java"));
        require(first.contains("new com.hanchen.veritycustomapi.config.SettingsExperienceScreen"),"first launch must ask experience choice");
        require(home.contains("切换新手 / 完整菜单"),"choice must remain reversible");
        // 切换场景必须跨过设置界面，否则返回会绕回旧菜单形成环。
        String picker=java.nio.file.Files.readString(java.nio.file.Path.of(base+"config/SettingsExperienceScreen.java"));
        require(picker.contains("hostForChosenMenu()")&&picker.contains("parentScreen()")&&picker.contains("parentScreenRef()"),
                "切换菜单后新界面必须以外层界面为父屏，不能指回旧设置界面");
        require(!picker.contains("new VeritySettingsHomeScreen(parent)\n")||picker.contains("hostForChosenMenu"),
                "选择后构造菜单不得直接复用设置界面的 parent");
        // 旧引导标记已不再决定是否弹窗，complete() 不得再把它当「已完成」写盘。
        require(!java.nio.file.Files.readString(java.nio.file.Path.of(base+"client/FirstRunGuide.java")).contains("resolve(\"verity_extended/onboarding-v1.done\")"),
                "旧引导标记已成死逻辑，不能再误导是否重弹");
        require(java.nio.file.Files.readString(java.nio.file.Path.of(base+"client/VerityWelcomeScreen.java"))
                        .contains("SettingsExperience.open(this)"),
                "欢迎页入口必须跟随已选菜单模式，不能写死简化首页");
        require(client.contains("com.hanchen.veritycustomapi.config.SettingsExperience.open("),"entry points must respect saved menu preference");
        require(connect.contains("new CloudConfigFetcher().exchangeCode")&&connect.contains("new CloudConfigFetcher().exchangeCode(address,code"),"pairing must execute real exchange");
        require("ABC123".equals(InGameCloudSetupScreen.normalizePairingCode("ＡＢＣ１２３ \u200B")),"pairing paste normalization");
        require(connect.contains("无需填回游戏")&&connect.contains("只选一种连接方式"),"two code types must be distinguished");
        require(connect.contains("reauthorizing")&&connect.contains("重新连接 / 更换账户"),"invalid saved credential must not trap user");
        require(!methodBody(connect,"private void authorized(").contains("new PlayerServiceConfigScreen"),"web-configured players must not be forced to configure again");
        require(!methodBody(connect,"private void sync()").contains("savePlayerConfig"),"sync must not create configurations");
        require(editor.contains("savedSelections")&&editor.contains("这个选项已经选中"),"unchanged selection must not create writes");
        for(int h:new int[]{180,240,360,720})require(50+56+20<=h-54,"connection controls must stay above status/footer");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
