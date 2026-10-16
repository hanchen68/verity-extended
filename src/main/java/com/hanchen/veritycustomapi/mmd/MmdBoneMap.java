package com.hanchen.veritycustomapi.mmd;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * MMD 骨骼名（PMX/VMD 通用约定）到 verity_demon.geo.json 骨骼名的映射表。
 * 恶魔模型骨骼层级：Body -> torso ->（RightArm/LeftArm 及手骨）、neck -> Head、LeftLeg/RightLeg 及脚骨。
 * verity_demon 用 RightlArm、LowerlArm 这类 MMD 习惯拼写。
 */
public final class MmdBoneMap {
    private static final Map<String, String> MMD_TO_DEMON;
    static {
        Map<String, String> m = new HashMap<>();
        // Body：只用下半身旋转。中心/全亲/Groove 是位移控制器，不能当作 Body 局部旋转。
        m.put("下半身", "Body");
        m.put("LowerBody", "Body");
        m.put("Pelvis", "Body");
        m.put("Hips", "Body");
        // Torso
        m.put("上半身", "torso");
        m.put("UpperBody", "torso");
        m.put("Spine", "torso");
        // Neck / Head
        m.put("首", "neck");
        m.put("Neck", "neck");
        m.put("頭", "Head");
        m.put("Head", "Head");
        // Right arm chain（模型没有独立肩骨，只驱动上臂，避免肩轨覆盖手臂）
        m.put("右腕", "RightArm");
        m.put("RightArm", "RightArm");
        m.put("右ひじ", "RightlArm");
        m.put("RightElbow", "RightlArm");
        m.put("RightLowerArm", "RightlArm");
        m.put("右手首", "Rhand");
        m.put("RightWrist", "Rhand");
        m.put("RightHand", "Rhand");
        // Left arm chain（模型没有独立肩骨，只驱动上臂，避免肩轨覆盖手臂）
        m.put("左腕", "LeftArm");
        m.put("LeftArm", "LeftArm");
        m.put("左ひじ", "LowerlArm");
        m.put("LeftElbow", "LowerlArm");
        m.put("LeftLowerArm", "LowerlArm");
        m.put("左手首", "Lhand");
        m.put("LeftWrist", "Lhand");
        m.put("LeftHand", "Lhand");
        // Right leg chain
        m.put("右足", "RightLeg");
        m.put("RightLeg", "RightLeg");
        m.put("右ひざ", "RightLower");
        m.put("RightKnee", "RightLower");
        m.put("RightLowerLeg", "RightLower");
        m.put("右足首", "foot");
        m.put("RightAnkle", "foot");
        m.put("RightFoot", "foot");
        // Left leg chain
        m.put("左足", "LeftLeg");
        m.put("LeftLeg", "LeftLeg");
        m.put("左ひざ", "LeftLower");
        m.put("LeftKnee", "LeftLower");
        m.put("LeftLowerLeg", "LeftLower");
        m.put("左足首", "Lfoot");
        m.put("LeftAnkle", "Lfoot");
        m.put("LeftFoot", "Lfoot");
        MMD_TO_DEMON = Collections.unmodifiableMap(m);
    }

    private MmdBoneMap() {}

    /** 把 MMD 骨骼名翻译为 verity_demon 骨骼名；找不到时返回 null。 */
    public static String translate(String mmdName) {
        if (mmdName == null) return null;
        String trimmed = mmdName.trim();
        if (trimmed.isEmpty()) return null;
        // 精确匹配
        String hit = MMD_TO_DEMON.get(trimmed);
        if (hit != null) return hit;
        // 大小写不敏感匹配
        for (Map.Entry<String, String> e : MMD_TO_DEMON.entrySet()) {
            if (e.getKey().equalsIgnoreCase(trimmed)) return e.getValue();
        }
        return null;
    }
}
