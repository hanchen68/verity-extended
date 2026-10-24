package com.hanchen.veritycustomapi.compat;

import com.hanchen.veritycustomapi.util.Silent;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.config.ModConfig;
import com.hanchen.veritycustomapi.network.VerityHoldNetwork;
import com.hanchen.veritycustomapi.ride.VerityEntityLookup;
import com.mojang.authlib.GameProfile;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.registries.ForgeRegistries;
import varmite.verity.entity.verity.VerityEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 《The Shooting Star》(shooting_star_demo) 的可选桥接：让 Verity 借用星之遥控器施放技能。
 * 第三方类全部走反射并带熔断，模组缺席时整条链路静默跳过。
 * 施法走模组自己的入口 magic.a#a(ServerPlayer, skill, tuning)，冷却、提示、动画都由它处理。
 */
public final class StellarRemoteCompat {
    private static final ResourceLocation REMOTE_ID =
            new ResourceLocation("shooting_star_demo", "stellar_remote");
    private static final String SKILL_PACKAGE = "cyou.rimuru.shootingstardemo.mc1201.magic.";
    private static final String CASTER_NAME = "Verity";
    /** 复算落点时射线的最小长度，避免近距离目标被截断成“没打中”。 */
    private static final double MIN_PREDICT_REACH = 64.0D;
    /** 施法后“举着”遥控器的时长（tick）。 */
    private static final int HOLD_TICKS = 60;

    private static volatile boolean resolved;
    private static volatile boolean failed;

    private static Class<?> skillClass;   // magic.d
    private static Class<?> groupClass;   // magic.e
    private static Class<?> tuningClass;  // magic.f

    private static Method skillId;        // magic.d#a() -> String
    private static Method skillName;      // magic.d#b() -> String
    private static Method allGroups;      // magic.e#a() -> List
    private static Method groupOf;        // magic.e#b(magic.d) -> magic.e
    private static Method tuningOf;       // magic.f#a(magic.d) -> magic.f
    private static Method cooldownOf;     // magic.a#b(ServerPlayer, magic.d) -> int
    private static Method castSkill;      // magic.a#a(ServerPlayer, magic.d, magic.f)
    private static Field groupSkills;     // magic.e#c -> List

    private StellarRemoteCompat() {
    }

    /** 一条可用技能：id 供模型调用，display 供聊天栏展示。 */
    public record SkillInfo(String id, String display) {
    }

    public static boolean available() {
        if (!resolve()) return false;
        return remoteItem() != null;
    }

    /** 遥控器上的全部技能（含附加包注册的哈雷、露娜）。 */
    public static List<SkillInfo> skills() {
        if (!resolve()) return List.of();
        List<SkillInfo> result = new ArrayList<>();
        try {
            Object groups = allGroups.invoke(null);
            if (!(groups instanceof List<?> list)) return result;
            for (Object group : list) {
                Object raw = groupSkills.get(group);
                if (!(raw instanceof List<?> entries)) continue;
                for (Object skill : entries) {
                    String id = asText(skillId.invoke(skill));
                    if (id == null || id.isEmpty()) continue;
                    String display = asText(skillName.invoke(skill));
                    result.add(new SkillInfo(id, display == null ? id : display));
                }
            }
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.warn("读取《The Shooting Star》技能列表失败", error);
        }
        return result;
    }

    public static String describeSkills() {
        List<SkillInfo> list = skills();
        if (list.isEmpty()) return "遥控器上现在没有可用技能。";
        StringBuilder builder = new StringBuilder("遥控器可用技能：");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) builder.append("；");
            SkillInfo info = list.get(i);
            builder.append(info.id()).append("（").append(info.display()).append("）");
        }
        return builder.toString();
    }

    /**
     * 让 Verity 施放一个技能。
     *
     * @param owner  请求施法的玩家（校验与提示都发给他）
     * @param query  技能 id 或关键字，也接受 list
     * @param rawAim 落点 "x y z"；look/空 = 玩家准星指向的位置
     */
    public static String cast(ServerPlayer owner, String query, String rawAim) {
        if (owner == null) return "现在读不到你的玩家数据，稍后再试。";
        String keyword = query == null ? "" : query.trim();
        if (keyword.isEmpty() || keyword.equalsIgnoreCase("list") || keyword.equals("列表")) {
            return describeSkills();
        }
        if (!available()) return "没检测到《The Shooting Star》，用不了星之遥控器。";
        if (!enabled()) return "星之遥控器适配在配置里关掉了（stellar_remote.enabled）。";
        if (requiresItem() && !ownerHasRemote(owner)) {
            return "你身上没有星之遥控器（shooting_star_demo:stellar_remote），Verity 借不到。";
        }

        VerityEntity verity = VerityEntityLookup.find(owner);
        if (verity == null) return "现在没找到你的 Verity，没办法让 Verity 出手。";
        if (!(verity.level() instanceof ServerLevel level)) return "现在不在服务端世界里，用不了。";

        Object skill = findSkill(keyword);
        if (skill == null) return "遥控器上没有「" + keyword + "」这个技能。" + describeSkills();
        if (!allowed(skill)) return "配置的技能白名单没放行「" + keyword + "」。";

        Vec3 aim = parseAim(rawAim);
        if (aim == null) aim = defaultAim(owner);

        try {
            FakePlayer caster = FakePlayerFactory.get(level, profile(verity));
            caster.setPos(verity.getX(), verity.getY(), verity.getZ());
            caster.setOldPosAndRot();
            Vec3 eye = caster.getEyePosition();
            aimCasterAt(caster, eye, aim);
            caster.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(remoteItem()));

            int remaining = (int) cooldownOf.invoke(null, caster, skill);
            if (remaining > 0) {
                return "「" + displayOf(skill) + "」还在充能，还剩 " + (remaining + 19) / 20 + " 秒。";
            }

            Vec3 landing = predictLanding(level, caster, eye, aim);
            castSkill.invoke(null, caster, skill, tuningOf.invoke(null, skill));
            verity.getLookControl().setLookAt(aim.x, aim.y, aim.z, 30.0F, 30.0F);
            verity.swing(InteractionHand.MAIN_HAND);
            showHold(verity, HOLD_TICKS);
            OllamaProxyMod.LOGGER.info("【星之遥控器】Verity 施放 {}：指向 {}，实际落点 {}",
                    displayOf(skill), fmt(aim), fmt(landing));
            double offset = landing.distanceTo(aim);
            String tail = offset > 1.5D
                    ? "，你指的是 " + fmt(aim) + "，Verity 这条视线先撞上了东西，实际落在 " + fmt(landing)
                    : "，落点 " + fmt(landing);
            return "Verity 抬起星之遥控器，" + displayOf(skill) + " 朝目标落了下去" + tail + "。";
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.warn("Verity 施放星之遥控器技能失败: {}", keyword, error);
            return "施放「" + keyword + "」时出问题了，看看日志里的【星之遥控器】。";
        }
    }

    /** 让 Verity“举着”遥控器若干 tick；纯客户端渲染，不影响判定。 */
    public static void showHold(VerityEntity verity, int ticks) {
        if (verity == null || !showInHand()) return;
        Item item = remoteItem();
        if (item == null) return;
        VerityHoldNetwork.broadcast(verity, new ItemStack(item), Math.max(1, ticks));
    }

    /** /verity-stellar hold 用：不放技能，只预览手持效果。 */
    public static String previewHold(ServerPlayer owner, int seconds) {
        if (owner == null) return "现在读不到你的玩家数据，稍后再试。";
        if (!available()) return "没检测到《The Shooting Star》，没有遥控器可举。";
        VerityEntity verity = VerityEntityLookup.find(owner);
        if (verity == null) return "现在没找到你的 Verity。";
        int ticks = Math.max(20, Math.min(seconds, 60) * 20);
        showHold(verity, ticks);
        return "让 Verity 举着星之遥控器 " + (ticks / 20) + " 秒（纯预览，不放技能）。";
    }

    /** 玩家准星命中点，没命中就沿视线 48 格。 */
    public static Vec3 defaultAim(ServerPlayer player) {
        HitResult hit = player.pick(64.0D, 0.0F, false);
        if (hit.getType() != HitResult.Type.MISS) return hit.getLocation();
        return player.getEyePosition().add(player.getLookAngle().scale(48.0D));
    }

    /** "x y z" -> 落点；look/空 返回 null 表示沿用准星。 */
    public static Vec3 parseAim(String raw) {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.isEmpty() || text.equalsIgnoreCase("look") || text.equals("准星")) return null;
        String[] parts = text.split("[,\\s]+");
        if (parts.length < 3) return null;
        try {
            return new Vec3(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), Double.parseDouble(parts[2]));
        } catch (NumberFormatException error) {
            return null;
        }
    }

    private static void aimCasterAt(FakePlayer caster, Vec3 eye, Vec3 target) {
        Vec3 dir = target.subtract(eye);
        if (dir.lengthSqr() < 1.0E-6D) return;
        double horizontal = Math.sqrt(dir.x * dir.x + dir.z * dir.z);
        float yaw = (float) (Mth.atan2(dir.z, dir.x) * 180.0D / Math.PI) - 90.0F;
        float pitch = (float) (-(Mth.atan2(dir.y, horizontal) * 180.0D / Math.PI));
        caster.setYRot(yaw);
        caster.setXRot(pitch);
        caster.setYHeadRot(yaw);
    }

    /**
     * 复算模组真正会用的落点：它把施法者的 eye + look 交给 player.pick，
     * 撞到方块就用命中点往回收 0.05 格，什么都没撞到就往视线尽头走。
     */
    private static Vec3 predictLanding(ServerLevel level, FakePlayer caster, Vec3 eye, Vec3 aim) {
        Vec3 dir = aim.subtract(eye);
        double distance = dir.length();
        if (distance < 1.0E-4D) return aim;
        Vec3 unit = dir.scale(1.0D / distance);
        double reach = Math.max(distance, MIN_PREDICT_REACH);
        Vec3 end = eye.add(unit.scale(reach));
        BlockHitResult hit = level.clip(new ClipContext(eye, end,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, caster));
        if (hit.getType() == HitResult.Type.MISS) return end;
        return hit.getLocation().subtract(unit.scale(0.05D));
    }

    private static String fmt(Vec3 vec) {
        return String.format(Locale.ROOT, "(%.1f, %.1f, %.1f)", vec.x, vec.y, vec.z);
    }

    private static GameProfile profile(VerityEntity verity) {
        UUID id = UUID.nameUUIDFromBytes(("verity_extended:stellar:" + verity.getUUID()).getBytes());
        return new GameProfile(id, CASTER_NAME);
    }

    private static Item remoteItem() {
        return ForgeRegistries.ITEMS.getValue(REMOTE_ID);
    }

    private static boolean ownerHasRemote(ServerPlayer player) {
        Item item = remoteItem();
        if (item == null) return false;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (player.getInventory().getItem(slot).is(item)) return true;
        }
        return false;
    }

    private static Object findSkill(String query) {
        List<SkillInfo> list = skills();
        String key = normalize(query);
        for (SkillInfo info : list) {
            if (normalize(info.id()).equals(key)) return resolveSkillObject(info.id());
        }
        for (SkillInfo info : list) {
            if (normalize(info.id()).contains(key) || normalize(info.display()).contains(key)) {
                return resolveSkillObject(info.id());
            }
        }
        String alias = aliasOf(key);
        if (alias != null) {
            for (SkillInfo info : list) {
                if (normalize(info.id()).equals(alias)) return resolveSkillObject(info.id());
            }
        }
        return null;
    }

    private static Object resolveSkillObject(String id) {
        try {
            Object groups = allGroups.invoke(null);
            if (!(groups instanceof List<?> list)) return null;
            for (Object group : list) {
                Object raw = groupSkills.get(group);
                if (!(raw instanceof List<?> entries)) continue;
                for (Object skill : entries) {
                    if (id.equals(asText(skillId.invoke(skill)))) return skill;
                }
            }
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.warn("定位《The Shooting Star》技能失败: {}", id, error);
        }
        return null;
    }

    private static String displayOf(Object skill) {
        try {
            String display = asText(skillName.invoke(skill));
            if (display != null && !display.isEmpty()) return display;
            return asText(skillId.invoke(skill));
        } catch (Throwable ignored) {
            return "技能";
        }
    }

    private static boolean allowed(Object skill) {
        String whitelist = "";
        try {
            whitelist = ModConfig.STELLAR_REMOTE_ALLOWED_SKILLS.get();
        } catch (Throwable ignored) { Silent.ignore("StellarRemoteCompat", ignored); }
        if (whitelist == null || whitelist.isBlank()) return true;
        String id;
        try {
            id = asText(skillId.invoke(skill));
        } catch (Throwable error) {
            return true;
        }
        if (id == null) return true;
        for (String entry : whitelist.split(",")) {
            if (normalize(entry).equals(normalize(id))) return true;
        }
        return false;
    }

    /**
     * 适配开关是否开启（配置项 {@code stellar_remote.enabled}）。
     *
     * <p>public 是给 {@code ai.ToolAvailabilityFilter} 用的：那个类要在请求转发前
     * 判断这个工具该不该出现在清单里。配置读不到时按「开着」处理——
     * 与 {@link #cast} 的兜底一致，避免配置未加载时把功能误关掉。
     */
    public static boolean enabled() {
        try {
            return ModConfig.STELLAR_REMOTE_ENABLED.get();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static boolean requiresItem() {
        try {
            return ModConfig.STELLAR_REMOTE_REQUIRE_ITEM.get();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static boolean showInHand() {
        try {
            return ModConfig.STELLAR_REMOTE_SHOW_IN_HAND.get();
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static String aliasOf(String key) {
        return switch (key) {
            case "railgun", "轨道炮", "轨道枪", "射击星", "星星" -> "the_shooting_star";
            case "gungnir", "冈格尼尔", "长枪", "神枪" -> "gungnir";
            case "sevenstars", "七星", "七星连珠" -> "seven_stars";
            case "tesseract", "超立方体", "四维" -> "tesseract";
            case "halley", "哈雷", "彗星" -> "halley";
            case "luna", "露娜", "月亮", "月球" -> "luna";
            default -> null;
        };
    }

    private static String normalize(String text) {
        if (text == null) return "";
        return text.toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-:·（）()]", "");
    }

    private static String asText(Object value) {
        return value instanceof String text ? text : null;
    }

    private static boolean resolve() {
        if (resolved) return !failed;
        synchronized (StellarRemoteCompat.class) {
            if (resolved) return !failed;
            try {
                ClassLoader loader = StellarRemoteCompat.class.getClassLoader();
                groupClass = Class.forName(SKILL_PACKAGE + "e", false, loader);
                skillClass = Class.forName(SKILL_PACKAGE + "d", false, loader);
                tuningClass = Class.forName(SKILL_PACKAGE + "f", false, loader);
                Class<?> casting = Class.forName(SKILL_PACKAGE + "a", false, loader);

                skillId = skillClass.getMethod("a");
                skillName = skillClass.getMethod("b");
                allGroups = groupClass.getDeclaredMethod("a");
                groupOf = groupClass.getDeclaredMethod("b", skillClass);
                tuningOf = tuningClass.getDeclaredMethod("a", skillClass);
                cooldownOf = casting.getDeclaredMethod("b", ServerPlayer.class, skillClass);
                castSkill = casting.getDeclaredMethod("a", ServerPlayer.class, skillClass, tuningClass);
                groupSkills = groupClass.getField("c");
                OllamaProxyMod.LOGGER.info("检测到《The Shooting Star》，已启用星之遥控器适配");
            } catch (ClassNotFoundException ignored) {
                failed = true;
            } catch (Throwable error) {
                failed = true;
                OllamaProxyMod.LOGGER.warn("初始化《The Shooting Star》适配失败", error);
            } finally {
                resolved = true;
            }
            return !failed;
        }
    }
}