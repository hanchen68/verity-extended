package com.hanchen.veritycustomapi.mixin;

import org.spongepowered.asm.mixin.Mixin;

/**
 * 原版界面汉化的占位 Mixin，不注入代码。
 * 仅让 Mixin 把下面这些原版 Verity 类交给 {@link MixinPlugin#preApply}，再由 VerityLocalization 在类加载阶段替换其中的硬编码英文文本。
 */
@Mixin(targets = {
        "varmite.verity.VerityConfigUI",
        "varmite.verity.event.ModEvents",
        "varmite.verity.command.RecoverVerityCommand",
        "varmite.verity.command.ChangeKarmaCommand",
        "varmite.verity.client.audio.MicrophoneManager"
}, remap = false)
public abstract class VerityLocalizationMixin {
}
