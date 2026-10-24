package com.hanchen.veritycustomapi.item;

import com.hanchen.veritycustomapi.util.Silent;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.config.ModConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.language.IModFileInfo;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 全物品双语索引：遍历模组物品注册表，叠加各 jar/源码目录里的 en_us、zh_cn 语言文件，
 * 供 AI 按 ID / 中文名 / 英文名搜索，并直接把物品放进玩家背包。
 */
public final class ItemRegistryService {
    private record Entry(String id, String path, String en, String zh) {
    }

    private static volatile List<Entry> entries = List.of();
    private static volatile boolean built;

    private ItemRegistryService() {
    }

    public static void warmup() {
        try {
            ensureBuilt();
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.warn("物品索引预热失败", error);
        }
    }

    /** AI 工具入口：按关键词（ID / 中文 / English）搜索，返回给模型的紧凑文本。 */
    public static String search(String keyword) {
        ensureBuilt();
        int limit = ModConfig.SERVER_VERITY_ITEM_QUERY_LIMIT.get();
        String raw = keyword == null ? "" : keyword.trim();
        String key = raw.toLowerCase();

        List<Entry> matches = new ArrayList<>();
        for (Entry entry : entries) {
            if (key.isEmpty() || entry.id().contains(key) || entry.path().contains(key)
                    || entry.en().toLowerCase().contains(key)
                    || (entry.zh() != null && entry.zh().contains(raw))) {
                matches.add(entry);
                if (matches.size() >= limit) break;
            }
        }

        if (matches.isEmpty()) {
            return "没有找到匹配的物品。可以用物品ID（如 minecraft:diamond）、英文名（如 Diamond）或中文名（如 钻石）来搜索。";
        }

        StringBuilder builder = new StringBuilder("物品ID | 英文名");
        builder.append(" | 中文名\n");
        for (Entry entry : matches) {
            builder.append(entry.id()).append(" | ").append(entry.en());
            if (entry.zh() != null) {
                builder.append(" | ").append(entry.zh());
            }
            builder.append('\n');
        }
        if (matches.size() >= limit) {
            builder.append("...以上是前 ").append(limit)
                    .append(" 条结果，试试更精确的关键词能找到更多。");
        }
        return builder.toString();
    }

    /** AI 工具入口：解析物品并塞进玩家背包，装不下的掉脚边。 */
    public static String give(ServerPlayer player, String idOrName, int requested) {
        ensureBuilt();
        if (player == null) return "现在拿不到你的玩家数据，请稍后再试。";
        Entry entry = resolve(idOrName);
        if (entry == null) {
            return "没有找到物品「" + idOrName
                    + "」，先用 list_available_itemids 搜索关键词拿到准确的物品ID再试。";
        }
        // 1.20.1 用 new ResourceLocation(...)；ResourceLocation.parse 是 1.21 才有的写法
        Optional<Item> item = BuiltInRegistries.ITEM.getOptional(new ResourceLocation(entry.id()));
        if (item.isEmpty()) {
            return "物品「" + entry.id() + "」对应的模组没有安装或没启用，暂时拿不到它。";
        }

        int count = Math.max(1, Math.min(64, requested));
        int maxStack = Math.max(1, item.get().getDefaultInstance().getMaxStackSize());
        int remaining = count;
        int dropped = 0;
        while (remaining > 0) {
            int n = Math.min(remaining, maxStack);
            ItemStack stack = new ItemStack(item.get(), n);
            if (!player.getInventory().add(stack)) {
                int actual = stack.getCount();
                if (actual > 0) {
                    ItemEntity entity = new ItemEntity(player.level(),
                            player.getX(), player.getY() + 0.5D, player.getZ(), stack);
                    player.level().addFreshEntity(entity);
                    dropped += actual;
                }
            }
            remaining -= n;
        }

        String display = entry.zh() != null ? entry.en() + " / " + entry.zh() : entry.en();
        String result = "已将 " + count + " 个「" + display + "」（" + entry.id()
                + "）放入玩家背包";
        if (dropped > 0) {
            result += "；背包已满，有 " + dropped + " 个掉在了玩家脚边";
        }
        return result + "。";
    }

    private static Entry resolve(String idOrName) {
        if (idOrName == null || idOrName.isBlank()) return null;
        String raw = idOrName.trim();
        String lower = raw.toLowerCase();

        ResourceLocation parsed = ResourceLocation.tryParse(lower);
        if (parsed != null) {
            Optional<Item> direct = BuiltInRegistries.ITEM.getOptional(parsed);
            if (direct.isPresent()) {
                ResourceLocation key = BuiltInRegistries.ITEM.getKey(direct.get());
                for (Entry entry : entries) {
                    if (entry.id().equals(key.toString())) return entry;
                }
            }
        }

        Entry best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Entry entry : entries) {
            int score = matchScore(entry, raw, lower);
            if (score > 0 && score < bestScore) {
                bestScore = score;
                best = entry;
            }
        }
        return best;
    }

    /** 分数越小越优先；0 表示不匹配。 */
    private static int matchScore(Entry entry, String raw, String lower) {
        if (entry.path().equals(lower)) return 1;
        if (entry.zh() != null && entry.zh().equals(raw)) return 2;
        if (entry.en().equalsIgnoreCase(raw)) return 3;
        if (entry.path().startsWith(lower)) return 4;
        if (entry.zh() != null && entry.zh().startsWith(raw)) return 5;
        if (entry.en().toLowerCase().startsWith(lower)) return 6;
        if (entry.id().contains(lower)) return 7;
        if (entry.zh() != null && entry.zh().contains(raw)) return 8;
        if (entry.en().toLowerCase().contains(lower)) return 9;
        return 0;
    }

    private static void ensureBuilt() {
        if (built) return;
        synchronized (ItemRegistryService.class) {
            if (built) return;
            Map<String, String> en = new HashMap<>();
            Map<String, String> zh = new HashMap<>();
            loadLanguageMaps(en, zh);

            List<Entry> list = new ArrayList<>();
            for (Item item : BuiltInRegistries.ITEM) {
                ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
                String descId = item.getDescriptionId();
                String fallback = humanize(key.getPath());
                list.add(new Entry(key.toString(), key.getPath(),
                        en.getOrDefault(descId, fallback), zh.get(descId)));
            }
            list.sort(Comparator.comparing(Entry::id));
            entries = List.copyOf(list);
            built = true;
            OllamaProxyMod.LOGGER.info("物品双语索引构建完成，共 {} 条", list.size());
        }
    }

    private static String humanize(String path) {
        String[] parts = path.split("_");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            if (!builder.isEmpty()) builder.append(' ');
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.toString();
    }

    /** 扫描所有已加载模组（含原版、开发目录）里的语言文件。 */
    private static void loadLanguageMaps(Map<String, String> en, Map<String, String> zh) {
        for (var modInfo : ModList.get().getMods()) {
            try {
                IModFileInfo info = modInfo.getOwningFile();
                if (info == null) continue;
                Object modFile = info.getClass().getMethod("getFile").invoke(info);
                Path root = (Path) modFile.getClass().getMethod("getFilePath").invoke(modFile);
                scanRoot(root, en, zh);
            } catch (Throwable ignored) { Silent.ignore("ItemRegistryService", ignored); }
        }
        loadClasspathFallback(en, zh);
    }

    private static final Pattern LANG_FILE =
            Pattern.compile(".*/assets/[^/]+/lang/(en_us|zh_cn)\\.json$");

    private static void scanRoot(Path root, Map<String, String> en, Map<String, String> zh) {
        if (!Files.exists(root)) return;
        if (Files.isDirectory(root)) {
            walkAndLoad(root, en, zh);
            return;
        }
        java.net.URI uri = java.net.URI.create("jar:" + root.toUri());
        boolean opened = false;
        FileSystem fs;
        try {
            fs = FileSystems.newFileSystem(uri, Map.of());
            opened = true;
        } catch (java.nio.file.FileSystemAlreadyExistsException exists) {
            fs = FileSystems.getFileSystem(uri);
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.debug("打开 jar 失败 {}: {}", root, error.toString());
            return;
        }
        try {
            for (Path fsRoot : fs.getRootDirectories()) {
                walkAndLoad(fsRoot, en, zh);
            }
        } finally {
            if (opened) {
                try {
                    fs.close();
                } catch (Throwable ignored) { Silent.ignore("ItemRegistryService", ignored); }
            }
        }
    }

    private static void walkAndLoad(Path root, Map<String, String> en, Map<String, String> zh) {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(Files::isRegularFile).forEach(path -> {
                String name = path.toString().replace('\\', '/');
                if (!LANG_FILE.matcher(name).matches()) return;
                Map<String, String> target = name.endsWith("zh_cn.json") ? zh : en;
                mergeJson(path, target);
            });
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.debug("遍历语言目录失败 {}: {}", root, error.toString());
        }
    }

    private static void mergeJson(Path path, Map<String, String> target) {
        try (InputStream in = Files.newInputStream(path)) {
            mergeJson(in, target);
        } catch (Throwable ignored) { Silent.ignore("ItemRegistryService", ignored); }
    }

    private static void mergeJson(InputStream in, Map<String, String> target) {
        JsonObject json = JsonParser.parseReader(
                new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            if (entry.getValue().isJsonPrimitive()) {
                target.putIfAbsent(entry.getKey(), entry.getValue().getAsString());
            }
        }
    }

    /** 个别环境下 ModFile 路径拿不到时，直接从类加载器找原版语言文件。 */
    private static void loadClasspathFallback(Map<String, String> en, Map<String, String> zh) {
        String[] files = {
                "/assets/minecraft/lang/en_us.json", "/assets/minecraft/lang/zh_cn.json"
        };
        for (String file : files) {
            try (InputStream in = ItemRegistryService.class.getResourceAsStream(file)) {
                if (in != null) {
                    mergeJson(in, file.endsWith("zh_cn.json") ? zh : en);
                }
            } catch (Throwable ignored) { Silent.ignore("ItemRegistryService", ignored); }
        }
    }
}
