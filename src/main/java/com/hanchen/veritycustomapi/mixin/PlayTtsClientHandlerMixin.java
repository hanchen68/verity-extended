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
            //
            // 🔴 双语模式这里必须传 EN，不能传 ZH。
            // 双语约定是「聊天栏显示中文、朗读念英文」，而 spoken 取的是 parts.english()。
            // 传 ZH 会让 VoiceTranslator.needsTranslation(ZH, ZH)=false，于是那句**英文**
            // 被当成中文直接送进中文音色——念出来是「英文字母按中文发音」，玩家听到的就是
            // 「双语模式语音不对/时好时坏」。传 EN 后，朗读语言=中文时才会正确地转一次。
            if (!TtsManager.speak(spoken, payload.entityId(),
                    bilingual ? com.hanchen.veritycustomapi.voice.VoiceLanguage.EN : null)) {
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
