package com.hanchen.veritycustomapi.autonomy;

import com.google.gson.*;
import net.minecraft.nbt.CompoundTag;
import varmite.verity.entity.verity.VerityEntity;

import java.util.*;

/**
 * 技能库：把「验证成功的动作序列」存下来，下次直接复用。
 *
 * <p><b>出处</b>：架构借自 Voyager（MineDojo Team，MIT）的 SkillManager——
 * 成功任务落成可复用技能，并按查询检索后注入提示词。
 * 这里没有搬它的 Node/Mineflayer 运行时，只保留「技能 = 参数化步骤序列 + 描述 + 检索」这套结构。
 *
 * <p><b>为什么不是代码</b>：Voyager 让模型写 JavaScript 直接跑，我们需要在服务端执行，
 * 所以技能体是受控的步骤数组（与 {@link AutonomyDecision} 同一套白名单），
 * 模型只能组合既有原语，不能生成任意代码。
 */
public final class AutonomySkills {

    private static final String KEY = "verity_ext_skills";
    private static final int MAX_SKILLS = 24;
    private static final int MAX_STEPS = 6;
    private static final int RETRIEVE_TOP_K = 3;

    /** 一条技能。 */
    public record Skill(String name, String description, JsonArray steps, int uses, int wins) {
    }

    private AutonomySkills() {
    }

    public static List<Skill> all(VerityEntity verity) {
        List<Skill> skills = new ArrayList<>();
        CompoundTag root = verity.getPersistentData().getCompound(KEY);
        for (String key : root.getAllKeys()) {
            try {
                JsonObject entry = JsonParser.parseString(root.getString(key)).getAsJsonObject();
                skills.add(new Skill(key, entry.get("d").getAsString(),
                        entry.getAsJsonArray("s").deepCopy(),
                        entry.has("u") ? entry.get("u").getAsInt() : 0,
                        entry.has("w") ? entry.get("w").getAsInt() : 0));
            } catch (RuntimeException ignored) {
                // 单条坏数据不能连累整个技能库
            }
        }
        return skills;
    }

    /** 按查询打分取前 K 条；没有任何命中时返回空（不硬塞无关技能）。 */
    public static List<Skill> retrieve(VerityEntity verity, String query, int topK) {
        String text = query == null ? "" : query.toLowerCase(Locale.ROOT);
        List<Skill> scored = new ArrayList<>();
        Map<Skill, Integer> score = new HashMap<>();
        for (Skill skill : all(verity)) {
            int value = similarity(text, skill);
            if (value <= 0) continue;
            score.put(skill, value);
            scored.add(skill);
        }
        scored.sort(Comparator.comparingInt((Skill s) -> score.get(s)).reversed());
        return scored.size() <= topK ? scored : new ArrayList<>(scored.subList(0, topK));
    }

    /** 词命中 + 中文双字重合；够用且不引入向量库依赖。 */
    private static int similarity(String query, Skill skill) {
        if (query.isBlank()) return 0;
        String target = (skill.name() + " " + skill.description()).toLowerCase(Locale.ROOT);
        int score = 0;
        for (String word : query.split("[^a-z0-9_]+")) {
            if (word.length() >= 3 && target.contains(word)) score += 2;
        }
        for (int i = 0; i + 1 < query.length(); i++) {
            char a = query.charAt(i);
            char b = query.charAt(i + 1);
            if (a > 0x2E80 && b > 0x2E80 && target.contains("" + a + b)) score++;
        }
        return score;
    }

    /** 注入提示词：只给名称、描述和步骤骨架，避免整库撑爆上下文。 */
    public static JsonArray toPrompt(VerityEntity verity, String query) {
        JsonArray array = new JsonArray();
        for (Skill skill : retrieve(verity, query, RETRIEVE_TOP_K)) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", skill.name());
            entry.addProperty("description", skill.description());
            entry.add("steps", skill.steps().deepCopy());
            entry.addProperty("successRate", skill.uses() == 0 ? "未知"
                    : Math.round(100.0F * skill.wins() / skill.uses()) + "%");
            array.add(entry);
        }
        return array;
    }

    /** 取指定技能，不存在返回 null。 */
    public static Skill find(VerityEntity verity, String name) {
        if (name == null || name.isBlank()) return null;
        for (Skill skill : all(verity)) if (skill.name().equals(name)) return skill;
        return null;
    }

    /**
     * 学一条新技能。同名覆盖（保留累计战绩），超过上限淘汰最久未用且成功率最低的。
     *
     * @return 是否写入成功；步骤非法一律拒绝，不能把坏序列存成「经验」
     */
    public static boolean learn(VerityEntity verity, String name, String description, JsonArray steps) {
        if (!validName(name) || steps == null || steps.isEmpty() || steps.size() > MAX_STEPS) return false;
        for (JsonElement step : steps) {
            if (!step.isJsonObject()) return false;
            try {
                AutonomyDecision.step(step.getAsJsonObject());
            } catch (RuntimeException rejected) {
                return false;
            }
        }
        CompoundTag root = verity.getPersistentData().getCompound(KEY);
        int uses = 0;
        int wins = 0;
        if (root.contains(name)) {
            try {
                JsonObject old = JsonParser.parseString(root.getString(name)).getAsJsonObject();
                uses = old.has("u") ? old.get("u").getAsInt() : 0;
                wins = old.has("w") ? old.get("w").getAsInt() : 0;
            } catch (RuntimeException ignored) {
                // 旧数据坏了就按新技能处理
            }
        }
        if (!root.contains(name) && root.getAllKeys().size() >= MAX_SKILLS) {
            String victim = worst(verity);
            if (victim != null) root.remove(victim);
        }
        JsonObject entry = new JsonObject();
        entry.addProperty("d", clip(description, 160));
        entry.add("s", steps.deepCopy());
        entry.addProperty("u", uses);
        entry.addProperty("w", wins);
        root.putString(name, entry.toString());
        verity.getPersistentData().put(KEY, root);
        return true;
    }

    /** 复用一次技能，记录战绩（成功与否都记，用来算成功率）。 */
    public static void record(VerityEntity verity, String name, boolean success) {
        CompoundTag root = verity.getPersistentData().getCompound(KEY);
        if (!root.contains(name)) return;
        try {
            JsonObject entry = JsonParser.parseString(root.getString(name)).getAsJsonObject();
            entry.addProperty("u", (entry.has("u") ? entry.get("u").getAsInt() : 0) + 1);
            if (success) entry.addProperty("w", (entry.has("w") ? entry.get("w").getAsInt() : 0) + 1);
            root.putString(name, entry.toString());
            verity.getPersistentData().put(KEY, root);
        } catch (RuntimeException ignored) {
            // 战绩统计失败不影响执行
        }
    }

    private static String worst(VerityEntity verity) {
        String victim = null;
        double lowest = Double.MAX_VALUE;
        for (Skill skill : all(verity)) {
            double rate = skill.uses() == 0 ? 1.0D : (double) skill.wins() / skill.uses();
            double weight = rate + skill.uses() * 0.01D;
            if (weight < lowest) {
                lowest = weight;
                victim = skill.name();
            }
        }
        return victim;
    }

    public static boolean validName(String name) {
        return name != null && name.matches("[a-z][a-z0-9_]{2,31}");
    }

    public static String describe(VerityEntity verity) {
        List<Skill> skills = all(verity);
        if (skills.isEmpty()) return "技能库：空";
        StringBuilder sb = new StringBuilder("技能库：" + skills.size() + " 条（");
        int shown = 0;
        for (Skill skill : skills) {
            if (shown++ > 0) sb.append("、");
            if (shown > 6) {
                sb.append("…");
                break;
            }
            sb.append(skill.name());
        }
        return sb.append("）").toString();
    }

    public static void forget(VerityEntity verity) {
        verity.getPersistentData().remove(KEY);
    }

    private static String clip(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max);
    }
}
