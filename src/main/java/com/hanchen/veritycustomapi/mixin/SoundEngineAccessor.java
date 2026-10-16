package com.hanchen.veritycustomapi.mixin;

import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundEngineExecutor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SoundEngine.class)
public interface SoundEngineAccessor {
    @Accessor("executor")
    SoundEngineExecutor verityExtended$getExecutor();

    @Accessor("channelAccess")
    ChannelAccess verityExtended$getChannelAccess();
}
