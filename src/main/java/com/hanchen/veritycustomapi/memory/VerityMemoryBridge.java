package com.hanchen.veritycustomapi.memory;

import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import net.minecraft.server.MinecraftServer;
import varmite.verity.entity.llm.store.memory.VerityMemoryManager;
import varmite.verity.entity.llm.store.memory.VerityMemoryStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 扩展层和原版长期记忆之间的唯一桥梁。原版 VerityMemoryStore 只有 String -> String，也没有查询和排序，
 * 这里就在它上面加一层：只读快照给检索排序用，写入统一走服务器线程。
 * 访问原版单例全部容错，专用服务器或没初始化时返回空结果，而不是抛异常。
 */
public final class VerityMemoryBridge {
    /** 注入提示词的单条记忆长度上限，免得个别超长条目挤占上下文。 */
    private static final int MAX_VALUE_LENGTH = 400;
    private static final int MAX_KEY_LENGTH = 128;

    private VerityMemoryBridge() {
    }

    /** @return 原版记忆存储；没初始化或不在服务端时返回 null。 */
    public static VerityMemoryStore storeOrNull() {
        try {
            return VerityMemoryManager.get();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 记忆可不可用，决定要不要注入记忆相关提示。 */
    public static boolean isAvailable() {
        return storeOrNull() != null;
    }

    /** 全量记忆快照；不可用时返回空 Map。 */
    public static Map<String, String> snapshot() {
        VerityMemoryStore store = storeOrNull();
        if (store == null) {
            return Map.of();
        }
        try {
            Map<String, String> all = store.getAllMemories();
            return all == null ? Map.of() : all;
        } catch (Throwable ignored) {
            return Map.of();
        }
    }

    /** 生成要注入 system 提示词的记忆文本；latestUserMessage 用来做相关性检索，没内容可注入时返回 null。 */
    public static String buildInjection(String latestUserMessage) {
        if (!ConfigManager.isMemoryEnabled()) {
            return null;
        }
        // 滚动摘要：逐字窗口之外的旧对话浓缩成背景；热路径只读缓存，后台增量刷新
        String summary = ConversationContext.rollingSummary();
        Map<String, String> memories = snapshot();
        if (memories.isEmpty()) {
            return summary.isBlank() ? null : buildRuleText(null, summary);
        }
        long now = System.currentTimeMillis();
        MemoryMetaStore metaStore = metaStoreOrNull();
        String ownerId = currentOwnerId();
        boolean perPlayer = ConfigManager.isMemoryPerPlayer();

        Map<String, String> visible = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : memories.entrySet()) {
            if (isVisibleTo(entry.getKey(), metaStore, perPlayer, ownerId)) {
                visible.put(entry.getKey(), entry.getValue());
            }
        }
        if (visible.isEmpty()) {
            return summary.isBlank() ? null : buildRuleText(null, summary);
        }

        int topN = Math.max(1, ConfigManager.getMemoryRetrievalTopN());
        if (!ConfigManager.isMemoryRetrievalEnabled()) {
            // 关掉检索就退回旧行为：只给规则，不给具体条目
            return buildRuleText(null, summary);
        }
        Set<String> tokens = MemoryRanker.tokenize(latestUserMessage);
        List<MemoryRanker.Scored> ranked = MemoryRanker.rank(
                visible,
                key -> metaStore == null ? null : metaStore.peek(key),
                tokens,
                now,
                ConfigManager.getMemoryDecayDays(),
                topN);
        if (ranked.isEmpty()) {
            return buildRuleText(null, summary);
        }
        StringBuilder retrieved = new StringBuilder();
        // 记忆区预算：总预算减掉摘要实际占用动态再平衡，免得摘要膨胀把记忆挤没了
        int budget = memoryBudgetFor(summary);
        for (MemoryRanker.Scored scored : ranked) {
            String line = "- " + scored.key() + ": " + trim(clean(scored.value())) + "\n";
            if (retrieved.length() + line.length() > budget) {
                break;
            }
            retrieved.append(line);
            if (metaStore != null) {
                metaStore.touchAccessed(scored.key(), now);
            }
        }
        OllamaProxyMod.LOGGER.info("记忆检索注入 {} 条（候选 {} 条）", ranked.size(), visible.size());
        return buildRuleText(retrieved.toString(), summary);
    }

    /** 玩家侧主动写一条记忆。必须在服务器线程调。 */
    public static boolean write(String rawKey, String rawValue, MemoryCategory category, int importance, String ownerId) {
        VerityMemoryStore store = storeOrNull();
        if (store == null) {
            return false;
        }
        String key = normalizeKey(rawKey);
        String value = clean(rawValue);
        if (key.isEmpty() || value.isEmpty()) {
            return false;
        }
        try {
            store.addMemory(key, value);
        } catch (Throwable error) {
            OllamaProxyMod.LOGGER.warn("记忆写入失败: {}", error.toString());
            return false;
        }
        MemoryMetaStore metaStore = metaStoreOrNull();
        if (metaStore != null) {
            metaStore.put(key, new MemoryMeta(category, importance, System.currentTimeMillis(),
                    ConfigManager.isMemoryPerPlayer() ? ownerId : null));
        }
        return true;
    }

    /** 删一条记忆，连它的元数据一起清。必须在服务器线程调。 */
    public static boolean remove(String rawKey) {
        VerityMemoryStore store = storeOrNull();
        if (store == null) {
            return false;
        }
        String key = normalizeKey(rawKey);
        if (key.isEmpty()) {
            return false;
        }
        try {
            store.removeMemory(key);
        } catch (Throwable error) {
            return false;
        }
        MemoryMetaStore metaStore = metaStoreOrNull();
        if (metaStore != null) {
            metaStore.remove(key);
        }
        return true;
    }

    /** 手动新建记忆后补一份元数据（分类按 key 前缀推）。需在服务器线程调。 */
    public static void ensureMeta(String key) {
        MemoryMetaStore metaStore = metaStoreOrNull();
        if (metaStore == null || key == null || key.isBlank()) {
            return;
        }
        metaStore.meta(key, System.currentTimeMillis(), currentOwnerId());
    }

    /** 删单条记忆时同步清元数据。需在服务器线程调。 */
    public static void forgetMeta(String key) {
        MemoryMetaStore metaStore = metaStoreOrNull();
        if (metaStore != null && key != null && !key.isBlank()) {
            metaStore.remove(key);
        }
    }

    /** 清空记忆时把元数据一并清掉，只写一次盘。需在服务器线程调。 */
    public static void forgetAllMeta() {
        MemoryMetaStore metaStore = metaStoreOrNull();
        if (metaStore != null) {
            metaStore.retainOnly(Set.of());
        }
    }

    /** 把记忆 key 规整成「小写字母 / 数字 / 下划线」的形式。 */
    public static String normalizeKey(String raw) {
        if (raw == null) {
            return "";
        }
        String lowered = raw.trim().toLowerCase(Locale.ROOT);
        StringBuilder builder = new StringBuilder(lowered.length());
        boolean lastUnderscore = false;
        for (int i = 0; i < lowered.length() && builder.length() < MAX_KEY_LENGTH; i++) {
            char c = lowered.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                builder.append(c);
                lastUnderscore = false;
            } else if (c > 127 && Character.isLetterOrDigit(c)) {
                // 中日韩字符原样留着（原版工具也允许）
                builder.append(c);
                lastUnderscore = false;
            } else if (!lastUnderscore && builder.length() > 0) {
                builder.append('_');
                lastUnderscore = true;
            }
        }
        String result = builder.toString();
        while (result.endsWith("_")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /** 当前玩家 UUID；定不下来就返回 null（表示公共记忆）。 */
    public static String currentOwnerId() {
        if (!ConfigManager.isMemoryPerPlayer()) {
            return null;
        }
        try {
            if (!net.minecraftforge.fml.loading.FMLEnvironment.dist.isClient()) {
                return null;
            }
            String id = com.hanchen.veritycustomapi.client.MemoryPlayerContext.localPlayerId();
            return id == null || id.isEmpty() ? null : id;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** UI 展示用的归属名，取不到就返回「公共」。 */
    public static String ownerLabel(String owner) {
        return owner == null || owner.isBlank() ? "公共" : owner;
    }

    /** 按玩家过滤：对当前玩家可见的只有「公共记忆」和「自己写的」。 */
    private static boolean isVisibleTo(String key, MemoryMetaStore metaStore, boolean perPlayer, String ownerId) {
        if (!perPlayer || metaStore == null) {
            return true;
        }
        MemoryMeta meta = metaStore.peek(key);
        if (meta == null) {
            // 原版工具手动写的记忆没有归属信息，当作公共
            return true;
        }
        String owner = meta.owner();
        if (owner == null || owner.isBlank()) {
            return true;
        }
        return ownerId != null && owner.equals(ownerId);
    }

    private static MemoryMetaStore metaStoreOrNull() {
        try {
            MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            return MemoryMetaStore.get(server);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String buildRuleText(String retrieved, String summary) {
        StringBuilder text = new StringBuilder();
        if (summary != null && !summary.isBlank()) {
            text.append("[Earlier conversation, condensed]\n")
                    .append(summary)
                    .append("\nBackground only, never quote this summary back verbatim.\n\n");
        }
        text.append("Memory rules: store only durable user preferences or confirmed facts; "
                + "never secrets, credentials, or transient requests; check existing entries before writing.");
        if (retrieved != null && !retrieved.isBlank()) {
            text.append("\n\n[Relevant long-term memory]\n")
                    .append(retrieved)
                    .append("Use naturally when helpful, never list back verbatim; "
                            + "call get_from_memory / get_all_memory_key for more detail.");
        }
        return clipInjection(text.toString());
    }

    /** 注入块最后的兜底：各区预算之外，保证整块加起来也不超总账。 */
    private static String clipInjection(String text) {
        int total = ContextSettings.injectionBudgetChars();
        return text.length() <= total ? text : text.substring(0, total);
    }

    /** 记忆区预算：总预算减掉摘要实际占用动态再平衡，下限 600 字。 */
    private static int memoryBudgetFor(String summary) {
        int used = summary == null ? 0 : summary.length();
        return Math.max(600, Math.min(ConversationContext.memoryBudgetChars(),
                ContextSettings.injectionBudgetChars() - used - 200));
    }

    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
        return trim(cleaned);
    }

    private static String trim(String value) {
        return value.length() > MAX_VALUE_LENGTH ? value.substring(0, MAX_VALUE_LENGTH) + "…" : value;
    }
}
