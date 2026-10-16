package com.hanchen.veritycustomapi.memory;

/**
 * 单条记忆的旁路元数据：分类、重要度、时间戳、归属玩家、命中次数。
 * 原版记忆存储只能放 String -> String，这些字段就单独存一份旁路文件。
 */
public final class MemoryMeta {
    /** 重要度范围，1 最低、5 最高。 */
    public static final int MIN_IMPORTANCE = 1;
    public static final int MAX_IMPORTANCE = 5;

    private String category;
    private int importance = 3;
    private long createdAt;
    private long lastAccessAt;
    private int accessCount;
    /** 归属玩家的 UUID 字符串；null 表示全局面板可见的公共记忆。 */
    private String owner;

    public MemoryMeta() {
    }

    public MemoryMeta(MemoryCategory category, int importance, long now, String owner) {
        this.category = category == null ? MemoryCategory.FACT.id() : category.id();
        this.importance = clampImportance(importance);
        this.createdAt = now;
        this.lastAccessAt = now;
        this.owner = owner;
    }

    /** 给没有元数据的旧记忆补一份默认值，分类按 key 前缀推。 */
    public static MemoryMeta defaults(String key, long now, String owner) {
        return new MemoryMeta(MemoryCategory.inferFromKey(key), 3, now, owner);
    }

    public static int clampImportance(int value) {
        return Math.max(MIN_IMPORTANCE, Math.min(MAX_IMPORTANCE, value));
    }

    public MemoryCategory category() {
        return MemoryCategory.fromId(category);
    }

    public String categoryId() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public int importance() {
        return clampImportance(importance);
    }

    public void setImportance(int importance) {
        this.importance = clampImportance(importance);
    }

    public long createdAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long lastAccessAt() {
        return lastAccessAt;
    }

    public void setLastAccessAt(long lastAccessAt) {
        this.lastAccessAt = lastAccessAt;
    }

    public int accessCount() {
        return accessCount;
    }

    public void setAccessCount(int accessCount) {
        this.accessCount = accessCount;
    }

    public String owner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    /** 排序用的时间基准：优先取最近一次被检索到的时间。 */
    public long referenceAt() {
        return Math.max(lastAccessAt, createdAt);
    }

    public void markAccessed(long now) {
        this.lastAccessAt = now;
        this.accessCount++;
    }
}
