package com.hanchen.veritycustomapi.resource;

import net.minecraft.Util;

import java.net.URI;

public final class ResourceUploadClient {
    public static final boolean supportsDirectUpload = false;
    public static final String DEFAULT_RESOURCE_CENTER_URL = "https://verity.xrstudio.cn/cloud/app.php?view=skins";

    private ResourceUploadClient() { }

    public static void openWebsite() {
        Util.getPlatform().openUri(URI.create(DEFAULT_RESOURCE_CENTER_URL));
    }
}
