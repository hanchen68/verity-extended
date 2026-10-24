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
import java.util.ArrayList;
import java.util.List;

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

    /**
     * 下拉里可选的人设预设。
     *
     * <p>🔴 刻意**不列出 {@code ORIGINAL_VERITY}**：它的语义是「什么都不注入，用原版提示词」，
     * 和「启用自定义人设」这个开关正好互相抵消——玩家打开开关、选了「原版人设」，
     * 结果提示词里一个字都没加，看起来就像「自定义人设不生效」。
     * 想用原版提示词，直接把开关关掉即可，不需要这个选项。
     *
     * <p>枚举里保留这个值只是为了兼容老配置，见 {@code NativeOllamaProxyConfigScreen#migrateOriginalPreset}。
     */
    public static String[] promptNames() {
        return selectablePresets().stream()
                .map(preset -> preset.displayName)
                .toArray(String[]::new);
    }

    public static String[] promptValues() {
        return selectablePresets().stream()
                .map(Enum::name)
                .toArray(String[]::new);
    }

    /** 除「原版人设」以外的所有预设，顺序与枚举一致。 */
    public static java.util.List<ConfigManager.PromptPreset> selectablePresets() {
        return java.util.Arrays.stream(ConfigManager.PromptPreset.values())
                .filter(preset -> preset != ConfigManager.PromptPreset.ORIGINAL_VERITY)
                .toList();
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

    /**
     * 把文本按像素宽折成多行，**不丢字**。
     *
     * <p>{@link #fitText} 是「一行放不下就截断」，只适合标题、按钮这种绝不能溢出的地方。
     * 状态值和说明文字用截断就变成「显示不全」——玩家看到的是一句带 {@code ...} 的半截话，
     * 想知道的恰好被省略号吃掉了。这类文字改用本方法折行，行高由调用方按行数撑开。
     *
     * <p>折行规则：先按显式 {@code \n} 分段；段内贪心按像素宽度装字，
     * 遇到空格优先在空格处断（英文单词不被劈开），中文没有空格就逐字断——
     * 中文本来就可以在任意字间断行，强行找「词边界」既做不到也不必要。
     * 单个字符就超过整行宽时仍然强制吃掉一个字符，否则会死循环。
     *
     * @return 至少一行；入参为 {@code null}/空串时返回只含一个空串的列表
     */
    public static List<String> wrapText(Font font, String value, int maxWidth) {
        List<String> lines = new ArrayList<>();
        if (value == null || value.isEmpty() || maxWidth <= 0) {
            lines.add("");
            return lines;
        }
        for (String paragraph : value.replace("\r", "").split("\n", -1)) {
            wrapParagraph(font, paragraph, maxWidth, lines);
        }
        if (lines.isEmpty()) lines.add("");
        return lines;
    }

    private static void wrapParagraph(Font font, String text, int maxWidth, List<String> out) {
        if (text.isEmpty()) {
            out.add("");
            return;
        }
        int start = 0;
        int length = text.length();
        while (start < length) {
            int width = 0;
            int end = start;
            int lastSpace = -1;
            while (end < length) {
                char c = text.charAt(end);
                int charWidth = font.width(String.valueOf(c));
                // end > start 保证每行至少装一个字，否则超宽的单个字符会卡死循环
                if (width + charWidth > maxWidth && end > start) break;
                width += charWidth;
                end++;
                if (c == ' ') lastSpace = end;
            }
            if (end >= length) {
                out.add(text.substring(start).stripTrailing());
                return;
            }
            int cut = end;
            // 只在「断在空格处不会浪费掉半行」时才认这个空格，否则窄列里会一行只剩一个词
            if (lastSpace > start && font.width(text.substring(start, lastSpace)) * 2 >= maxWidth) {
                cut = lastSpace;
            }
            out.add(text.substring(start, cut).stripTrailing());
            start = cut;
            while (start < length && text.charAt(start) == ' ') start++;
        }
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
