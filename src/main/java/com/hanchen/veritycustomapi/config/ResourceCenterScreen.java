package com.hanchen.veritycustomapi.config;

import com.hanchen.veritycustomapi.util.Silent;

import net.minecraft.client.gui.components.Button;
import com.hanchen.veritycustomapi.config.VerityUiWidgets;
import com.hanchen.veritycustomapi.config.VerityUiDraw;
import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.cloud.CloudConfig;
import com.hanchen.veritycustomapi.cloud.CloudMmdProject;
import com.hanchen.veritycustomapi.cloud.CloudSkin;
import com.hanchen.veritycustomapi.resource.CloudResourceClient;
import com.hanchen.veritycustomapi.resource.MmdResourceManager;
import com.hanchen.veritycustomapi.resource.ResourceCacheIndex;
import com.hanchen.veritycustomapi.resource.ResourceException;
import com.hanchen.veritycustomapi.resource.ResourceUploadClient;
import com.hanchen.veritycustomapi.resource.SkinResourceManager;
import com.hanchen.veritycustomapi.resource.VoiceResourceManager;
import com.hanchen.veritycustomapi.skin.SkinManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

public final class ResourceCenterScreen extends Screen {
    private static final int BACKGROUND = VerityUiTheme.BACKGROUND;
    private static final int SURFACE = VerityUiTheme.SURFACE;
    private static final int SEPARATOR = VerityUiTheme.SEPARATOR;
    private static final int TEXT = VerityUiTheme.TEXT;
    private static final int MUTED = VerityUiTheme.MUTED;
    private static final int ACCENT = VerityUiTheme.ACCENT;
    private static final int SUCCESS = VerityUiTheme.SUCCESS;
    private static final int WARNING = VerityUiTheme.WARNING;
    private static final int DANGER = VerityUiTheme.DANGER;
    private static final int ITEM_HOVER = 0x332D3942;
    private static final int ITEM_HEIGHT = 58;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private final Screen parent;
    private final ExecutorService executor = Executors.newFixedThreadPool(3, task -> {
        Thread thread = new Thread(task, "Verity-ResourceCenter");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger generation = new AtomicInteger();
    private final List<Future<?>> tasks = new ArrayList<>();
    private final List<Button> itemButtons = new ArrayList<>();
    private final List<ResourceItem> items = new ArrayList<>();
    private Tab tab = Tab.SKINS;
    private Filter filter = Filter.ALL;
    private StatusType statusType = StatusType.INFO;
    private String status = "等待同步";
    private long lastSync;
    private long cacheBytes;
    private int scroll;
    private boolean loading;
    private boolean initialLoadRequested;
    private boolean closed;

    public ResourceCenterScreen(Screen parent) {
        super(Component.literal("Verity 资源中心"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        clearWidgets();
        itemButtons.clear();
        int left = panelLeft();
        int right = panelRight();
        int innerWidth = right - left - 24;
        boolean compact = compactHeader();
        int tabY = compact ? 66 : 54;
        int filterY = compact ? 92 : 82;
        int tabWidth = Math.min(120, Math.max(54, (innerWidth - 12) / 3));
        addRenderableWidget(VerityUiWidgets.secondaryButton(font, left + 12, tabY, tabWidth, 20,
                    Component.literal("皮肤"), button -> setTab(Tab.SKINS)));
        addRenderableWidget(VerityUiWidgets.secondaryButton(font, left + 18 + tabWidth, tabY, tabWidth, 20,
                    Component.literal("音色"), button -> setTab(Tab.VOICES)));
        addRenderableWidget(VerityUiWidgets.secondaryButton(font, left + 24 + tabWidth * 2, tabY, tabWidth, 20,
                    Component.literal("MMD"), button -> setTab(Tab.MMD)));
        int actionWidth = Math.max(48, Math.min(82, (innerWidth - 8) / 3));
        int actionY = compact ? 42 : 18;
        int actionLeft = compact ? left + 12 : right - 12 - actionWidth * 3 - 8;
        addRenderableWidget(VerityUiWidgets.secondaryButton(font, actionLeft, actionY, actionWidth, 20,
                    Component.literal("上传与管理"), button -> openWebsite()));
        addRenderableWidget(VerityUiWidgets.secondaryButton(font, actionLeft + actionWidth + 4, actionY, actionWidth, 20,
                    Component.literal("清除缓存"), button -> confirmClearCache()));
        addRenderableWidget(VerityUiWidgets.secondaryButton(font, actionLeft + (actionWidth + 4) * 2, actionY, actionWidth, 20,
                    Component.literal("刷新"), button -> refresh()));
        int filterWidth = Math.max(38, Math.min(58, (innerWidth - 15) / Filter.values().length));
        int x = left + 12;
        for (Filter value : Filter.values()) {
            addRenderableWidget(VerityUiWidgets.secondaryButton(font, x, filterY, filterWidth, 18,
                    Component.literal(value.title), button -> setFilter(value)));
            x += filterWidth + 5;
        }
        addRenderableWidget(VerityUiWidgets.secondaryButton(font, right - 70, height - 29, 58, 20,
                    Component.literal("返回"), button -> onClose()));
        rebuildItems();
        if (!initialLoadRequested && items.isEmpty() && !loading) {
            initialLoadRequested = true;
            refresh();
        }
    }

    private void setTab(Tab value) {
        if (tab == value) return;
        tab = value;
        scroll = 0;
        init(minecraft, width, height);
    }

    private void setFilter(Filter value) {
        if (filter == value) return;
        filter = value;
        scroll = 0;
        init(minecraft, width, height);
    }

    private void refresh() {
        refresh(null);
    }

    private void refresh(String completedMessage) {
        if (!ConfigManager.isCloudEnabled() || blank(ConfigManager.getCloudApiKey())) {
            loading = false;
            statusType = StatusType.WARNING;
            status = "请先完成账号授权";
            loadCachedOnly();
            return;
        }
        int token = generation.incrementAndGet();
        String requestKey = ConfigManager.getCloudApiKey();
        loading = true;
        statusType = StatusType.INFO;
        status = "正在同步当前账号可用的资源…";
        cancelTasks();
        tasks.add(executor.submit(() -> {
            try {
                CloudResourceClient client = new CloudResourceClient(ConfigManager.getCloudApiUrl(), requestKey, ConfigManager.getCloudTimeoutSeconds());
                CloudSkin[] skins = client.fetchSkins();
                CloudConfig.MimoVoice[] voices = client.fetchVoices();
                CloudMmdProject[] mmdProjects;
                try {
                    mmdProjects = client.fetchMmdProjects();
                } catch (ResourceException error) {
                    if (error.status() != 404) throw error;
                    mmdProjects = new CloudMmdProject[0];
                }
                ResourceCacheIndex index = new ResourceCacheIndex(requestKey);
                List<ResourceItem> next = merge(skins, voices, mmdProjects, index);
                Minecraft.getInstance().execute(() -> {
                    if (!accept(token) || !requestKey.equals(ConfigManager.getCloudApiKey())) return;
                    items.clear();
                    items.addAll(next);
                    ConfigManager.setMimoVoices(voices);
                    cacheBytes = index.sizeBytes();
                    lastSync = System.currentTimeMillis();
                    loading = false;
                    statusType = StatusType.SUCCESS;
                    status = blank(completedMessage) ? "同步完成，共 " + next.size() + " 项可见资源" : completedMessage;
                    init(minecraft, width, height);
                });
            } catch (Exception error) {
                Minecraft.getInstance().execute(() -> {
                    if (!accept(token) || !requestKey.equals(ConfigManager.getCloudApiKey())) return;
                    loading = false;
                    statusType = StatusType.ERROR;
                    status = safeMessage(error);
                    if (items.isEmpty()) loadCachedOnly();
                });
            }
        }));
    }

    private void loadCachedOnly() {
        if (blank(ConfigManager.getCloudApiKey())) return;
        try {
            ResourceCacheIndex index = new ResourceCacheIndex(ConfigManager.getCloudApiKey());
            if (items.isEmpty()) items.addAll(fromCache(index.entries()));
            cacheBytes = index.sizeBytes();
            init(minecraft, width, height);
        } catch (Exception ignored) { Silent.ignore("ResourceCenterScreen", ignored); }
    }

    private void rebuildItems() {
        for (Button button : itemButtons) removeWidget(button);
        itemButtons.clear();
        List<ResourceItem> visible = filtered();
        int rows = visibleRows();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, visible.size() - rows)));
        for (int i = scroll; i < Math.min(visible.size(), scroll + rows); i++) {
            ResourceItem item = visible.get(i);
            int y = listTop() + (i - scroll) * ITEM_HEIGHT;
            addItemButtons(item, y);
        }
    }

    private void addItemButtons(ResourceItem item, int y) {
        int right = panelRight() - 18;
        boolean compact = compactItems();
        if (item.kind == Tab.SKINS) {
            addItemButton(item.cached ? "应用" : "下载", right - 57, y + 7, 55, () -> skinAction(item, !item.cached));
            if (!compact && item.cached) addItemButton("重新下载", right - 116, y + 7, 55, () -> skinAction(item, true));
        } else if (item.kind == Tab.VOICES) {
            // 音色是云端按 ID 直接使用，不存在本地下载；当前音色给一个明确的“使用中”
            Button choose = addItemButton(item.current ? "使用中" : "选择",
                    right - 57, y + 7, 55, () -> selectVoice(item));
            if (item.current) choose.active = false;
        } else {
            addItemButton(item.cached ? "重新下载" : "下载", right - 67, y + 7, 65, () -> mmdAction(item));
        }
        if ((item.kind == Tab.SKINS || item.kind == Tab.MMD) && item.cached) {
            addItemButton("删除缓存", right - 67, y + 31, 65, () -> deleteCache(item));
        }
        if (item.owner && !item.builtin && !compact) addItemButton("隐私管理", right - 146, y + 31, 75, this::openWebsite);
    }

    private Button addItemButton(String title, int x, int y, int width, Runnable action) {
        Button button = VerityUiWidgets.secondaryButton(font, x, y, width, 18,
                    Component.literal(title), ignored -> action.run());
        button.active = !loading;
        itemButtons.add(button);
        addRenderableWidget(button);
        return button;
    }

    private void skinAction(ResourceItem item, boolean force) {
        runItemTask("正在下载皮肤并检查文件…", () -> {
            SkinResourceManager manager = new SkinResourceManager();
            if (force || !manager.isCached(item.id)) manager.download(item.skin, true);
            else if (!manager.applyCached(item.id)) throw new ResourceException(0, "皮肤应用失败");
            return "已应用皮肤：" + item.displayName;
        });
    }

    private void selectVoice(ResourceItem item) {
        try {
            new VoiceResourceManager().select(item.voice);
            for (int i = 0; i < items.size(); i++) {
                ResourceItem current = items.get(i);
                if (current.kind == Tab.VOICES) items.set(i, current.withCurrent(current.id.equals(item.id)));
            }
            statusType = StatusType.SUCCESS;
            status = "已选择音色：" + item.displayName + (item.builtin ? "（平台内置，无需下载）" : "（云端音色，无需下载）");
            init(minecraft, width, height);
        } catch (Exception error) {
            statusType = StatusType.ERROR;
            status = safeMessage(error);
        }
    }

    private void mmdAction(ResourceItem item) {
        if (item.mmd == null) return;
        runItemTask("正在下载并检查 MMD 项目…", () -> {
            new MmdResourceManager().download(item.mmd);
            return "已安装 MMD 项目：" + item.displayName;
        });
    }

    private void deleteCache(ResourceItem item) {
        runItemTask("正在删除本地缓存...", () -> {
            if (item.kind == Tab.MMD) new MmdResourceManager().deleteCached(item.id);
            else new SkinResourceManager().deleteCached(item.id);
            return "本地缓存已删除";
        });
    }

    private void runItemTask(String message, TaskOperation operation) {
        if (loading) return;
        int token = generation.incrementAndGet();
        loading = true;
        statusType = StatusType.INFO;
        status = message;
        tasks.add(executor.submit(() -> {
            try {
                String result = operation.run();
                Minecraft.getInstance().execute(() -> {
                    if (!accept(token)) return;
                    loading = false;
                    refresh(result);
                });
            } catch (Exception error) {
                Minecraft.getInstance().execute(() -> {
                    if (!accept(token)) return;
                    loading = false;
                    statusType = StatusType.ERROR;
                    status = safeMessage(error);
                });
            }
        }));
    }

    private void confirmClearCache() {
        minecraft.setScreen(VerityConfirmScreen.dangerous(this, confirmed -> {
            minecraft.setScreen(this);
            if (!confirmed || blank(ConfigManager.getCloudApiKey())) return;
            runItemTask("正在清理当前账号缓存...", () -> {
                String accountPrefix = new ResourceCacheIndex(ConfigManager.getCloudApiKey()).accountId() + ":";
                String activeSkin = SkinManager.getInstance().getActiveSkinName();
                if (activeSkin != null && activeSkin.startsWith(accountPrefix)) SkinManager.getInstance().useDefaultSkin();
                String voice = ConfigManager.getTtsApiVoice();
                if (ConfigManager.isSavedMimoVoiceClone(voice)) ConfigManager.saveMimoVoice("苏打", false);
                new ResourceCacheIndex(ConfigManager.getCloudApiKey()).clear();
                com.hanchen.veritycustomapi.mmd.MmdPlaybackManager.get().scanAndLoad();
                return "当前账号资源缓存已清理";
            });
        }, Component.literal("清理资源缓存"), Component.literal("只清除当前账号下载到本地的皮肤、音色和 MMD，不会影响云端资源。")));
    }

    private void openWebsite() {
        try {
            ResourceUploadClient.openWebsite();
            statusType = StatusType.INFO;
            status = "上传和隐私设置将在网页资源中心完成";
        } catch (Exception error) {
            statusType = StatusType.ERROR;
            status = "无法打开网页资源中心";
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseX < panelLeft() || mouseX > panelRight() || mouseY < listTop() || mouseY > listBottom()) return false;
        int previous = scroll;
        int max = Math.max(0, filtered().size() - visibleRows());
        scroll = Math.max(0, Math.min(scroll - (int) Math.signum(delta), max));
        if (scroll == previous) return false;
        rebuildItems();
        return true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 半透明：世界里打开时透出世界；主菜单打开时走原版不透明背景
        com.hanchen.veritycustomapi.client.ScreenBackgrounds.draw(this, graphics);
        int left = panelLeft();
        int right = panelRight();
        VerityUiDraw.card(graphics, left, 8, right, height - 8, 5);
        graphics.drawString(font, "资源中心", left + 12, 18, TEXT);
        String sync = lastSync == 0 ? "尚未更新" : "最近更新 " + formatTime(lastSync);
        graphics.drawString(font, fit(sync + "  ·  缓存 " + formatBytes(cacheBytes), Math.max(40, right - left - 24)),
                left + 12, 32, MUTED);
        List<ResourceItem> visible = filtered();
        int end = Math.min(visible.size(), scroll + visibleRows());
        String count = compactHeader() || visible.size() <= visibleRows()
                ? visible.size() + " 项"
                : (scroll + 1) + "-" + end + " / " + visible.size() + " 项";
        graphics.drawString(font, count, right - 12 - font.width(count), headerBottom() - 13, MUTED);
        graphics.fill(left + 12, headerBottom(), right - 12, headerBottom() + 1, SEPARATOR);
        int textWidth = Math.max(60, right - left - (compactItems() ? 110 : 230));
        for (int i = scroll; i < Math.min(visible.size(), scroll + visibleRows()); i++) {
            ResourceItem item = visible.get(i);
            int y = listTop() + (i - scroll) * ITEM_HEIGHT;
            boolean hovered = mouseX >= left + 12 && mouseX <= right - 12
                    && mouseY >= y && mouseY < y + ITEM_HEIGHT - 4;
            if (hovered) graphics.fill(left + 12, y, right - 12, y + ITEM_HEIGHT - 4, ITEM_HOVER);
            graphics.fill(left + 12, y + ITEM_HEIGHT - 5, right - 12, y + ITEM_HEIGHT - 4, SEPARATOR);
            if (item.current) graphics.fill(left + 12, y + 5, left + 14, y + ITEM_HEIGHT - 9, SUCCESS);
            graphics.drawString(font, fit(item.displayName, textWidth), left + 20, y + 8, TEXT);
            String meta = item.id + "  ·  " + item.typeLabel + "  ·  " + privacy(item);
            graphics.drawString(font, fit(meta, textWidth), left + 20, y + 22, MUTED);
            String detail;
            if (item.kind == Tab.VOICES) {
                // 音色由云端按 ID 直接使用，没有本地文件，不能再显示“未下载”
                detail = item.builtin ? "平台内置  ·  无需下载" : "云端克隆  ·  无需下载";
            } else {
                detail = item.cached ? "已下载" : "未下载";
            }
            if (item.current) detail += "  ·  正在使用";
            if (item.size > 0) detail += "  ·  " + formatBytes(item.size);
            if (!item.createdAt.isBlank()) detail += "  ·  " + item.createdAt;
            graphics.drawString(font, fit(detail, textWidth), left + 20, y + 38, item.current ? SUCCESS : MUTED);
        }
        if (visible.isEmpty()) graphics.drawCenteredString(font, loading ? "正在更新资源…" : "没有符合条件的资源", width / 2, listTop() + 38, MUTED);
        renderScrollBar(graphics, visible.size());
        int color = statusType == StatusType.ERROR ? DANGER : statusType == StatusType.WARNING ? WARNING
                : statusType == StatusType.SUCCESS ? SUCCESS : ACCENT;
        graphics.drawString(font, fit(status, Math.max(80, right - left - 100)), left + 12, height - 23, color);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderActiveControls(graphics);
    }

    private List<ResourceItem> filtered() {
        return items.stream().filter(item -> item.kind == tab).filter(item -> switch (filter) {
            case ALL -> true;
            case MINE -> item.owner;
            case PUBLIC -> item.publicResource && !item.platform;
            case PLATFORM -> item.platform;
        }).sorted(Comparator.comparing((ResourceItem item) -> !item.current)
                .thenComparing(item -> !item.owner).thenComparing(item -> item.displayName)).toList();
    }

    private List<ResourceItem> merge(CloudSkin[] skins, CloudConfig.MimoVoice[] voices,
                                     CloudMmdProject[] mmdProjects, ResourceCacheIndex index) {
        List<ResourceItem> result = new ArrayList<>();
        Map<String, ResourceCacheIndex.Entry> cached = index.entries();
        for (CloudSkin skin : skins) {
            ResourceCacheIndex.Entry entry = cached.get("skin:" + skin.name);
            boolean current = SkinManager.getInstance().getActiveSkinName() != null
                    && SkinManager.getInstance().getActiveSkinName().endsWith(":" + skin.name);
            result.add(new ResourceItem(Tab.SKINS, skin.name, name(skin), "皮肤 ZIP", skin.isOwner == 1,
                    skin.isPublic == 1, skin.isGlobal == 1, entry != null, current, false,
                    skin.fileSize, shortDate(skin.createdAt), skin, null, null));
        }
        for (CloudConfig.MimoVoice voice : voices) {
            boolean builtin = "builtin".equalsIgnoreCase(voice.type);
            ResourceCacheIndex.Entry entry = cached.get("voice:" + voice.id);
            result.add(new ResourceItem(Tab.VOICES, voice.id, name(voice), builtin ? "内置音色" : "克隆音色",
                    voice.isOwner != null && voice.isOwner == 1, voice.isPublic != null && voice.isPublic == 1,
                    builtin || voice.isGlobal != null && voice.isGlobal == 1, entry != null,
                    voice.id.equals(ConfigManager.getTtsApiVoice()), builtin, voice.fileSize, shortDate(voice.createdAt), null, voice, null));
        }
        for (CloudMmdProject project : mmdProjects) {
            ResourceCacheIndex.Entry entry = cached.get("mmd:" + project.id);
            result.add(new ResourceItem(Tab.MMD, project.id, name(project), "MMD 项目 ZIP", project.isOwner == 1,
                    project.isPublic == 1, project.isGlobal == 1, entry != null, false, false,
                    project.fileSize, shortDate(project.updatedAt == null ? project.createdAt : project.updatedAt), null, null, project));
        }
        for (ResourceItem cachedItem : fromCache(cached)) {
            if (result.stream().noneMatch(item -> item.kind == cachedItem.kind && item.id.equals(cachedItem.id))) result.add(cachedItem);
        }
        return result;
    }

    private List<ResourceItem> fromCache(Map<String, ResourceCacheIndex.Entry> entries) {
        List<ResourceItem> result = new ArrayList<>();
        for (ResourceCacheIndex.Entry entry : entries.values()) {
            if ("skin".equals(entry.type())) {
                CloudSkin skin = new CloudSkin();
                skin.name = entry.resourceId();
                skin.displayName = entry.displayName();
                result.add(new ResourceItem(Tab.SKINS, entry.resourceId(), entry.displayName(), "已缓存皮肤", entry.owner(),
                        entry.publicResource(), entry.platform(), true, false, false, entry.size(), "离线缓存", skin, null, null));
            } else if ("voice".equals(entry.type())) {
                CloudConfig.MimoVoice voice = new CloudConfig.MimoVoice();
                voice.id = entry.resourceId();
                voice.name = entry.displayName();
                voice.type = entry.platform() ? "builtin" : "clone";
                result.add(new ResourceItem(Tab.VOICES, entry.resourceId(), entry.displayName(), "已缓存音色", entry.owner(),
                        entry.publicResource(), entry.platform(), true, voice.id.equals(ConfigManager.getTtsApiVoice()),
                        entry.platform(), entry.size(), "离线缓存", null, voice, null));
            } else if ("mmd".equals(entry.type())) {
                CloudMmdProject project = new CloudMmdProject();
                project.id = entry.resourceId();
                project.name = entry.displayName();
                project.format = "zip";
                result.add(new ResourceItem(Tab.MMD, entry.resourceId(), entry.displayName(), "已安装 MMD 项目", entry.owner(),
                        entry.publicResource(), entry.platform(), true, false, false, entry.size(), "离线缓存", null, null, project));
            }
        }
        return result;
    }

    @Override
    public void onClose() {
        closed = true;
        generation.incrementAndGet();
        cancelTasks();
        executor.shutdownNow();
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    private void cancelTasks() {
        for (Future<?> task : tasks) task.cancel(true);
        tasks.clear();
    }

    private boolean accept(int token) { return !closed && generation.get() == token && minecraft != null && minecraft.screen == this; }

    private void renderActiveControls(GuiGraphics graphics) {
        int left = panelLeft();
        int innerWidth = panelRight() - left - 24;
        int tabWidth = Math.min(120, Math.max(54, (innerWidth - 12) / 3));
        int tabY = compactHeader() ? 66 : 54;
        int tabIndex = tab == Tab.SKINS ? 0 : tab == Tab.VOICES ? 1 : 2;
        int tabLeft = left + 12 + tabIndex * (tabWidth + 6);
        graphics.fill(tabLeft, tabY + 20, tabLeft + tabWidth, tabY + 22, ACCENT);

        int filterWidth = Math.max(38, Math.min(58, (innerWidth - 15) / Filter.values().length));
        int filterY = compactHeader() ? 92 : 82;
        int filterLeft = left + 12 + filter.ordinal() * (filterWidth + 5);
        graphics.fill(filterLeft, filterY + 18, filterLeft + filterWidth, filterY + 20, ACCENT);
    }

    private void renderScrollBar(GuiGraphics graphics, int itemCount) {
        int rows = visibleRows();
        if (itemCount <= rows) return;
        int top = listTop();
        int bottom = listBottom();
        int trackX = panelRight() - 9;
        int thumbHeight = Math.max(14, (bottom - top) * rows / itemCount);
        int maxScroll = itemCount - rows;
        int thumbTop = top + (bottom - top - thumbHeight) * scroll / Math.max(1, maxScroll);
        graphics.fill(trackX, top, trackX + 2, bottom, SEPARATOR);
        graphics.fill(trackX, thumbTop, trackX + 2, thumbTop + thumbHeight, ACCENT);
    }

    private boolean compactHeader() { return panelRight() - panelLeft() < 620; }
    private boolean compactItems() { return panelRight() - panelLeft() < 650; }
    private int panelLeft() { return Math.max(8, (width - Math.min(900, width - 16)) / 2); }
    private int panelRight() { return Math.min(width - 8, panelLeft() + 900); }
    private int headerBottom() { return compactHeader() ? 116 : 105; }
    private int listTop() { return headerBottom() + 7; }
    private int listBottom() { return height - 38; }
    private int visibleRows() { return Math.max(1, (listBottom() - listTop()) / ITEM_HEIGHT); }
    private String fit(String value, int width) { return font.plainSubstrByWidth(value == null ? "" : value, width); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static String name(CloudSkin skin) { return blank(skin.displayName) ? skin.name : skin.displayName; }
    private static String name(CloudConfig.MimoVoice voice) { return blank(voice.name) ? voice.id : voice.name; }
    private static String name(CloudMmdProject project) { return blank(project.name) ? project.id : project.name; }
    private static String privacy(ResourceItem item) { return item.platform ? "平台内置" : item.owner && !item.publicResource ? "私有" : item.publicResource ? "公开" : "账号可见"; }
    private static String shortDate(String value) { return blank(value) ? "" : value.length() > 10 ? value.substring(0, 10) : value; }
    private static String formatTime(long value) { return TIME_FORMAT.format(Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault())); }
    private static String formatBytes(long value) { return value < 1024 ? value + " B" : value < 1024 * 1024 ? String.format("%.1f KB", value / 1024.0) : String.format("%.1f MB", value / 1048576.0); }
    private static String safeMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && (current.getMessage() == null || current.getMessage().isBlank())) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? "资源操作失败" : message.replace('\r', ' ').replace('\n', ' ');
    }

    private enum Tab { SKINS, VOICES, MMD }
    private enum Filter {
        ALL("全部"), MINE("我的"), PUBLIC("公开"), PLATFORM("平台");
        final String title;
        Filter(String title) { this.title = title; }
    }
    private enum StatusType { INFO, SUCCESS, WARNING, ERROR }
    private record ResourceItem(Tab kind, String id, String displayName, String typeLabel, boolean owner,
                                boolean publicResource, boolean platform, boolean cached, boolean current,
                                boolean builtin, long size, String createdAt, CloudSkin skin, CloudConfig.MimoVoice voice,
                                CloudMmdProject mmd) {
        private ResourceItem withCurrent(boolean value) {
            return new ResourceItem(kind, id, displayName, typeLabel, owner, publicResource, platform, cached,
                    value, builtin, size, createdAt, skin, voice, mmd);
        }
    }
    @FunctionalInterface private interface TaskOperation { String run() throws Exception; }
}
