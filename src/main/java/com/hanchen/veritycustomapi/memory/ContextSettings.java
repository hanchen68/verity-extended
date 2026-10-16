package com.hanchen.veritycustomapi.memory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 上下文系统的可调参数，存在 config/verity_extended/context_settings.json，玩家和服主都能自己改。
 */
public final class ContextSettings {
    /** 记忆条目总数上限。多人共用一个池子，满了就自动淘汰低价值的。 */
    private static volatile int maxMemoryEntries = 1000;
    /** 注入块的总预算（摘要区 + 记忆区 + 标题）。 */
    private static volatile int injectionBudgetChars = 3000;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    static {
        load();
    }

    private ContextSettings() {
    }

    public static int maxMemoryEntries() {
        return maxMemoryEntries;
    }

    public static int injectionBudgetChars() {
        return injectionBudgetChars;
    }

    /** 改完直接落盘（低频操作，同步写就行），越界的值自动收敛。 */
    public static void update(Integer maxEntries, Integer budgetChars) {
        if (maxEntries != null) {
            maxMemoryEntries = Math.max(100, Math.min(20000, maxEntries));
        }
        if (budgetChars != null) {
            injectionBudgetChars = Math.max(1500, Math.min(8000, budgetChars));
        }
        save();
    }

    /** 配置目录拿不到时返回 null（自检环境），那就只走内存、不落盘。 */
    private static Path filePath() {
        try {
            return FMLPaths.CONFIGDIR.get()
                    .resolve("verity_extended").resolve("context_settings.json");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void load() {
        Path file = filePath();
        if (file == null) {
            return;
        }
        try {
            JsonObject root = GSON.fromJson(
                    Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
            if (root == null) {
                return;
            }
            if (root.has("maxMemoryEntries")) {
                maxMemoryEntries = Math.max(100, Math.min(20000, root.get("maxMemoryEntries").getAsInt()));
            }
            if (root.has("injectionBudgetChars")) {
                injectionBudgetChars = Math.max(1500, Math.min(8000, root.get("injectionBudgetChars").getAsInt()));
            }
        } catch (Exception ignored) {
            // 没有配置文件或格式不对，就用默认值
        }
    }

    private static void save() {
        Path file = filePath();
        if (file == null) {
            return;
        }
        try {
            Files.createDirectories(file.getParent());
            JsonObject root = new JsonObject();
            root.addProperty("maxMemoryEntries", maxMemoryEntries);
            root.addProperty("injectionBudgetChars", injectionBudgetChars);
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (Exception error) {
            OllamaProxyMod.LOGGER.warn("上下文设置保存失败", error);
        }
    }
}