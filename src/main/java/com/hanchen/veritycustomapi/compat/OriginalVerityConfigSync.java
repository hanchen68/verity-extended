package com.hanchen.veritycustomapi.compat;

import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.OllamaProxyMod;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OriginalVerityConfigSync {
    private static final String GENERAL_SECTION = "[GeneralSettings]";
    private static final String VOICE_SECTION = "[GeneralSettings.VoiceSettings]";
    private static final String LEGACY_GENERAL_SECTION = "";
    private static final String LEGACY_VOICE_SECTION = "[AISettings]";
    private static final Map<String, String> PENDING_GENERAL = new LinkedHashMap<>();
    private static final Map<String, String> PENDING_VOICE = new LinkedHashMap<>();

    private OriginalVerityConfigSync() {
    }

    // ========== 配置屏幕 TOML 读写（保留用于用户 UI 设置持久化） ==========

    public static boolean getGeneralBoolean(String key, boolean defaultValue) {
        return getBoolean(GENERAL_SECTION, key, defaultValue);
    }

    public static int getGeneralInt(String key, int defaultValue) {
        String value = getString(GENERAL_SECTION, key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static synchronized void queueGeneralBoolean(String key, boolean value) {
        PENDING_GENERAL.put(key, Boolean.toString(value));
    }

    public static synchronized void queueGeneralInt(String key, int value) {
        PENDING_GENERAL.put(key, Integer.toString(value));
    }

    public static boolean getVoiceBoolean(String key, boolean defaultValue) {
        return getBoolean(VOICE_SECTION, key, defaultValue);
    }

    public static String getVoiceValue(String key, String defaultValue) {
        String value = getString(VOICE_SECTION, key);
        return value != null ? value : defaultValue;
    }

    public static synchronized void queueVoiceString(String key, String value) {
        PENDING_VOICE.put(key, quote(value));
    }

    public static synchronized void applyPendingChanges() {
        Map<String, String> generalValues = new LinkedHashMap<>(PENDING_GENERAL);
        Map<String, String> voiceValues = new LinkedHashMap<>(PENDING_VOICE);
        voiceValues.put("useTTS", Boolean.toString(ConfigManager.shouldOriginalTtsBeEnabled()));
        boolean updated = updateFiles(generalValues, voiceValues);
        if (updated) {
            OriginalVerityConfigInjector.injectGameplayConfig(generalValues);
            PENDING_GENERAL.clear();
            PENDING_VOICE.clear();
        } else {
            OllamaProxyMod.LOGGER.error("原版 Verity 配置保存失败，待保存内容已保留，将在下次保存时重试");
        }
    }

    private static boolean getBoolean(String section, String key, boolean defaultValue) {
        String value = getString(section, key);
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }

    private static String getString(String section, String key) {
        for (Path path : configFiles()) {
            String value = readValue(path, section, key);
            if (value != null) {
                return unquote(value);
            }
            String legacySection = section.equals(GENERAL_SECTION) ? LEGACY_GENERAL_SECTION : LEGACY_VOICE_SECTION;
            value = readValue(path, legacySection, key);
            if (value != null) {
                return unquote(value);
            }
        }
        return null;
    }

    private static String readValue(Path path, String section, String key) {
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            int sectionStart = section.isEmpty() ? -1 : findSection(lines, section);
            if (!section.isEmpty() && sectionStart < 0) {
                return null;
            }
            int sectionEnd = findSectionEnd(lines, sectionStart + 1);
            String prefix = key + " =";
            for (int i = sectionStart + 1; i < sectionEnd; i++) {
                String trimmed = lines.get(i).trim();
                if (trimmed.startsWith(prefix)) {
                    return trimmed.substring(prefix.length()).trim();
                }
            }
        } catch (IOException e) {
            OllamaProxyMod.LOGGER.warn("读取原版 Verity 配置失败: {}", path, e);
        }
        return null;
    }

    private static boolean updateFiles(Map<String, String> generalValues, Map<String, String> voiceValues) {
        boolean success = true;
        for (Path path : configFiles()) {
            success &= updateFile(path, generalValues, voiceValues);
        }
        return success;
    }

    private static boolean updateFile(Path path, Map<String, String> generalValues, Map<String, String> voiceValues) {
        if (!Files.isRegularFile(path)) {
            return true;
        }
        Path temporary = path.resolveSibling(path.getFileName() + ".verity_extended.tmp");
        try {
            List<String> lines = sanitizeTomlLines(Files.readAllLines(path, StandardCharsets.UTF_8));
            boolean changed = applySectionValues(lines, GENERAL_SECTION, LEGACY_GENERAL_SECTION, generalValues);
            changed |= applySectionValues(lines, VOICE_SECTION, LEGACY_VOICE_SECTION, voiceValues);
            if (changed) {
                Files.write(temporary, lines, StandardCharsets.UTF_8);
                try {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
                }
                OllamaProxyMod.LOGGER.info("已同步原版 Verity 配置: {}", path.getFileName());
            }
            return true;
        } catch (IOException e) {
            OllamaProxyMod.LOGGER.warn("更新原版 Verity 配置失败: {}", path, e);
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException cleanupError) {
                OllamaProxyMod.LOGGER.debug("清理原版 Verity 临时配置失败: {}", temporary, cleanupError);
            }
            return false;
        }
    }

    private static List<String> sanitizeTomlLines(List<String> lines) {
        List<String> sanitized = new ArrayList<>(lines);
        while (!sanitized.isEmpty()) {
            String first = sanitized.get(0);
            String cleaned = first.replace("\uFEFF", "").trim();
            if (cleaned.isEmpty()) {
                sanitized.remove(0);
                continue;
            }
            if (!cleaned.equals(first)) {
                sanitized.set(0, first.replace("\uFEFF", ""));
            }
            break;
        }
        return sanitized;
    }

    private static boolean applySectionValues(List<String> lines, String section, String legacySection, Map<String, String> values) {
        if (values.isEmpty()) {
            return false;
        }
        int sectionStart = findSection(lines, section);
        String targetSection = section;
        if (sectionStart < 0) {
            sectionStart = legacySection.isEmpty() ? -1 : findSection(lines, legacySection);
            targetSection = legacySection;
        }
        if (!targetSection.isEmpty() && sectionStart < 0) {
            return false;
        }
        boolean changed = false;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            int sectionEnd = findSectionEnd(lines, sectionStart + 1);
            changed |= setTomlValue(lines, sectionStart + 1, sectionEnd, entry.getKey(), entry.getValue());
        }
        return changed;
    }

    private static Path[] configFiles() {
        Path configDir = FMLPaths.CONFIGDIR.get();
        return new Path[] {
                configDir.resolve("verity-common.toml"),
                configDir.resolve("verity-client.toml")
        };
    }

    private static int findSection(List<String> lines, String section) {
        if (section.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().equals(section)) {
                return i;
            }
        }
        return -1;
    }

    private static int findSectionEnd(List<String> lines, int start) {
        for (int i = start; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                return i;
            }
        }
        return lines.size();
    }

    private static boolean setTomlValue(List<String> lines, int start, int end, String key, String value) {
        String prefix = key + " =";
        for (int i = start; i < end; i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.startsWith(prefix)) {
                String indent = line.substring(0, line.indexOf(trimmed));
                String replacement = indent + key + " = " + value;
                if (!line.equals(replacement)) {
                    lines.set(i, replacement);
                    return true;
                }
                return false;
            }
        }
        lines.add(end, "                " + key + " = " + value);
        return true;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String quote(String value) {
        String safeValue = value == null ? "" : value;
        return "\"" + safeValue.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
