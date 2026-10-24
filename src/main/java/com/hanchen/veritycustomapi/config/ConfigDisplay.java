package com.hanchen.veritycustomapi.config;

import com.hanchen.veritycustomapi.ApiProviderEnum;
import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.TtsApiProviderEnum;
import com.hanchen.veritycustomapi.compat.RuntimePlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 配置界面的「显示层」纯函数：把配置值翻译成给人看的中文、截断长文本、脱敏密钥等。
 *
 * <p>抽自 {@code NativeOllamaProxyConfigScreen}。这些方法**只读配置、只拼字符串**，
 * 不碰控件也不碰屏幕状态，所以能独立成类并单独验证——留在 3000 行的界面类里
 * 既没人愿意改，也没法单独测。
 *
 * <p>约定：**任何取不到的配置都返回一个能读的兜底文案，绝不抛异常**。
 * 界面渲染期间抛异常会整屏黑掉，代价远大于少显示一行信息。
 */
public final class ConfigDisplay {

    private ConfigDisplay() {
    }

    // ==================== 配置值 -> 中文 ====================

    /** 语音识别模式。 */
    public static String describeRecognitionMode(String value) {
        return "local".equalsIgnoreCase(value) ? "本地识别" : "在线识别";
    }

    /** 运行平台。 */
    public static String describePlatform() {
        if (RuntimePlatform.isAndroidRuntime()) return "Android / FCL";
        return "PC (Desktop)";
    }

    /** 本地 TTS 模型目录的可用性：只做浅层判断，不加载模型。 */
    public static String describeLocalTtsModel() {
        String path = ModConfig.TTS_MODEL_PATH.get();
        if (path == null || path.isBlank()) return "路径未配置";
        try {
            Path modelPath = Paths.get(path);
            if (!Files.isDirectory(modelPath)) return "目录不存在";
            try (var stream = Files.list(modelPath)) {
                long count = stream.limit(10).count();
                return count > 0 ? "已检测到模型文件" : "目录为空";
            }
        } catch (Exception e) {
            return "路径无效";
        }
    }

    /** TTS 服务商的展示名；枚举值不认识时原样显示配置值。 */
    public static String getTtsProviderDisplay() {
        try {
            return TtsApiProviderEnum.valueOf(ModConfig.TTS_API_PROVIDER.get()).displayName;
        } catch (Exception e) {
            return ModConfig.TTS_API_PROVIDER.get();
        }
    }

    /** 对话服务商的展示名；枚举值不认识时原样显示配置值。 */
    public static String getLlmProviderDisplay() {
        try {
            return ApiProviderEnum.valueOf(ModConfig.LLM_API_PROVIDER.get()).displayName;
        } catch (Exception e) {
            return ModConfig.LLM_API_PROVIDER.get();
        }
    }

    /** 抓取后端名称。 */
    public static String backendLabel(String backend) {
        return switch (backend == null ? "auto" : backend) {
            case "powershell" -> "Windows 内置";
            case "ffmpeg" -> "ffmpeg";
            case "http" -> "HTTP 快照";
            default -> "自动";
        };
    }

    // ==================== 脱敏 / 人设 ====================

    /**
     * 密钥脱敏：短于 8 位全打码，否则保留首尾各 4 位。
     *
     * <p>⚠️ 界面、日志、崩溃报告里凡是显示密钥的地方都必须过这里，
     * 明文一旦进了日志就等于泄露。
     */
    public static String maskKey(String key) {
        if (key == null || key.isBlank()) return "";
        if (key.length() <= 8) return "*".repeat(key.length());
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }

    /** 当前人设预设；配置里存的是显示名或枚举名，两种都认。 */
    public static ConfigManager.PromptPreset getCurrentPreset() {
        String selectedName = ModConfig.SELECTED_PRESET.get();
        for (ConfigManager.PromptPreset preset : ConfigManager.PromptPreset.values()) {
            if (preset.name().equals(selectedName) || preset.displayName.equals(selectedName)) {
                return preset;
            }
        }
        return ConfigManager.PromptPreset.ORIGINAL_VERITY;
    }

    public static String[] promptNames() {
        ConfigManager.PromptPreset[] presets = ConfigManager.PromptPreset.values();
        String[] result = new String[presets.length];
        for (int i = 0; i < presets.length; i++) result[i] = presets[i].displayName;
        return result;
    }

    public static String[] promptValues() {
        ConfigManager.PromptPreset[] presets = ConfigManager.PromptPreset.values();
        String[] result = new String[presets.length];
        for (int i = 0; i < presets.length; i++) result[i] = presets[i].name();
        return result;
    }

    /** 一句话说明当前实际会注入什么人设（注入来源 + 实际字符数，0 字符表示不会注入）。 */
    public static String describeEffectivePersona() {
        if (!ModConfig.ENABLE_CUSTOM_PROMPT.get()) {
            return "未启用，使用原版 Verity 提示词";
        }
        ConfigManager.PromptPreset preset = getCurrentPreset();
        String label;
        String persona = ConfigManager.getCustomPersona();
        if (persona != null && !persona.isBlank()) {
            label = "自定义人设（覆盖预设）";
        } else if (preset == ConfigManager.PromptPreset.CUSTOM_FILE) {
            String file = ModConfig.CUSTOM_PROMPT_FILE.get();
            label = "外部文件 " + (file == null || file.isBlank() ? "（未填写路径）" : file);
        } else if (preset == ConfigManager.PromptPreset.ORIGINAL_VERITY) {
            label = "原版人设";
        } else {
            label = preset.displayName;
        }
        String effective = ConfigManager.getCustomPersonaPrompt();
        int length = effective == null ? 0 : effective.strip().length();
        return length == 0
                ? label + " —— 不会注入任何内容（等于用原版提示词）"
                : label + " —— 实际注入 " + length + " 字符";
    }

    /** 是否连着别人的服务器（配置作用域提示用）。 */
    public static boolean isRemoteServerSession() {
        Minecraft mc = Minecraft.getInstance();
        return mc != null && mc.getCurrentServer() != null && !mc.hasSingleplayerServer();
    }

    // ==================== 下拉选项 / 数值 ====================

    public static String[] names(Enum<?>[] enums) {
        String[] result = new String[enums.length];
        for (int i = 0; i < enums.length; i++) result[i] = enums[i].toString();
        return result;
    }

    public static String[] values(Enum<?>[] enums) {
        String[] result = new String[enums.length];
        for (int i = 0; i < enums.length; i++) result[i] = enums[i].name();
        return result;
    }

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * 把文本裁到指定像素宽，超出部分用 {@code ...} 收尾。
     *
     * <p>按**像素**而不是字符数裁：中英文混排时字符数和显示宽度完全不成比例，
     * 按字符裁必然出现「有的行溢出、有的行浪费一大截」。
     */
    public static String fitText(Font font, String value, int maxWidth) {
        if (value == null || value.isBlank()) return "";
        String singleLine = value.replace('\n', ' ').replace('\r', ' ');
        if (font.width(singleLine) <= maxWidth) return singleLine;
        String ellipsis = "...";
        int ellipsisWidth = font.width(ellipsis);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < singleLine.length(); i++) {
            String next = builder + String.valueOf(singleLine.charAt(i));
            if (font.width(next) + ellipsisWidth > maxWidth) break;
            builder.append(singleLine.charAt(i));
        }
        return builder + ellipsis;
    }

    // ==================== 说明文字分流 ====================

    /**
     * 判断一条 info 的 value 是「短状态」还是「长说明」。
     *
     * <p>短状态（如「已配置」「98951」）要常驻显示；长说明收进悬停浮层，免得把界面撑长。
     *
     * <p>判据刻意简单可预测：含标点视为句子→说明；以 {@code /} 开头视为命令清单→常驻；
     * 其余按长度 10 字以内算状态。**不要在别处另写一套判据**，否则同一行会时隐时现。
     */
    public static boolean isStatusValue(String value) {
        if (value == null) return false;
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return false;
        // 先排除带标点的句子：状态词与命令清单都不会有句号分号，
        // 而「关闭后 → /v! 会被服务器拒绝；/v 私聊仍然可用」这种说明会。
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '，' || c == '。' || c == '；' || c == '：'
                    || c == ',' || c == '.' || c == ';' || c == ':') {
                return false;
            }
        }
        // 命令清单本身就是给人照着敲的，长度不限也常驻
        if (trimmed.charAt(0) == '/') return true;
        return trimmed.length() <= 10;
    }

    /** 分组收放状态的键：分类 + 分组，避免不同分类下同名分组互相串状态。 */
    public static String sectionKey(String categoryTitle, String sectionTitle) {
        return categoryTitle + '|' + sectionTitle;
    }
}
