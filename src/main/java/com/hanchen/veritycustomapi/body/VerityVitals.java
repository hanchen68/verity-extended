package com.hanchen.veritycustomapi.body;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import varmite.verity.entity.verity.VerityEntity;

/**
 * 她的饥饿与进食。
 *
 * <p>生物没有饥饿值，但「伙伴一起生存」需要这个：会饿、会吃、没吃的会说出来。
 *
 * <p>节奏刻意放慢——每 60 秒掉 1 点，满值 20，约 20 分钟才见底；
 * 饿到 6 以下自动找背包里的食物吃，吃饱继续干活。不做「饿死」，
 * 只做「饿了会停下吃东西」，避免把伙伴变成负担。
 */
public final class VerityVitals {

    private static final String KEY_HUNGER = "verity_ext_hunger";
    private static final String KEY_LAST_DECAY = "verity_ext_hunger_at";
    public static final int MAX_HUNGER = 20;
    /** 低于这个值就找东西吃。 */
    private static final int EAT_THRESHOLD = 6;
    /** 每 60 秒掉 1 点。 */
    private static final long DECAY_TICKS = 20L * 60L;

    private VerityVitals() {
    }

    public static int hunger(VerityEntity verity) {
        var data = verity.getPersistentData();
        if (!data.contains(KEY_HUNGER)) return MAX_HUNGER;
        return Math.max(0, Math.min(MAX_HUNGER, data.getInt(KEY_HUNGER)));
    }

    public static void setHunger(VerityEntity verity, int value) {
        verity.getPersistentData().putInt(KEY_HUNGER,
                Math.max(0, Math.min(MAX_HUNGER, value)));
    }

    /** 每 20 tick 调一次。 */
    public static void tick(VerityEntity verity) {
        long now = verity.level().getGameTime();
        var data = verity.getPersistentData();
        long last = data.getLong(KEY_LAST_DECAY);
        if (last <= 0L) {
            data.putLong(KEY_LAST_DECAY, now);
            return;
        }
        if (now - last < DECAY_TICKS) return;
        data.putLong(KEY_LAST_DECAY, now);
        int hunger = hunger(verity);
        if (hunger > 0) setHunger(verity, hunger - 1);
        if (hunger(verity) <= EAT_THRESHOLD) eat(verity);
    }

    /**
     * 从背包找一样能吃的，吃掉。
     *
     * @return 结果文本；没吃的返回空串
     */
    public static String eat(VerityEntity verity) {
        for (ItemStack slot : VerityInventory.load(verity)) {
            if (slot.isEmpty()) continue;
            FoodProperties food = slot.getItem().getFoodProperties();
            if (food == null) continue;
            String id = BuiltInRegistries.ITEM.getKey(slot.getItem()).toString();
            VerityInventory.extract(verity, id, 1);
            setHunger(verity, hunger(verity) + food.getNutrition());
            return "吃了 " + id + "，饥饿值回到 " + hunger(verity) + "/" + MAX_HUNGER;
        }
        return "";
    }

    /** 饿到影响干活的程度（模型据此决定先找吃的）。 */
    public static boolean hungry(VerityEntity verity) {
        return hunger(verity) <= EAT_THRESHOLD;
    }

    public static String describe(VerityEntity verity) {
        int hunger = hunger(verity);
        String state = hunger >= 16 ? "很饱" : hunger >= 10 ? "正常" : hunger >= 6 ? "有点饿" : hunger > 0 ? "饿" : "很饿";
        return "饥饿：" + hunger + "/" + MAX_HUNGER + "（" + state + "）";
    }
}
