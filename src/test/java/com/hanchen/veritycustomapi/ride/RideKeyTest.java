package com.hanchen.veritycustomapi.ride;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 骑乘手势自检：骑乘**不能**再占用右键。
 *
 * <p>2026-10-25 用户报「骑乘键和摸头键冲突」。根因是两个功能抢同一个手势：
 * <ul>
 *   <li>骑乘原来是「潜行 + 空手右键」，为了拦住原版「空手右键把 Verity 收进物品」，
 *       必须在 {@code PlayerInteractEvent.EntityInteract} 的 HIGHEST 优先级取消该事件；</li>
 *   <li>PatPat 的抚摸手势**默认就是潜行右键**。</li>
 * </ul>
 * 事件一被取消，PatPat 永远收不到抚摸请求——两个功能只能有一个生效。
 *
 * <p>所以骑乘改成独立按键（默认 R）。这条自检守的是「别再退回右键」：
 * 只要有人重新加上右键拦截，抚摸就又废了。
 */
public final class RideKeyTest {

    private RideKeyTest() {
    }

    private static final Path ROOT = Path.of("src", "main", "java", "com", "hanchen",
            "veritycustomapi");

    public static void main(String[] args) throws Exception {
        String keys = read("client/KeyBindings.java");
        require(keys.contains("RIDE_KEY"), "没有注册骑乘按键");
        require(keys.contains("event.register(RIDE_KEY)"), "骑乘按键没有注册到事件里");

        String handler = read("client/ClientEventHandler.java");
        require(handler.contains("RIDE_KEY.consumeClick()"), "客户端没有轮询骑乘按键");
        require(handler.contains("RideKeyHandler.onRideKey"), "骑乘按键没有接到处理逻辑");

        // 右键拦截必须已经消失：文件删了、注册也删了
        require(!Files.exists(ROOT.resolve("ride/VerityRideInteraction.java")),
                "右键骑乘拦截类又回来了：它会在 HIGHEST 取消右键，PatPat 的抚摸永远收不到");
        require(!read("OllamaProxyMod.java").contains("VerityRideInteraction"),
                "右键骑乘拦截又被注册回事件总线了");
        require(!read("mixin/MobVerityInteractMixin.java").contains("mobInteract"),
                "Mob.mobInteract 又接上骑乘了：那是「潜行右键」的另一条入口，同样会和抚摸冲突");

        // requestMount 不该再校验手势（潜行/空手），那是按键入口的职责
        String manager = read("ride/RideManager.java");
        require(!manager.contains("isShiftKeyDown"),
                "requestMount 又在校验潜行：按键路径会被直接拒掉");

        System.out.println("RideKey checks passed");
    }

    private static String read(String relative) throws Exception {
        return Files.readString(ROOT.resolve(relative), StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
