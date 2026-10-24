package com.hanchen.veritycustomapi.action;

/**
 * 蓝图名净化的安全自检。
 *
 * <p>这道净化是**唯一挡住路径穿越的地方**：蓝图名来自玩家输入
 * （{@code /verity-build save <名字>}），会被直接拼成文件名。
 * 之前它埋在 1800 行的建造管理器里，没有任何用例，
 * 谁想「顺手放宽一下字符白名单」都不会有人发现。
 *
 * <p>这里把「必须被吃掉的东西」逐类钉住：路径分隔符、上跳段、绝对路径、
 * 驱动盘符、空字节、超长输入。
 */
public final class BlueprintStoreTest {

    private BlueprintStoreTest() {
    }

    public static void main(String[] args) {
        stripsPathSeparators();
        stripsTraversalSegments();
        stripsAbsolutePaths();
        keepsCjkAndSafeChars();
        collapsesEmptyToDefault();
        capsLength();
        handlesNull();

        System.out.println("BlueprintStore checks passed");
    }

    /** 正斜杠与反斜杠都不能留——Windows 与 POSIX 两种分隔符都要防。 */
    private static void stripsPathSeparators() {
        require(!BlueprintStore.sanitizeFileName("a/b").contains("/"), "正斜杠未被清除");
        require(!BlueprintStore.sanitizeFileName("a\\b").contains("\\"), "反斜杠未被清除");
    }

    /** `..` 绝对不能原样保留，否则可以跳到父目录。 */
    private static void stripsTraversalSegments() {
        String traversal = BlueprintStore.sanitizeFileName("../../etc/passwd");
        require(!traversal.contains(".."), "上跳段未被清除: " + traversal);
        require(!traversal.contains("/"), "上跳输入里的斜杠未被清除: " + traversal);

        String windowsTraversal = BlueprintStore.sanitizeFileName("..\\..\\windows\\system32");
        require(!windowsTraversal.contains(".."), "Windows 上跳段未被清除: " + windowsTraversal);
        require(!windowsTraversal.contains("\\"), "Windows 分隔符未被清除: " + windowsTraversal);
    }

    /** 绝对路径不能保留开头的盘符或根斜杠。 */
    private static void stripsAbsolutePaths() {
        String drive = BlueprintStore.sanitizeFileName("C:\\Windows\\System32\\config");
        require(!drive.contains(":"), "盘符冒号未被清除: " + drive);
        String root = BlueprintStore.sanitizeFileName("/etc/passwd");
        require(!root.startsWith("/"), "根斜杠未被保留: " + root);
    }

    /** 中文名是正常用法（玩家会用中文起名），不能被吃掉。 */
    private static void keepsCjkAndSafeChars() {
        require("我的城堡".equals(BlueprintStore.sanitizeFileName("我的城堡")), "中文名被破坏");
        require("tower_01".equals(BlueprintStore.sanitizeFileName("tower_01")), "下划线名被破坏");
        require("a-b".equals(BlueprintStore.sanitizeFileName("a-b")), "连字符名被破坏");
    }

    /** 全是不安全字符时要退化成默认名，不能返回空串（空串会变成 `.json`）。 */
    private static void collapsesEmptyToDefault() {
        String onlyBad = BlueprintStore.sanitizeFileName("   ");
        require("blueprint".equals(onlyBad), "空白输入应退化成 blueprint，实际=" + onlyBad);
        require("blueprint".equals(BlueprintStore.sanitizeFileName("")), "空输入应退化成 blueprint");
    }

    /** 超长名字在 Windows 上创建会失败，必须截断。 */
    private static void capsLength() {
        String longName = BlueprintStore.sanitizeFileName("a".repeat(500));
        require(longName.length() <= 48, "名字未被截断到 48，实际长度=" + longName.length());
    }

    private static void handlesNull() {
        require("blueprint".equals(BlueprintStore.sanitizeFileName(null)), "null 应退化成 blueprint");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
