package com.hanchen.veritycustomapi.plugin;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.concurrent.ThreadLocalRandom;

/** 条件求值。纯判断，不做任何副作用；抛异常一律当作「不满足」。 */
public final class PluginConditions {

    private PluginConditions() {
    }

    /** 全部条件都满足才返回 true；没有条件视为满足。 */
    public static boolean allPass(PluginDefinition plugin, PluginContext context) {
        for (PluginDefinition.Condition condition : plugin.conditions) {
            if (!test(condition, context)) {
                return false;
            }
        }
        return true;
    }

    static boolean test(PluginDefinition.Condition condition, PluginContext context) {
        try {
            return switch (condition.type) {
                case "health_below" -> context.player.getHealth() < context.intParam(condition, "value", 0);
                case "health_above" -> context.player.getHealth() > context.intParam(condition, "value", 0);
                case "hunger_below" ->
                        context.player.getFoodData().getFoodLevel() < context.intParam(condition, "value", 0);
                case "has_item" -> countItem(context, context.textParam(condition, "item"))
                        >= context.intParam(condition, "count", 1);
                case "missing_item" -> countItem(context, context.textParam(condition, "item"))
                        < context.intParam(condition, "count", 1);
                case "inventory_full" -> context.player.getInventory().getFreeSlot() < 0;
                case "dimension" -> context.player.level().dimension().location().toString()
                        .equals(context.textParam(condition, "id"));
                case "time_of_day" -> inTimeRange(
                        context.player.level().getDayTime() % 24000L,
                        context.intParam(condition, "from", 0),
                        context.intParam(condition, "to", 0));
                case "cooldown_ready" -> System.currentTimeMillis() - context.lastFiredAt
                        >= context.intParam(condition, "seconds", 1) * 1000L;
                case "random_chance" ->
                        ThreadLocalRandom.current().nextInt(100) < context.intParam(condition, "percent", 0);
                case "var_at_least" -> context.var(context.textParam(condition, "name"),
                        com.hanchen.veritycustomapi.plugin.PluginVocabulary.isSharedScope(
                                condition.params.get("scope")))
                        >= context.intParam(condition, "value", 0);
                case "nearby_entity" -> nearbyCount(context, context.textParam(condition, "entity"),
                        context.intParam(condition, "radius", 8)) >= context.intParam(condition, "count", 1);
                case "level_at_least" ->
                        context.player.experienceLevel >= context.intParam(condition, "value", 0);
                case "exp_at_least" ->
                        context.player.totalExperience >= context.intParam(condition, "value", 0);
                case "sneaking" -> context.player.isShiftKeyDown();
                case "on_ground" -> context.player.onGround();
                case "weather" -> matchesWeather(context, context.textParam(condition, "kind"));
                default -> false;
            };
        } catch (Throwable ignored) {
            // 条件本身出问题（比如物品名解析不了）时按不满足处理，绝不把异常抛给 tick 线程
            return false;
        }
    }

    /** 时间区间支持跨零点，例如 from=22000, to=2000。 */
    static boolean inTimeRange(long now, long from, long to) {
        if (from == to) {
            return true;
        }
        if (from < to) {
            return now >= from && now <= to;
        }
        return now >= from || now <= to;
    }

    /** 统计背包里某物品的数量（含副手）。 */
    static int countItem(PluginContext context, String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return 0;
        }
        int total = 0;
        var inventory = context.player.getInventory();
        for (ItemStack stack : inventory.items) {
            if (matches(stack, itemId)) {
                total += stack.getCount();
            }
        }
        for (ItemStack stack : inventory.offhand) {
            if (matches(stack, itemId)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** 统计半径内指定类型的实体数量。 */
    static int nearbyCount(PluginContext context, String typeId, int radius) {
        if (typeId == null || typeId.isBlank() || radius <= 0) {
            return 0;
        }
        int found = 0;
        for (net.minecraft.world.entity.Entity entity : context.player.level().getEntities(
                context.player, context.player.getBoundingBox().inflate(radius))) {
            if (entity == context.player) {
                continue;
            }
            if (net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())
                    .toString().equals(typeId)) {
                found++;
            }
        }
        return found;
    }

    /** clear / rain / thunder 三种天气。 */
    static boolean matchesWeather(PluginContext context, String type) {
        if (type == null || type.isBlank()) {
            return true;
        }
        String normalized = type.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "clear" -> !context.player.level().isRaining();
            case "rain" -> context.player.level().isRaining() && !context.player.level().isThundering();
            case "thunder" -> context.player.level().isThundering();
            default -> false;
        };
    }

    private static boolean matches(ItemStack stack, String itemId) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId);
    }
}
