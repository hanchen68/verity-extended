package com.hanchen.veritycustomapi.resource;

import com.hanchen.veritycustomapi.ConfigManager;
import com.hanchen.veritycustomapi.cloud.CloudConfig;
import com.hanchen.veritycustomapi.config.ModConfig;

import java.nio.file.Files;
import java.nio.file.Path;

public final class VoiceResourceManager {
    private final ResourceCacheIndex index;

    public VoiceResourceManager() throws ResourceException {
        String key = ConfigManager.getCloudApiKey();
        this.index = new ResourceCacheIndex(key);
    }

    public void select(CloudConfig.MimoVoice voice) {
        ConfigManager.setMimoVoices(mergeVoice(voice));
        ConfigManager.saveMimoVoice(voice.id, "clone".equalsIgnoreCase(voice.type));
        ModConfig.ENABLE_BUILTIN_TTS_APIS.set(true);
        ConfigManager.saveCommonConfig();
    }

    public void deleteCached(String id) throws Exception {
        id = requireId(id);
        ResourceCacheIndex.Entry entry = index.get("voice:" + id);
        if (entry != null) {
            Path path = index.root().resolve(entry.relativePath()).normalize();
            if (path.startsWith(index.root())) Files.deleteIfExists(path);
            index.remove("voice:" + id);
            if (id.equals(ConfigManager.getTtsApiVoice()) && ConfigManager.isSavedMimoVoiceClone(id)) {
                ConfigManager.saveMimoVoice("苏打", false);
            }
        }
    }

    public ResourceCacheIndex index() { return index; }

    private static CloudConfig.MimoVoice[] mergeVoice(CloudConfig.MimoVoice selected) {
        CloudConfig.MimoVoice[] current = ConfigManager.getMimoVoices();
        for (CloudConfig.MimoVoice voice : current) if (selected.id.equals(voice.id)) return current;
        CloudConfig.MimoVoice[] merged = java.util.Arrays.copyOf(current, current.length + 1);
        merged[current.length] = selected;
        return merged;
    }

    private static String requireId(String id) throws ResourceException {
        if (id == null || !id.matches("[\\p{L}\\p{N}._-]{1,96}") || id.equals(".") || id.equals("..")) {
            throw new ResourceException(0, "音色标识无效");
        }
        return id;
    }

}
