package com.hanchen.veritycustomapi.tts.route;

import com.hanchen.veritycustomapi.OllamaProxyMod;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 原版朗读路线：扩展不出声，把这句话交回原版 mod 自己的朗读。
 *
 * 「交回原版」不等于「静音」：收到朗读包的那一侧看到本引擎不接管，
 * 会放行原版的处理器，由原版把台词念出来。所以这条路线永远可用——
 * 它也是所有候选顺序的最后一级，保证不会出现「无路可走」。
 */
@OnlyIn(Dist.CLIENT)
public final class NativeTtsEngine implements TtsEngine {

    @Override
    public TtsRouteKind kind() {
        return TtsRouteKind.NATIVE;
    }

    @Override
    public String id() {
        return "native";
    }

    @Override
    public String displayName() {
        return "原版朗读";
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public boolean speak(String text, int entityId, long session) {
        OllamaProxyMod.LOGGER.debug("【TTS】路线=原版朗读，这句交回原版");
        return false;
    }
}
