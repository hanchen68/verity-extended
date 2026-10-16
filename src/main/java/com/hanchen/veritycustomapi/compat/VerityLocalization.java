package com.hanchen.veritycustomapi.compat;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 原版 Verity 界面的汉化。
 * 原模组的配置界面、命令反馈这些文本硬编码在 class 里，资源包盖不住，
 * 所以在类加载阶段把映射表里登记的英文常量换成中文，没登记的保持原样。
 */
public final class VerityLocalization {

    private static final String TABLE_RESOURCE =
            "assets/verity_extended/localization/verity_ui_zh_cn.json";

    /** 归一化后的英文原文 -> 中文译文。 */
    private static final Map<String, String> TABLE = new HashMap<>();

    private static boolean loaded;
    private static boolean enabled = true;

    private VerityLocalization() {
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** 加载映射表，只会真正执行一次。 */
    public static synchronized void load() {
        if (loaded) return;
        loaded = true;
        try (InputStream in = VerityLocalization.class.getClassLoader()
                .getResourceAsStream(TABLE_RESOURCE)) {
            if (in == null) {
                OllamaProxyMod.LOGGER.warn("[汉化] 未找到汉化表 {}，跳过原版界面汉化", TABLE_RESOURCE);
                return;
            }
            Type type = new TypeToken<Map<String, String>>() { }.getType();
            Map<String, String> raw = new Gson().fromJson(
                    new InputStreamReader(in, StandardCharsets.UTF_8), type);
            if (raw != null) {
                raw.forEach((key, value) -> {
                    if (key == null || value == null) return;
                    String normalized = normalize(key);
                    if (!normalized.isEmpty()) TABLE.put(normalized, value);
                });
            }
            OllamaProxyMod.LOGGER.info("[汉化] 原版 Verity 界面汉化表已加载，共 {} 条", TABLE.size());
        } catch (Throwable t) {
            OllamaProxyMod.LOGGER.warn("[汉化] 汉化表加载失败: {}", t.toString());
        }
    }

    /**
     * 把 class 中命中的英文常量替换为中文。
     *
     * @return 实际替换的文本数量
     */
    public static int apply(ClassNode classNode) {
        if (!enabled || TABLE.isEmpty() || classNode == null) return 0;
        int replaced = 0;
        if (classNode.methods != null) {
            for (MethodNode method : classNode.methods) {
                if (method.instructions == null) continue;
                for (AbstractInsnNode node : method.instructions.toArray()) {
                    if (node instanceof LdcInsnNode ldc && ldc.cst instanceof String original) {
                        String translated = translate(original);
                        if (translated != null) {
                            ldc.cst = translated;
                            replaced++;
                        }
                    } else if (node instanceof InvokeDynamicInsnNode indy
                            && indy.bsm != null
                            && "java/lang/invoke/StringConcatFactory".equals(indy.bsm.getOwner())
                            && indy.bsmArgs != null
                            && indy.bsmArgs.length > 0) {
                        // bsmArgs[0] 是拼接配方，\u0001/\u0002 是 JVM 参数占位符，替换时必须完整保留。
                        if (indy.bsmArgs[0] instanceof String recipe) {
                            String translated = translateConcatRecipe(recipe);
                            if (translated != null) {
                                indy.bsmArgs[0] = translated;
                                replaced++;
                            }
                        }
                        for (int i = 1; i < indy.bsmArgs.length; i++) {
                            if (indy.bsmArgs[i] instanceof String original) {
                                String translated = translate(original);
                                if (translated != null) {
                                    indy.bsmArgs[i] = translated;
                                    replaced++;
                                }
                            }
                        }
                    }
                }
            }
        }
        if (classNode.fields != null) {
            for (FieldNode field : classNode.fields) {
                if (field.value instanceof String original) {
                    String translated = translate(original);
                    if (translated != null) {
                        field.value = translated;
                        replaced++;
                    }
                }
            }
        }
        return replaced;
    }

    /** 命中则返回替换后的文本，未命中返回 null。 */
    private static String translate(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String key = normalize(raw);
        if (key.isEmpty()) return null;
        String translated = TABLE.get(key);
        if (translated == null) return null;
        // 保留原文开头的颜色代码，例如 §c / §a
        String prefix = raw.substring(0, raw.length() - stripColor(raw).length());
        // 原文以空格结尾说明后面还会拼接内容，译文也要补上分隔空格
        if (raw.endsWith(" ") && !translated.endsWith(" ")) {
            translated = translated + " ";
        }
        String result = prefix + translated;
        return result.equals(raw) ? null : result;
    }

    static String translateConcatRecipe(String recipe) {
        if (recipe == null || recipe.isEmpty()) return null;
        StringBuilder result = new StringBuilder(recipe.length());
        StringBuilder literal = new StringBuilder();
        boolean changed = false;
        for (int i = 0; i < recipe.length(); i++) {
            char current = recipe.charAt(i);
            if (current != '\u0001' && current != '\u0002') {
                literal.append(current);
                continue;
            }
            changed |= appendTranslatedLiteral(result, literal);
            result.append(current);
        }
        changed |= appendTranslatedLiteral(result, literal);
        if (!changed) return null;
        String translatedRecipe = result.toString();
        return countConcatMarkers(recipe) == countConcatMarkers(translatedRecipe)
                ? translatedRecipe : null;
    }

    private static boolean appendTranslatedLiteral(StringBuilder result, StringBuilder literal) {
        if (literal.isEmpty()) return false;
        String original = literal.toString();
        String translated = translate(original);
        result.append(translated != null ? translated : original);
        literal.setLength(0);
        return translated != null;
    }

    static int countConcatMarkers(String value) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == '\u0001' || current == '\u0002') count++;
        }
        return count;
    }

    /** 归一化：去掉颜色代码、把换行折叠为空格，并去掉首尾空白。 */
    private static String normalize(String raw) {
        return stripColor(raw).replace('\n', ' ').replace('\r', ' ').trim();
    }

    /** 去掉字符串开头的颜色代码，返回剩余部分。 */
    private static String stripColor(String raw) {
        if (raw == null) return "";
        int index = 0;
        while (index + 1 < raw.length() && raw.charAt(index) == '\u00a7') {
            index += 2;
        }
        return raw.substring(index);
    }
}
