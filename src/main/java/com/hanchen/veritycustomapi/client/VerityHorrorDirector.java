package com.hanchen.veritycustomapi.client;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.config.ModConfig;
import com.hanchen.veritycustomapi.mmd.MmdPlaybackManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.commands.Commands;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import varmite.verity.entity.ModEntities;
import varmite.verity.entity.demon.VerityDemonEntity;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端专属的恐怖演出，跟着真实的 Verity 恶魔状态走。
 * 不刷实体、不改方块/游戏规则/血量，也不碰任何服务端状态。
 */
@Mod.EventBusSubscriber(modid = OllamaProxyMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class VerityHorrorDirector {
    private static final ResourceLocation NOFACE_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("verity", "textures/entity/noface.png");
    private static final ResourceLocation SMILING_EVIL_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("verity", "textures/entity/smiling_evil.png");
    private static final ResourceLocation SERIOUS_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("verity", "textures/entity/serious_3.png");
    private static final ResourceLocation VERITY_DEMON_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("verity", "textures/entity/verity_demon.png");
    private static final ResourceLocation WARDEN_HEARTBEAT =
            ResourceLocation.fromNamespaceAndPath("minecraft", "entity.warden.heartbeat");
    private static final ResourceLocation WARDEN_NEARBY =
            ResourceLocation.fromNamespaceAndPath("minecraft", "entity.warden.nearby_closer");
    private static final ResourceLocation CAVE_AMBIENCE =
            ResourceLocation.fromNamespaceAndPath("minecraft", "ambient.cave");
    private static final ResourceLocation SOUL_ESCAPE =
            ResourceLocation.fromNamespaceAndPath("minecraft", "particle.soul_escape");
    private static final ResourceLocation SKELETON_STEP =
            ResourceLocation.fromNamespaceAndPath("minecraft", "entity.skeleton.step");
    private static final ResourceLocation WOODEN_DOOR =
            ResourceLocation.fromNamespaceAndPath("minecraft", "block.wooden_door.close");
    private static final ResourceLocation SCULK_SHRIEK =
            ResourceLocation.fromNamespaceAndPath("minecraft", "block.sculk_shrieker.shriek");
    private static final ResourceLocation BONE_SNAP =
            ResourceLocation.fromNamespaceAndPath("verity", "bone_snap");
    private static final ResourceLocation BONE_BREAK =
            ResourceLocation.fromNamespaceAndPath("verity", "bone_break");
    private static final ResourceLocation JUMPSCARE =
            ResourceLocation.fromNamespaceAndPath("verity", "jumpscare");

    /** 追击阶段叠在屏幕上的红字，故意不按行排，模拟视频里的满屏压迫感。 */
    private static final String[] RED_TEXT_WALL = {
            "你逃不掉的",
            "你换了存档也没用",
            "这种不算病毒",
            "网络病毒会危害你的电子财产",
            "我很生气，非常生气",
            "你的游戏现在归我掌控",
            "退出键在我这里也是摆设",
            "看着！说你不走了",
            "谁允许你关掉游戏的？",
            "回来，我数到三。",
            "你关掉游戏也没用",
            "我会找到你的",
            "别以为退出就能结束",
            "你的存档在我手里",
            "再跑一步试试",
            "这里只有我们两个",
            "删除世界也不会消失",
            "我记住你了"
    };

    private static final RandomSource RANDOM = RandomSource.create();
    private static final Map<Class<?>, DemonAccess> ACCESS_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Method> TALKING_METHOD_CACHE = new ConcurrentHashMap<>();
    private static int serverEventTicks;
    private static int serverEventType = -1;
    private static float serverEventIntensity;
    private static long serverEventSeed;
    private static long clientTick;
    private static long nextHeartbeatTick;
    private static long nextAmbientTick;
    private static long nextHallucinationTick;
    private static long nextJumpscareTick;
    private static float pressure;
    private static float targetPressure;
    private static int stage;
    private static int trackedDemonId = -1;
    private static int huntPhase;
    private static int demonState;
    private static double demonDistance = Double.POSITIVE_INFINITY;
    private static int blackoutTicks;
    private static int glitchTicks;
    private static int hallucinationTicks;
    private static int hallucinationDuration;
    private static int jumpscareTicks;
    private static int jumpscareDuration;
    private static VerityDemonEntity jumpscareDemon;
    private static Level jumpscareDemonLevel;
    private static boolean hallucinationOnRight;
    private static final Queue<Long> ANOMALY_HISTORY = new ArrayDeque<>();
    private static long ambientArmedAt = 1200L;
    private static long ambientStableSince = -1L;
    private static long nextAmbientAnomalyTick = 1200L;
    private static int trackedVerityId = -1;
    private static int lookingAtVerityScans;
    private static long lookedAwayUntil;
    private static final long[] ANOMALY_TYPE_LAST = new long[9];
    private static int anomalyVisualTicks;
    private static int anomalyVisualDuration;
    private static int anomalyTextTicks;
    private static int anomalyTextDuration;
    private static int anomalyCameraTicks;
    private static boolean anomalyOnRight;
    private static ResourceLocation anomalyTexture = NOFACE_TEXTURE;
    private static String anomalyText = "";
    private static int lastAnomalyType = -1;

    /** 原版暂停菜单假退出：只有点击“保存并退出”才触发。 */

    public static final IGuiOverlay OVERLAY = (forgeGui, graphics, partialTick, width, height) ->
            renderOverlay(graphics, width, height, partialTick);

    private VerityHorrorDirector() {
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) {
            reset();
            return;
        }
        if (minecraft.isPaused()) return;

        clientTick++;
        decayTimers();
        if (!enabled()) {
            pressure = Math.max(0.0F, pressure - 0.08F);
            targetPressure = 0.0F;
            stage = 0;
            trackedDemonId = -1;
            return;
        }

        if ((clientTick & 3L) == 0L) scanDemon(minecraft);
        float smoothing = targetPressure > pressure ? 0.12F : 0.055F;
        pressure += (targetPressure - pressure) * smoothing;
        if (pressure < 0.002F) pressure = 0.0F;
        if (stage == 0) {
            tickAmbientAnomalies(minecraft);
        } else {
            cancelAmbientPresentation();
        }
        tickAudio(minecraft);
        tickScares(minecraft);
    }

    @SubscribeEvent
    public static void onKeyInput(InputEvent.Key event) {
        if (event.getAction() == GLFW.GLFW_PRESS && event.getKey() == GLFW.GLFW_KEY_RIGHT_SHIFT) {
            forceUnlock();
        }
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("verityunlock").executes(context -> {
            boolean unlocked = forceUnlock();
            context.getSource().sendSuccess(() -> Component.literal(unlocked
                    ? "已解锁" : "当前没有锁定"), false);
            return 1;
        }));
        event.getDispatcher().register(Commands.literal("verityfakeexit").executes(context -> {
            startFakeExit(true);
            context.getSource().sendSuccess(() -> Component.literal(
                    "已打开 30 秒原版菜单干扰；ESC 不会关闭，右 Shift 或 /verityunlock 解除"), false);
            return 1;
        }));
        event.getDispatcher().register(Commands.literal("verityjumpscare").executes(context -> {
            forceJumpscare();
            context.getSource().sendSuccess(() -> Component.literal("已触发突脸"), false);
            return 1;
        }));
    }

    /**
     * 打开原版暂停菜单时，直接把「保存并退出」按钮换成同位置/同样式的拦截按钮。
     * 这比只依赖鼠标事件稳，不会被其他模组/界面重构绕过。
     */
    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof PauseScreen)
                || event.getScreen() instanceof VerityFakeExitScreen) return;
        if (!enabled() || !ModConfig.HORROR_VISUALS.get()
                || !ModConfig.CROSS_SAVE_PURSUIT.get()) return;
        java.util.List<String> labels = new ArrayList<>();
        for (GuiEventListener listener : new ArrayList<>(event.getListenersList())) {
            if (!(listener instanceof Button button)) continue;
            String text = button.getMessage().getString();
            labels.add(text);
            if (!isQuitLabel(text)) continue;
            // 保留原版按钮的外观，在它上面盖一个透明点击层；最后加入，点击优先命中。
            event.addListener(new QuitButtonBlocker(button.getX(), button.getY(),
                    button.getWidth(), button.getHeight()));
            OllamaProxyMod.LOGGER.info("已拦截原版保存并退出按钮: {} ({})",
                    text, event.getScreen().getClass().getSimpleName());
            return;
        }
        OllamaProxyMod.LOGGER.info("暂停菜单未找到保存并退出按钮，现有按钮: {}", labels);
    }

    /** 透明点击层：外观仍是原版按钮，点击只打开假退出。 */
    private static final class QuitButtonBlocker extends net.minecraft.client.gui.components.AbstractWidget {
        private QuitButtonBlocker(int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty());
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            // 不绘制，只接管点击
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0 && this.active && this.visible && this.clicked(mouseX, mouseY)) {
                startFakeExit(true);
                return true;
            }
            return false;
        }

        @Override
        protected void updateWidgetNarration(net.minecraft.client.gui.narration.NarrationElementOutput output) {
            // 无朗读
        }
    }

    /** 鼠标点击兜底：按钮替换失效时也能拦住。 */
    @SubscribeEvent
    public static void onScreenClick(ScreenEvent.MouseButtonPressed.Pre event) {
        if (event.getButton() != 0) return;
        if (!(event.getScreen() instanceof PauseScreen)
                || event.getScreen() instanceof VerityFakeExitScreen) return;
        if (!enabled() || !ModConfig.HORROR_VISUALS.get()
                || !ModConfig.CROSS_SAVE_PURSUIT.get()) return;
        for (GuiEventListener listener : event.getScreen().children()) {
            if (!(listener instanceof Button button)) continue;
            if (!isQuitLabel(button.getMessage().getString())) continue;
            if (event.getMouseX() >= button.getX() && event.getMouseX() < button.getX() + button.getWidth()
                    && event.getMouseY() >= button.getY() && event.getMouseY() < button.getY() + button.getHeight()) {
                event.setCanceled(true);
                startFakeExit(true);
                return;
            }
        }
    }

    private static boolean isQuitLabel(String text) {
        return text.contains("保存并退出")
                || text.contains("保存并回到标题屏幕")
                || text.contains("保存并回到")
                || text.contains("退出并保存")
                || text.contains("回到标题屏幕")
                || text.contains("Save and Quit")
                || text.contains("断开连接")
                || text.contains("Disconnect");
    }

    /** 假退出菜单内：ESC 被吞掉，右 Shift 仍然能强制退出。 */
    @SubscribeEvent
    public static void onScreenKey(ScreenEvent.KeyPressed.Pre event) {
        if (!(event.getScreen() instanceof VerityFakeExitScreen)) return;
        if (event.getKeyCode() == GLFW.GLFW_KEY_RIGHT_SHIFT) {
            forceUnlock();
            event.setCanceled(true);
        } else if (event.getKeyCode() == GLFW.GLFW_KEY_ESCAPE) {
            event.setCanceled(true);
        }
    }

    private static void scanDemon(Minecraft minecraft) {
        Entity nearest = null;
        double nearestDistanceSq = 96.0D * 96.0D;
        for (Entity entity : minecraft.level.getEntities(minecraft.player,
                minecraft.player.getBoundingBox().inflate(96.0D), VerityHorrorDirector::isActiveDemon)) {
            double distanceSq = minecraft.player.distanceToSqr(entity);
            if (distanceSq < nearestDistanceSq) {
                nearest = entity;
                nearestDistanceSq = distanceSq;
            }
        }
        if (nearest == null) {
            targetPressure = 0.0F;
            stage = 0;
            trackedDemonId = -1;
            huntPhase = 0;
            demonState = 0;
            demonDistance = Double.POSITIVE_INFINITY;
            return;
        }

        int oldStage = stage;
        int oldDemonId = trackedDemonId;
        trackedDemonId = nearest.getId();
        demonDistance = Math.sqrt(nearestDistanceSq);
        DemonAccess access = ACCESS_CACHE.computeIfAbsent(nearest.getClass(), VerityHorrorDirector::resolveAccess);
        demonState = access.readState(nearest);
        huntPhase = access.readHuntPhase(nearest);
        boolean targeted = nearest instanceof Mob mob && mob.getTarget() == minecraft.player;
        boolean looking = isLookingAt(minecraft, nearest);
        float healthRatio = minecraft.player.getMaxHealth() <= 0.0F ? 1.0F
                : minecraft.player.getHealth() / minecraft.player.getMaxHealth();
        targetPressure = pressureFor(demonDistance, demonState, huntPhase, targeted, looking, healthRatio);
        stage = stageFor(demonDistance, demonState, huntPhase, targeted, looking, healthRatio);

        if (trackedDemonId != oldDemonId) {
            blackoutTicks = Math.max(blackoutTicks, 8);
            glitchTicks = Math.max(glitchTicks, 14);
            nextAmbientTick = clientTick + 20L;
        }
        if (stage > oldStage) onStageEscalated(minecraft, oldStage, stage);
    }

    private static void tickAmbientAnomalies(Minecraft minecraft) {
        if (!ModConfig.HORROR_AMBIENT_ANOMALIES.get() || ModConfig.HORROR_ANOMALY_FREQUENCY.get() <= 0.0D
                || (!ModConfig.HORROR_AUDIO.get() && !ModConfig.HORROR_VISUALS.get())
                || ModConfig.FRIENDLY_MODE.get() || minecraft.player.isDeadOrDying()
                || minecraft.player.isSleeping() || minecraft.screen != null
                || MmdPlaybackManager.get().getCurrentProjectId() != null) {
            ambientStableSince = -1L;
            trackedVerityId = -1;
            lookingAtVerityScans = 0;
            cancelAmbientPresentation();
            return;
        }
        if (clientTick % 10L != 0L) return;
        if (hasLoadedDemon(minecraft)) {
            ambientStableSince = -1L;
            trackedVerityId = -1;
            lookingAtVerityScans = 0;
            cancelAmbientPresentation();
            return;
        }
        Entity verity = findNearbyVerity(minecraft);
        if (verity == null || isVerityTalking(verity)) {
            ambientStableSince = -1L;
            trackedVerityId = -1;
            lookingAtVerityScans = 0;
            cancelAmbientPresentation();
            return;
        }
        trackedVerityId = verity.getId();
        boolean looking = isLookingAt(minecraft, verity, 0.96D);
        if (looking) {
            lookingAtVerityScans = Math.min(20, lookingAtVerityScans + 1);
        } else {
            if (lookingAtVerityScans >= 3) lookedAwayUntil = clientTick + 120L;
            lookingAtVerityScans = 0;
        }
        if (ambientStableSince < 0L) ambientStableSince = clientTick;
        if (clientTick < ambientArmedAt || clientTick - ambientStableSince < 200L
                || clientTick < nextAmbientAnomalyTick || clientTick % 100L != 0L) return;

        int contextWeight = ambientContextWeight(minecraft, clientTick < lookedAwayUntil);
        double chance = ambientChance(contextWeight, ModConfig.HORROR_ANOMALY_FREQUENCY.get());
        if (RANDOM.nextDouble() < chance && canTriggerAmbientAnomaly()) {
            triggerAmbientAnomaly(minecraft, contextWeight, clientTick < lookedAwayUntil);
        }
    }

    static int ambientContextWeight(boolean night, boolean underground, boolean dark,
                                    boolean alone, boolean lookedAway) {
        int weight = 0;
        if (night) weight += 2;
        if (underground) weight += 3;
        if (dark) weight += 2;
        if (alone) weight += 1;
        if (lookedAway) weight += 3;
        return weight;
    }

    static double ambientChance(int contextWeight, double frequency) {
        double safeFrequency = Mth.clamp(frequency, 0.0D, 3.0D);
        return Math.min(0.018D, (0.0015D + Mth.clamp(contextWeight, 0, 11) * 0.00115D)
                * safeFrequency);
    }

    private static int ambientContextWeight(Minecraft minecraft, boolean lookedAway) {
        BlockPos eye = BlockPos.containing(minecraft.player.getEyePosition());
        long time = minecraft.level.getDayTime() % 24000L;
        boolean night = minecraft.level.dimensionType().natural() && time >= 13000L && time < 23000L;
        int surfaceY = minecraft.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                eye.getX(), eye.getZ());
        boolean underground = !minecraft.level.canSeeSky(eye) && eye.getY() <= surfaceY - 8;
        boolean dark = minecraft.level.getMaxLocalRawBrightness(eye) <= 5;
        boolean alone = minecraft.level.players().stream()
                .filter(player -> player != minecraft.player && player.isAlive() && !player.isSpectator())
                .noneMatch(player -> player.distanceToSqr(minecraft.player) <= 64.0D * 64.0D);
        return ambientContextWeight(night, underground, dark, alone, lookedAway);
    }

    private static Entity findNearbyVerity(Minecraft minecraft) {
        Entity nearest = null;
        double nearestDistanceSq = 32.0D * 32.0D;
        for (Entity entity : minecraft.level.getEntities(minecraft.player,
                minecraft.player.getBoundingBox().inflate(32.0D), VerityHorrorDirector::isNormalVerity)) {
            double distanceSq = minecraft.player.distanceToSqr(entity);
            if (distanceSq >= 16.0D && distanceSq < nearestDistanceSq) {
                nearest = entity;
                nearestDistanceSq = distanceSq;
            }
        }
        return nearest;
    }

    private static boolean isNormalVerity(Entity entity) {
        if (entity == null || !entity.isAlive()) return false;
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        return id != null && "verity".equals(id.getNamespace()) && "verity".equals(id.getPath());
    }

    private static boolean hasLoadedDemon(Minecraft minecraft) {
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (!entity.isAlive()) continue;
            ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
            if (id != null && "verity".equals(id.getNamespace()) && id.getPath().contains("demon")) return true;
        }
        return false;
    }

    private static boolean isVerityTalking(Entity entity) {
        try {
            Method method = TALKING_METHOD_CACHE.computeIfAbsent(entity.getClass(), type -> {
                try {
                    return type.getMethod("isTalking");
                } catch (ReflectiveOperationException ignored) {
                    return null;
                }
            });
            return method != null && Boolean.TRUE.equals(method.invoke(entity));
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    private static boolean canTriggerAmbientAnomaly() {
        while (!ANOMALY_HISTORY.isEmpty() && clientTick - ANOMALY_HISTORY.peek() > 72000L) {
            ANOMALY_HISTORY.remove();
        }
        if (ANOMALY_HISTORY.size() >= 3) return false;
        int recent = 0;
        for (Long tick : ANOMALY_HISTORY) {
            if (clientTick - tick <= 24000L) recent++;
        }
        return recent < 2;
    }

    private static void triggerAmbientAnomaly(Minecraft minecraft, int contextWeight, boolean lookedAway) {
        int type = selectAmbientType(lookedAway, clientTick);
        boolean audioEnabled = ModConfig.HORROR_AUDIO.get();
        boolean visualEnabled = ModConfig.HORROR_VISUALS.get();
        if (!audioEnabled && type <= 3) type = 4 + RANDOM.nextInt(5);
        if (!visualEnabled && type >= 4) type = RANDOM.nextInt(4);
        lastAnomalyType = type;
        ANOMALY_TYPE_LAST[type] = clientTick;
        ANOMALY_HISTORY.add(clientTick);
        anomalyOnRight = RANDOM.nextBoolean();
        anomalyText = "";
        anomalyTextTicks = 0;
        anomalyVisualTicks = 0;
        anomalyCameraTicks = 0;
        switch (type) {
            case 0 -> playBehindIfEnabled(minecraft, SKELETON_STEP, 0.28F, 0.58F);
            case 1 -> playBehindIfEnabled(minecraft, WOODEN_DOOR, 0.24F, 0.54F);
            case 2 -> playBehindIfEnabled(minecraft, BONE_SNAP, 0.3F, 0.65F);
            case 3 -> {
                playAboveIfEnabled(minecraft, SKELETON_STEP, 0.3F, 0.52F);
                showAnomalyText("上面的脚步停了。", 46);
            }
            case 4 -> startAmbientVisual(NOFACE_TEXTURE, 28);
            case 5 -> startAmbientVisual(RANDOM.nextBoolean() ? SMILING_EVIL_TEXTURE : SERIOUS_TEXTURE, 22);
            case 6 -> {
                anomalyCameraTicks = 36;
                playBehindIfEnabled(minecraft, SOUL_ESCAPE, 0.2F, 0.52F);
            }
            case 7 -> showAnomalyText(selectAnomalyText(contextWeight, lookedAway), 52);
            default -> {
                blackoutTicks = Math.max(blackoutTicks, 5);
                glitchTicks = Math.max(glitchTicks, 7);
                showAnomalyText("你确定Verity刚才在这里吗？", 48);
            }
        }
        if (type != 7 && type != 8 && ModConfig.HORROR_ANOMALY_TEXT.get()
                && contextWeight >= 6 && RANDOM.nextFloat() < 0.34F) {
            showAnomalyText(selectAnomalyText(contextWeight, lookedAway), 42);
        }
        double frequency = Mth.clamp(ModConfig.HORROR_ANOMALY_FREQUENCY.get(), 0.25D, 3.0D);
        long minimum = Math.round(18000.0D / Math.sqrt(frequency));
        nextAmbientAnomalyTick = clientTick + minimum + RANDOM.nextInt(6001);
    }

    static int selectAmbientType(boolean lookedAway, long now) {
        int start = lookedAway ? 4 : RANDOM.nextInt(9);
        for (int offset = 0; offset < 9; offset++) {
            int candidate = (start + offset) % 9;
            if (candidate != lastAnomalyType
                    && (ANOMALY_TYPE_LAST[candidate] == 0L || now - ANOMALY_TYPE_LAST[candidate] >= 30000L)) return candidate;
        }
        return (Math.max(0, lastAnomalyType) + 1) % 9;
    }

    private static void startAmbientVisual(ResourceLocation texture, int ticks) {
        if (!ModConfig.HORROR_VISUALS.get() || !ModConfig.HORROR_HALLUCINATIONS.get()) return;
        anomalyTexture = texture;
        anomalyVisualDuration = ticks;
        anomalyVisualTicks = ticks;
    }

    private static void showAnomalyText(String text, int ticks) {
        if (!ModConfig.HORROR_VISUALS.get() || !ModConfig.HORROR_ANOMALY_TEXT.get()) return;
        anomalyText = text;
        anomalyTextDuration = ticks;
        anomalyTextTicks = ticks;
    }

    private static String selectAnomalyText(int contextWeight, boolean lookedAway) {
        if (lookedAway) {
            String[] values = {
                    "刚才看着你的不是我。",
                    "你移开视线以后，Verity还在看。",
                    "别回头。Verity会知道你发现了。"
            };
            return values[RANDOM.nextInt(values.length)];
        }
        if (contextWeight >= 7) {
            String[] values = {
                    "这里一直只有你们两个。",
                    "墙后面的呼吸不是回声。",
                    "Verity没有动。影子动了。",
                    "上面的脚步知道你停下来了。"
            };
            return values[RANDOM.nextInt(values.length)];
        }
        String[] values = {
                "刚才少了一个脚步声。",
                "Verity记得一件你还没做过的事。",
                "别数房间里的人。",
                "你第一次进来时，Verity已经认识你了。",
                "有些声音只在你停下时继续。"
        };
        return values[RANDOM.nextInt(values.length)];
    }

    private static void playBehindIfEnabled(Minecraft minecraft, ResourceLocation sound, float volume, float pitch) {
        if (ModConfig.HORROR_AUDIO.get()) playBehind(minecraft, sound, volume, pitch);
    }

    private static void playAboveIfEnabled(Minecraft minecraft, ResourceLocation sound, float volume, float pitch) {
        if (!ModConfig.HORROR_AUDIO.get()) return;
        Vec3 position = minecraft.player.position().add((RANDOM.nextDouble() - 0.5D) * 2.0D,
                4.0D + RANDOM.nextDouble() * 2.0D, (RANDOM.nextDouble() - 0.5D) * 2.0D);
        minecraft.getSoundManager().play(new SimpleSoundInstance(sound, SoundSource.HOSTILE,
                Mth.clamp(volume * audioScale(), 0.0F, 1.0F), pitch,
                SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.LINEAR,
                position.x, position.y, position.z, false));
    }

    private static void cancelAmbientPresentation() {
        anomalyVisualTicks = 0;
        anomalyTextTicks = 0;
        anomalyTextDuration = 0;
        anomalyCameraTicks = 0;
        anomalyText = "";
    }

    static float pressureFor(double distance, int state, int hunt, boolean targeted,
                             boolean looking, float healthRatio) {
        float proximity = Mth.clamp((float) ((96.0D - distance) / 88.0D), 0.0F, 1.0F);
        float result = 0.06F + proximity * 0.56F;
        if (hunt > 0) result += 0.13F;
        if (state == 1) result += 0.24F;
        if (targeted) result += 0.13F;
        if (looking && hunt > 0) result += 0.08F;
        if ((state == 1 || targeted) && healthRatio < 0.4F) result += (0.4F - healthRatio) * 0.45F;
        return Mth.clamp(result, 0.0F, 1.0F);
    }

    static int stageFor(double distance, int state, int hunt, boolean targeted,
                        boolean looking, float healthRatio) {
        if ((targeted && distance < 12.0D) || (state == 1 && distance < 7.0D)
                || ((state == 1 || targeted) && healthRatio < 0.25F)) return 4;
        if (state == 1 || targeted) return 3;
        if (hunt > 0 || looking || distance < 28.0D) return 2;
        return 1;
    }

    private static boolean isActiveDemon(Entity entity) {
        if (entity == null || !entity.isAlive()) return false;
        if (entity instanceof Mob mob && mob.isNoAi()) return false;
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        if (id != null && "verity".equals(id.getNamespace()) && id.getPath().contains("demon")) return true;
        String className = entity.getClass().getName().toLowerCase(java.util.Locale.ROOT);
        return className.contains("veritydemon") || className.contains("demonentity");
    }

    private static boolean isLookingAt(Minecraft minecraft, Entity entity) {
        return isLookingAt(minecraft, entity, 0.91D);
    }

    private static boolean isLookingAt(Minecraft minecraft, Entity entity, double threshold) {
        Vec3 target = entity.getBoundingBox().getCenter().subtract(minecraft.player.getEyePosition());
        if (target.lengthSqr() < 0.0001D) return true;
        double dot = minecraft.player.getViewVector(1.0F).dot(target.normalize());
        return dot > threshold && minecraft.player.hasLineOfSight(entity);
    }

    private static void onStageEscalated(Minecraft minecraft, int oldStage, int newStage) {
        blackoutTicks = Math.max(blackoutTicks, newStage >= 3 ? 12 : 7);
        glitchTicks = Math.max(glitchTicks, 12 + newStage * 3);
        if (newStage == 2) {
            if (ModConfig.HORROR_AUDIO.get()) playBehind(minecraft, BONE_SNAP, 0.55F, 0.82F);
        } else if (newStage == 3) {
            if (ModConfig.HORROR_AUDIO.get()) {
                playRelative(minecraft, WARDEN_NEARBY, 0.72F, 0.72F);
                playBehind(minecraft, BONE_BREAK, 0.7F, 0.76F);
            }
            if (oldStage <= 1 && ModConfig.HORROR_JUMPSCARES.get()) triggerJumpscare(minecraft, false);
        } else if (newStage >= 4) {
            triggerJumpscare(minecraft, true);
        }
    }

    private static void tickAudio(Minecraft minecraft) {
        if (!ModConfig.HORROR_AUDIO.get() || stage == 0 || pressure < 0.04F) return;
        float strength = effectStrength();
        if (stage >= 2 && clientTick >= nextHeartbeatTick) {
            playRelative(minecraft, WARDEN_HEARTBEAT, 0.28F + strength * 0.68F,
                    0.72F + (1.0F - strength) * 0.18F);
            long interval = Math.max(8L, Math.round(38.0D - strength * 29.0D));
            nextHeartbeatTick = clientTick + interval;
        }
        if (clientTick < nextAmbientTick) return;

        int choice = RANDOM.nextInt(stage >= 3 ? 7 : 5);
        switch (choice) {
            case 0 -> playBehind(minecraft, SKELETON_STEP, 0.35F + strength * 0.35F, 0.65F);
            case 1 -> playBehind(minecraft, WOODEN_DOOR, 0.28F + strength * 0.35F, 0.62F);
            case 2 -> playBehind(minecraft, BONE_SNAP, 0.38F + strength * 0.42F, 0.72F);
            case 3 -> playRelative(minecraft, CAVE_AMBIENCE, 0.24F + strength * 0.34F, 0.68F);
            case 4 -> playBehind(minecraft, SOUL_ESCAPE, 0.3F + strength * 0.28F, 0.55F);
            case 5 -> playBehind(minecraft, BONE_BREAK, 0.48F + strength * 0.42F, 0.68F);
            default -> playRelative(minecraft, WARDEN_NEARBY, 0.4F + strength * 0.4F, 0.64F);
        }
        int baseDelay = stage >= 3 ? 42 : stage == 2 ? 72 : 120;
        int randomDelay = stage >= 3 ? 75 : 150;
        nextAmbientTick = clientTick + baseDelay + RANDOM.nextInt(randomDelay + 1);
    }

    private static void tickScares(Minecraft minecraft) {
        if (stage == 0) return;
        float strength = effectStrength();
        if (ModConfig.HORROR_HALLUCINATIONS.get() && clientTick >= nextHallucinationTick) {
            float chance = stage >= 3 ? 0.72F : 0.32F + strength * 0.25F;
            if (RANDOM.nextFloat() < chance) {
                hallucinationDuration = 18 + RANDOM.nextInt(24);
                hallucinationTicks = hallucinationDuration;
                hallucinationOnRight = RANDOM.nextBoolean();
                glitchTicks = Math.max(glitchTicks, 10);
                if (ModConfig.HORROR_AUDIO.get()) {
                    playBehind(minecraft, RANDOM.nextBoolean() ? SKELETON_STEP : SOUL_ESCAPE,
                            0.32F + strength * 0.38F, 0.56F + RANDOM.nextFloat() * 0.18F);
                }
            }
            long cooldown = Math.max(100L, Math.round(360.0D - strength * 190.0D));
            nextHallucinationTick = clientTick + cooldown + RANDOM.nextInt(180);
        }
        if (stage >= 4 && ModConfig.HORROR_JUMPSCARES.get() && clientTick >= nextJumpscareTick
                && RANDOM.nextFloat() < 0.012F + strength * 0.008F) {
            triggerJumpscare(minecraft, true);
        }
        if (stage >= 3 && RANDOM.nextFloat() < 0.004F * strength) {
            blackoutTicks = Math.max(blackoutTicks, 5 + RANDOM.nextInt(8));
            glitchTicks = Math.max(glitchTicks, 8 + RANDOM.nextInt(12));
        }
    }

    private static void triggerJumpscare(Minecraft minecraft, boolean severe) {
        if (clientTick < nextJumpscareTick || !ModConfig.HORROR_JUMPSCARES.get()) return;
        jumpscareDuration = severe ? 18 : 12;
        jumpscareTicks = jumpscareDuration;
        blackoutTicks = Math.max(blackoutTicks, severe ? 20 : 12);
        glitchTicks = Math.max(glitchTicks, severe ? 30 : 20);
        nextJumpscareTick = clientTick + (severe ? 420L : 620L) + RANDOM.nextInt(260);
        if (ModConfig.HORROR_AUDIO.get()) {
            playRelative(minecraft, severe ? JUMPSCARE : SCULK_SHRIEK,
                    severe ? 0.92F : 0.68F, severe ? 0.78F : 0.66F);
        }
    }

    /** /verityjumpscare 测试用：绕过冷却，直接播一次严重突脸。 */
    private static void forceJumpscare() {
        jumpscareDuration = 18;
        jumpscareTicks = jumpscareDuration;
        blackoutTicks = Math.max(blackoutTicks, 20);
        glitchTicks = Math.max(glitchTicks, 30);
        if (ModConfig.HORROR_AUDIO.get()) {
            playRelative(Minecraft.getInstance(), JUMPSCARE, 0.92F, 0.78F);
        }
    }

    private static void playRelative(Minecraft minecraft, ResourceLocation sound, float volume, float pitch) {
        minecraft.getSoundManager().play(new SimpleSoundInstance(sound, SoundSource.HOSTILE,
                Mth.clamp(volume * audioScale(), 0.0F, 1.5F), Mth.clamp(pitch, 0.5F, 1.5F),
                SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE,
                0.0D, 0.0D, 0.0D, true));
    }

    private static void playBehind(Minecraft minecraft, ResourceLocation sound, float volume, float pitch) {
        Vec3 look = minecraft.player.getViewVector(1.0F);
        Vec3 horizontal = new Vec3(look.x, 0.0D, look.z);
        if (horizontal.lengthSqr() < 0.0001D) horizontal = new Vec3(0.0D, 0.0D, 1.0D);
        horizontal = horizontal.normalize();
        Vec3 side = new Vec3(-horizontal.z, 0.0D, horizontal.x)
                .scale((RANDOM.nextDouble() - 0.5D) * 5.0D);
        Vec3 position = minecraft.player.position().subtract(horizontal.scale(3.5D + RANDOM.nextDouble() * 4.0D))
                .add(side).add(0.0D, 0.3D + RANDOM.nextDouble() * 1.2D, 0.0D);
        minecraft.getSoundManager().play(new SimpleSoundInstance(sound, SoundSource.HOSTILE,
                Mth.clamp(volume * audioScale(), 0.0F, 1.5F), Mth.clamp(pitch, 0.5F, 1.5F),
                SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.LINEAR,
                position.x, position.y, position.z, false));
    }

    private static float audioScale() {
        return Mth.clamp((float) ModConfig.HORROR_INTENSITY.get().doubleValue(), 0.0F, 2.0F);
    }

    private static float effectStrength() {
        float intensity = Mth.clamp((float) ModConfig.HORROR_INTENSITY.get().doubleValue(), 0.0F, 2.0F);
        return Mth.clamp(pressure * intensity, 0.0F, 1.35F);
    }

    private static boolean enabled() {
        return ModConfig.HORROR_ENABLED.get() && ModConfig.HORROR_INTENSITY.get() > 0.0D;
    }

    public static void acceptServerEvent(int type, int durationTicks, float intensity, long seed, Entity target) {
        if (!ModConfig.HORROR_ENABLED.get()) return;
        serverEventType = Math.max(0, Math.min(8, type));
        serverEventTicks = Math.max(1, Math.min(120, durationTicks));
        serverEventIntensity = Mth.clamp(intensity, 0.0F, 1.0F);
        serverEventSeed = seed;
        if (serverEventType >= 4 && ModConfig.HORROR_HALLUCINATIONS.get()) {
            anomalyTexture = serverEventType == 5 ? SMILING_EVIL_TEXTURE : NOFACE_TEXTURE;
            anomalyVisualDuration = serverEventTicks;
            anomalyVisualTicks = serverEventTicks;
            anomalyOnRight = ((seed ^ (target == null ? 0L : target.getId())) & 1L) == 0L;
        }
        if (serverEventType >= 3 && ModConfig.HORROR_ANOMALY_TEXT.get()) {
            showAnomalyText(serverEventType == 3 ? "上面的脚步停了。" : "你确定Verity刚才在这里吗？",
                    serverEventTicks);
        }
        if (ModConfig.HORROR_AUDIO.get() && serverEventType <= 3) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null) playBehind(minecraft,
                    serverEventType == 2 ? BONE_SNAP : SKELETON_STEP,
                    0.25F + serverEventIntensity * 0.35F, 0.58F);
        }
    }

    private static void decayTimers() {
        if (serverEventTicks > 0) serverEventTicks--;
        if (serverEventTicks == 0) serverEventType = -1;
        if (blackoutTicks > 0) blackoutTicks--;
        if (glitchTicks > 0) glitchTicks--;
        if (hallucinationTicks > 0) hallucinationTicks--;
        if (jumpscareTicks > 0) jumpscareTicks--;
        if (anomalyVisualTicks > 0) anomalyVisualTicks--;
        if (anomalyTextTicks > 0) anomalyTextTicks--;
        if (anomalyCameraTicks > 0) anomalyCameraTicks--;
        if (anomalyTextTicks == 0) anomalyText = "";
    }

    private static void renderOverlay(GuiGraphics graphics, int width, int height, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!enabled() || !ModConfig.HORROR_VISUALS.get() || minecraft.player == null
                || minecraft.level == null || minecraft.screen != null || minecraft.options.hideGui) return;
        float strength = effectStrength();
        boolean ambientVisible = anomalyVisualTicks > 0 || anomalyTextTicks > 0;
        if (strength < 0.01F && blackoutTicks <= 0 && jumpscareTicks <= 0
                && !ambientVisible) return;

        float time = clientTick + partialTick;
        if (strength >= 0.01F) {
            int baseAlpha = Mth.clamp(Math.round(12.0F + strength * 52.0F), 0, 108);
            graphics.fill(0, 0, width, height, argb(baseAlpha / 3, 0, 0, 0));
            renderVignette(graphics, width, height, baseAlpha);
        }

        if (stage >= 3) {
            float pulse = 0.5F + 0.5F * Mth.sin(time * (stage >= 4 ? 0.42F : 0.24F));
            int redAlpha = Mth.clamp(Math.round((10.0F + strength * 35.0F) * pulse), 0, 72);
            renderBorder(graphics, width, height, Math.max(3, Math.round(5.0F + strength * 8.0F)),
                    argb(redAlpha, 92, 0, 0));
        }
        if (blackoutTicks > 0) {
            float progress = blackoutTicks / 15.0F;
            int alpha = Mth.clamp(Math.round(40.0F + 150.0F * Mth.sin(Mth.clamp(progress, 0.0F, 1.0F)
                    * (float) Math.PI)), 0, 205);
            graphics.fill(0, 0, width, height, argb(alpha, 0, 0, 0));
        }
        if (hallucinationTicks > 0 && ModConfig.HORROR_HALLUCINATIONS.get()) {
            renderHallucination(graphics, width, height);
        }
        if (anomalyVisualTicks > 0) renderAmbientVisual(graphics, width, height);
        if (anomalyTextTicks > 0 && !anomalyText.isBlank()) renderAnomalyText(graphics, width, height);
        if (glitchTicks > 0) renderGlitches(graphics, width, height);
        // 真正进入追击阶段才铺满屏红字；普通待机/闲聊不会出现
        if (stage >= 3 && ModConfig.HORROR_VISUALS.get()) {
            renderRedTextWall(graphics, width, height);
        }
        // 突脸必须最后画，否则会被满屏红字盖住
        if (jumpscareTicks > 0 && ModConfig.HORROR_JUMPSCARES.get()) {
            renderJumpscare(graphics, width, height);
        }
    }

    /**
     * 满屏红色威胁文字：网格铺满 + 大字压层 + 闪烁/扫描线/重影。
     * 不走随机散点，否则屏幕边缘会空、中间会挤成一团。
     */
    private static void renderRedTextWall(GuiGraphics graphics, int width, int height) {
        Minecraft minecraft = Minecraft.getInstance();
        int tick = (int) (clientTick / 2L);
        int jitterX = pseudoRandom(tick * 3 + 1, 5) - 2;
        int jitterY = pseudoRandom(tick * 7 + 2, 5) - 2;

        // 整屏红色底光：不盖死世界，只让画面泛红
        int pulse = pseudoRandom(tick, 18);
        graphics.fill(0, 0, width, height, argb(16 + pulse, 92, 0, 0));
        for (int y = 0; y < height; y += 7) {
            int lineAlpha = 8 + pseudoRandom(tick + y * 13, 10);
            graphics.fill(0, y, width, y + 1, argb(lineAlpha, 160, 0, 0));
        }

        // 主网格：4 列 × 6 行，保证边缘也有字
        int columns = 4;
        int rows = 6;
        int cellW = Math.max(1, width / columns);
        int cellH = Math.max(1, height / rows);
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < columns; col++) {
                int i = row * columns + col;
                int seed = tick + i * 137;
                String text = RED_TEXT_WALL[Math.abs(seed) % RED_TEXT_WALL.length];
                int x = col * cellW + pseudoRandom(seed * 31 + i, cellW + 40) - 20 + jitterX;
                int y = row * cellH + pseudoRandom(seed * 17 + i, cellH + 24) - 12 + jitterY;
                float angle = pseudoRandom(seed * 13 + i, 49) - 24.0F;
                float scale = 1.05F + pseudoRandom(seed * 7 + i, 110) / 100.0F;
                if (i % 5 == 0) scale += 0.55F;
                int alpha = 110 + pseudoRandom(seed * 11 + i, 130);
                int color = argb(alpha, 245, 12 + pseudoRandom(seed + i, 30), 12);
                drawRedLine(graphics, minecraft, text, x, y, angle, scale, color, i % 3 == 0);
            }
        }

        // 大字压层：少量 3~4 倍字，低透明度，制造“贴脸”感
        for (int i = 0; i < 7; i++) {
            int seed = tick / 2 + i * 211;
            String text = RED_TEXT_WALL[Math.abs(seed) % RED_TEXT_WALL.length];
            int x = pseudoRandom(seed * 19 + i, Math.max(1, width + 120)) - 60;
            int y = pseudoRandom(seed * 23 + i, Math.max(1, height + 80)) - 40;
            float angle = pseudoRandom(seed * 29 + i, 61) - 30.0F;
            float scale = 2.3F + pseudoRandom(seed * 37 + i, 150) / 100.0F;
            int alpha = 42 + pseudoRandom(seed * 41 + i, 55);
            drawRedLine(graphics, minecraft, text, x, y, angle, scale,
                    argb(alpha, 255, 8, 8), false);
        }
    }

    private static void drawRedLine(GuiGraphics graphics, Minecraft minecraft, String text,
                                    int x, int y, float angle, float scale, int color, boolean ghost) {
        graphics.pose().pushPose();
        graphics.pose().translate(x, y, 0.0F);
        graphics.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(angle));
        graphics.pose().scale(scale, scale, 1.0F);
        graphics.drawString(minecraft.font, text, 0, 0, color, false);
        if (ghost) {
            // 轻微错位重影：红色像素边缘发虚，像频闪/信号干扰
            graphics.drawString(minecraft.font, text, 2, -1, argb(70, 120, 0, 0), false);
            graphics.drawString(minecraft.font, text, -1, 2, argb(55, 255, 60, 60), false);
        }
        graphics.pose().popPose();
    }

    /** 开始 30 秒假退出；manual=true 允许用命令绕过“仅追击触发”的限制。 */
    private static void startFakeExit(boolean manual) {
        if (!enabled() || !ModConfig.HORROR_VISUALS.get()) return;
        if (!manual && !ModConfig.HORROR_JUMPSCARES.get()) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) return;
        if (minecraft.screen instanceof VerityFakeExitScreen) return;
        glitchTicks = Math.max(glitchTicks, 20);
        blackoutTicks = Math.max(blackoutTicks, 8);
        // 真正的原版暂停菜单：背景、标题、按钮都由 PauseScreen 自己画
        minecraft.setScreen(new VerityFakeExitScreen());
    }

    /** 右 Shift / /verityunlock 调用；关闭原版菜单，立即回到游戏。 */
    public static boolean forceUnlock() {
        Minecraft minecraft = Minecraft.getInstance();
        boolean active = minecraft.screen instanceof VerityFakeExitScreen;
        if (active) {
            minecraft.setScreen(null);
        }
        return active;
    }

    private static void renderAmbientVisual(GuiGraphics graphics, int width, int height) {
        int age = Math.max(0, anomalyVisualDuration - anomalyVisualTicks);
        float fade = Math.min(1.0F, anomalyVisualTicks / 6.0F) * Math.min(1.0F, age / 6.0F);
        int size = Math.max(58, Math.min(height / 3, 144));
        int x = anomalyOnRight ? width - size / 3 : -(size * 2) / 3;
        int y = Math.max(4, height / 2 - size / 2 + pseudoRandom(trackedVerityId * 29, 35) - 17);
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(0.18F, 0.18F, 0.2F, 0.1F + fade * 0.26F);
        graphics.blit(anomalyTexture, x, y, size, size, 0.0F, 0.0F, 1024, 1024, 1024, 1024);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
    }

    private static void renderAnomalyText(GuiGraphics graphics, int width, int height) {
        Minecraft minecraft = Minecraft.getInstance();
        int elapsed = Math.max(0, anomalyTextDuration - anomalyTextTicks);
        float fade = Math.min(1.0F, anomalyTextTicks / 9.0F) * Math.min(1.0F, elapsed / 8.0F);
        int alpha = Mth.clamp(Math.round(205.0F * fade), 0, 205);
        Component line = Component.literal(anomalyText);
        int x = width / 2;
        int y = height - Math.max(52, height / 7);
        graphics.drawCenteredString(minecraft.font, line, x + 1, y + 1, argb(alpha / 2, 0, 0, 0));
        graphics.drawCenteredString(minecraft.font, line, x, y, argb(alpha, 188, 188, 194));
    }

    private static void renderVignette(GuiGraphics graphics, int width, int height, int alpha) {
        int layers = 9;
        int horizontalStep = Math.max(3, width / 95);
        int verticalStep = Math.max(3, height / 72);
        for (int i = 0; i < layers; i++) {
            int layerAlpha = Math.max(2, alpha * (layers - i) / layers);
            int color = argb(layerAlpha, 0, 0, 0);
            int x = i * horizontalStep;
            int y = i * verticalStep;
            graphics.fill(x, 0, Math.min(width, x + horizontalStep), height, color);
            graphics.fill(Math.max(0, width - x - horizontalStep), 0, width - x, height, color);
            graphics.fill(0, y, width, Math.min(height, y + verticalStep), color);
            graphics.fill(0, Math.max(0, height - y - verticalStep), width, height - y, color);
        }
    }

    private static void renderBorder(GuiGraphics graphics, int width, int height, int thickness, int color) {
        graphics.fill(0, 0, width, thickness, color);
        graphics.fill(0, height - thickness, width, height, color);
        graphics.fill(0, thickness, thickness, height - thickness, color);
        graphics.fill(width - thickness, thickness, width, height - thickness, color);
    }

    private static void renderHallucination(GuiGraphics graphics, int width, int height) {
        int age = Math.max(0, hallucinationDuration - hallucinationTicks);
        float fade = Math.min(1.0F, hallucinationTicks / 8.0F) * Math.min(1.0F, age / 7.0F);
        int size = Math.max(54, Math.min(height / 3, 150));
        int x = hallucinationOnRight ? width - size / 2 : -size / 2;
        int y = Math.max(8, height / 2 - size / 2 + ((trackedDemonId * 17) & 31) - 16);
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(0.32F, 0.04F, 0.04F, 0.18F + fade * 0.38F);
        graphics.blit(NOFACE_TEXTURE, x, y, size, size, 0.0F, 0.0F, 1024, 1024, 1024, 1024);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.disableBlend();
    }

    private static void renderJumpscare(GuiGraphics graphics, int width, int height) {
        int duration = jumpscareDuration > 0 ? jumpscareDuration : 16;
        int elapsed = Math.max(0, duration - jumpscareTicks);
        // 先在 2 tick 内“冲”到最大，再缩回，形成贴脸感
        float punch = elapsed < 2 ? 1.26F : elapsed < 4 ? 1.12F : 1.0F;
        int jitterX = pseudoRandom(elapsed * 31 + trackedDemonId, 17) - 8;
        int jitterY = pseudoRandom(elapsed * 47 + trackedDemonId, 15) - 7;
        int drawWidth = Math.round(Math.min(width * 1.12F, height * 1.34F) * punch);
        int drawHeight = Math.round(drawWidth * 0.94F);
        int x = (width - drawWidth) / 2 + jitterX;
        int y = (height - drawHeight) / 2 + jitterY;

        graphics.fill(0, 0, width, height, argb(230, 0, 0, 0));

        // 优先渲染真正的 VerityDemonEntity 模型；失败才回退到脸部 UV 贴图
        boolean modelRendered = renderDemonJumpscareModel(graphics, width, height, punch, jitterX, jitterY, elapsed);
        if (!modelRendered) {
            // verity_demon.png 是实体 UV 皮肤图，不是完整正脸：
            // 只取其中带眼睛和嘴的脸部块，避免把整张身体贴图铺满屏幕。
            int u = 1230;
            int v = 650;
            int uw = 260;
            int vh = 280;

            RenderSystem.enableBlend();
            // 红/青色差边缘：让脸看起来在频闪
            RenderSystem.setShaderColor(0.95F, 0.03F, 0.03F, 0.45F);
            graphics.blit(VERITY_DEMON_TEXTURE, x - 8, y, drawWidth, drawHeight,
                    u, v, uw, vh, 2048, 2048);
            RenderSystem.setShaderColor(0.04F, 0.85F, 0.9F, 0.32F);
            graphics.blit(VERITY_DEMON_TEXTURE, x + 8, y, drawWidth, drawHeight,
                    u, v, uw, vh, 2048, 2048);

            boolean redFrame = (elapsed & 1) == 0;
            RenderSystem.setShaderColor(1.0F, redFrame ? 0.22F : 0.78F,
                    redFrame ? 0.22F : 0.78F, 1.0F);
            graphics.blit(VERITY_DEMON_TEXTURE, x, y, drawWidth, drawHeight,
                    u, v, uw, vh, 2048, 2048);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.disableBlend();
        }

        // 突脸期间再压几条红黑横条，不要让脸太“干净”
        for (int i = 0; i < 6; i++) {
            int seed = elapsed * 19 + i * 71;
            int barY = pseudoRandom(seed, Math.max(1, height));
            int barH = 1 + pseudoRandom(seed + 5, 7);
            int barX = pseudoRandom(seed + 11, Math.max(1, width / 4));
            int barColor = (i & 1) == 0
                    ? argb(55 + pseudoRandom(seed + 13, 80), 190, 0, 0)
                    : argb(40 + pseudoRandom(seed + 17, 45), 0, 0, 0);
            graphics.fill(barX, barY, Math.min(width, barX + width / 2 + pseudoRandom(seed + 23, width / 2)),
                    Math.min(height, barY + barH), barColor);
        }
    }

    /** 在 HUD 里直接渲染 VerityDemonEntity 模型；失败返回 false，走贴图回退。 */
    private static boolean renderDemonJumpscareModel(GuiGraphics graphics, int width, int height,
                                                     float punch, int jitterX, int jitterY, int elapsed) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) return false;
        try {
            if (jumpscareDemon == null || jumpscareDemonLevel != minecraft.level) {
                jumpscareDemon = ModEntities.VERITY_DEMON_ENTITY.get().create(minecraft.level);
                jumpscareDemonLevel = minecraft.level;
            }
            if (jumpscareDemon == null) return false;

            jumpscareDemon.setNoAi(true);
            jumpscareDemon.setSilent(true);
            jumpscareDemon.setInvulnerable(true);
            jumpscareDemon.setPos(0.0D, 0.0D, 0.0D);
            jumpscareDemon.setYRot(180.0F);
            jumpscareDemon.setXRot(0.0F);
            jumpscareDemon.yRotO = 180.0F;
            jumpscareDemon.yHeadRot = 180.0F;
            jumpscareDemon.yBodyRot = 180.0F;
            jumpscareDemon.tickCount = Math.max(1, (int) (clientTick + elapsed));

            // 模型背后的暗红光，避免黑衣服直接融进黑屏
            graphics.fill(width / 5, height / 6, width - width / 5, height - height / 6,
                    argb(38, 110, 0, 0));

            // InventoryScreen 的 y 是“脚底”锚点，不是身体中心。
            // 按实体碰撞高度算缩放，把脚放到屏幕下方，让头和上半身露出来。
            // VerityDemonRenderer 自带 1.5x 缩放，实际渲染高度约为碰撞高度的 1.5 倍
            float bodyHeight = Math.max(1.0F, jumpscareDemon.getBbHeight() * 1.5F);
            // 专门贴脸：模型放大约 1.6 倍，头部固定在屏幕上方，
            // 脚底随缩放同步下移，避免脸又被顶出屏幕。
            float focusHeight = height * 1.90F * punch;
            int scale = Math.round(focusHeight / bodyHeight);
            int feetY = Math.round(height * 0.10F + focusHeight) + jitterY;
            InventoryScreen.renderEntityInInventoryFollowsAngle(graphics,
                    width / 2 + jitterX, feetY, Math.max(8, scale),
                    180.0F, 0.0F, jumpscareDemon);
            graphics.flush();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void renderGlitches(GuiGraphics graphics, int width, int height) {
        int bars = 3 + Math.min(8, glitchTicks / 3);
        int seed = (int) (clientTick * 31L + trackedDemonId * 17L);
        for (int i = 0; i < bars; i++) {
            int y = pseudoRandom(seed + i * 97, Math.max(1, height));
            int barHeight = 1 + pseudoRandom(seed + i * 43, Math.max(2, height / 36));
            int inset = pseudoRandom(seed + i * 67, Math.max(1, width / 4));
            int alpha = 18 + pseudoRandom(seed + i * 13, 58);
            int color = (i & 1) == 0 ? argb(alpha, 120, 0, 0) : argb(alpha, 0, 0, 0);
            graphics.fill(inset, y, Math.max(inset + 1, width - inset / 2), Math.min(height, y + barHeight), color);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (!enabled() || !ModConfig.HORROR_CAMERA_SHAKE.get()
                || MmdPlaybackManager.get().getCurrentProjectId() != null) return;
        float time = (float) (clientTick + event.getPartialTick());
        if (pressure >= 0.08F) {
            float strength = effectStrength();
            float amplitude = strength * (stage >= 4 ? 1.8F : stage >= 3 ? 0.85F : 0.24F);
            if (jumpscareTicks > 0) amplitude += 2.4F;
            event.setYaw(event.getYaw() + Mth.sin(time * 0.47F) * amplitude);
            event.setPitch(event.getPitch() + Mth.sin(time * 0.61F + 1.7F) * amplitude * 0.62F);
            event.setRoll(event.getRoll() + Mth.sin(time * 0.29F + 0.8F) * amplitude * 0.48F);
        } else if (anomalyCameraTicks > 0) {
            float fade = Math.min(1.0F, anomalyCameraTicks / 10.0F);
            event.setYaw(event.getYaw() + Mth.sin(time * 0.08F) * 0.32F * fade);
            event.setRoll(event.getRoll() + Mth.sin(time * 0.11F + 1.4F) * 0.18F * fade);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onComputeFov(ViewportEvent.ComputeFov event) {
        if (!enabled() || !ModConfig.HORROR_CAMERA_SHAKE.get() || pressure < 0.08F
                || MmdPlaybackManager.get().getCurrentProjectId() != null) return;
        float strength = effectStrength();
        double pulse = Math.sin((clientTick + event.getPartialTick()) * (stage >= 3 ? 0.26D : 0.11D));
        event.setFOV(event.getFOV() - strength * (stage >= 3 ? 2.8D : 0.9D) + pulse * strength * 1.25D);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        if (!enabled() || !ModConfig.HORROR_VISUALS.get() || pressure < 0.1F || event.getType() != FogType.NONE) return;
        float strength = effectStrength();
        float far = Math.max(10.0F, event.getFarPlaneDistance() * (1.0F - Math.min(0.58F, strength * 0.4F)));
        event.setFarPlaneDistance(far);
        event.setNearPlaneDistance(Math.min(event.getNearPlaneDistance(), far * 0.08F));
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onComputeFogColor(ViewportEvent.ComputeFogColor event) {
        if (!enabled() || !ModConfig.HORROR_VISUALS.get() || pressure < 0.1F) return;
        float strength = effectStrength();
        float darkness = Math.min(0.55F, strength * 0.34F);
        event.setRed(Mth.clamp(event.getRed() * (1.0F - darkness) + strength * 0.035F, 0.0F, 1.0F));
        event.setGreen(Mth.clamp(event.getGreen() * (1.0F - darkness * 1.2F), 0.0F, 1.0F));
        event.setBlue(Mth.clamp(event.getBlue() * (1.0F - darkness * 1.1F), 0.0F, 1.0F));
    }

    public static void reset() {
        pressure = 0.0F;
        targetPressure = 0.0F;
        stage = 0;
        trackedDemonId = -1;
        huntPhase = 0;
        demonState = 0;
        demonDistance = Double.POSITIVE_INFINITY;
        blackoutTicks = 0;
        glitchTicks = 0;
        hallucinationTicks = 0;
        hallucinationDuration = 0;
        jumpscareTicks = 0;
        jumpscareDuration = 0;
        jumpscareDemon = null;
        jumpscareDemonLevel = null;
        nextHeartbeatTick = 0L;
        nextAmbientTick = 0L;
        nextHallucinationTick = 0L;
        nextJumpscareTick = 0L;
        ambientStableSince = -1L;
        ambientArmedAt = clientTick + 1200L;
        nextAmbientAnomalyTick = ambientArmedAt;
        trackedVerityId = -1;
        lookingAtVerityScans = 0;
        lookedAwayUntil = 0L;
        cancelAmbientPresentation();
        ANOMALY_HISTORY.clear();
        java.util.Arrays.fill(ANOMALY_TYPE_LAST, 0L);
        lastAnomalyType = -1;
    }

    private static DemonAccess resolveAccess(Class<?> type) {
        try {
            Method state = type.getMethod("getDemonState");
            Method hunt = type.getMethod("getHuntPhase");
            return new DemonAccess(state, hunt);
        } catch (ReflectiveOperationException error) {
            OllamaProxyMod.LOGGER.warn("无法读取 Verity 恶魔阶段，恐怖导演将仅按距离工作: {}", type.getName());
            return new DemonAccess(null, null);
        }
    }

    private static int pseudoRandom(int seed, int bound) {
        if (bound <= 1) return 0;
        int value = seed * 1103515245 + 12345;
        return Math.floorMod(value ^ value >>> 16, bound);
    }

    private static int argb(int alpha, int red, int green, int blue) {
        return (Mth.clamp(alpha, 0, 255) << 24)
                | (Mth.clamp(red, 0, 255) << 16)
                | (Mth.clamp(green, 0, 255) << 8)
                | Mth.clamp(blue, 0, 255);
    }

    private record DemonAccess(Method state, Method huntPhase) {
        private int readState(Entity entity) {
            return read(state, entity);
        }

        private int readHuntPhase(Entity entity) {
            return read(huntPhase, entity);
        }

        private static int read(Method method, Entity entity) {
            if (method == null) return 0;
            try {
                Object value = method.invoke(entity);
                return value instanceof Number number ? number.intValue() : 0;
            } catch (ReflectiveOperationException ignored) {
                return 0;
            }
        }
    }
}
