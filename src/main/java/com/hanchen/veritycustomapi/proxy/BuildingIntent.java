package com.hanchen.veritycustomapi.proxy;

import java.util.Locale;

/**
 * 「玩家是不是在让我盖东西」的判定。
 *
 * <p>抽自 {@code OllamaProxyServer.ChatProxyHandler}。抽出来的理由很实在：
 * 这套判据有 100+ 个关键词、四条规则、五种输入包装格式，是全项目最容易被改错的一块，
 * 而它一旦误判就会**凭空开始建造**——比任何 UI 问题都严重。
 * 单独成类之后可以直接用 {@code buildingIntentTest} 跑全量用例。
 *
 * <p>判定分三层，从强到弱：
 * <ol>
 *   <li>{@link #hasBuildingPrefix} —— 显式前缀（「建造：」「build:」），最可靠；</li>
 *   <li>{@link #hasBuildingIntent} —— 动词 + 结构名词，且**名词必须在动词之后**；</li>
 *   <li>包装剥离 —— {@link #unwrapCurrentUserMessage} 先去掉时间戳与 {@code <名字>} 前缀，
 *       否则「[12-34-56 12:00:00] <小明> 建个房子」会因为前缀而误判。</li>
 * </ol>
 */
public final class BuildingIntent {

    /** 建造动词。 */
    private static final String[] VERB_MARKERS = {
            "建造", "建筑", "修建", "搭建", "建", "造", "盖", "搭", "起", "立", "砌", "做", "弄", "搞",
            "修", "筑",
            "build", "construct", "erect", "make", "create", "generate",
    };

    /** 结构名词：建筑、设施，以及自由几何结构（火箭/雕塑/像素画…）。 */
    private static final String[] STRUCTURE_MARKERS = {
            "房子", "房屋", "小屋", "木屋", "树屋", "别墅", "公寓", "楼", "塔", "城堡", "要塞",
            "基地", "营地", "宫殿", "神庙", "教堂", "桥", "亭", "棚", "院子", "围墙", "墙", "门",
            "屋", "房", "仓库", "谷仓", "农场", "花园", "家园", "广场", "纪念碑", "雕像", "雕塑",
            "模型", "像素画", "火箭", "飞船", "宇宙飞船", "飞机", "汽车", "车辆", "坦克", "轮船",
            "船只", "大桥",
            "house", "home", "hut", "cabin", "cottage", "villa", "tower", "castle", "fort",
            "fortress", "base", "palace", "temple", "church", "bridge", "pavilion", "shelter",
            "shed", "wall", "gate", "farm", "barn", "garden", "plaza", "monument", "statue",
            "sculpture", "model", "pixel art", "rocket", "spaceship", "aircraft", "plane",
            "car", "tank", "ship", "boat",
    };

    private BuildingIntent() {
    }

    /**
     * 是否是明确的建造请求。
     *
     * @param request 玩家原话（可能带时间戳 / {@code <名字>} 前缀）
     */
    public static boolean isExplicitBuildingRequest(String request) {
        if (request == null || request.isBlank()) return false;
        String normalized = unwrapCurrentUserMessage(request).toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) return false;
        if (hasBuildingPrefix(normalized)) return true;
        return hasBuildingIntent(normalized);
    }

    /**
     * 整句意图：必须同时出现建造动词与结构名词，且**名词在动词之后**。
     *
     * <p>要求顺序是为了区分「帮我建个房子」（请求）与「这个模型做得不错」（评论）——
     * 后者同时含「做」和「模型」，只看共现就会误判。
     */
    public static boolean hasBuildingIntent(String normalized) {
        int verbIndex = firstMarkerIndex(normalized, VERB_MARKERS);
        if (verbIndex < 0) return false;
        int nounIndex = firstMarkerIndex(normalized, STRUCTURE_MARKERS);
        return nounIndex > verbIndex;
    }

    /** 取所有标记里**最早**出现的位置；都不出现返回 -1。 */
    public static int firstMarkerIndex(String text, String[] markers) {
        int best = -1;
        for (String marker : markers) {
            int index = text.indexOf(marker);
            if (index >= 0 && (best < 0 || index < best)) {
                best = index;
            }
        }
        return best;
    }

    /**
     * 剥掉聊天界面的包装：{@code [时间戳]} 与 {@code <发言者>}。
     *
     * <p>不剥的话，「&lt;小明&gt; 建个房子」会因为开头是 {@code <} 而走错分支。
     * 时间戳格式固定为 {@code [dd-dd-dd HH:mm:ss]}。
     */
    public static String unwrapCurrentUserMessage(String request) {
        String normalized = request.trim();
        boolean hadTimestamp = normalized.matches("^\\[\\d{2}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}].*");
        if (hadTimestamp) {
            normalized = normalized.replaceFirst("^\\[\\d{2}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}]\\s*", "");
        }
        if (normalized.startsWith("<")) {
            normalized = normalized.replaceFirst("^<[^>]{1,64}>\\s*", "");
        } else if (hadTimestamp) {
            int separator = normalized.indexOf(' ');
            if (separator > 0 && separator + 1 < normalized.length()) {
                String possibleMessage = normalized.substring(separator + 1).trim();
                if (hasBuildingPrefix(possibleMessage.toLowerCase(Locale.ROOT))) {
                    normalized = possibleMessage;
                }
            }
        }
        return normalized.trim();
    }

    /** 显式建造前缀：玩家/插件直接点明要建造，不必再猜意图。 */
    public static boolean hasBuildingPrefix(String normalized) {
        return normalized.startsWith("建造：") || normalized.startsWith("建造:")
                || normalized.startsWith("建筑：") || normalized.startsWith("建筑:")
                || normalized.startsWith("请建造") || normalized.startsWith("请建筑")
                || normalized.startsWith("帮我建造") || normalized.startsWith("帮我建筑")
                || normalized.startsWith("请建") || normalized.startsWith("帮我建")
                || normalized.startsWith("给我建") || normalized.startsWith("请盖")
                || normalized.startsWith("帮我盖") || normalized.startsWith("给我盖")
                || normalized.startsWith("build:") || normalized.startsWith("build ");
    }
}
