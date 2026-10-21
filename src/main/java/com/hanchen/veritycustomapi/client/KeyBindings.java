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
    public static final KeyMapping SPEECH_KEY = new KeyMapping(
            "key.veritycustomapi.speech",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_V,
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

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(SPEECH_KEY);
        event.register(MMD_CONTROL_KEY);
        event.register(CAMERA_LOOK_KEY);
        event.register(QUICK_COMMAND_KEY);
        event.register(HEAD_PERCH_KEY);
    }
}
