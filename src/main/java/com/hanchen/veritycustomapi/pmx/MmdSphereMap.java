package com.hanchen.veritycustomapi.pmx;

/**
 * PMX 球面贴图的纯数学部分（那种带第二张贴图的材质）。MMD 把明暗渐变烘焙进球面贴图，
 * 靠顶点 UV 加 UV1 morph 偏移来索引；模式决定采样怎么和材质色合成：乘、加，或按 alpha 在两半贴图间选。
 * 内置渲染器每材质只有一张贴图、每顶点一个颜色，所以球面采样折进顶点色（近似 MMD 的逐像素结果，也让 UV1 morph 看得见）。
 */
public final class MmdSphereMap {
    private MmdSphereMap() {
    }

    /** 把 {@code NativeImage} 像素（{@code 0xAABBGGRR}）拆成 0..1 的 {@code {r, g, b, a}}。 */
    public static float[] unpack(int abgr) {
        return new float[]{
                (abgr & 0xFF) / 255.0F,
                ((abgr >> 8) & 0xFF) / 255.0F,
                ((abgr >> 16) & 0xFF) / 255.0F,
                ((abgr >>> 24) & 0xFF) / 255.0F
        };
    }

    /** 把纹理坐标绕回 {@code [0, 1)}。 */
    public static float wrap(float value) {
        if (!Float.isFinite(value)) return 0.0F;
        float wrapped = value % 1.0F;
        return wrapped < 0.0F ? wrapped + 1.0F : wrapped;
    }

    /** @return 要读的像素列，钳在图像范围内 */
    public static int column(float u, int width) {
        if (width <= 0) return 0;
        int column = (int) (wrap(u) * width);
        return Math.max(0, Math.min(width - 1, column));
    }

    /** @return 要读的像素行，钳在图像范围内 */
    public static int row(float v, int height) {
        if (height <= 0) return 0;
        int row = (int) (wrap(v) * height);
        return Math.max(0, Math.min(height - 1, row));
    }

    /**
     * 把球面采样折进顶点色。
     *
     * @param mode   {@link MmdModelData.Material#SPHERE_MULTIPLY}、{@link MmdModelData.Material#SPHERE_ADD} 或 {@link MmdModelData.Material#SPHERE_SUBTEXTURE}
     * @param base   顶点色 {@code {r, g, b}}，不被修改
     * @param sphere 采样到的球面像素 {@code {r, g, b, a}}
     * @return 调制后的 {@code {r, g, b}}，钳制到 0..1
     */
    public static float[] apply(int mode, float[] base, float[] sphere) {
        float[] result = {
                channel(base, 0, 1.0F),
                channel(base, 1, 1.0F),
                channel(base, 2, 1.0F)
        };
        applyInto(mode, result, sphere);
        return result;
    }

    /**
     * 原地把球面采样折进顶点色（无分配版本，渲染器每顶点调用一次）。
     *
     * @param mode   球面模式
     * @param base   {@code {r, g, b}}，被调制后的颜色覆盖
     * @param sphere 采样到的球面像素 {@code {r, g, b, a}}
     */
    public static void applyInto(int mode, float[] base, float[] sphere) {
        float sphereRed = channel(sphere, 0, 1.0F);
        float sphereGreen = channel(sphere, 1, 1.0F);
        float sphereBlue = channel(sphere, 2, 1.0F);
        float sphereAlpha = channel(sphere, 3, 1.0F);
        if (mode == MmdModelData.Material.SPHERE_ADD) {
            base[0] += sphereRed * sphereAlpha;
            base[1] += sphereGreen * sphereAlpha;
            base[2] += sphereBlue * sphereAlpha;
        } else {
            // 乘法，子贴图当回退：子贴图模式得往第一张旁存的第二张贴图混，单次采样取不到，
            // 所以主半张按乘法处理。
            base[0] *= sphereRed;
            base[1] *= sphereGreen;
            base[2] *= sphereBlue;
        }
        base[0] = clamp01(base[0]);
        base[1] = clamp01(base[1]);
        base[2] = clamp01(base[2]);
    }

    private static float channel(float[] values, int index, float fallback) {
        if (values == null || index >= values.length) return fallback;
        float value = values[index];
        return Float.isFinite(value) ? value : fallback;
    }

    private static float clamp01(float value) {
        if (!Float.isFinite(value)) return 0.0F;
        return Math.max(0.0F, Math.min(1.0F, value));
    }
}
