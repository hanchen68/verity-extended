package com.hanchen.veritycustomapi.mixin;

import com.hanchen.veritycustomapi.network.ServerVerityChatService;
import net.minecraftforge.event.ServerChatEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "varmite.verity.event.ModEvents", remap = false)
public abstract class ServerVerityChatMixin {
    @Inject(method = "onPlayerChat", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void verityExtended$routePrivateChat(ServerChatEvent event, CallbackInfo callback) {
        if (ServerVerityChatService.handle(event)) callback.cancel();
    }
}
