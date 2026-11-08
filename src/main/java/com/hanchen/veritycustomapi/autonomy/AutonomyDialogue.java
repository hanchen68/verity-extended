package com.hanchen.veritycustomapi.autonomy;

import com.hanchen.veritycustomapi.compat.VeritySay;
import com.hanchen.veritycustomapi.network.ServerVerityRegistry;
import net.minecraft.server.level.ServerPlayer;
import varmite.verity.entity.verity.VerityEntity;
import java.util.Set;
import java.util.regex.*;

/** Called only from addressed Verity chat, never listens to unrelated public conversations. */
public final class AutonomyDialogue {
    private static final Pattern WAIT = Pattern.compile("(?:等我|等一下|等我一下|在这里等我)(?:\\s*(\\d{1,3})\\s*秒)?[。！! ]*");
    private AutonomyDialogue() {}
    public static boolean allowsWrite(String instruction, String tool) {
        if (instruction == null || instruction.length() > 2000 || instruction.matches("(?s).*(不|别|停|取消|禁止|算了|不用|无需|不必|先别|结束).*")) return false;
        return switch (tool) {
            case "harvest_area" -> instruction.contains("砍") || instruction.contains("采集") || instruction.contains("挖");
            case "plant_crop" -> instruction.contains("种");
            case "tidy_containers" -> instruction.contains("整理箱子") || instruction.contains("整理背包");
            default -> false;
        };
    }
    public static boolean handle(VerityEntity verity, ServerPlayer player, String message) {
        if (player == null || player.getServer() == null || message == null) return false;
        String text = message.strip();
        boolean stop = Set.of("停下", "停下来", "停止行动", "别动", "取消计划").contains(text)
                || text.matches("(?s).*(停止|取消|不用|不需要|不必|别再|不要再|先别|别)(采集|挖.*|种.*|整理.*|行动|任务).*?");
        boolean follow = Set.of("回来", "跟上我", "跟着我", "恢复自主", "继续行动").contains(text);
        boolean hold = Set.of("守在这里", "留在这里", "在这里等我回来").contains(text);
        if (text.length() > 2000) return false;
        Matcher match = WAIT.matcher(text.replaceAll("[。！!]+$", ""));
        boolean wait = match.matches();
        int seconds = wait && match.group(1) != null ? Math.min(600, Integer.parseInt(match.group(1))) : 60;
        boolean status = Set.of("你在干什么", "你在想什么", "你有什么计划", "你的计划是什么").contains(text);
        boolean control = stop || follow || hold || wait || status;
        boolean writeRequest = allowsWrite(text, "harvest_area") || allowsWrite(text, "plant_crop") || allowsWrite(text, "tidy_containers");
        // Decide on the main thread: prevents both the normal tool chain and autonomy executing one write.
        if (!player.getServer().isSameThread()) return false; // Event-generated AI reports are not player instructions.
        boolean consumeWrite = writeRequest && VerityAutonomy.state(verity).enabled
                && ServerVerityRegistry.owns(player, verity) && AutonomyActions.authorized(player, verity);
        player.getServer().execute(() -> {
            if (verity.isRemoved() || player.level() != verity.level() || !ServerVerityRegistry.owns(player, verity)) return;
            VerityAutonomy.State state = VerityAutonomy.state(verity);
            if (!state.enabled) return;
            if (!status) {
                AutonomyDecisionService.cancel(verity);
                AutonomyAgenda.interrupted(verity, "玩家发来新内容，保留动机并根据新情况重规划：" + text);
            }
            if (stop || hold || wait || follow) AutonomyActions.cancelOwnedJob(player, verity);
            if (status) {
                VeritySay.to(player, VerityAutonomy.describe(verity));
            } else if (stop) {
                VerityAutonomy.stop(verity); AutonomyActions.revoke(verity);
                verity.startTalking(40); VeritySay.to(player, "停下了。");
            } else if (follow) {
                VerityAutonomy.resume(verity); AutonomyBrain.backToCompanion(verity);
                verity.startTalking(40); VeritySay.to(player, "好，跟着你。");
            } else if (hold) {
                AutonomyBrain.holdHere(verity); verity.startTalking(50); VeritySay.to(player, "好，我在这里等你。");
            } else if (wait) {
                VerityAutonomy.waitFor(verity, seconds); verity.startTalking(50); VeritySay.to(player, "好，等你 " + seconds + " 秒。");
            } else {
                // An ungranted write remains solely with the original explicit tool chain.
                state.lastInstruction = writeRequest && !consumeWrite ? ""
                        : text.length() > 1800 ? text.substring(0, 1800) : text;
                state.revision++;
                state.nextThinkAt = Math.max(verity.level().getGameTime() + 40, state.nextThinkAt - 1000);
                if (allowsWrite(text, "harvest_area") || allowsWrite(text, "plant_crop") || allowsWrite(text, "tidy_containers")) {
                    state.modelStatus = consumeWrite ? "收到行动请求，将由自主执行器处理"
                            : "世界工具已关闭或自主暂停，未转交后台执行";
                }
            }
        });
        // Avoid swallowing normal chat unless autonomy really has been explicitly enabled.
        return (control || consumeWrite) && ServerVerityRegistry.owns(player, verity)
                && verity.getPersistentData().getBoolean("verity_ext_autonomy_enabled");
    }
}
