package com.hanchen.veritycustomapi.mixin;

import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.OllamaProxyServer;
import com.hanchen.veritycustomapi.compat.BilingualSpeechText;
import com.hanchen.veritycustomapi.compat.OverheadOnlySpeechText;
import com.hanchen.veritycustomapi.compat.VeritySubtitleManager;
import com.hanchen.veritycustomapi.tts.TtsManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import varmite.verity.network.PlayTtsClientHandler;
import varmite.verity.network.PlayTtsPayload;

@Mixin(value = PlayTtsClientHandler.class, remap = false)
public class PlayTtsClientHandlerMixin {
    @Inject(method = "handlePacket", at = @At("HEAD"), cancellable = true)
    private static void verityExtended$handleTts(PlayTtsPayload payload, CallbackInfo ci) {
        if (payload.text() != null && payload.text().contains(OllamaProxyServer.CAMERA_HOLD_MARKER)) {
            ci.cancel();
            return;
        }
        OverheadOnlySpeechText.Parts display = OverheadOnlySpeechText.decode(payload.text());
        BilingualSpeechText.Parts parts = BilingualSpeechText.decode(display.text());
        // 朗读用文本统一剥掉「[翻译] / 译文：」这类标签，避免 TTS 把标签念出来
        String spoken = BilingualSpeechText.stripLabels(parts.english());
        boolean bilingual = ConfigManager.isBilingualSpeechSubtitlesEnabled() && parts.bilingual();
        if (bilingual || display.overheadOnly()) {
            // 「仅头顶」模式下这句话在聊天栏里被撤掉了，头顶字幕是唯一的可视化，必须显示；
            // 双语模式的普通台词聊天栏已经显示中文译文，头顶再放一遍是纯重复，不显示。
            if (display.overheadOnly()) {
                String subtitle = bilingual
                        ? BilingualSpeechText.cleanTranslation(parts.chinese()) : spoken;
                VeritySubtitleManager.show(payload.entityId(), subtitle, spoken);
            }
            // 扩展没接管（路线是原版、或当前没有可用路线）-> 把改写后的文本交回原版去念，
            // 否则这一句会连原版的声音一起消失
            // 双语台词自带中文译文：阅读语言按中文算，交给「朗读语言」去决定要不要再转换，
            // 否则 朗读语言=中文 时会把模型已经给出的译文再转换一遍，白花一次调用
            if (!TtsManager.speak(spoken, payload.entityId(),
                    bilingual ? com.hanchen.veritycustomapi.voice.VoiceLanguage.ZH : null)) {
                PlayTtsClientHandler.handlePacket(new PlayTtsPayload(payload.entityId(), spoken));
            }
            ci.cancel();
            return;
        }
        // 扩展接管了才拦掉原版播放；没接管就放行，让原版自己念（路线 = 原版朗读）
        if (TtsManager.speak(spoken, payload.entityId())) {
            ci.cancel();
        }
    }
}
