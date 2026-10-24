package com.hanchen.veritycustomapi.resource;

import com.hanchen.veritycustomapi.util.Silent;

import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.mmd.MmdPlaybackManager;
import com.hanchen.veritycustomapi.skin.SkinManager;

public final class PairingResourceFallback {
    private PairingResourceFallback() {
    }

    public static void apply(String apiKey) {
        try {
            ResourceCacheIndex index = new ResourceCacheIndex(apiKey);
            String activeSkin = SkinManager.getInstance().getActiveSkinName();
            for (ResourceCacheIndex.Entry entry : index.entries().values()) {
                if (!isPrivate(entry)) continue;
                if (isSelectedSkin(index.accountId(), activeSkin, entry)) {
                    SkinManager.getInstance().useDefaultSkin();
                }
                if (isSelectedVoice(ConfigManager.getTtsApiVoice(), entry)) {
                    ConfigManager.saveMimoVoice("苏打", false);
                }
            }
        } catch (Exception ignored) { Silent.ignore("PairingResourceFallback", ignored); }
        MmdPlaybackManager.get().stopPlaybackForAllDemons();
    }

    public static boolean isPrivate(ResourceCacheIndex.Entry entry) {
        return entry != null && entry.owner() && !entry.publicResource() && !entry.platform();
    }

    public static boolean isSelectedSkin(String accountId, String activeSkin, ResourceCacheIndex.Entry entry) {
        return entry != null && "skin".equals(entry.type())
                && (accountId + ":" + entry.resourceId()).equals(activeSkin);
    }

    public static boolean isSelectedVoice(String activeVoice, ResourceCacheIndex.Entry entry) {
        return entry != null && "voice".equals(entry.type()) && entry.resourceId().equals(activeVoice);
    }
}
