package com.hanchen.veritycustomapi.compat;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 AI 回复里的文件路径渲染成可点的 "路径[点我转跳]"，点了用系统默认程序打开。
 * 只放行白名单根目录里的路径，规范化后还要再复查一次，防止 ../ 这种写法逃逸。
 */
public final class ChatFileLinks {
    private static final Pattern PATH = Pattern.compile(
            "(?<![A-Za-z0-9_./\\\\])((?:config|logs|saves|screenshots|resourcepacks|mods|shaderpacks)"
                    + "[\\\\/][^\\s，。、；：\"\"''（）【】《》<>()\\[\\]{};,|]+)");

    private ChatFileLinks() {
    }

    public static MutableComponent decorate(String text) {
        MutableComponent root = Component.literal("");
        if (text == null || text.isEmpty()) {
            return root;
        }
        Matcher matcher = PATH.matcher(text);
        int last = 0;
        while (matcher.find()) {
            String matched = matcher.group(1);
            String raw = cleanTail(matched);
            String tail = matched.substring(raw.length());
            String normalized = safeRelative(raw);
            if (matcher.start() > last) {
                root.append(Component.literal(text.substring(last, matcher.start())));
            }
            if (normalized == null) {
                root.append(Component.literal(raw));
            } else {
                root.append(Component.literal(raw + "[点我转跳]")
                        .withStyle(style -> style
                                .withColor(ChatFormatting.GREEN)
                                .withUnderlined(true)
                                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_FILE, normalized))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                        Component.literal("点击打开 " + normalized)))));
            }
            // 被 cleanTail 去掉的尾部标点原样补回，避免吞掉句号/斜杠
            if (!tail.isEmpty()) {
                root.append(Component.literal(tail));
            }
            last = matcher.end();
        }
        if (last < text.length()) {
            root.append(Component.literal(text.substring(last)));
        }
        return root;
    }

    /** 去掉路径尾部跟着的句号/括号等标点。 */
    private static String cleanTail(String raw) {
        String value = raw;
        while (!value.isEmpty()) {
            char c = value.charAt(value.length() - 1);
            if (c == '.' || c == '\\' || c == '/') {
                value = value.substring(0, value.length() - 1);
            } else {
                break;
            }
        }
        return value;
    }

    /** 白名单根目录：规范化后仍必须落在这些目录里。 */
    private static final Set<String> ALLOWED_ROOTS = Set.of(
            "config", "logs", "saves", "screenshots", "resourcepacks", "mods", "shaderpacks");

    /** 校验：相对路径、无 .. 逃逸、规范化后仍在白名单根目录、文件真实存在；通过返回正斜杠路径。 */
    private static String safeRelative(String raw) {
        if (raw.indexOf(':') >= 0) {
            return null;
        }
        Path path;
        try {
            path = Paths.get(raw).normalize();
        } catch (Exception error) {
            return null;
        }
        if (path.startsWith("..") || path.isAbsolute() || path.getNameCount() == 0) {
            return null;
        }
        // config/../options.txt 这类写法规范化后会逃出白名单，必须再核对第一层目录
        if (!ALLOWED_ROOTS.contains(path.getName(0).toString())) {
            return null;
        }
        if (!Files.exists(path)) {
            return null;
        }
        return path.toString().replace('\\', '/');
    }
}
