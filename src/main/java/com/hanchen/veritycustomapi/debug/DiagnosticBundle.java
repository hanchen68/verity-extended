package com.hanchen.veritycustomapi.debug;

import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.NetworkSecurity;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 诊断包导出：把连接检查结果、脱敏后的关键配置与 latest.log 尾部（2000 行）打成一个 zip。
 * 只导出排查所需片段，绝不写入任何密钥，密钥相关仅上报布尔型的"是否已配置"。
 */
public final class DiagnosticBundle {
    private static final int LOG_TAIL_LINES = 2000;
    private static final Pattern LOG_SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(\\b(?:api[_-]?key|authorization|bearer|token|secret(?:[_-]?key)?|access[_-]?token|appkey)\\b\\s*[:=]\\s*)([^,;\\s}]+)");
    private static final Pattern LOG_JSON_SECRET = Pattern.compile(
            "(?i)(\\\"(?:api[_-]?key|authorization|token|secret(?:[_-]?key)?|access[_-]?token|appkey)\\\"\\s*:\\s*\\\")[^\\\"]*(\\\")");
    private static final Pattern LOG_URL_CREDENTIALS = Pattern.compile(
            "(?i)(https?://)([^/@\\s]+):([^/@\\s]+)@");
    private static final Pattern LOG_URL = Pattern.compile("(?i)https?://[^\\s\\\"'<>]+");
    private static final Pattern LOG_SENSITIVE_FIELDS = Pattern.compile(
            "(?i)(\\b(?:model|voice|provider|apiProvider|apiUrl)\\b\\s*[:=]\\s*)([^,;\\s}]+)");
    private static final Pattern LOG_SENSITIVE_CONTENT = Pattern.compile(
            "(?i)(\\b(?:response|responseBody|errorBody|requestBody|prompt|transcript|transcription|userText|assistantText|content)\\b\\s*[:=]\\s*)([^\\r\\n]+)");
    private static final Pattern LOG_SENSITIVE_LINE = Pattern.compile(
            "(?i).*(?:transcribeAudio 返回|TTS请求体|TTS请求参数|响应正文|上游响应正文|请求正文).*");

    private interface ValueSupplier {
        String get();
    }

    private DiagnosticBundle() {
    }

    /** @return 生成的 zip 路径；失败返回 null */
    public static Path export(List<ConnectionTestHelper.TestResult> results) {
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
        Path target = FMLPaths.GAMEDIR.get().resolve("verity-diag-" + stamp + ".zip");
        try {
            Files.createDirectories(target.getParent());
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
                writeEntry(zip, "summary.txt", buildSummary(results));
                writeEntry(zip, "config.txt", buildConfigDump());
                List<String> tail = readLogTail();
                writeEntry(zip, "latest.log", tail.isEmpty()
                        ? "（未找到 logs/latest.log）"
                        : String.join(System.lineSeparator(), tail));
            }
            OllamaProxyMod.LOGGER.info("诊断包已导出: {}", target);
            return target;
        } catch (IOException error) {
            OllamaProxyMod.LOGGER.warn("诊断包导出失败: {}", error.toString());
            return null;
        }
    }

    private static void writeEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String buildSummary(List<ConnectionTestHelper.TestResult> results) {
        StringBuilder text = new StringBuilder();
        text.append("Verity 扩展增强 · 诊断包\n")
                .append("生成时间: ").append(LocalDateTime.now()).append('\n')
                .append("Mod 版本: ").append(modVersion()).append('\n')
                .append("Java: ").append(System.getProperty("java.version")).append('\n')
                .append("操作系统: ").append(System.getProperty("os.name")).append('\n')
                .append("最大堆: ").append(Runtime.getRuntime().maxMemory() / 1024 / 1024).append(" MB\n\n")
                .append("== 服务连接检查 ==\n");
        if (results == null || results.isEmpty()) {
            text.append("（没有检查结果）\n");
            return text.toString();
        }
        for (ConnectionTestHelper.TestResult result : results) {
            text.append('[').append(result.state()).append("] ").append(result.name())
                    .append(" | 模式=").append(result.mode())
                    .append(" | 地址=").append(safeEndpoint(result.endpoint())).append('\n');
            if (result.detail() != null && !result.detail().isBlank()) {
                text.append("    详情: ").append(result.detail().replace('\n', ' ')).append('\n');
            }
        }
        return text.toString();
    }

    /** 只导出开关与路由形态，绝不包含密钥。 */
    private static String buildConfigDump() {
        StringBuilder text = new StringBuilder();
        text.append("== 关键配置（已脱敏，不含密钥）==\n");
        append(text, "代理端口", () -> String.valueOf(ConfigManager.getPort()));
        append(text, "云端配置", () -> String.valueOf(ConfigManager.isCloudEnabled()));
        append(text, "云端地址", () -> safeEndpoint(ConfigManager.getCloudApiUrl()));
        append(text, "内置对话服务", () -> String.valueOf(ConfigManager.isEnableBuiltinLlmApis()));
        append(text, "剩余额度", () -> ConfigManager.formatCredits(ConfigManager.getCreditsRemainingExact()));
        append(text, "联网搜索", () -> String.valueOf(ConfigManager.isWebSearchEnabled()));

        text.append('\n').append("== 对话线路 ==\n");
        try {
            List<ConfigManager.ChatRoute> routes = ConfigManager.snapshotChatRoutes();
            if (routes.isEmpty()) {
                text.append("（没有可用线路）\n");
            }
            for (ConfigManager.ChatRoute route : routes) {
                text.append("source=").append(route.source())
                        .append(", tier=").append(route.tier())
                        .append(", routeId=").append(safeValue(route.id()))
                        .append(", hasKey=").append(route.apiKey() != null && !route.apiKey().isBlank())
                        .append('\n');
            }
        } catch (Throwable error) {
            text.append("读取线路失败: <内部错误>").append('\n');
        }

        text.append('\n').append("== 功能开关 ==\n");
        append(text, "长期记忆", () -> String.valueOf(ConfigManager.isMemoryEnabled()));
        append(text, "记忆检索", () -> String.valueOf(ConfigManager.isMemoryRetrievalEnabled()));
        append(text, "自动记忆", () -> String.valueOf(ConfigManager.isMemoryAutoWriteEnabled()));
        append(text, "记忆按玩家隔离", () -> String.valueOf(ConfigManager.isMemoryPerPlayer()));
        append(text, "主动感知", () -> String.valueOf(ConfigManager.isProactiveAwarenessEnabled()));
        append(text, "摄像头视觉", () -> String.valueOf(ConfigManager.isCameraEnabled()));
        append(text, "视觉技能", () -> String.valueOf(ConfigManager.isCameraSkillEnabled()));
        append(text, "对话气泡 HUD", () -> String.valueOf(ConfigManager.isDialogueHudEnabled()));
        append(text, "状态角标", () -> String.valueOf(ConfigManager.isStatusBadgeEnabled()));
        append(text, "导航箭头", () -> String.valueOf(ConfigManager.isNavigationArrowEnabled()));
        append(text, "背包上下文", () -> String.valueOf(ConfigManager.isInventoryContextEnabled()));
        append(text, "对话记录", () -> String.valueOf(ConfigManager.isConversationLogEnabled()));
        append(text, "定时提醒", () -> String.valueOf(ConfigManager.isReminderEnabled()));
        return text.toString();
    }

    private static void append(StringBuilder text, String label, ValueSupplier supplier) {
        String value;
        try {
            value = supplier.get();
        } catch (Throwable error) {
            value = "<不可用: " + error.getClass().getSimpleName() + ">";
        }
        text.append(label).append(" = ").append(value).append('\n');
    }

    /** 端点只保留主机名与路径，避免把查询参数里的凭证带出去。 */
    private static String safeEndpoint(String url) {
        if (url == null || url.isBlank()) {
            return "<空>";
        }
        try {
            return NetworkSecurity.sanitizedEndpoint(url);
        } catch (Throwable ignored) {
            return "<已省略>";
        }
    }

    private static List<String> readLogTail() {
        Path log = FMLPaths.GAMEDIR.get().resolve("logs").resolve("latest.log");
        if (!Files.exists(log)) {
            return List.of();
        }
        try {
            List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
            int from = Math.max(0, lines.size() - LOG_TAIL_LINES);
            List<String> sanitized = new ArrayList<>(lines.size() - from);
            for (int index = from; index < lines.size(); index++) {
                sanitized.add(sanitizeLogLine(lines.get(index)));
            }
            return sanitized;
        } catch (IOException error) {
            return List.of("（读取日志失败: <内部错误>）");
        }
    }

    private static String sanitizeLogLine(String line) {
        if (line == null || line.isEmpty()) return "";
        if (LOG_SENSITIVE_LINE.matcher(line).matches()) {
            return "<敏感日志内容已省略>";
        }
        String sanitized = LOG_URL_CREDENTIALS.matcher(line).replaceAll("$1****:****@");
        sanitized = LOG_JSON_SECRET.matcher(sanitized).replaceAll("$1****$2");
        sanitized = LOG_SECRET_ASSIGNMENT.matcher(sanitized).replaceAll("$1****");
        sanitized = LOG_SENSITIVE_FIELDS.matcher(sanitized).replaceAll("$1****");
        sanitized = LOG_SENSITIVE_CONTENT.matcher(sanitized).replaceAll("$1<已省略>");
        sanitized = LOG_URL.matcher(sanitized).replaceAll("<endpoint已省略>");
        return sanitized;
    }

    private static String safeValue(String value) {
        if (value == null || value.isBlank()) return "<空>";
        return value.replaceAll("[\\r\\n\\t]+", " ").trim();
    }

    private static String modVersion() {
        try {
            return ModList.get().getModContainerById(OllamaProxyMod.MOD_ID)
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("unknown");
        } catch (Throwable ignored) {
            return "unknown";
        }
    }
}
