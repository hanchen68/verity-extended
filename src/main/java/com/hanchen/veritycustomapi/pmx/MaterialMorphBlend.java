package com.hanchen.veritycustomapi.pmx;

import java.util.List;
import java.util.Map;

/**
 * 把 PMX 材质 morph 偏移（类型 8）折进各材质的最终颜色。乘法偏移累加成从 1 起的因子，加法偏移累加成从 0 起的和，
 * 所以漫反射是 {@code base * (1 + multiply) + add}；贴图色调两种模式都从 1 起当乘法器用，内置渲染器没有色调通道，就折进顶点色。
 * 高光、环境、描边、球面、toon 这些色调在加载时消费掉，为的是保持字节流对齐，但顶点格式里没有对应通道。
 */
public final class MaterialMorphBlend {
    private static final float MIN_WEIGHT = 0.001F;

    private MaterialMorphBlend() {
    }

    /**
     * 解出各材质的最终 RGBA。
     *
     * @param materials  模型材质，按声明顺序
     * @param morphIndex 按名字索引的 morph
     * @param weights    激活的 morph 权重（通常为舞蹈表情帧）
     * @return {@code 4 * materials.size()} 个 float，RGBA 顺序；无激活材质 morph 时返回 {@code null}，调用方保持材质自身颜色
     */
    public static float[] resolve(List<MmdModelData.Material> materials,
                                  Map<String, MmdModelData.Morph> morphIndex,
                                  Map<String, Float> weights) {
        if (materials == null || materials.isEmpty() || morphIndex == null
                || weights == null || weights.isEmpty()) {
            return null;
        }
        int materialCount = materials.size();
        float[] multiply = new float[materialCount * 4];
        float[] add = new float[materialCount * 4];
        float[] texture = new float[materialCount * 4];
        boolean any = false;
        for (Map.Entry<String, Float> entry : weights.entrySet()) {
            float weight = entry.getValue() == null ? 0.0F : entry.getValue();
            if (weight <= MIN_WEIGHT) continue;
            MmdModelData.Morph morph = morphIndex.get(entry.getKey());
            if (morph == null || morph.materialOffsets().isEmpty()) continue;
            for (MmdModelData.MaterialOffset offset : morph.materialOffsets()) {
                int index = offset.materialIndex();
                if (index < 0 || index >= materialCount) continue;
                int base = index * 4;
                float[] target = offset.additive() ? add : multiply;
                target[base] += offset.diffuseR() * weight;
                target[base + 1] += offset.diffuseG() * weight;
                target[base + 2] += offset.diffuseB() * weight;
                target[base + 3] += offset.diffuseA() * weight;
                texture[base] += offset.textureR() * weight;
                texture[base + 1] += offset.textureG() * weight;
                texture[base + 2] += offset.textureB() * weight;
                texture[base + 3] += offset.textureA() * weight;
                any = true;
            }
        }
        if (!any) return null;
        float[] result = new float[materialCount * 4];
        for (int material = 0; material < materialCount; material++) {
            MmdModelData.Material base = materials.get(material);
            float[] source = {base.red(), base.green(), base.blue(), base.alpha()};
            int offset = material * 4;
            for (int channel = 0; channel < 4; channel++) {
                float value = source[channel] * (1.0F + multiply[offset + channel]) + add[offset + channel];
                value *= 1.0F + texture[offset + channel];
                result[offset + channel] = clamp01(value);
            }
        }
        return result;
    }

    private static float clamp01(float value) {
        if (!Float.isFinite(value)) return 0.0F;
        return Math.max(0.0F, Math.min(1.0F, value));
    }
}
