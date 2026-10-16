package com.hanchen.veritycustomapi.plugin;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.compat.OriginalVerityChatBridge;
import com.hanchen.veritycustomapi.config.ModConfig;
import com.hanchen.veritycustomapi.ride.VerityEntityLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import varmite.verity.entity.verity.VerityEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 动作词汇表的实现。
 *
 * 每个动作都是模组里一段固定的逻辑，插件只能提供参数、改不了行为——可执行的东西永远在模组里，
 * 插件只是「什么时候、满足什么、做什么」的声明。
 *
 * 新增动作：在 {@link PluginVocabulary#ACTIONS} 加规格，然后在这里 {@code register} 一次。
 */
public final class PluginActions {

    private static final Map<String, BiConsumer<PluginContext, PluginDefinition.Action>> EXECUTORS =
            new LinkedHashMap<>();

    private PluginActions() {
    }

    static {
        register("message", PluginActions::message);
        register("title", PluginActions::title);
        register("sound", PluginActions::sound);
        register("speak", PluginActions::speak);
        register("give_item", PluginActions::giveItem);
        register("take_item", PluginActions::takeItem);
        register("sort_inventory", PluginActions::sortInventory);
        register("set_block", PluginActions::setBlock);
        register("particle", PluginActions::particle);
        register("talk_to_verity", PluginActions::talkToVerity);
        register("broadcast", PluginActions::broadcast);
        register("heal", PluginActions::heal);
        register("feed", PluginActions::feed);
        register("effect", PluginActions::effect);
        register("set_var", (context, action) ->
                context.setVar(context.textParam(action, "name"), context.intParam(action, "value", 0),
                        PluginVocabulary.isSharedScope(action.params.get("scope"))));
        register("add_var", (context, action) ->
                context.addVar(context.textParam(action, "name"), context.intParam(action, "delta", 0),
                        PluginVocabulary.isSharedScope(action.params.get("scope"))));
    }

    private static void register(String type, BiConsumer<PluginContext, PluginDefinition.Action> executor) {
        EXECUTORS.put(type, executor);
    }

    /** 动作实现是否与能力清单一一对应；供自检使用。 */
    public static boolean isImplemented(String type) {
        return EXECUTORS.containsKey(type);
    }

    /** 已实现的动作类型集合。 */
    public static java.util.Set<String> implementedTypes() {
        return java.util.Set.copyOf(EXECUTORS.keySet());
    }

    /**
     * 执行一个动作。单个动作失败只记日志、不中断后续动作，更不会把异常抛给 tick 线程。
     *
     * @return 是否执行成功
     */
    public static boolean run(PluginContext context, PluginDefinition.Action action) {
        BiConsumer<PluginContext, PluginDefinition.Action> executor = EXECUTORS.get(action.type);
        if (executor == null) {
            OllamaProxyMod.LOGGER.warn("插件 {} 引用了未实现的动作 {}", context.plugin.id, action.type);
            return false;
        }
        try {
            executor.accept(context, action);
            return true;
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.warn("插件 {} 的动作 {} 执行失败: {}",
                    context.plugin.id, action.type, error.toString());
            return false;
        }
    }

    private static void message(PluginContext context, PluginDefinition.Action action) {
        context.player.displayClientMessage(Component.literal(context.textParam(action, "text")), false);
    }

    private static void title(PluginContext context, PluginDefinition.Action action) {
        context.player.connection.send(new ClientboundSetTitlesAnimationPacket(5, 40, 10));
        String subtitle = context.textParam(action, "subtitle");
        if (!subtitle.isEmpty()) {
            context.player.connection.send(new ClientboundSetSubtitleTextPacket(Component.literal(subtitle)));
        }
        context.player.connection.send(
                new ClientboundSetTitleTextPacket(Component.literal(context.textParam(action, "text"))));
    }

    private static void sound(PluginContext context, PluginDefinition.Action action) {
        SoundEvent event = BuiltInRegistries.SOUND_EVENT.get(resource(context.textParam(action, "id")));
        if (event == null) {
            return;
        }
        context.player.level().playSound(null, context.player.blockPosition(), event,
                SoundSource.PLAYERS, 1.0F, 1.0F);
    }

    /**
     * 让 Verity 念一句。
     *
     * 刻意走聊天栏而不是直接调语音：聊天栏是扩展与客户端语音之间唯一稳定的入口，
     * 从这里出去的内容会经过同一套去重与语言处理，不会绕开已有链路。
     */
    private static void speak(PluginContext context, PluginDefinition.Action action) {
        String text = context.textParam(action, "text");
        if (text.isBlank()) {
            return;
        }
        String name = verityName(context);
        context.player.sendSystemMessage(Component.literal("<" + name + "> " + text));
    }

    private static void giveItem(PluginContext context, PluginDefinition.Action action) {
        Item item = BuiltInRegistries.ITEM.get(resource(context.textParam(action, "item")));
        int count = Math.max(1, context.intParam(action, "count", 1));
        if (item == net.minecraft.world.item.Items.AIR) {
            return;
        }
        ItemStack stack = new ItemStack(item, count);
        if (!context.player.getInventory().add(stack)) {
            context.player.drop(stack, false);
        }
    }

    private static void takeItem(PluginContext context, PluginDefinition.Action action) {
        String itemId = context.textParam(action, "item");
        int remaining = Math.max(1, context.intParam(action, "count", 1));
        List<ItemStack> slots = new ArrayList<>(context.player.getInventory().items);
        for (ItemStack stack : slots) {
            if (remaining <= 0) {
                break;
            }
            if (stack.isEmpty()) {
                continue;
            }
            if (!BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId)) {
                continue;
            }
            int take = Math.min(remaining, stack.getCount());
            stack.shrink(take);
            remaining -= take;
        }
    }

    /** 把主背包按「物品名 + 数量」排序，便于一眼看清；盔甲与副手不动。 */
    private static void sortInventory(PluginContext context, PluginDefinition.Action action) {
        var inventory = context.player.getInventory();
        List<ItemStack> stacks = new ArrayList<>();
        for (ItemStack stack : inventory.items) {
            if (!stack.isEmpty()) {
                stacks.add(stack.copy());
            }
        }
        stacks.sort(Comparator
                .comparing((ItemStack stack) -> BuiltInRegistries.ITEM.getKey(stack.getItem()).toString())
                .thenComparingInt(ItemStack::getCount));
        for (int i = 0; i < inventory.items.size(); i++) {
            inventory.items.set(i, i < stacks.size() ? stacks.get(i) : ItemStack.EMPTY);
        }
        inventory.setChanged();
    }

    private static void setBlock(PluginContext context, PluginDefinition.Action action) {
        if (!Boolean.TRUE.equals(ModConfig.PLUGIN_ALLOW_BLOCK_EDIT.get())) {
            return;
        }
        Block block = BuiltInRegistries.BLOCK.get(resource(context.textParam(action, "block")));
        if (block == net.minecraft.world.level.block.Blocks.AIR) {
            return;
        }
        BlockPos target = context.player.blockPosition()
                .offset(context.intParam(action, "x", 0),
                        context.intParam(action, "y", 0),
                        context.intParam(action, "z", 0));
        // 只允许在玩家附近改方块，且不做任何区块加载/批量操作
        if (context.player.level().isLoaded(target)
                && context.player.level().getBlockState(target).isAir()) {
            context.player.level().setBlockAndUpdate(target, block.defaultBlockState());
        }
    }

    private static void particle(PluginContext context, PluginDefinition.Action action) {
        ParticleType<?> type = BuiltInRegistries.PARTICLE_TYPE.get(resource(context.textParam(action, "id")));
        if (!(type instanceof SimpleParticleType simple)) {
            return;
        }
        if (!(context.player.level() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }
        int count = Math.max(1, context.intParam(action, "count", 10));
        level.sendParticles(simple, context.player.getX(), context.player.getY() + 1.0D, context.player.getZ(),
                count, 0.4D, 0.5D, 0.4D, 0.02D);
    }

    private static void talkToVerity(PluginContext context, PluginDefinition.Action action) {
        VerityEntity verity = VerityEntityLookup.find(context.player);
        if (verity == null || !verity.isAlive()) {
            return;
        }
        OriginalVerityChatBridge.queryAiAsync(verity, context.player, context.textParam(action, "text"));
    }

    /** 公屏消息：所有人可见，请配合 cooldown_ready 使用，避免刷屏。 */
    private static void broadcast(PluginContext context, PluginDefinition.Action action) {
        if (context.server == null) {
            return;
        }
        context.server.getPlayerList().broadcastSystemMessage(
                Component.literal(context.textParam(action, "text")), false);
    }

    private static void heal(PluginContext context, PluginDefinition.Action action) {
        context.player.heal(Math.max(1, context.intParam(action, "amount", 1)));
    }

    private static void feed(PluginContext context, PluginDefinition.Action action) {
        context.player.getFoodData().eat(Math.max(1, context.intParam(action, "amount", 1)), 0.6F);
    }

    private static void effect(PluginContext context, PluginDefinition.Action action) {
        net.minecraft.world.effect.MobEffect mobEffect =
                BuiltInRegistries.MOB_EFFECT.get(resource(context.textParam(action, "id")));
        if (mobEffect == null) {
            return;
        }
        int seconds = Math.max(1, context.intParam(action, "seconds", 5));
        int amplifier = Math.max(0, context.intParam(action, "amplifier", 0));
        context.player.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                mobEffect, seconds * 20, amplifier));
    }

    private static String verityName(PluginContext context) {
        try {
            VerityEntity verity = VerityEntityLookup.find(context.player);
            if (verity != null) {
                String name = verity.getName().getString();
                if (name != null && !name.isBlank()) {
                    return name;
                }
            }
        } catch (Throwable ignored) {
        }
        return "Verity";
    }

    private static ResourceLocation resource(String id) {
        ResourceLocation parsed = ResourceLocation.tryParse(id);
        return parsed == null ? ResourceLocation.withDefaultNamespace("air") : parsed;
    }
}
