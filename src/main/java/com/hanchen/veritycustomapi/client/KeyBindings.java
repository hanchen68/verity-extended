package com.hanchen.veritycustomapi.client;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = OllamaProxyMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class KeyBindings {
    /**
     * 语音键：**桌面端是备用入口，手机端是唯一入口**，所以默认值按平台分开。
     *
     * <p>🔴 桌面端默认 J，不能是 V：原版 Verity 自带 {@code PUSH_TO_TALK} 默认也是 V，
     * 而我们通过 {@link com.hanchen.veritycustomapi.compat.OriginalVeritySttInjector}
     * 已经接进那条链路了——再注册一个默认 V 的键，只会在按键设置里显示红色
     * 「该按键也用于」，看起来像两个功能抢键。
     *
     * <p>🔴 也不能是 B：{@code quick_command}（快捷指令面板）默认就是 B，
     * 换一个冲突只是把红字挪到另一行。J 目前没有任何键占用。
     *
     * <p>🔴 手机端（FCL / Pojav / Zalith）默认必须保持 V：原版那个注入**会主动跳过**
     * （{@code OriginalVeritySttInjector.inject()} 里 {@code isAndroidRuntime()} 直接 return），
     * 手机上没有可用的原版录音链，扩展自己的键就是**唯一**入口。
     */
    public static final KeyMapping SPEECH_KEY = new KeyMapping(
            "key.veritycustomapi.speech",
            InputConstants.Type.KEYSYM,
            com.hanchen.veritycustomapi.compat.RuntimePlatform.isAndroidRuntime()
                    ? GLFW.GLFW_KEY_V : GLFW.GLFW_KEY_J,
            "category.veritycustomapi"
    );

    public static final KeyMapping MMD_CONTROL_KEY = new KeyMapping(
            "key.veritycustomapi.mmd_control",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_N,
            "category.veritycustomapi"
    );

    /** 让 Verity 看一眼摄像头画面（默认 O 键）。 */
    public static final KeyMapping CAMERA_LOOK_KEY = new KeyMapping(
            "key.veritycustomapi.camera_look",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_O,
            "category.veritycustomapi"
    );

    /** 界面里的 Verity：开/关（默认 J 键）。 */
    public static final KeyMapping GUI_COMPANION_KEY = new KeyMapping(
            "key.veritycustomapi.gui_companion",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F8,
            "category.veritycustomapi"
    );

    /** 第二视角：借 Verity 的位置看世界（默认 R 键）。 */
    public static final KeyMapping SECOND_SIGHT_KEY = new KeyMapping(
            "key.veritycustomapi.second_sight",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F9,
            "category.veritycustomapi"
    );

    /** 打开快捷指令面板（默认 K 键）。 */
    public static final KeyMapping QUICK_COMMAND_KEY = new KeyMapping(
            "key.veritycustomapi.quick_command",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            "category.veritycustomapi"
    );

    /** 让 Verity 站到头上 / 从头上下来（默认 H 键）。 */
    public static final KeyMapping HEAD_PERCH_KEY = new KeyMapping(
            "key.veritycustomapi.head_perch",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_H,
            "category.veritycustomapi"
    );

    /**
     * 骑上 / 从 Verity 身上下来（默认 R 键）。
     *
     * <p>🔴 为什么不能继续用「潜行 + 空手右键」：PatPat 的抚摸手势**默认就是潜行右键**，
     * 而我们为了拦住原版「空手右键把 Verity 收进物品」必须在 HIGHEST 优先级取消那个事件——
     * 一取消，PatPat 就永远收不到抚摸请求。两个功能抢同一个手势，只能有一个生效。
     * 单独给骑乘一个键，抚摸还给 PatPat，两边互不干扰。
     */
    public static final KeyMapping RIDE_KEY = new KeyMapping(
            "key.veritycustomapi.ride",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_R,
            "category.veritycustomapi"
    );

    /** Independent story interaction; configurable, without changing existing bindings. */
    public static final KeyMapping STORY_KEY = new KeyMapping("key.veritycustomapi.story", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F7, "category.veritycustomapi");

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(SPEECH_KEY);
        event.register(MMD_CONTROL_KEY);
        event.register(CAMERA_LOOK_KEY);
        event.register(QUICK_COMMAND_KEY);
        event.register(GUI_COMPANION_KEY);
        event.register(SECOND_SIGHT_KEY);
        event.register(HEAD_PERCH_KEY);
        event.register(RIDE_KEY);
        event.register(STORY_KEY);
    }
}
