package com.hanchen.veritycustomapi.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端侧的游戏状态快照，主动感知的提示词和代理层背包上下文共用；
 * 把维度、坐标、背包这些原来标"未知"的信息补上。
 */
public final class PlayerStateSnapshot {
    private PlayerStateSnapshot() {
    }

    /** 一行式的当前状态摘要；不在世界内时返回说明文字。 */
    public static String gameStateSummary() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return "玩家不在世界内";
        }
        return "维度=" + describeDimension(minecraft)
                + "，坐标=" + position(player)
                + "，时间=" + (isDay(minecraft) ? "白天" : "夜晚")
                + "，天气=" + describeWeather(minecraft)
                + "，生命值=" + player.getHealth() + "/" + player.getMaxHealth()
                + "，饥饿值=" + player.getFoodData().getFoodLevel() + "/20"
                + "，经验等级=" + player.experienceLevel
                + "，手持=" + heldItem(player);
    }

    /** 背包摘要：按数量从多到少列出前 {@code maxTypes} 种物品。 */
    public static String inventorySummary(int maxTypes) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return "未知";
        }
        try {
            Map<String, Integer> counts = new LinkedHashMap<>();
            Inventory inventory = player.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                accumulate(counts, inventory.getItem(slot));
            }
            if (counts.isEmpty()) {
                return "空";
            }
            List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
            sorted.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())
                    .thenComparing(Map.Entry::getKey));
            int limit = Math.min(Math.max(1, maxTypes), sorted.size());
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < limit; i++) {
                if (builder.length() > 0) {
                    builder.append('、');
                }
                builder.append(sorted.get(i).getKey()).append('×').append(sorted.get(i).getValue());
            }
            if (sorted.size() > limit) {
                builder.append("，等 ").append(sorted.size()).append(" 种");
            }
            return builder.toString();
        } catch (Throwable ignored) {
            return "未知";
        }
    }

    /** 手持物品名，空手时返回「空手」。 */
    public static String heldItem(LocalPlayer player) {
        try {
            ItemStack stack = player.getMainHandItem();
            return stack.isEmpty() ? "空手" : stack.getHoverName().getString();
        } catch (Throwable ignored) {
            return "未知";
        }
    }

    public static String describeDimension(Minecraft minecraft) {
        String id = dimensionId(minecraft);
        if (id.isEmpty()) {
            return "未知维度";
        }
        return switch (id) {
            case "minecraft:overworld" -> "主世界";
            case "minecraft:the_nether" -> "下界";
            case "minecraft:the_end" -> "末地";
            default -> id;
        };
    }

    public static String dimensionId(Minecraft minecraft) {
        try {
            return minecraft.level == null ? "" : minecraft.level.dimension().location().toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static boolean isDay(Minecraft minecraft) {
        try {
            return minecraft.level == null || minecraft.level.getDayTime() % 24000L < 13000L;
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static String describeWeather(Minecraft minecraft) {
        try {
            if (minecraft.level == null) {
                return "晴";
            }
            if (minecraft.level.isThundering()) {
                return "雷暴";
            }
            return minecraft.level.isRaining() ? "下雨" : "晴";
        } catch (Throwable ignored) {
            return "未知";
        }
    }

    private static String position(LocalPlayer player) {
        try {
            BlockPos pos = player.blockPosition();
            return pos.getX() + ", " + pos.getY() + ", " + pos.getZ();
        } catch (Throwable ignored) {
            return "未知";
        }
    }

    private static void accumulate(Map<String, Integer> counts, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        try {
            String name = stack.getHoverName().getString();
            if (name.isBlank()) {
                return;
            }
            counts.merge(name, stack.getCount(), Integer::sum);
        } catch (Throwable ignored) {
        }
    }
}
