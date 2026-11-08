package com.hanchen.veritycustomapi.body;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import varmite.verity.entity.verity.VerityEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 通用合成：直接读原版配方表。
 *
 * <p><b>关键教训（2026-11-06 自测实录）</b>：同一件物品往往有多条配方——
 * 原版一条，整合包里其它模组还会加「竖木板→木板」「竹块→木棍」这类替代配方。
 * 之前取「第一条匹配」，结果拿到需要 {@code vertical_oak_planks}、{@code bamboo_block}
 * 的模组配方，手里有 8 个原木却合不出木板。
 *
 * <p>现在的做法：<b>枚举所有能产出该物品的配方，按当前背包逐条试算，选第一条真做得出的</b>；
 * 都不行才报缺料，并给出「缺得最少」的那条。
 */
public final class VerityCrafting {

    private VerityCrafting() {
    }

    /** 合成若干次（最多 16），返回结果文本。 */
    public static String craft(VerityEntity verity, String itemId, int times) {
        if (!(verity.level() instanceof ServerLevel level)) return "失败：不在服务端";
        ResourceLocation id = itemId.contains(":") ? ResourceLocation.tryParse(itemId)
                : ResourceLocation.tryParse("minecraft:" + itemId);
        if (id == null) return "失败：物品 ID 不合法";

        int count = Math.max(1, Math.min(16, times));
        boolean table = tableNearby(verity);

        List<CraftingRecipe> candidates = new ArrayList<>();
        boolean blockedByTable = false;
        for (CraftingRecipe recipe : level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            if (!produces(recipe, level, id)) continue;
            if (!recipe.canCraftInDimensions(2, 2) && !table) {
                blockedByTable = true;
                continue;
            }
            candidates.add(recipe);
        }
        if (candidates.isEmpty()) {
            if (blockedByTable) return "失败：合成 " + id + " 需要工作台，附近 4 格内没有";
            return "失败：没有找到能做出 " + id + " 的配方";
        }

        Map<Item, Integer> pool = pool(verity);
        CraftingRecipe chosen = null;
        for (CraftingRecipe candidate : candidates) {
            if (consumeOnce(candidate, pool, true)) {
                chosen = candidate;
                break;
            }
        }
        if (chosen == null) return "失败：材料不足，" + describeMissing(bestCandidate(candidates, pool), pool);

        // 先试算能做几次，再真扣，避免扣一半失败
        int possible = 0;
        Map<Item, Integer> simulated = new LinkedHashMap<>(pool);
        for (int attempt = 0; attempt < count; attempt++) {
            if (!consumeOnce(chosen, simulated, false)) break;
            possible++;
        }
        if (possible == 0) return "失败：材料不足，" + describeMissing(chosen, pool);

        Map<Item, Integer> real = new LinkedHashMap<>(pool);
        for (int i = 0; i < possible; i++) consumeOnce(chosen, real, false);
        for (var entry : real.entrySet()) {
            int spent = pool.getOrDefault(entry.getKey(), 0) - entry.getValue();
            if (spent > 0) VerityInventory.extract(verity,
                    BuiltInRegistries.ITEM.getKey(entry.getKey()).toString(), spent);
        }

        ItemStack result = chosen.getResultItem(level.registryAccess()).copy();
        result.setCount(result.getCount() * possible);
        ItemStack leftover = VerityInventory.insert(verity, result);
        if (!leftover.isEmpty()) {
            net.minecraft.world.level.block.Block.popResource(level, verity.blockPosition(), leftover);
            return "合成 " + result.getCount() + " 个 " + id + "，背包满了，"
                    + leftover.getCount() + " 个掉在脚下";
        }
        return "合成 " + result.getCount() + " 个 " + id + "（配方 " + chosen.getId().getPath() + "）；背包：" + VerityInventory.summary(verity);
    }

    /** 该配方产出的是不是目标物品。 */
    private static boolean produces(CraftingRecipe recipe, ServerLevel level, ResourceLocation id) {
        ItemStack result = recipe.getResultItem(level.registryAccess());
        if (result.isEmpty()) return false;
        return BuiltInRegistries.ITEM.getKey(result.getItem()).equals(id);
    }

    /** 缺得最少的候选，用来生成更有用的报错。 */
    private static CraftingRecipe bestCandidate(List<CraftingRecipe> candidates, Map<Item, Integer> pool) {
        CraftingRecipe best = candidates.get(0);
        int fewest = Integer.MAX_VALUE;
        for (CraftingRecipe candidate : candidates) {
            int missing = missingCount(candidate, pool);
            if (missing < fewest) {
                fewest = missing;
                best = candidate;
            }
        }
        return best;
    }

    private static int missingCount(CraftingRecipe recipe, Map<Item, Integer> pool) {
        Map<Item, Integer> working = new LinkedHashMap<>(pool);
        int missing = 0;
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) continue;
            boolean matched = false;
            for (var entry : working.entrySet()) {
                if (entry.getValue() <= 0) continue;
                if (!ingredient.test(new ItemStack(entry.getKey()))) continue;
                working.put(entry.getKey(), entry.getValue() - 1);
                matched = true;
                break;
            }
            if (!matched) missing++;
        }
        return missing;
    }

    /** 背包按物品类型汇总（合成材料不看 NBT）。 */
    private static Map<Item, Integer> pool(VerityEntity verity) {
        Map<Item, Integer> pool = new LinkedHashMap<>();
        for (ItemStack slot : VerityInventory.load(verity)) {
            if (slot.isEmpty()) continue;
            pool.merge(slot.getItem(), slot.getCount(), Integer::sum);
        }
        return pool;
    }

    /** 尝试扣一份料；dryRun 时只改内部副本。 */
    private static boolean consumeOnce(CraftingRecipe recipe, Map<Item, Integer> pool, boolean dryRun) {
        Map<Item, Integer> working = dryRun ? new LinkedHashMap<>(pool) : pool;
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) continue;
            boolean matched = false;
            for (var entry : working.entrySet()) {
                if (entry.getValue() <= 0) continue;
                if (!ingredient.test(new ItemStack(entry.getKey()))) continue;
                working.put(entry.getKey(), entry.getValue() - 1);
                matched = true;
                break;
            }
            if (!matched) return false;
        }
        return true;
    }

    /** 报错时去重，避免「缺少 oak_planks、oak_planks、oak_planks」。 */
    private static String describeMissing(CraftingRecipe recipe, Map<Item, Integer> pool) {
        Set<String> missing = new LinkedHashSet<>();
        Map<Item, Integer> working = new LinkedHashMap<>(pool);
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) continue;
            boolean matched = false;
            for (var entry : working.entrySet()) {
                if (entry.getValue() <= 0) continue;
                if (!ingredient.test(new ItemStack(entry.getKey()))) continue;
                working.put(entry.getKey(), entry.getValue() - 1);
                matched = true;
                break;
            }
            if (matched) continue;
            ItemStack[] options = ingredient.getItems();
            if (options.length > 0)
                missing.add(BuiltInRegistries.ITEM.getKey(options[0].getItem()).getPath());
        }
        return missing.isEmpty() ? "数量不够" : "缺少 " + String.join("、", missing);
    }

    /** 3x3 配方要求附近真的有工作台。 */
    public static boolean tableNearby(VerityEntity verity) {
        var level = verity.level();
        var origin = verity.blockPosition();
        for (var pos : net.minecraft.core.BlockPos.betweenClosed(
                origin.offset(-4, -2, -4), origin.offset(4, 2, 4))) {
            if (!level.hasChunkAt(pos)) continue;
            if (level.getBlockState(pos).is(Blocks.CRAFTING_TABLE)) return true;
        }
        return false;
    }

    /** 给模型看的「现在能做什么」：只列真的能做的。 */
    public static String availableFor(VerityEntity verity) {
        if (!(verity.level() instanceof ServerLevel level)) return "不在服务端";
        Map<Item, Integer> pool = pool(verity);
        boolean table = tableNearby(verity);
        List<String> craftable = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (CraftingRecipe recipe : level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)) {
            if (craftable.size() >= 24) break;
            if (!table && !recipe.canCraftInDimensions(2, 2)) continue;
            ItemStack result = recipe.getResultItem(level.registryAccess());
            if (result.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(result.getItem()).getPath();
            if (!seen.add(id)) continue;
            if (!consumeOnce(recipe, pool, true)) continue;
            craftable.add(id + (result.getCount() > 1 ? "×" + result.getCount() : ""));
        }
        return craftable.isEmpty() ? "现在什么都做不了（先挖点木头）" : String.join("、", craftable);
    }
}
