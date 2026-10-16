package com.hanchen.veritycustomapi.client;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import com.hanchen.veritycustomapi.mixin.CameraAccessor;
import com.hanchen.veritycustomapi.mmd.MmdPlaybackManager;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = OllamaProxyMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MmdCameraController {
    private MmdCameraController() {}

    @SubscribeEvent
    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        MmdPlaybackManager.CameraSample sample = MmdPlaybackManager.get().sampleActiveCamera();
        if (sample == null) return;
        Vec3 position = sample.position();
        ((CameraAccessor) event.getCamera()).verityExtended$setPosition(position.x, position.y, position.z);
        event.setYaw(sample.yaw());
        event.setPitch(sample.pitch());
        event.setRoll(sample.roll());
    }

    @SubscribeEvent
    public static void onComputeFov(ViewportEvent.ComputeFov event) {
        MmdPlaybackManager.CameraSample sample = MmdPlaybackManager.get().sampleActiveCamera();
        if (sample != null) event.setFOV(sample.fov());
    }
}
