package com.hanchen.veritycustomapi.mixin;

import com.hanchen.veritycustomapi.network.ServerVerityMemoryContext;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import varmite.verity.entity.llm.AiAPI;
import varmite.verity.entity.verity.VerityEntity;

@Mixin(value = AiAPI.class, remap = false)
public abstract class AiApiMemoryIsolationMixin {
    @ModifyConstant(method = "ask", constant = @Constant(stringValue = "verity-chat-memory"),
            require = 0, remap = false)
    private static String verityExtended$memoryId(String original) {
        return ServerVerityMemoryContext.current(original);
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "ask", at = @At("HEAD"), require = 0, remap = false)
    private static void verityExtended$begin(VerityEntity verity, ServerPlayer player, String prompt,
                                             long day, float karma, boolean ignored,
                                             CallbackInfoReturnable<String> callback) {
        ServerVerityMemoryContext.begin(verity, player);
    }

    @org.spongepowered.asm.mixin.injection.Inject(method = "ask", at = @At("RETURN"), require = 0, remap = false)
    private static void verityExtended$end(VerityEntity verity, ServerPlayer player, String prompt,
                                           long day, float karma, boolean ignored,
                                           CallbackInfoReturnable<String> callback) {
        ServerVerityMemoryContext.clear();
    }
}
